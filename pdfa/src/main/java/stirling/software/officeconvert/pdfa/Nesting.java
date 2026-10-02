package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;

final class Nesting {

    static final int MAX_DEPTH = 28;

    static final int MOVE_AT = 20;

    static final int MAX_MOVED_DEPTH = MOVE_AT * 64;

    private final COSDictionary resources;

    private final float extent;

    private int moved;

    private boolean stuck;

    private Nesting(COSDictionary resources, PdfALevel level) {
        this.resources = resources;
        this.extent = level.part() == 1 ? 32_767 : 1e7f;
    }

    static int depth(List<Object> tokens) {
        int depth = 0;
        int max = 0;
        for (Object t : tokens) {
            if (t instanceof Operator op) {
                if ("q".equals(op.getName())) {
                    max = Math.max(max, ++depth);
                } else if ("Q".equals(op.getName()) && depth > 0) {
                    depth--;
                }
            }
        }
        return max;
    }

    static List<Object> flatten(List<Object> tokens, COSDictionary resources, PdfALevel level, Report report)
            throws IOException {
        int deepest = depth(tokens);
        if (deepest > MAX_MOVED_DEPTH) {
            throw new IOException("The content nests graphics states " + deepest + " levels deep, more than the "
                    + MAX_MOVED_DEPTH + " that can be moved into forms");
        }
        Nesting n = new Nesting(resources, level);
        List<Object> out = n.rewrite(balanced(tokens));
        if (n.stuck || depth(out) > MAX_DEPTH) {
            report.warn("Some content nests graphics states deeper than " + MAX_DEPTH
                    + " levels and could not be moved into a form; it is left as it is");
        }
        return out;
    }

    private static List<Object> balanced(List<Object> tokens) {
        int depth = 0;
        for (Object t : tokens) {
            if (t instanceof Operator op) {
                if ("q".equals(op.getName())) {
                    depth++;
                } else if ("Q".equals(op.getName()) && depth > 0) {
                    depth--;
                }
            }
        }
        if (depth == 0) {
            return tokens;
        }
        List<Object> out = new ArrayList<>(tokens);
        for (int i = 0; i < depth; i++) {
            out.add(Operator.getOperator("Q"));
        }
        return out;
    }

    private record Block(int end, int inner, boolean movable) {}

    private static final class Open {
        final int t0;
        final int m0;
        final int bad0;
        int minT;
        int minM;
        int inner;

        Open(int t, int m, int bad) {
            t0 = t;
            m0 = m;
            bad0 = bad;
            minT = t;
            minM = m;
        }
    }

    private static List<Block> blocks(List<Object> tokens) {
        List<Block> out = new ArrayList<>();
        Deque<Open> open = new ArrayDeque<>();
        Deque<Integer> slots = new ArrayDeque<>();
        int text = 0;
        int marked = 0;
        int bad = 0;
        Object previous = null;
        for (int i = 0; i < tokens.size(); i++) {
            Object t = tokens.get(i);
            if (t instanceof Operator op) {
                switch (op.getName()) {
                    case "q" -> {
                        slots.push(out.size());
                        out.add(null);
                        open.push(new Open(text, marked, bad));
                    }
                    case "Q" -> {
                        if (!open.isEmpty()) {
                            Open b = open.pop();
                            boolean movable = text == b.t0 && marked == b.m0 && bad == b.bad0 && b.minT >= b.t0
                                    && b.minM >= b.m0;
                            out.set(slots.pop(), new Block(i, b.inner, movable));
                            Open parent = open.peek();
                            if (parent != null) {
                                parent.inner = Math.max(parent.inner, b.inner + 1);
                                parent.minT = Math.min(parent.minT, b.minT);
                                parent.minM = Math.min(parent.minM, b.minM);
                            }
                        }
                    }
                    case "BT" -> text++;
                    case "ET" -> text--;
                    case "BMC" -> marked++;
                    case "BDC" -> {
                        marked++;
                        if (previous instanceof COSDictionary props && props.containsKey(COSName.MCID)) {
                            bad++;
                        }
                    }
                    case "EMC" -> marked--;
                    case "scn", "SCN" -> {
                        if (previous instanceof COSName) {
                            bad++;
                        }
                    }
                    default -> {
                    }
                }
                Open top = open.peek();
                if (top != null) {
                    top.minT = Math.min(top.minT, text);
                    top.minM = Math.min(top.minM, marked);
                }
            }
            previous = t;
        }
        return out;
    }

    private static final class Frame {
        final List<Object> out = new ArrayList<>();
        final int end;
        int depth;

        Frame(int end) {
            this.end = end;
        }
    }

    private List<Object> rewrite(List<Object> tokens) throws IOException {
        List<Block> blocks = blocks(tokens);
        Deque<Frame> frames = new ArrayDeque<>();
        Frame cur = new Frame(-1);
        int q = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Object t = tokens.get(i);
            if (i == cur.end) {
                COSName name = form(cur.out);
                cur = frames.pop();
                cur.out.add(name);
                cur.out.add(Operator.getOperator("Do"));
                continue;
            }
            if (t instanceof Operator op) {
                String name = op.getName();
                if ("q".equals(name)) {
                    Block b = blocks.get(q++);
                    if (cur.depth == MOVE_AT && b != null && b.inner() + MOVE_AT + 1 > MAX_DEPTH) {
                        if (b.movable()) {
                            frames.push(cur);
                            cur = new Frame(b.end());
                            continue;
                        }
                        stuck = true;
                    }
                    cur.depth++;
                } else if ("Q".equals(name) && cur.depth > 0) {
                    cur.depth--;
                }
            }
            cur.out.add(t);
        }
        return cur.out;
    }

    private COSName form(List<Object> content) throws IOException {
        COSStream form = new COSStream();
        form.setItem(COSName.TYPE, COSName.XOBJECT);
        form.setItem(COSName.SUBTYPE, COSName.FORM);
        COSArray box = new COSArray();
        for (float v : new float[] {-extent, -extent, extent, extent}) {
            box.add(new COSFloat(v));
        }
        form.setItem(COSName.BBOX, box);
        form.setItem(COSName.RESOURCES, resources);
        ContentTokens.write(form, content);
        COSDictionary xobjects = ContentGraph.dict(resources.getDictionaryObject(COSName.XOBJECT));
        if (xobjects == null) {
            xobjects = new COSDictionary();
            resources.setItem(COSName.XOBJECT, xobjects);
        }
        COSName name;
        do {
            name = COSName.getPDFName("PdfANest" + moved++);
        } while (xobjects.containsKey(name));
        xobjects.setItem(name, form);
        return name;
    }
}
