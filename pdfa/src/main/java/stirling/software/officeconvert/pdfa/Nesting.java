package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.ArrayList;
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
        Nesting n = new Nesting(resources, level);
        List<Object> out = n.rewrite(tokens);
        if (n.stuck || depth(out) > MAX_DEPTH) {
            report.warn("Some content nests graphics states deeper than " + MAX_DEPTH
                    + " levels and could not be moved into a form; it is left as it is");
        }
        return out;
    }

    private List<Object> rewrite(List<Object> tokens) throws IOException {
        List<Object> out = new ArrayList<>(tokens.size());
        int depth = 0;
        int start = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (!(tokens.get(i) instanceof Operator op)) {
                continue;
            }
            String name = op.getName();
            if ("q".equals(name) && depth == MOVE_AT) {
                int end = match(tokens, i);
                if (end > 0) {
                    List<Object> block = tokens.subList(i + 1, end);
                    if (depth(block) + MOVE_AT + 1 > MAX_DEPTH) {
                        if (movable(block)) {
                            out.addAll(tokens.subList(start, i));
                            out.add(form(rewrite(new ArrayList<>(block))));
                            out.add(Operator.getOperator("Do"));
                            i = end;
                            start = end + 1;
                            continue;
                        }
                        stuck = true;
                    }
                }
            }
            if ("q".equals(name)) {
                depth++;
            } else if ("Q".equals(name) && depth > 0) {
                depth--;
            }
        }
        out.addAll(tokens.subList(start, tokens.size()));
        return out;
    }

    private static int match(List<Object> tokens, int q) {
        int depth = 0;
        for (int i = q; i < tokens.size(); i++) {
            if (tokens.get(i) instanceof Operator op) {
                if ("q".equals(op.getName())) {
                    depth++;
                } else if ("Q".equals(op.getName()) && --depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean movable(List<Object> block) {
        int text = 0;
        int marked = 0;
        Object previous = null;
        for (Object t : block) {
            if (t instanceof Operator op) {
                switch (op.getName()) {
                    case "BT" -> text++;
                    case "ET" -> text--;
                    case "BMC", "BDC" -> marked++;
                    case "EMC" -> marked--;
                    case "scn", "SCN" -> {
                        if (previous instanceof COSName) {
                            return false;
                        }
                    }
                    default -> {
                    }
                }
                if (text < 0 || marked < 0) {
                    return false;
                }
                if ("BDC".equals(op.getName()) && previous instanceof COSDictionary props
                        && props.containsKey(COSName.MCID)) {
                    return false;
                }
            }
            previous = t;
        }
        return text == 0 && marked == 0;
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
