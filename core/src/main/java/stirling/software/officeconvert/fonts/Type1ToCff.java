package stirling.software.officeconvert.fonts;

import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.fontbox.cff.CFFStandardString;
import org.apache.fontbox.cff.CharStringCommand;
import org.apache.fontbox.cff.Type1CharString;
import org.apache.fontbox.cff.Type1CharStringParser;
import org.apache.fontbox.encoding.StandardEncoding;
import org.apache.fontbox.type1.Type1Font;
import org.apache.fontbox.util.BoundingBox;

public final class Type1ToCff {

    private static final int STANDARD_STRINGS = 391;

    private static final Map<String, Integer> STANDARD = standard();

    private static final int MAX_ARGS = 48;

    private static final String NOTDEF = ".notdef";

    private final Type1Font font;

    private final Map<String, Integer> sids = new HashMap<>();

    private final List<byte[]> strings = new ArrayList<>();

    private Type1ToCff(Type1Font font) {
        this.font = font;
    }

    public static byte[] convert(Type1Font font, Map<Integer, String> codes, Set<String> outlined) throws IOException {
        if (font == null || font.getFontType() != 1 || font.getPaintType() != 0
                || font.getCharStringsDict().isEmpty()) {
            return null;
        }
        return new Type1ToCff(font).run(codes, outlined);
    }

    private static Map<String, Integer> standard() {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < STANDARD_STRINGS; i++) {
            m.put(CFFStandardString.getName(i), i);
        }
        return m;
    }

    private byte[] run(Map<Integer, String> codes, Set<String> outlined) throws IOException {
        Map<String, byte[]> dict = font.getCharStringsDict();
        Map<String, TreeSet<Integer>> encoded = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : new TreeMap<>(codes).entrySet()) {
            int code = e.getKey();
            String name = e.getValue();
            if (code < 0 || code > 255 || name == null || NOTDEF.equals(name) || !dict.containsKey(name)) {
                continue;
            }
            encoded.computeIfAbsent(name, k -> new TreeSet<>()).add(code);
        }
        List<String> glyphs = new ArrayList<>();
        glyphs.add(NOTDEF);
        glyphs.addAll(encoded.keySet());
        Set<String> rest = new TreeSet<>(dict.keySet());
        rest.removeAll(glyphs);
        glyphs.addAll(rest);

        List<byte[]> charStrings = new ArrayList<>();
        for (String g : glyphs) {
            byte[] cs = charString(g, outlined.contains(g));
            if (cs == null) {
                return null;
            }
            charStrings.add(cs);
        }
        byte[] charset = charset(glyphs);
        byte[] encoding = encoding(encoded);
        byte[] privateDict = privateDict();
        byte[] charStringIndex = CffSubset.index(charStrings);

        String name = font.getFontName() == null || font.getFontName().isEmpty() ? "Font" : font.getFontName();
        byte[] nameIndex = CffSubset.index(List.of(name.getBytes(StandardCharsets.ISO_8859_1)));
        byte[] probe = topDict(0, 0, 0, privateDict.length, 0);
        byte[] stringIndex = CffSubset.index(strings);
        byte[] globalSubrs = CffSubset.index(List.of());
        int base = 4 + nameIndex.length + CffSubset.index(List.of(probe)).length + stringIndex.length
                + globalSubrs.length;
        int charsetAt = base;
        int encodingAt = charsetAt + charset.length;
        int charStringsAt = encodingAt + encoding.length;
        int privateAt = charStringsAt + charStringIndex.length;
        byte[] top = topDict(charsetAt, encodingAt, charStringsAt, privateDict.length, privateAt);
        if (top.length != probe.length) {
            return null;
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(new byte[] {1, 0, 4, 4});
        o.writeBytes(nameIndex);
        o.writeBytes(CffSubset.index(List.of(top)));
        o.writeBytes(stringIndex);
        o.writeBytes(globalSubrs);
        o.writeBytes(charset);
        o.writeBytes(encoding);
        o.writeBytes(charStringIndex);
        o.writeBytes(privateDict);
        return o.toByteArray();
    }

    private int sid(String s) {
        Integer std = STANDARD.get(s);
        if (std != null) {
            return std;
        }
        return sids.computeIfAbsent(s, k -> {
            strings.add(k.getBytes(StandardCharsets.ISO_8859_1));
            return STANDARD_STRINGS + strings.size() - 1;
        });
    }

    private byte[] charset(List<String> glyphs) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0);
        for (int i = 1; i < glyphs.size(); i++) {
            int s = sid(glyphs.get(i));
            o.write(s >> 8);
            o.write(s);
        }
        return o.toByteArray();
    }

    private byte[] encoding(Map<String, TreeSet<Integer>> encoded) {
        ByteArrayOutputStream codes = new ByteArrayOutputStream();
        ByteArrayOutputStream sups = new ByteArrayOutputStream();
        int supCount = 0;
        int nCodes = 0;
        for (Map.Entry<String, TreeSet<Integer>> e : encoded.entrySet()) {
            boolean inFormat = nCodes < 255;
            if (inFormat) {
                codes.write(e.getValue().first());
                nCodes++;
            }
            for (int code : inFormat ? e.getValue().tailSet(e.getValue().first(), false) : e.getValue()) {
                int s = sid(e.getKey());
                sups.write(code);
                sups.write(s >> 8);
                sups.write(s);
                supCount++;
            }
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(supCount > 0 ? 0x80 : 0);
        o.write(nCodes);
        o.writeBytes(codes.toByteArray());
        if (supCount > 0) {
            o.write(supCount);
            o.writeBytes(sups.toByteArray());
        }
        return o.toByteArray();
    }

    private byte[] topDict(int charsetAt, int encodingAt, int charStringsAt, int privateSize, int privateAt)
            throws IOException {
        Dict d = new Dict();
        string(d, font.getVersion(), 0);
        string(d, font.getNotice(), 1);
        string(d, font.getFullName(), 2);
        string(d, font.getFamilyName(), 3);
        string(d, font.getWeight(), 4);
        if (font.isFixedPitch()) {
            d.number(1).op(12, 1);
        }
        if (font.getItalicAngle() != 0) {
            d.number(font.getItalicAngle()).op(12, 2);
        }
        if (font.getUnderlinePosition() != -100) {
            d.number(font.getUnderlinePosition()).op(12, 3);
        }
        if (font.getUnderlineThickness() != 50) {
            d.number(font.getUnderlineThickness()).op(12, 4);
        }
        List<Number> matrix = font.getFontMatrix();
        if (matrix != null && matrix.size() == 6 && !standardMatrix(matrix)) {
            for (Number n : matrix) {
                d.number(n);
            }
            d.op(12, 7);
        }
        BoundingBox box = font.getFontBBox();
        if (box != null) {
            d.number(box.getLowerLeftX()).number(box.getLowerLeftY()).number(box.getUpperRightX())
                    .number(box.getUpperRightY()).op(5);
        }
        d.fixed(charsetAt).op(15);
        d.fixed(encodingAt).op(16);
        d.fixed(charStringsAt).op(17);
        d.fixed(privateSize).fixed(privateAt).op(18);
        return d.bytes();
    }

    private void string(Dict d, String value, int op) {
        if (value != null && !value.isEmpty()) {
            d.number(sid(value)).op(op);
        }
    }

    private static boolean standardMatrix(List<Number> m) {
        double[] want = {0.001, 0, 0, 0.001, 0, 0};
        for (int i = 0; i < 6; i++) {
            if (Math.abs(m.get(i).doubleValue() - want[i]) > 1e-9) {
                return false;
            }
        }
        return true;
    }

    private byte[] privateDict() {
        Dict d = new Dict();
        delta(d, font.getBlueValues(), 6);
        delta(d, font.getOtherBlues(), 7);
        delta(d, font.getFamilyBlues(), 8);
        delta(d, font.getFamilyOtherBlues(), 9);
        first(d, font.getStdHW(), 10);
        first(d, font.getStdVW(), 11);
        if (Math.abs(font.getBlueScale() - 0.039625f) > 1e-7) {
            d.number(font.getBlueScale()).op(12, 9);
        }
        if (font.getBlueShift() != 7) {
            d.number(font.getBlueShift()).op(12, 10);
        }
        if (font.getBlueFuzz() != 1) {
            d.number(font.getBlueFuzz()).op(12, 11);
        }
        delta(d, font.getStemSnapH(), 12, 12);
        delta(d, font.getStemSnapV(), 12, 13);
        if (font.isForceBold()) {
            d.number(1).op(12, 14);
        }
        if (font.getLanguageGroup() != 0) {
            d.number(font.getLanguageGroup()).op(12, 17);
        }
        return d.bytes();
    }

    private static void delta(Dict d, List<Number> values, int... op) {
        if (values == null || values.isEmpty()) {
            return;
        }
        double prev = 0;
        for (Number n : values) {
            d.number(n.doubleValue() - prev);
            prev = n.doubleValue();
        }
        d.op(op);
    }

    private static void first(Dict d, List<Number> values, int op) {
        if (values != null && !values.isEmpty()) {
            d.number(values.get(0)).op(op);
        }
    }

    private byte[] charString(String name, boolean outline) throws IOException {
        byte[] raw = font.getCharStringsDict().get(name);
        if (raw == null) {
            return NOTDEF.equals(name) ? new Type2().op(14).bytes() : null;
        }
        Type1CharString cs = font.getType1CharString(name);
        List<Object> seq = new Type1CharStringParser(font.getName()).parse(raw, font.getSubrsArray(), name);
        Type2 t = new Type2();
        int width = cs.getWidth();
        if (width != 0) {
            t.arg(width);
        }
        if (!outline) {
            return t.op(14).bytes();
        }
        if (!componentsPresent(seq)) {
            return null;
        }
        Hints hints = hints(seq);
        GeneralPath glyph = cs.getPath();
        Map<Integer, byte[]> masks = Map.of();
        if (hints != null && t.size() + 2 * hints.h().size() <= MAX_ARGS && 2 * hints.v().size() <= MAX_ARGS) {
            boolean masked = !hints.h().isEmpty() || !hints.v().isEmpty();
            masked &= hints.groups().size() > 1 || overlap(hints.h()) || overlap(hints.v());
            if (!masked || Arrays.equals(hints.segments(), segments(glyph))) {
                if (masked) {
                    masks = masks(hints);
                }
                if (!hints.h().isEmpty()) {
                    for (double v : encodeStems(hints.h())) {
                        t.arg(v);
                    }
                    t.op(masked ? 18 : 1);
                }
                if (!hints.v().isEmpty()) {
                    for (double v : encodeStems(hints.v())) {
                        t.arg(v);
                    }
                    t.op(masked ? 23 : 3);
                }
            }
        }
        path(t, glyph, masks);
        return t.op(14).bytes();
    }

    private boolean componentsPresent(List<Object> seq) {
        for (int i = 0; i < seq.size(); i++) {
            if (seq.get(i) instanceof CharStringCommand c && c.getType1KeyWord() == CharStringCommand.Type1KeyWord.SEAC) {
                if (i < 2 || !(seq.get(i - 2) instanceof Number b) || !(seq.get(i - 1) instanceof Number a)) {
                    return false;
                }
                String base = StandardEncoding.INSTANCE.getName(b.intValue());
                String accent = StandardEncoding.INSTANCE.getName(a.intValue());
                if (!font.hasGlyph(base) || !font.hasGlyph(accent)) {
                    return false;
                }
            }
        }
        return true;
    }

    private record Stem(boolean horizontal, double at, double size) {

        double lo() {
            return Math.min(at, at + size);
        }

        double hi() {
            return Math.max(at, at + size);
        }
    }

    private record Hints(List<Stem> h, List<Stem> v, Map<Integer, Set<Stem>> groups, int[] segments) {}

    private static final class Segments {

        private final List<Integer> types = new ArrayList<>();

        private final Map<Integer, Set<Stem>> groups = new TreeMap<>();

        private Set<Stem> pending;

        void add(int type) {
            int last = types.isEmpty() ? -1 : types.get(types.size() - 1);
            if (!(type == PathIterator.SEG_MOVETO && last == PathIterator.SEG_MOVETO)
                    && !(type == PathIterator.SEG_CLOSE && last == PathIterator.SEG_CLOSE)) {
                types.add(type);
            }
            if (pending != null) {
                groups.put(types.size() - 1, pending);
                pending = null;
            }
        }

        void draw(int type) {
            add(types.isEmpty() ? PathIterator.SEG_MOVETO : type);
        }

        void close() {
            if (!types.isEmpty()) {
                add(PathIterator.SEG_CLOSE);
            }
            add(PathIterator.SEG_MOVETO);
        }
    }

    private static Hints hints(List<Object> seq) {
        Set<Stem> h = new TreeSet<>(Type1ToCff::compare);
        Set<Stem> v = new TreeSet<>(Type1ToCff::compare);
        Segments segments = new Segments();
        Set<Stem> group = new LinkedHashSet<>();
        segments.pending = group;
        List<Number> stack = new ArrayList<>();
        double sbx = 0;
        boolean drawing = false;
        boolean stemmed = false;
        boolean flex = false;
        int flexPoints = 0;
        for (Object o : seq) {
            if (o instanceof Number n) {
                stack.add(n);
                continue;
            }
            if (!(o instanceof CharStringCommand c) || c.getType1KeyWord() == null) {
                return null;
            }
            CharStringCommand.Type1KeyWord k = c.getType1KeyWord();
            if (k == CharStringCommand.Type1KeyWord.DIV) {
                if (stack.size() < 2 || stack.get(stack.size() - 1).doubleValue() == 0) {
                    return null;
                }
                double b = stack.remove(stack.size() - 1).doubleValue();
                double a = stack.remove(stack.size() - 1).doubleValue();
                stack.add(a / b);
                continue;
            }
            boolean stem = false;
            int n = stack.size();
            switch (k) {
                case HSBW -> {
                    if (drawing || n < 2) {
                        return null;
                    }
                    sbx = stack.get(0).doubleValue();
                }
                case SBW, SEAC -> {
                    return null;
                }
                case SETCURRENTPOINT, POP, CALLSUBR, RET -> {
                    if (!drawing) {
                        return null;
                    }
                }
                case HSTEM, VSTEM, HSTEM3, VSTEM3 -> {
                    boolean horizontal = k == CharStringCommand.Type1KeyWord.HSTEM
                            || k == CharStringCommand.Type1KeyWord.HSTEM3;
                    int pairs = k == CharStringCommand.Type1KeyWord.HSTEM || k == CharStringCommand.Type1KeyWord.VSTEM
                            ? 1 : 3;
                    if (n != pairs * 2) {
                        return null;
                    }
                    if (drawing && !stemmed) {
                        group = new LinkedHashSet<>();
                        segments.pending = group;
                    }
                    for (int p = 0; p < pairs; p++) {
                        Stem s = new Stem(horizontal, stack.get(2 * p).doubleValue() + (horizontal ? 0 : sbx),
                                stack.get(2 * p + 1).doubleValue());
                        (horizontal ? h : v).add(s);
                        group.add(s);
                    }
                    stem = true;
                }
                case RMOVETO, HMOVETO, VMOVETO -> {
                    drawing = true;
                    if (n >= (k == CharStringCommand.Type1KeyWord.RMOVETO ? 2 : 1)) {
                        if (flex) {
                            flexPoints++;
                        } else {
                            segments.add(PathIterator.SEG_MOVETO);
                        }
                    }
                }
                case RLINETO, HLINETO, VLINETO -> {
                    drawing = true;
                    if (n >= (k == CharStringCommand.Type1KeyWord.RLINETO ? 2 : 1)) {
                        segments.draw(PathIterator.SEG_LINETO);
                    }
                }
                case RRCURVETO, VHCURVETO, HVCURVETO -> {
                    drawing = true;
                    if (n >= (k == CharStringCommand.Type1KeyWord.RRCURVETO ? 6 : 4)) {
                        segments.draw(PathIterator.SEG_CUBICTO);
                    }
                }
                case CLOSEPATH -> {
                    drawing = true;
                    segments.close();
                }
                case CALLOTHERSUBR -> {
                    drawing = true;
                    int which = n == 0 ? -1 : stack.get(0).intValue();
                    if (which == 1) {
                        flex = true;
                    } else if (which == 0) {
                        flex = false;
                        if (flexPoints >= 7) {
                            segments.draw(PathIterator.SEG_CUBICTO);
                            segments.draw(PathIterator.SEG_CUBICTO);
                            flexPoints = 0;
                        }
                    }
                }
                case ENDCHAR, DOTSECTION -> {
                }
                default -> {
                    return null;
                }
            }
            stemmed = stem;
            stack.clear();
        }
        return new Hints(new ArrayList<>(h), new ArrayList<>(v), segments.groups,
                segments.types.stream().mapToInt(Integer::intValue).toArray());
    }

    private static int compare(Stem a, Stem b) {
        int c = Double.compare(a.lo(), b.lo());
        if (c == 0) {
            c = Double.compare(a.hi(), b.hi());
        }
        return c == 0 ? Double.compare(a.at(), b.at()) : c;
    }

    private static boolean overlap(List<Stem> stems) {
        double end = Double.NEGATIVE_INFINITY;
        for (Stem s : stems) {
            if (s.lo() < end) {
                return true;
            }
            end = Math.max(end, s.hi());
        }
        return false;
    }

    private static List<Double> encodeStems(List<Stem> stems) {
        List<Double> out = new ArrayList<>();
        double edge = 0;
        for (Stem s : stems) {
            out.add(s.at() - edge);
            out.add(s.size());
            edge = s.at() + s.size();
        }
        return out;
    }

    private static int[] segments(GeneralPath path) {
        List<Integer> types = new ArrayList<>();
        float[] c = new float[6];
        for (PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
            types.add(it.currentSegment(c));
        }
        return types.stream().mapToInt(Integer::intValue).toArray();
    }

    private static Map<Integer, byte[]> masks(Hints hints) {
        List<Stem> all = new ArrayList<>(hints.h());
        all.addAll(hints.v());
        Map<Integer, byte[]> masks = new HashMap<>();
        for (Map.Entry<Integer, Set<Stem>> e : hints.groups().entrySet()) {
            byte[] mask = new byte[(all.size() + 7) / 8];
            for (int i = 0; i < all.size(); i++) {
                if (e.getValue().contains(all.get(i))) {
                    mask[i / 8] |= (byte) (0x80 >> i % 8);
                }
            }
            masks.put(e.getKey(), mask);
        }
        return masks;
    }

    private static void path(Type2 t, GeneralPath path, Map<Integer, byte[]> masks) {
        PathIterator it = path.getPathIterator(null);
        float[] c = new float[6];
        double cx = 0;
        double cy = 0;
        double sx = 0;
        double sy = 0;
        boolean open = false;
        List<double[]> run = new ArrayList<>();
        byte[] active = null;
        for (int index = 0; !it.isDone(); index++) {
            int seg = it.currentSegment(c);
            it.next();
            byte[] mask = masks.get(index);
            if (mask != null && !Arrays.equals(mask, active)) {
                Operators.encode(t, run);
                t.op(19).mask(mask);
                active = mask;
            }
            if (seg == PathIterator.SEG_QUADTO) {
                double x1 = cx + 2.0 / 3 * (c[0] - cx);
                double y1 = cy + 2.0 / 3 * (c[1] - cy);
                double x2 = c[2] + 2.0 / 3 * (c[0] - c[2]);
                double y2 = c[3] + 2.0 / 3 * (c[1] - c[3]);
                c = new float[] {(float) x1, (float) y1, (float) x2, (float) y2, c[2], c[3]};
                seg = PathIterator.SEG_CUBICTO;
            }
            if (seg == PathIterator.SEG_MOVETO || seg == PathIterator.SEG_CLOSE) {
                if (open && (cx != sx || cy != sy)) {
                    run.add(new double[] {sx - cx, sy - cy});
                    cx = sx;
                    cy = sy;
                }
                open = false;
                if (seg == PathIterator.SEG_CLOSE) {
                    continue;
                }
                Operators.encode(t, run);
                double x = q(c[0]);
                double y = q(c[1]);
                if (y == cy) {
                    t.arg(x - cx).op(22);
                } else if (x == cx) {
                    t.arg(y - cy).op(4);
                } else {
                    t.arg(x - cx).arg(y - cy).op(21);
                }
                cx = sx = x;
                cy = sy = y;
            } else if (seg == PathIterator.SEG_LINETO) {
                double x = q(c[0]);
                double y = q(c[1]);
                run.add(new double[] {x - cx, y - cy});
                cx = x;
                cy = y;
                open = true;
            } else {
                double[] d = new double[6];
                double px = cx;
                double py = cy;
                for (int k = 0; k < 6; k += 2) {
                    double x = q(c[k]);
                    double y = q(c[k + 1]);
                    d[k] = x - px;
                    d[k + 1] = y - py;
                    px = x;
                    py = y;
                }
                run.add(d);
                cx = px;
                cy = py;
                open = true;
            }
        }
        if (open && (cx != sx || cy != sy)) {
            run.add(new double[] {sx - cx, sy - cy});
        }
        Operators.encode(t, run);
    }

    private static double q(float v) {
        return Math.rint(v * 65536.0) / 65536.0;
    }

    private static final class Operators {

        private static final int RLINETO = 5;
        private static final int HLINETO = 6;
        private static final int VLINETO = 7;
        private static final int RRCURVETO = 8;
        private static final int RCURVELINE = 24;
        private static final int RLINECURVE = 25;
        private static final int VVCURVETO = 26;
        private static final int HHCURVETO = 27;
        private static final int VHCURVETO = 30;
        private static final int HVCURVETO = 31;

        private Operators() {}

        static void encode(Type2 t, List<double[]> run) {
            int i = 0;
            while (i < run.size()) {
                i = run.get(i).length == 2 ? lines(t, run, i) : curves(t, run, i);
            }
            run.clear();
        }

        private static boolean line(List<double[]> run, int i) {
            return i < run.size() && run.get(i).length == 2;
        }

        private static boolean curve(List<double[]> run, int i) {
            return i < run.size() && run.get(i).length == 6;
        }

        private static int lines(Type2 t, List<double[]> run, int i) {
            List<Double> args = new ArrayList<>();
            double[] s = run.get(i);
            if (s[0] == 0 || s[1] == 0) {
                boolean horizontal = s[1] == 0;
                int op = horizontal ? HLINETO : VLINETO;
                while (line(run, i) && args.size() < MAX_ARGS && run.get(i)[horizontal ? 1 : 0] == 0) {
                    args.add(run.get(i)[horizontal ? 0 : 1]);
                    horizontal = !horizontal;
                    i++;
                }
                emit(t, args, op);
                return i;
            }
            while (line(run, i) && run.get(i)[0] != 0 && run.get(i)[1] != 0 && args.size() + 2 <= MAX_ARGS) {
                args.add(run.get(i)[0]);
                args.add(run.get(i)[1]);
                i++;
            }
            if (curve(run, i) && args.size() + 6 <= MAX_ARGS && !special(run.get(i))) {
                add(args, run.get(i), 0, 6);
                emit(t, args, RLINECURVE);
                return i + 1;
            }
            emit(t, args, RLINETO);
            return i;
        }

        private static boolean special(double[] d) {
            return d[0] == 0 || d[1] == 0 || d[4] == 0 || d[5] == 0;
        }

        private static int curves(Type2 t, List<double[]> run, int i) {
            List<Double> args = new ArrayList<>();
            double[] d = run.get(i);
            boolean sh = d[1] == 0;
            boolean sv = d[0] == 0;
            boolean eh = d[5] == 0;
            boolean ev = d[4] == 0;
            if (sh && ev || sv && eh) {
                boolean horizontal = sh && ev;
                int op = horizontal ? HVCURVETO : VHCURVETO;
                while (curve(run, i) && args.size() + 5 <= MAX_ARGS) {
                    double[] c = run.get(i);
                    if (horizontal ? c[1] != 0 : c[0] != 0) {
                        break;
                    }
                    boolean turns = horizontal ? c[4] == 0 : c[5] == 0;
                    if (horizontal) {
                        args.add(c[0]);
                        args.add(c[2]);
                        args.add(c[3]);
                        args.add(c[5]);
                    } else {
                        args.add(c[1]);
                        args.add(c[2]);
                        args.add(c[3]);
                        args.add(c[4]);
                    }
                    i++;
                    if (!turns) {
                        args.add(horizontal ? c[4] : c[5]);
                        break;
                    }
                    horizontal = !horizontal;
                }
                emit(t, args, op);
                return i;
            }
            if (eh || ev) {
                boolean horizontal = eh;
                if (horizontal ? d[1] != 0 : d[0] != 0) {
                    args.add(horizontal ? d[1] : d[0]);
                }
                do {
                    double[] c = run.get(i);
                    if (horizontal) {
                        args.add(c[0]);
                        args.add(c[2]);
                        args.add(c[3]);
                        args.add(c[4]);
                    } else {
                        args.add(c[1]);
                        args.add(c[2]);
                        args.add(c[3]);
                        args.add(c[5]);
                    }
                    i++;
                } while (curve(run, i) && args.size() + 4 <= MAX_ARGS
                        && (horizontal ? run.get(i)[1] == 0 && run.get(i)[5] == 0 : run.get(i)[0] == 0 && run.get(i)[4] == 0));
                emit(t, args, horizontal ? HHCURVETO : VVCURVETO);
                return i;
            }
            if (sh || sv) {
                if (sh) {
                    args.add(d[0]);
                    args.add(d[2]);
                    args.add(d[3]);
                    args.add(d[5]);
                    args.add(d[4]);
                } else {
                    args.add(d[1]);
                    args.add(d[2]);
                    args.add(d[3]);
                    args.add(d[4]);
                    args.add(d[5]);
                }
                emit(t, args, sh ? HVCURVETO : VHCURVETO);
                return i + 1;
            }
            while (curve(run, i) && !special(run.get(i)) && args.size() + 6 <= MAX_ARGS) {
                add(args, run.get(i), 0, 6);
                i++;
            }
            if (line(run, i) && args.size() + 2 <= MAX_ARGS) {
                add(args, run.get(i), 0, 2);
                emit(t, args, RCURVELINE);
                return i + 1;
            }
            emit(t, args, RRCURVETO);
            return i;
        }

        private static void add(List<Double> args, double[] values, int from, int to) {
            for (int k = from; k < to; k++) {
                args.add(values[k]);
            }
        }

        private static void emit(Type2 t, List<Double> args, int op) {
            for (double v : args) {
                t.arg(v);
            }
            t.op(op);
        }
    }

    private static final class Type2 {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        private int args;

        Type2 arg(double v) {
            args++;
            double r = Math.rint(v);
            if (Math.abs(v - r) < 1e-9 && r >= -32768 && r <= 32767) {
                int i = (int) r;
                if (i >= -107 && i <= 107) {
                    out.write(i + 139);
                } else if (i >= 108 && i <= 1131) {
                    i -= 108;
                    out.write((i >> 8) + 247);
                    out.write(i);
                } else if (i >= -1131 && i <= -108) {
                    i = -i - 108;
                    out.write((i >> 8) + 251);
                    out.write(i);
                } else {
                    out.write(28);
                    out.write(i >> 8);
                    out.write(i);
                }
            } else {
                int f = (int) Math.round(v * 65536.0);
                out.write(255);
                out.write(f >> 24);
                out.write(f >> 16);
                out.write(f >> 8);
                out.write(f);
            }
            return this;
        }

        int size() {
            return args;
        }

        Type2 mask(byte[] mask) {
            out.writeBytes(mask);
            return this;
        }

        Type2 op(int op) {
            out.write(op);
            args = 0;
            return this;
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    private static final class Dict {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Dict number(Number n) {
            double v = n.doubleValue();
            if (n instanceof Float f) {
                v = new BigDecimal(Float.toString(f)).doubleValue();
            }
            double r = Math.rint(v);
            if (Math.abs(v - r) < 1e-9 && Math.abs(r) <= Integer.MAX_VALUE) {
                integer((int) r);
            } else {
                real(n instanceof Float f ? new BigDecimal(Float.toString(f)) : BigDecimal.valueOf(v));
            }
            return this;
        }

        private void integer(int i) {
            if (i >= -107 && i <= 107) {
                out.write(i + 139);
            } else if (i >= 108 && i <= 1131) {
                i -= 108;
                out.write((i >> 8) + 247);
                out.write(i);
            } else if (i >= -1131 && i <= -108) {
                i = -i - 108;
                out.write((i >> 8) + 251);
                out.write(i);
            } else if (i >= -32768 && i <= 32767) {
                out.write(28);
                out.write(i >> 8);
                out.write(i);
            } else {
                fixed(i);
            }
        }

        Dict fixed(int i) {
            out.write(29);
            out.write(i >> 24);
            out.write(i >> 16);
            out.write(i >> 8);
            out.write(i);
            return this;
        }

        private void real(BigDecimal d) {
            String s = d.stripTrailingZeros().toString().toUpperCase(Locale.ROOT);
            List<Integer> nibbles = new ArrayList<>();
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch >= '0' && ch <= '9') {
                    nibbles.add(ch - '0');
                } else if (ch == '.') {
                    nibbles.add(0xa);
                } else if (ch == '-') {
                    nibbles.add(0xe);
                } else if (ch == 'E') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '-') {
                        nibbles.add(0xc);
                        i++;
                    } else {
                        nibbles.add(0xb);
                        if (i + 1 < s.length() && s.charAt(i + 1) == '+') {
                            i++;
                        }
                    }
                }
            }
            nibbles.add(0xf);
            if (nibbles.size() % 2 == 1) {
                nibbles.add(0xf);
            }
            out.write(30);
            for (int i = 0; i < nibbles.size(); i += 2) {
                out.write(nibbles.get(i) << 4 | nibbles.get(i + 1));
            }
        }

        Dict op(int... op) {
            for (int b : op) {
                out.write(b);
            }
            return this;
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }
}
