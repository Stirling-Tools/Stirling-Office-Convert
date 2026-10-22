package stirling.software.officeconvert.pdfa;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.apache.fontbox.cff.CFFCIDFont;
import org.apache.fontbox.cff.CFFFont;
import org.apache.fontbox.cff.CFFParser;
import org.apache.fontbox.cff.CFFType1Font;
import org.apache.fontbox.cff.Type2CharString;
import org.apache.fontbox.type1.Type1Font;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDCIDFont;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.encoding.Encoding;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;

import stirling.software.officeconvert.extract.PdfFiles;

public final class WebFonts {

    public enum FontKind {
        TYPE1,
        CFF
    }

    private static final int UNITS_PER_EM = 1000;

    private static final int MAX_CODE = 0xFFFF;

    private WebFonts() {}

    public static byte[] toTrueType(byte[] fontProgram, FontKind kind, Map<Integer, Integer> unicodeByGlyph)
            throws IOException {
        return convert(Program.read(fontProgram, kind, -1, -1), unicodeByGlyph);
    }

    public static byte[] toTrueType(PDFont font) throws IOException {
        PDFontDescriptor fd = font.getFontDescriptor();
        if (fd == null) {
            throw new IOException("The font has no descriptor");
        }
        Program program;
        if (fd.getFontFile3() != null) {
            String subtype = fd.getFontFile3().getCOSObject().getNameAsString(COSName.SUBTYPE);
            if (subtype != null && !"Type1C".equals(subtype) && !"CIDFontType0C".equals(subtype)) {
                throw new IOException("FontFile3 /" + subtype + " is not a bare CFF program");
            }
            program = Program.read(bytes(fd.getFontFile3()), FontKind.CFF, -1, -1);
        } else if (fd.getFontFile() != null) {
            PDStream s = fd.getFontFile();
            program = Program.read(bytes(s), FontKind.TYPE1, s.getCOSObject().getInt(COSName.LENGTH1),
                    s.getCOSObject().getInt(COSName.LENGTH2));
        } else {
            throw new IOException("The font has no embedded Type 1 or CFF program");
        }
        return convert(program, unicodeByGlyph(font, program));
    }

    private static byte[] bytes(PDStream s) throws IOException {
        try (InputStream in = s.createInputStream()) {
            return in.readAllBytes();
        }
    }

    private static Map<Integer, Integer> unicodeByGlyph(PDFont font, Program program) throws IOException {
        Map<Integer, Integer> out = new HashMap<>();
        if (font instanceof PDType0Font t0) {
            PDCIDFont cid = t0.getDescendantFont();
            for (int code = 0; code <= MAX_CODE; code++) {
                if ((code & 0xFF) == 0) {
                    PdfFiles.stopIfInterrupted();
                }
                Integer cp = single(t0.toUnicode(code));
                if (cp != null) {
                    int gid = cid.codeToGID(code);
                    if (gid > 0 && gid < program.count()) {
                        out.putIfAbsent(gid, cp);
                    }
                }
            }
        } else if (font instanceof PDSimpleFont simple) {
            Encoding enc = simple.getEncoding();
            for (int code = 0; code < 256; code++) {
                Integer cp = single(simple.toUnicode(code));
                String name = enc == null ? null : enc.getName(code);
                int gid = name == null ? 0 : program.gid(name);
                if (cp != null && gid > 0) {
                    out.putIfAbsent(gid, cp);
                }
            }
        }
        return out;
    }

    private static Integer single(String s) {
        return s != null && !s.isEmpty() && s.codePointCount(0, s.length()) == 1 ? s.codePointAt(0) : null;
    }

    private static byte[] convert(Program program, Map<Integer, Integer> unicodeByGlyph) throws IOException {
        TrueTypeWriter w = new TrueTypeWriter(UNITS_PER_EM);
        AffineTransform toEm = program.toEm();
        w.replaceNotdef(program.path(0, toEm), program.advance(0, toEm));
        for (int gid = 1; gid < program.count(); gid++) {
            PdfFiles.stopIfInterrupted();
            w.addOutline(program.path(gid, toEm), program.advance(gid, toEm));
        }
        TreeMap<Integer, Integer> cmap = cmap(program, unicodeByGlyph);
        TreeMap<Integer, Integer> bmp = new TreeMap<>(cmap.headMap(0xFFFF));
        List<Cmaps.Subtable> subtables = new ArrayList<>();
        subtables.add(new Cmaps.Subtable(3, 1, bmp));
        if (!cmap.isEmpty() && cmap.lastKey() > 0xFFFF) {
            subtables.add(new Cmaps.Subtable(3, 10, cmap));
        }
        TrueTypeWriter.Metrics m = w.metrics();
        int ascent = Math.max(m.yMax(), 800);
        int descent = Math.min(m.yMin(), -200);
        Map<String, byte[]> tables = new HashMap<>();
        tables.put("head", head(m, program.style()));
        tables.put("hhea", hhea(m, ascent, descent, w.size()));
        tables.put("OS/2", os2(m, ascent, descent, cmap, program.style()));
        tables.put("post", post(program.italicAngle()));
        return w.build(FontRebuild.cleanName(program.name()), Cmaps.table(subtables), tables);
    }

    private static TreeMap<Integer, Integer> cmap(Program program, Map<Integer, Integer> unicodeByGlyph) {
        TreeMap<Integer, Integer> cmap = new TreeMap<>();
        if (unicodeByGlyph != null) {
            for (Map.Entry<Integer, Integer> e : new TreeMap<>(unicodeByGlyph).entrySet()) {
                int gid = e.getKey();
                if (gid > 0 && gid < program.count() && e.getValue() != null && mappable(e.getValue())) {
                    cmap.putIfAbsent(e.getValue(), gid);
                }
            }
        }
        for (int gid = 1; gid < program.count(); gid++) {
            String name = program.name(gid);
            String u = name == null || name.startsWith("cid") ? null : GlyphList.getAdobeGlyphList().toUnicode(name);
            Integer cp = single(u);
            if (cp != null && mappable(cp)) {
                cmap.putIfAbsent(cp, gid);
            }
        }
        return cmap;
    }

    private static boolean mappable(int cp) {
        return cp > 0 && cp <= 0x10FFFF && cp != 0xFFFF && cp != 0xFFFE && (cp < 0xD800 || cp > 0xDFFF);
    }

    private static byte[] head(TrueTypeWriter.Metrics m, int style) {
        ByteBuffer b = ByteBuffer.allocate(54);
        b.putInt(0, 0x00010000);
        b.putInt(4, 0x00010000);
        b.putInt(12, 0x5F0F3CF5);
        b.putShort(16, (short) 0x000B);
        b.putShort(18, (short) UNITS_PER_EM);
        b.putShort(36, (short) m.xMin());
        b.putShort(38, (short) m.yMin());
        b.putShort(40, (short) m.xMax());
        b.putShort(42, (short) m.yMax());
        b.putShort(44, (short) style);
        b.putShort(46, (short) 8);
        b.putShort(48, (short) 2);
        b.putShort(50, (short) 1);
        return b.array();
    }

    private static byte[] hhea(TrueTypeWriter.Metrics m, int ascent, int descent, int glyphs) {
        ByteBuffer b = ByteBuffer.allocate(36);
        b.putInt(0, 0x00010000);
        b.putShort(4, (short) ascent);
        b.putShort(6, (short) descent);
        b.putShort(10, (short) m.maxAdvance());
        b.putShort(12, (short) m.minLsb());
        b.putShort(14, (short) m.minRsb());
        b.putShort(16, (short) m.maxExtent());
        b.putShort(18, (short) 1);
        b.putShort(34, (short) glyphs);
        return b.array();
    }

    private static byte[] os2(TrueTypeWriter.Metrics m, int ascent, int descent, TreeMap<Integer, Integer> cmap,
            int style) {
        boolean bold = (style & 1) != 0;
        boolean italic = (style & 2) != 0;
        ByteBuffer b = ByteBuffer.allocate(96);
        b.putShort(0, (short) 4);
        b.putShort(2, (short) m.averageAdvance());
        b.putShort(4, (short) (bold ? 700 : 400));
        b.putShort(6, (short) 5);
        b.putShort(10, (short) 650);
        b.putShort(12, (short) 600);
        b.putShort(16, (short) 75);
        b.putShort(18, (short) 650);
        b.putShort(20, (short) 600);
        b.putShort(24, (short) 350);
        b.putShort(26, (short) 50);
        b.putShort(28, (short) 300);
        b.put(58, "SPDF".getBytes(StandardCharsets.US_ASCII));
        b.putShort(62, (short) ((bold ? 0x20 : 0) | (italic ? 0x01 : 0) | (bold || italic ? 0 : 0x40)));
        b.putShort(64, (short) (cmap.isEmpty() ? 0x20 : Math.min(0xFFFF, cmap.firstKey())));
        b.putShort(66, (short) (cmap.isEmpty() ? 0x7E : Math.min(0xFFFF, cmap.lastKey())));
        b.putShort(68, (short) ascent);
        b.putShort(70, (short) descent);
        b.putShort(74, (short) Math.max(0, m.yMax()));
        b.putShort(76, (short) Math.max(0, -m.yMin()));
        b.putInt(78, 1);
        b.putShort(86, (short) 500);
        b.putShort(88, (short) 700);
        b.putShort(92, (short) 32);
        return b.array();
    }

    private static byte[] post(float italicAngle) {
        ByteBuffer b = ByteBuffer.allocate(32);
        b.putInt(0, 0x00030000);
        b.putInt(4, Math.round(italicAngle * 65_536f));
        b.putShort(8, (short) -100);
        b.putShort(10, (short) 50);
        return b.array();
    }

    private abstract static class Program {

        abstract int count();

        abstract String name();

        abstract String name(int gid);

        abstract int gid(String name);

        abstract GeneralPath rawPath(int gid) throws IOException;

        abstract double rawWidth(int gid) throws IOException;

        abstract List<Number> matrix();

        abstract String weight();

        abstract float italicAngle();

        int style() {
            String weight = weight() == null ? "" : weight().toLowerCase(Locale.ROOT);
            boolean bold = weight.contains("bold") || weight.contains("black") || weight.contains("heavy");
            return (bold ? 1 : 0) | (italicAngle() != 0 ? 2 : 0);
        }

        AffineTransform toEm() {
            List<Number> m = matrix();
            if (m == null || m.size() < 6) {
                return new AffineTransform();
            }
            AffineTransform t = AffineTransform.getScaleInstance(UNITS_PER_EM, UNITS_PER_EM);
            t.concatenate(new AffineTransform(m.get(0).doubleValue(), m.get(1).doubleValue(), m.get(2).doubleValue(),
                    m.get(3).doubleValue(), m.get(4).doubleValue(), m.get(5).doubleValue()));
            return t;
        }

        GeneralPath path(int gid, AffineTransform toEm) throws IOException {
            GeneralPath p;
            try {
                p = rawPath(gid);
            } catch (IOException | RuntimeException e) {
                p = null;
            }
            if (p == null) {
                return new GeneralPath();
            }
            GeneralPath out = new GeneralPath(p);
            out.transform(toEm);
            return out;
        }

        int advance(int gid, AffineTransform toEm) {
            double w;
            try {
                w = rawWidth(gid);
            } catch (IOException | RuntimeException e) {
                w = 0;
            }
            return (int) Math.rint(w * toEm.getScaleX());
        }

        static Program read(byte[] data, FontKind kind, int length1, int length2) throws IOException {
            if (data == null || data.length == 0) {
                throw new IOException("The font program is empty");
            }
            try {
                return kind == FontKind.CFF ? new Cff(data) : new Type1(data, length1, length2);
            } catch (RuntimeException e) {
                throw new IOException("The font program cannot be read: " + e.getMessage(), e);
            }
        }
    }

    private static final class Cff extends Program {

        private final CFFFont font;

        Cff(byte[] data) throws IOException {
            List<CFFFont> fonts = new CFFParser().parse(new RandomAccessReadBuffer(data));
            if (fonts.isEmpty()) {
                throw new IOException("The CFF program has no font");
            }
            font = fonts.get(0);
            if (font.getNumCharStrings() < 1) {
                throw new IOException("The CFF program has no glyphs");
            }
        }

        @Override
        int count() {
            return font.getNumCharStrings();
        }

        @Override
        String name() {
            return font.getName();
        }

        @Override
        String name(int gid) {
            if (font instanceof CFFCIDFont) {
                return "cid" + font.getCharset().getCIDForGID(gid);
            }
            return font.getCharset().getNameForGID(gid);
        }

        @Override
        int gid(String name) {
            if (font instanceof CFFCIDFont) {
                return 0;
            }
            int sid = font.getCharset().getSID(name);
            return sid < 0 ? 0 : font.getCharset().getGIDForSID(sid);
        }

        private Type2CharString charString(int gid) throws IOException {
            if (font instanceof CFFCIDFont cid) {
                return cid.getType2CharString(font.getCharset().getCIDForGID(gid));
            }
            return font.getType2CharString(gid);
        }

        @Override
        GeneralPath rawPath(int gid) throws IOException {
            return charString(gid).getPath();
        }

        @Override
        double rawWidth(int gid) throws IOException {
            Map<String, Object> priv = privateDict(gid);
            Double operand = Type2Width.operand(font.getCharStringBytes().get(gid));
            if (operand == null || priv == null) {
                return charString(gid).getWidth();
            }
            return Double.isNaN(operand) ? number(priv.get("defaultWidthX"))
                    : number(priv.get("nominalWidthX")) + operand;
        }

        private Map<String, Object> privateDict(int gid) {
            if (font instanceof CFFCIDFont cid) {
                List<Map<String, Object>> dicts = cid.getPrivDicts();
                int fd = cid.getFdSelect() == null ? 0 : cid.getFdSelect().getFDIndex(gid);
                return fd >= 0 && fd < dicts.size() ? dicts.get(fd) : null;
            }
            return font instanceof CFFType1Font t1 ? t1.getPrivateDict() : null;
        }

        private static double number(Object o) {
            return o instanceof Number n ? n.doubleValue() : 0;
        }

        @Override
        List<Number> matrix() {
            return font.getFontMatrix();
        }

        @Override
        String weight() {
            Object w = font.getTopDict().get("Weight");
            return w instanceof String s ? s : null;
        }

        @Override
        float italicAngle() {
            Object a = font.getTopDict().get("ItalicAngle");
            return a instanceof Number n ? n.floatValue() : 0;
        }
    }

    private static final class Type1 extends Program {

        private final Type1Font font;

        private final List<String> names = new ArrayList<>();

        private final Map<String, Integer> gids = new HashMap<>();

        Type1(byte[] data, int length1, int length2) throws IOException {
            font = parse(data, length1, length2);
            names.add(".notdef");
            for (String n : font.getCharStringsDict().keySet()) {
                if (!".notdef".equals(n)) {
                    names.add(n);
                }
            }
            for (int i = 0; i < names.size(); i++) {
                gids.put(names.get(i), i);
            }
        }

        private static Type1Font parse(byte[] data, int length1, int length2) throws IOException {
            if (data.length > 6 && (data[0] & 0xFF) == 0x80 && data[1] == 1) {
                return Type1Font.createWithPFB(data);
            }
            int split = length1 > 0 && length1 < data.length && isEexecEnd(data, length1) ? length1 : eexecEnd(data);
            if (split < 0) {
                throw new IOException("The Type 1 program has no eexec section");
            }
            int end = length2 > 0 && split + length2 <= data.length ? split + length2 : trailerStart(data, split);
            byte[] clear = java.util.Arrays.copyOfRange(data, 0, split);
            byte[] binary = java.util.Arrays.copyOfRange(data, split, end);
            return Type1Font.createWithSegments(clear, binary);
        }

        private static boolean isEexecEnd(byte[] data, int at) {
            String before = new String(data, Math.max(0, at - 32), Math.min(32, at), StandardCharsets.ISO_8859_1);
            return before.contains("eexec");
        }

        private static int eexecEnd(byte[] data) {
            String s = new String(data, 0, Math.min(data.length, 65_536), StandardCharsets.ISO_8859_1);
            int at = s.indexOf("eexec");
            if (at < 0) {
                return -1;
            }
            at += 5;
            while (at < data.length && (data[at] == '\r' || data[at] == '\n' || data[at] == ' ' || data[at] == '\t')) {
                at++;
            }
            return at;
        }

        private static int trailerStart(byte[] data, int from) {
            int tail = Math.max(from, data.length - 2048);
            String s = new String(data, tail, data.length - tail, StandardCharsets.ISO_8859_1);
            int mark = s.lastIndexOf("cleartomark");
            if (mark < 0) {
                return data.length;
            }
            int at = tail + mark;
            while (at > from && (data[at - 1] == '0' || data[at - 1] == '\r' || data[at - 1] == '\n'
                    || data[at - 1] == ' ' || data[at - 1] == '\t')) {
                at--;
            }
            return at;
        }

        @Override
        int count() {
            return names.size();
        }

        @Override
        String name() {
            return font.getFontName();
        }

        @Override
        String name(int gid) {
            return gid >= 0 && gid < names.size() ? names.get(gid) : null;
        }

        @Override
        int gid(String name) {
            Integer g = gids.get(name);
            return g == null ? 0 : g;
        }

        @Override
        GeneralPath rawPath(int gid) throws IOException {
            return font.getPath(names.get(gid));
        }

        @Override
        double rawWidth(int gid) throws IOException {
            Double w = Type1Width.of(font.getCharStringsDict().get(names.get(gid)));
            return w != null ? w : font.getWidth(names.get(gid));
        }

        @Override
        List<Number> matrix() {
            return font.getFontMatrix();
        }

        @Override
        String weight() {
            return font.getWeight();
        }

        @Override
        float italicAngle() {
            return font.getItalicAngle();
        }
    }
}
