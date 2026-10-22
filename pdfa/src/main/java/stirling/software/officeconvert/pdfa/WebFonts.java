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
import java.util.function.IntFunction;

import org.apache.fontbox.cff.CFFCIDFont;
import org.apache.fontbox.cff.CFFFont;
import org.apache.fontbox.cff.CFFParser;
import org.apache.fontbox.cff.CFFType1Font;
import org.apache.fontbox.cff.Type2CharString;
import org.apache.fontbox.encoding.StandardEncoding;
import org.apache.fontbox.ttf.CmapSubtable;
import org.apache.fontbox.ttf.CmapTable;
import org.apache.fontbox.ttf.HorizontalHeaderTable;
import org.apache.fontbox.ttf.HorizontalMetricsTable;
import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.PostScriptTable;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TTFTable;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.fontbox.type1.Type1Font;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDCIDFont;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.encoding.Encoding;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;

import stirling.software.officeconvert.extract.PdfFiles;

public final class WebFonts {

    public enum FontKind {
        TYPE1,
        CFF,
        TRUETYPE
    }

    private static final int UNITS_PER_EM = 1000;

    private static final int MAX_CODE = 0xFFFF;

    private static final long MIN_GLYPH_BYTES = 16L << 20;

    private WebFonts() {}

    public static byte[] toTrueType(byte[] fontProgram, FontKind kind, Map<Integer, Integer> unicodeByGlyph)
            throws IOException {
        if (kind == FontKind.TRUETYPE) {
            return fromSfnt(fontProgram, null, unicodeByGlyph);
        }
        return convert(Program.read(fontProgram, kind, -1, -1), unicodeByGlyph);
    }

    private static byte[] fromSfnt(byte[] data, PDFont font, Map<Integer, Integer> unicodeByGlyph) throws IOException {
        if (data == null || data.length < 12) {
            throw new IOException("The font program is empty");
        }
        TrueTypeFont ttf;
        try {
            boolean otto = data[0] == 'O' && data[1] == 'T' && data[2] == 'T' && data[3] == 'O';
            ttf = (otto ? new OTFParser(true) : new TTFParser(true)).parse(new RandomAccessReadBuffer(data));
        } catch (IOException | RuntimeException e) {
            throw new IOException("The TrueType program cannot be read: " + e.getMessage(), e);
        }
        try (ttf) {
            TTFTable cff = ttf.getTableMap().get("CFF ");
            if (ttf.getTableMap().get("glyf") == null && cff != null) {
                Program program = Program.read(ttf.getTableBytes(cff), FontKind.CFF, -1, -1);
                return convert(program, font == null ? unicodeByGlyph : unicodeByGlyph(font, program.count(),
                        code -> font instanceof PDTrueTypeFont tt && !tt.isDamaged() ? tt.codeToGID(code)
                                : program.gid(name(font, code))));
            }
            Map<Integer, Integer> mapping = unicodeByGlyph;
            if (font != null && font.isDamaged()) {
                throw new IOException("PDFBox could not read the embedded font program");
            }
            if (font != null) {
                mapping = unicodeByGlyph(font, ttf.getNumberOfGlyphs(),
                        code -> font instanceof PDTrueTypeFont tt ? tt.codeToGID(code) : 0);
            }
            String name = ttf.getName() != null ? ttf.getName() : font != null ? font.getName() : null;
            return convertTrueType(ttf, name, mapping);
        } catch (RuntimeException e) {
            throw new IOException("The TrueType program cannot be converted: " + e.getMessage(), e);
        }
    }

    private static String name(PDFont font, int code) {
        Encoding enc = font instanceof PDSimpleFont s ? s.getEncoding() : null;
        return enc == null ? null : enc.getName(code);
    }

    private static byte[] convertTrueType(TrueTypeFont ttf, String name, Map<Integer, Integer> unicodeByGlyph)
            throws IOException {
        int count = ttf.getNumberOfGlyphs();
        if (count < 1) {
            throw new IOException("The TrueType program has no glyphs");
        }
        TrueTypeWriter w = new TrueTypeWriter(ttf);
        HorizontalMetricsTable hmtx = ttf.getHorizontalMetrics();
        long budget = glyphBudget(ttf.getTableMap().get("glyf").getLength());
        for (int gid = 1; gid < count; gid++) {
            PdfFiles.stopIfInterrupted();
            w.addCopy(gid, hmtx == null ? 0 : hmtx.getAdvanceWidth(gid));
            checkGlyphBytes(w, budget);
        }
        w.keepComponentIds();
        PostScriptTable post = ttf.getPostScript();
        TreeMap<Integer, Integer> cmap = cmap(count, gid -> post == null ? null : post.getName(gid), unicodeByGlyph,
                ownUnicode(ttf, count));
        Map<String, byte[]> tables = new HashMap<>();
        TTFTable os2 = ttf.getTableMap().get("OS/2");
        if (os2 == null || !saneOs2(ttf.getTableBytes(os2))) {
            int upem = w.unitsPerEm();
            TrueTypeWriter.Metrics m = w.metrics();
            HorizontalHeaderTable hhea = ttf.getHorizontalHeader();
            int ascent = hhea != null && hhea.getAscender() > 0 ? hhea.getAscender() : Math.max(m.yMax(), upem * 4 / 5);
            int descent = hhea != null && hhea.getDescender() < 0 ? hhea.getDescender()
                    : Math.min(m.yMin(), -upem / 5);
            int style = ttf.getHeader() == null ? 0 : ttf.getHeader().getMacStyle() & 3;
            tables.put("OS/2", os2(m, ascent, descent, cmap, style, upem));
        }
        return w.build(FontRebuild.cleanName(name), cmapTable(cmap), tables);
    }

    private static boolean saneOs2(byte[] t) {
        if (t.length < 78) {
            return false;
        }
        int version = ByteBuffer.wrap(t).getShort(0) & 0xFFFF;
        int needed = version == 0 ? 78 : version == 1 ? 86 : version <= 4 ? 96 : version == 5 ? 100 : Integer.MAX_VALUE;
        return t.length >= needed;
    }

    private static Map<Integer, Integer> ownUnicode(TrueTypeFont ttf, int count) throws IOException {
        Map<Integer, Integer> out = new HashMap<>();
        CmapTable table = ttf.getCmap();
        if (table == null) {
            return out;
        }
        for (CmapSubtable s : table.getCmaps()) {
            boolean unicode = s.getPlatformId() == 0 || s.getPlatformId() == 3 && (s.getPlatformEncodingId() == 1
                    || s.getPlatformEncodingId() == 10);
            if (!unicode) {
                continue;
            }
            for (int gid = 1; gid < count; gid++) {
                List<Integer> codes = s.getCharCodes(gid);
                if (codes != null) {
                    for (int cp : codes) {
                        out.putIfAbsent(cp, gid);
                    }
                }
            }
        }
        return out;
    }

    public static byte[] toTrueType(PDFont font) throws IOException {
        PDFontDescriptor fd = font.getFontDescriptor();
        if (fd == null) {
            throw new IOException("The font has no descriptor");
        }
        if (font instanceof PDType0Font && font.isDamaged()) {
            throw new IOException("PDFBox could not read the embedded font program");
        }
        Program program;
        if (fd.getFontFile2() != null) {
            return fromSfnt(bytes(fd.getFontFile2()), font, null);
        }
        if (fd.getFontFile3() != null) {
            String subtype = fd.getFontFile3().getCOSObject().getNameAsString(COSName.SUBTYPE);
            if ("OpenType".equals(subtype)) {
                return fromSfnt(bytes(fd.getFontFile3()), font, null);
            }
            if (subtype != null && !"Type1C".equals(subtype) && !"CIDFontType0C".equals(subtype)) {
                throw new IOException("FontFile3 /" + subtype + " is not a supported font program");
            }
            program = Program.read(bytes(fd.getFontFile3()), FontKind.CFF, -1, -1);
        } else if (fd.getFontFile() != null) {
            PDStream s = fd.getFontFile();
            program = Program.read(bytes(s), FontKind.TYPE1, s.getCOSObject().getInt(COSName.LENGTH1),
                    s.getCOSObject().getInt(COSName.LENGTH2));
        } else {
            throw new IOException("The font has no embedded font program");
        }
        Program p = program;
        return convert(p, unicodeByGlyph(font, p.count(), code -> p.gid(name(font, code))));
    }

    private static byte[] bytes(PDStream s) throws IOException {
        try (InputStream in = s.createInputStream()) {
            return in.readAllBytes();
        }
    }

    private static Map<Integer, Integer> unicodeByGlyph(PDFont font, int count, CodeToGlyph simpleGlyph)
            throws IOException {
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
                    if (gid > 0 && gid < count) {
                        out.putIfAbsent(gid, cp);
                    }
                }
            }
        } else if (font instanceof PDSimpleFont simple) {
            for (int code = 0; code < 256; code++) {
                Integer cp = single(simple.toUnicode(code));
                int gid = cp == null ? 0 : simpleGlyph.gid(code);
                if (gid > 0 && gid < count) {
                    out.putIfAbsent(gid, cp);
                }
            }
        }
        return out;
    }

    private interface CodeToGlyph {
        int gid(int code) throws IOException;
    }

    private static Integer single(String s) {
        return s != null && !s.isEmpty() && s.codePointCount(0, s.length()) == 1 ? s.codePointAt(0) : null;
    }

    private static byte[] convert(Program program, Map<Integer, Integer> unicodeByGlyph) throws IOException {
        try {
            return outlines(program, unicodeByGlyph);
        } catch (RuntimeException e) {
            throw new IOException("The font program cannot be converted: " + e.getMessage(), e);
        }
    }

    private static byte[] outlines(Program program, Map<Integer, Integer> unicodeByGlyph) throws IOException {
        program.checkExpansion();
        TrueTypeWriter w = new TrueTypeWriter(UNITS_PER_EM);
        AffineTransform toEm = program.toEm();
        w.replaceNotdef(program.path(0, toEm), program.advance(0, toEm));
        long budget = glyphBudget(program.sourceBytes);
        for (int gid = 1; gid < program.count(); gid++) {
            PdfFiles.stopIfInterrupted();
            w.addOutline(program.path(gid, toEm), program.advance(gid, toEm));
            checkGlyphBytes(w, budget);
        }
        TreeMap<Integer, Integer> cmap = cmap(program.count(), program::name, unicodeByGlyph, Map.of());
        TrueTypeWriter.Metrics m = w.metrics();
        int ascent = Math.max(m.yMax(), 800);
        int descent = Math.min(m.yMin(), -200);
        Map<String, byte[]> tables = new HashMap<>();
        tables.put("head", head(m, program.style()));
        tables.put("hhea", hhea(m, ascent, descent, w.size()));
        tables.put("OS/2", os2(m, ascent, descent, cmap, program.style(), UNITS_PER_EM));
        tables.put("post", post(program.italicAngle()));
        return w.build(FontRebuild.cleanName(program.name()), cmapTable(cmap), tables);
    }

    private static long glyphBudget(long sourceBytes) {
        return Math.max(MIN_GLYPH_BYTES, 8 * sourceBytes);
    }

    private static void checkGlyphBytes(TrueTypeWriter w, long budget) throws IOException {
        if (w.glyphBytes() > budget) {
            throw new IOException("The converted glyphs need more than " + budget + " bytes");
        }
    }

    private static byte[] cmapTable(TreeMap<Integer, Integer> cmap) {
        List<Cmaps.Subtable> subtables = new ArrayList<>();
        subtables.add(new Cmaps.Subtable(3, 1, new TreeMap<>(cmap.headMap(0xFFFF))));
        if (!cmap.isEmpty() && cmap.lastKey() > 0xFFFF) {
            subtables.add(new Cmaps.Subtable(3, 10, cmap));
        }
        return Cmaps.table(subtables);
    }

    private static TreeMap<Integer, Integer> cmap(int count, IntFunction<String> nameOf,
            Map<Integer, Integer> unicodeByGlyph, Map<Integer, Integer> ownByCodePoint) {
        TreeMap<Integer, Integer> cmap = new TreeMap<>();
        if (unicodeByGlyph != null) {
            for (Map.Entry<Integer, Integer> e : new TreeMap<>(unicodeByGlyph).entrySet()) {
                int gid = e.getKey();
                if (gid > 0 && gid < count && e.getValue() != null && mappable(e.getValue())) {
                    cmap.putIfAbsent(e.getValue(), gid);
                }
            }
        }
        for (int gid = 1; gid < count; gid++) {
            String name = nameOf.apply(gid);
            String u = name == null || name.startsWith("cid") || ".notdef".equals(name) ? null
                    : GlyphList.getAdobeGlyphList().toUnicode(name);
            Integer cp = single(u);
            if (cp != null && mappable(cp)) {
                cmap.putIfAbsent(cp, gid);
            }
        }
        for (Map.Entry<Integer, Integer> e : new TreeMap<>(ownByCodePoint).entrySet()) {
            if (e.getValue() > 0 && e.getValue() < count && mappable(e.getKey())) {
                cmap.putIfAbsent(e.getKey(), e.getValue());
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
            int style, int upem) {
        boolean bold = (style & 1) != 0;
        boolean italic = (style & 2) != 0;
        ByteBuffer b = ByteBuffer.allocate(96);
        b.putShort(0, (short) 4);
        b.putShort(2, (short) m.averageAdvance());
        b.putShort(4, (short) (bold ? 700 : 400));
        b.putShort(6, (short) 5);
        b.putShort(10, (short) (650 * upem / 1000));
        b.putShort(12, (short) (600 * upem / 1000));
        b.putShort(16, (short) (75 * upem / 1000));
        b.putShort(18, (short) (650 * upem / 1000));
        b.putShort(20, (short) (600 * upem / 1000));
        b.putShort(24, (short) (350 * upem / 1000));
        b.putShort(26, (short) (50 * upem / 1000));
        b.putShort(28, (short) (300 * upem / 1000));
        b.put(58, "SPDF".getBytes(StandardCharsets.US_ASCII));
        b.putShort(62, (short) ((bold ? 0x20 : 0) | (italic ? 0x01 : 0) | (bold || italic ? 0 : 0x40)));
        b.putShort(64, (short) (cmap.isEmpty() ? 0x20 : Math.min(0xFFFF, cmap.firstKey())));
        b.putShort(66, (short) (cmap.isEmpty() ? 0x7E : Math.min(0xFFFF, cmap.lastKey())));
        b.putShort(68, (short) ascent);
        b.putShort(70, (short) descent);
        b.putShort(74, (short) Math.max(0, m.yMax()));
        b.putShort(76, (short) Math.max(0, -m.yMin()));
        b.putInt(78, 1);
        b.putShort(86, (short) (500 * upem / 1000));
        b.putShort(88, (short) (700 * upem / 1000));
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

        private int sourceBytes;

        abstract int count();

        abstract String name();

        abstract String name(int gid);

        abstract int gid(String name);

        abstract GeneralPath rawPath(int gid) throws IOException;

        abstract double rawWidth(int gid) throws IOException;

        abstract CharStringExpansion.Result expansion(int gid) throws IOException;

        abstract List<Number> matrix();

        abstract String weight();

        abstract float italicAngle();

        int style() {
            String weight = weight() == null ? "" : weight().toLowerCase(Locale.ROOT);
            boolean bold = weight.contains("bold") || weight.contains("black") || weight.contains("heavy");
            return (bold ? 1 : 0) | (italicAngle() != 0 ? 2 : 0);
        }

        void checkExpansion() throws IOException {
            CharStringExpansion.Result[] glyphs = new CharStringExpansion.Result[count()];
            for (int gid = 0; gid < glyphs.length; gid++) {
                PdfFiles.stopIfInterrupted();
                glyphs[gid] = expansion(gid);
            }
            CharStringExpansion.check(glyphs, code -> gid(StandardEncoding.INSTANCE.getName(code)));
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
                Program program = kind == FontKind.CFF ? new Cff(data) : new Type1(data, length1, length2);
                program.sourceBytes = data.length;
                return program;
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
            if (name == null || font instanceof CFFCIDFont) {
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

        @Override
        CharStringExpansion.Result expansion(int gid) throws IOException {
            List<byte[]> global = font.getGlobalSubrIndex();
            Map<String, Object> priv = privateDict(gid);
            return CharStringExpansion.type2(font.getCharStringBytes().get(gid),
                    global == null ? null : global.toArray(new byte[0][]),
                    priv != null && priv.get("Subrs") instanceof byte[][] local ? local : null);
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
            Integer g = name == null ? null : gids.get(name);
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
        CharStringExpansion.Result expansion(int gid) throws IOException {
            return CharStringExpansion.type1(font.getCharStringsDict().get(names.get(gid)), font.getSubrsArray());
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
