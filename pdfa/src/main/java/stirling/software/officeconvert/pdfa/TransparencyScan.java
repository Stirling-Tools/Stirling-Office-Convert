package stirling.software.officeconvert.pdfa;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.util.Matrix;

final class TransparencyScan {

    record Op(int start, int end, Rectangle2D box, boolean text, boolean path) {}

    private static final Set<String> PAINT = Set.of("f", "F", "f*", "B", "B*", "b", "b*", "S", "s");

    private static final Set<String> TEXT = Set.of("Tj", "TJ", "'", "\"");

    private static final int MAX_DEPTH = 24;

    private final Map<COSBase, Boolean> cache = new IdentityHashMap<>();

    private static final class State {
        Matrix ctm;
        boolean alpha;
        boolean fillPattern;
        boolean strokePattern;
        boolean type3;

        State(Matrix ctm) {
            this.ctm = ctm;
        }

        State copy() {
            State s = new State(ctm.clone());
            s.alpha = alpha;
            s.fillPattern = fillPattern;
            s.strokePattern = strokePattern;
            s.type3 = type3;
            return s;
        }
    }

    List<Op> scan(List<Object> tokens, COSDictionary resources, Matrix start, int depth) {
        List<Op> out = new ArrayList<>();
        Deque<State> stack = new ArrayDeque<>();
        State st = new State(start);
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        int pathStart = -1;
        int opStart = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (!(tokens.get(i) instanceof Operator op)) {
                continue;
            }
            List<Object> args = tokens.subList(opStart, i);
            int begin = opStart;
            opStart = i + 1;
            String name = op.getName();
            switch (name) {
                case "q" -> {
                    if (stack.size() < 256) {
                        stack.push(st.copy());
                    }
                }
                case "Q" -> {
                    if (!stack.isEmpty()) {
                        st = stack.pop();
                    }
                }
                case "cm" -> {
                    if (args.size() == 6) {
                        Matrix m = matrix(args);
                        if (m != null) {
                            st.ctm = m.multiply(st.ctm);
                        }
                    }
                }
                case "gs" -> {
                    if (args.size() == 1 && args.get(0) instanceof COSName n) {
                        COSDictionary gs = lookup(resources, COSName.EXT_G_STATE, n);
                        if (gs != null) {
                            st.alpha = alpha(gs, st.alpha);
                        }
                    }
                }
                case "cs" -> st.fillPattern = false;
                case "CS" -> st.strokePattern = false;
                case "Tf" -> {
                    if (!args.isEmpty() && args.get(0) instanceof COSName fn) {
                        COSDictionary font = lookup(resources, COSName.FONT, fn);
                        st.type3 = font != null && COSName.TYPE3.equals(font.getCOSName(COSName.SUBTYPE))
                                && type3Transparent(font, resources, depth);
                    }
                }
                case "scn", "SCN" -> {
                    if (!args.isEmpty() && args.get(args.size() - 1) instanceof COSName pn) {
                        boolean t = patternTransparent(resources, pn, depth);
                        if ("scn".equals(name)) {
                            st.fillPattern = t;
                        } else {
                            st.strokePattern = t;
                        }
                    }
                }
                case "m", "l", "c", "v", "y", "re", "h" -> {
                    if (pathStart < 0) {
                        pathStart = begin;
                        minX = minY = Double.MAX_VALUE;
                        maxX = maxY = -Double.MAX_VALUE;
                    }
                    double[] v = numbers(args);
                    if ("re".equals(name) && v.length == 4) {
                        double[][] corners = {{v[0], v[1]}, {v[0] + v[2], v[1]}, {v[0], v[1] + v[3]},
                                {v[0] + v[2], v[1] + v[3]}};
                        for (double[] p : corners) {
                            java.awt.geom.Point2D q = st.ctm.transformPoint((float) p[0], (float) p[1]);
                            minX = Math.min(minX, q.getX());
                            minY = Math.min(minY, q.getY());
                            maxX = Math.max(maxX, q.getX());
                            maxY = Math.max(maxY, q.getY());
                        }
                    } else {
                        for (int k = 0; k + 1 < v.length; k += 2) {
                            java.awt.geom.Point2D q = st.ctm.transformPoint((float) v[k], (float) v[k + 1]);
                            minX = Math.min(minX, q.getX());
                            minY = Math.min(minY, q.getY());
                            maxX = Math.max(maxX, q.getX());
                            maxY = Math.max(maxY, q.getY());
                        }
                    }
                }
                case "n" -> pathStart = -1;
                case "sh" -> {
                    if (st.alpha) {
                        out.add(new Op(begin, i, null, false, false));
                    }
                }
                case "Do" -> {
                    if (args.size() == 1 && args.get(0) instanceof COSName n) {
                        COSDictionary x = lookup(resources, COSName.XOBJECT, n);
                        if (x instanceof COSStream xs && (st.alpha || xobjectTransparent(xs, resources, depth))) {
                            out.add(new Op(begin, i, xobjectBox(xs, st.ctm), false, false));
                        }
                    }
                }
                case "BI" -> {
                    if (st.alpha) {
                        out.add(new Op(begin, i, unit(st.ctm), false, false));
                    }
                }
                default -> {
                    if (PAINT.contains(name)) {
                        boolean fills = !name.equals("S") && !name.equals("s");
                        boolean strokes = !name.startsWith("f") && !name.equals("F");
                        boolean t = st.alpha || fills && st.fillPattern || strokes && st.strokePattern;
                        if (t && pathStart >= 0 && minX <= maxX) {
                            double pad = strokes ? 4 : 1;
                            out.add(new Op(begin, i, new Rectangle2D.Double(minX - pad, minY - pad,
                                    maxX - minX + 2 * pad, maxY - minY + 2 * pad), false, true));
                        } else if (t) {
                            out.add(new Op(begin, i, null, false, true));
                        }
                        pathStart = -1;
                    } else if (TEXT.contains(name) && (st.alpha || st.fillPattern || st.type3)) {
                        out.add(new Op(begin, i, null, true, false));
                    }
                }
            }
        }
        return out;
    }

    private boolean type3Transparent(COSDictionary font, COSDictionary resources, int depth) {
        Boolean known = cache.get(font);
        if (known != null) {
            return known;
        }
        cache.put(font, Boolean.FALSE);
        COSDictionary procs = ContentGraph.dict(font.getDictionaryObject(COSName.CHAR_PROCS));
        COSDictionary res = ContentGraph.dict(font.getDictionaryObject(COSName.RESOURCES));
        boolean t = false;
        if (procs != null) {
            for (COSName g : procs.keySet()) {
                if (procs.getDictionaryObject(g) instanceof COSStream s && contains(s, res == null ? resources : res, depth)) {
                    t = true;
                    break;
                }
            }
        }
        cache.put(font, t);
        return t;
    }

    boolean contains(COSStream stream, COSDictionary parentResources, int depth) {
        Boolean known = cache.get(stream);
        if (known != null) {
            return known;
        }
        if (depth > MAX_DEPTH) {
            return false;
        }
        cache.put(stream, Boolean.FALSE);
        COSDictionary res = ContentGraph.dict(stream.getDictionaryObject(COSName.RESOURCES));
        if (res == null) {
            res = parentResources;
        }
        boolean t;
        try {
            t = !scan(ContentTokens.parse(List.of(stream)), res, new Matrix(), depth + 1).isEmpty();
        } catch (IOException e) {
            t = false;
        }
        cache.put(stream, t);
        return t;
    }

    boolean xobjectTransparent(COSStream x, COSDictionary resources, int depth) {
        COSName sub = x.getCOSName(COSName.SUBTYPE);
        if (COSName.IMAGE.equals(sub)) {
            COSBase smask = x.getDictionaryObject(COSName.SMASK);
            return smask instanceof COSStream || x.getInt(COSName.getPDFName("SMaskInData"), 0) != 0;
        }
        if (!COSName.FORM.equals(sub)) {
            return false;
        }
        COSDictionary group = ContentGraph.dict(x.getDictionaryObject(COSName.GROUP));
        if (group != null && COSName.TRANSPARENCY.equals(group.getCOSName(COSName.S))) {
            return true;
        }
        return contains(x, resources, depth);
    }

    private boolean patternTransparent(COSDictionary resources, COSName name, int depth) {
        COSDictionary p = lookup(resources, COSName.PATTERN, name);
        if (p == null) {
            return false;
        }
        COSDictionary gs = ContentGraph.dict(p.getDictionaryObject(COSName.EXT_G_STATE));
        if (gs != null && alpha(gs, false)) {
            return true;
        }
        return p instanceof COSStream s && contains(s, resources, depth);
    }

    static boolean alpha(COSDictionary gs, boolean current) {
        boolean t = current;
        COSBase smask = gs.getDictionaryObject(COSName.SMASK);
        if (smask != null) {
            t = !COSName.NONE.equals(smask);
        }
        for (COSName k : new COSName[] {COSName.CA, COSName.CA_NS}) {
            COSBase v = gs.getDictionaryObject(k);
            if (v instanceof COSNumber n && n.floatValue() < 0.999f) {
                t = true;
            }
        }
        COSBase bm = gs.getDictionaryObject(COSName.BM);
        COSName mode = bm instanceof COSArray a && a.size() > 0 && a.getObject(0) instanceof COSName f ? f
                : bm instanceof COSName n ? n : null;
        if (mode != null && !mode.getName().equals("Normal") && !mode.getName().equals("Compatible")) {
            t = true;
        }
        return t;
    }

    static boolean opaque(COSDictionary gs) {
        return !alpha(gs, false);
    }

    private static Rectangle2D xobjectBox(COSStream x, Matrix ctm) {
        if (COSName.IMAGE.equals(x.getCOSName(COSName.SUBTYPE))) {
            return unit(ctm);
        }
        COSArray bbox = ContentGraph.array(x.getDictionaryObject(COSName.BBOX));
        if (bbox == null || bbox.size() < 4) {
            return null;
        }
        double[] b = numbers(bbox.toList());
        if (b.length < 4) {
            return null;
        }
        Matrix m = ctm;
        COSArray fm = ContentGraph.array(x.getDictionaryObject(COSName.MATRIX));
        if (fm != null && fm.size() == 6) {
            Matrix f = matrix(fm.toList());
            if (f != null) {
                m = f.multiply(ctm);
            }
        }
        return box(m, b[0], b[1], b[2], b[3]);
    }

    private static Rectangle2D unit(Matrix ctm) {
        return box(ctm, 0, 0, 1, 1);
    }

    private static Rectangle2D box(Matrix m, double x0, double y0, double x1, double y1) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (double[] p : new double[][] {{x0, y0}, {x1, y0}, {x0, y1}, {x1, y1}}) {
            java.awt.geom.Point2D q = m.transformPoint((float) p[0], (float) p[1]);
            minX = Math.min(minX, q.getX());
            minY = Math.min(minY, q.getY());
            maxX = Math.max(maxX, q.getX());
            maxY = Math.max(maxY, q.getY());
        }
        return new Rectangle2D.Double(minX - 1, minY - 1, maxX - minX + 2, maxY - minY + 2);
    }

    static COSDictionary lookup(COSDictionary resources, COSName category, COSName name) {
        COSDictionary c = resources == null ? null : ContentGraph.dict(resources.getDictionaryObject(category));
        return c == null ? null : ContentGraph.dict(c.getDictionaryObject(name));
    }

    static Matrix matrix(List<?> args) {
        double[] v = numbers(args);
        if (v.length != 6) {
            return null;
        }
        return new Matrix((float) v[0], (float) v[1], (float) v[2], (float) v[3], (float) v[4], (float) v[5]);
    }

    static double[] numbers(List<?> args) {
        double[] v = new double[args.size()];
        int n = 0;
        for (Object o : args) {
            if (o instanceof COSNumber num) {
                v[n++] = num.floatValue();
            }
        }
        return n == v.length ? v : java.util.Arrays.copyOf(v, n);
    }
}
