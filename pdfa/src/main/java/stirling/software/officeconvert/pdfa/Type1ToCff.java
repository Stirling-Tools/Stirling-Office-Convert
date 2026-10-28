package stirling.software.officeconvert.pdfa;

import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

final class Type1ToCff {

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

    static byte[] convert(Type1Font font, Map<Integer, String> codes, Set<String> outlined) throws IOException {
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
        for (Map.Entry<String, TreeSet<Integer>> e : encoded.entrySet()) {
            codes.write(e.getValue().first());
            for (int code : e.getValue().tailSet(e.getValue().first(), false)) {
                int s = sid(e.getKey());
                sups.write(code);
                sups.write(s >> 8);
                sups.write(s);
                supCount++;
            }
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(supCount > 0 ? 0x80 : 0);
        o.write(encoded.size());
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
        if (hints != null && t.size() + hints.h.size() <= MAX_ARGS && hints.v.size() <= MAX_ARGS) {
            if (!hints.h.isEmpty()) {
                for (double v : hints.h) {
                    t.arg(v);
                }
                t.op(1);
            }
            if (!hints.v.isEmpty()) {
                for (double v : hints.v) {
                    t.arg(v);
                }
                t.op(3);
            }
        }
        path(t, cs.getPath());
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

    private record Hints(List<Double> h, List<Double> v) {}

    private static Hints hints(List<Object> seq) {
        List<double[]> h = new ArrayList<>();
        List<double[]> v = new ArrayList<>();
        List<Number> stack = new ArrayList<>();
        double sbx = 0;
        boolean drawing = false;
        for (Object o : seq) {
            if (o instanceof Number n) {
                stack.add(n);
                continue;
            }
            if (!(o instanceof CharStringCommand c) || c.getType1KeyWord() == null) {
                return null;
            }
            CharStringCommand.Type1KeyWord k = c.getType1KeyWord();
            boolean stem = k == CharStringCommand.Type1KeyWord.HSTEM || k == CharStringCommand.Type1KeyWord.VSTEM
                    || k == CharStringCommand.Type1KeyWord.HSTEM3 || k == CharStringCommand.Type1KeyWord.VSTEM3;
            if (drawing) {
                if (stem) {
                    return null;
                }
                stack.clear();
                continue;
            }
            if (k == CharStringCommand.Type1KeyWord.DIV) {
                if (stack.size() < 2 || stack.get(stack.size() - 1).doubleValue() == 0) {
                    return null;
                }
                double b = stack.remove(stack.size() - 1).doubleValue();
                double a = stack.remove(stack.size() - 1).doubleValue();
                stack.add(a / b);
                continue;
            }
            switch (k) {
                case HSBW -> {
                    if (stack.size() < 2) {
                        return null;
                    }
                    sbx = stack.get(0).doubleValue();
                }
                case SBW, SEAC, CALLOTHERSUBR, SETCURRENTPOINT, POP, CALLSUBR -> {
                    return null;
                }
                case HSTEM, VSTEM, HSTEM3, VSTEM3 -> {
                    boolean horizontal = c.getType1KeyWord() == CharStringCommand.Type1KeyWord.HSTEM
                            || c.getType1KeyWord() == CharStringCommand.Type1KeyWord.HSTEM3;
                    int pairs = c.getType1KeyWord() == CharStringCommand.Type1KeyWord.HSTEM
                            || c.getType1KeyWord() == CharStringCommand.Type1KeyWord.VSTEM ? 1 : 3;
                    if (stack.size() != pairs * 2) {
                        return null;
                    }
                    for (int p = 0; p < pairs; p++) {
                        double at = stack.get(2 * p).doubleValue() + (horizontal ? 0 : sbx);
                        double size = stack.get(2 * p + 1).doubleValue();
                        (horizontal ? h : v).add(new double[] {at, size});
                    }
                }
                case RMOVETO, HMOVETO, VMOVETO, RLINETO, HLINETO, VLINETO, RRCURVETO, VHCURVETO, HVCURVETO,
                        CLOSEPATH -> drawing = true;
                default -> {
                }
            }
            stack.clear();
        }
        List<Double> hs = encodeStems(h);
        List<Double> vs = encodeStems(v);
        return hs == null || vs == null ? null : new Hints(hs, vs);
    }

    private static List<Double> encodeStems(List<double[]> stems) {
        Map<String, double[]> unique = new LinkedHashMap<>();
        for (double[] s : stems) {
            unique.putIfAbsent(s[0] + "/" + s[1], s);
        }
        List<double[]> sorted = new ArrayList<>(unique.values());
        sorted.sort((a, b) -> Double.compare(Math.min(a[0], a[0] + a[1]), Math.min(b[0], b[0] + b[1])));
        List<Double> out = new ArrayList<>();
        double end = Double.NEGATIVE_INFINITY;
        double edge = 0;
        for (double[] s : sorted) {
            double lo = Math.min(s[0], s[0] + s[1]);
            double hi = Math.max(s[0], s[0] + s[1]);
            if (lo < end) {
                return null;
            }
            out.add(s[0] - edge);
            out.add(s[1]);
            edge = s[0] + s[1];
            end = hi;
        }
        return out;
    }

    private static void path(Type2 t, GeneralPath path) {
        PathIterator it = path.getPathIterator(null);
        float[] c = new float[6];
        double cx = 0;
        double cy = 0;
        double sx = 0;
        double sy = 0;
        boolean open = false;
        List<Double> batch = new ArrayList<>();
        int batchOp = 0;
        while (!it.isDone()) {
            int seg = it.currentSegment(c);
            it.next();
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
                    batchOp = flushIf(t, batch, batchOp, 5, 2);
                    batch.add(sx - cx);
                    batch.add(sy - cy);
                    cx = sx;
                    cy = sy;
                }
                open = false;
                if (seg == PathIterator.SEG_CLOSE) {
                    continue;
                }
                batchOp = flush(t, batch, batchOp);
                double x = q(c[0]);
                double y = q(c[1]);
                t.arg(x - cx).arg(y - cy).op(21);
                cx = sx = x;
                cy = sy = y;
            } else if (seg == PathIterator.SEG_LINETO) {
                double x = q(c[0]);
                double y = q(c[1]);
                batchOp = flushIf(t, batch, batchOp, 5, 2);
                batch.add(x - cx);
                batch.add(y - cy);
                cx = x;
                cy = y;
                open = true;
            } else {
                batchOp = flushIf(t, batch, batchOp, 8, 6);
                double px = cx;
                double py = cy;
                for (int k = 0; k < 6; k += 2) {
                    double x = q(c[k]);
                    double y = q(c[k + 1]);
                    batch.add(x - px);
                    batch.add(y - py);
                    px = x;
                    py = y;
                }
                cx = px;
                cy = py;
                open = true;
            }
        }
        if (open && (cx != sx || cy != sy)) {
            batchOp = flushIf(t, batch, batchOp, 5, 2);
            batch.add(sx - cx);
            batch.add(sy - cy);
        }
        flush(t, batch, batchOp);
    }

    private static int flushIf(Type2 t, List<Double> batch, int batchOp, int op, int add) {
        if (batchOp != op || batch.size() + add > MAX_ARGS) {
            flush(t, batch, batchOp);
        }
        return op;
    }

    private static int flush(Type2 t, List<Double> batch, int batchOp) {
        if (!batch.isEmpty()) {
            for (double v : batch) {
                t.arg(v);
            }
            t.op(batchOp);
            batch.clear();
        }
        return 0;
    }

    private static double q(float v) {
        return Math.rint(v * 65536.0) / 65536.0;
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
