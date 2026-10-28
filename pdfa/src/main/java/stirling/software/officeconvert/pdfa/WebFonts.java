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
import org.apache.fontbox.cff.Type2CharStringParser;
import org.apache.fontbox.type1.Type1CharStringReader;
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
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDCIDFont;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType3CharProc;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.encoding.Encoding;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;
import org.apache.pdfbox.util.Matrix;

import stirling.software.officeconvert.extract.PdfFiles;

public final class WebFonts {

    public enum FontKind {
        TYPE1,
        CFF,
        TRUETYPE
    }

    private static final int UNITS_PER_EM = 1000;

    private static final int MAX_CODE = 0xFFFF;

    static final int MAX_PROGRAM_BYTES = 64 << 20;

    private static final long MIN_GLYPH_BYTES = 16L << 20;

    private WebFonts() {}

    public static byte[] toTrueType(byte[] fontProgram, FontKind kind, Map<Integer, Integer> unicodeByGlyph)
            throws IOException {
        PdfFiles.stopIfInterrupted();
        if (fontProgram != null && fontProgram.length > MAX_PROGRAM_BYTES) {
            throw new Decoded.TooLarge("The font program", MAX_PROGRAM_BYTES);
        }
        try {
            if (kind == FontKind.TRUETYPE) {
                return fromSfnt(fontProgram, null, unicodeByGlyph);
            }
            return convert(Program.read(fontProgram, kind, -1, -1), unicodeByGlyph);
        } catch (RuntimeException e) {
            PdfFiles.stopIfInterrupted();
            throw new IOException("The font cannot be converted: " + e, e);
        }
    }

    private static byte[] fromSfnt(byte[] data, PDFont font, Map<Integer, Integer> unicodeByGlyph) throws IOException {
        if (data == null || data.length < 12) {
            throw new IOException("The font program is empty");
        }
        SfntRepair.checkBudget(data);
        Parsed parsed = open(data);
        TrueTypeFont ttf = parsed.font();
        boolean repaired = parsed.repaired();
        try (ttf) {
            TTFTable cff = ttf.getTableMap().get("CFF ");
            TTFTable cff2 = ttf.getTableMap().get("CFF2");
            if (ttf.getTableMap().get("glyf") == null && cff == null && cff2 != null) {
                Program program = new Cff2Program(new Cff2(ttf.getTableBytes(cff2)), ttf, ownUnicode(ttf,
                        ttf.getNumberOfGlyphs()));
                program.fsType = fsType(ttf);
                return convert(program, font == null ? unicodeByGlyph : unicodeByGlyph(font, program.count(),
                        glyphs(font, cid -> cid, code -> font instanceof PDTrueTypeFont tt && !tt.isDamaged()
                                ? tt.codeToGID(code) : nameToGid(ttf, name(font, code)))));
            }
            if (ttf.getTableMap().get("glyf") == null && cff != null) {
                Program program = Program.read(ttf.getTableBytes(cff), FontKind.CFF, -1, -1);
                program.fsType = fsType(ttf);
                return convert(program, font == null ? unicodeByGlyph : unicodeByGlyph(font, program.count(),
                        glyphs(font, program::gidForCid, code -> font instanceof PDTrueTypeFont tt && !tt.isDamaged()
                                ? tt.codeToGID(code) : program.gid(name(font, code)))));
            }
            Map<Integer, Integer> mapping = unicodeByGlyph;
            boolean pdfBoxReads = font instanceof PDTrueTypeFont && !font.isDamaged() && !repaired;
            if (font != null) {
                CodeToGlyph cidToGid = font instanceof PDType0Font t0 ? cidToGidMap(t0) : cid -> cid;
                mapping = unicodeByGlyph(font, ttf.getNumberOfGlyphs(), glyphs(font, cidToGid,
                        code -> pdfBoxReads ? ((PDTrueTypeFont) font).codeToGID(code) : simpleGid(ttf, font, code)));
            }
            String name = ttf.getName() != null ? ttf.getName() : font != null ? font.getName() : null;
            return convertTrueType(ttf, parsed.bytes(), name, mapping);
        } catch (RuntimeException e) {
            throw new IOException("The TrueType program cannot be converted: " + e.getMessage(), e);
        }
    }

    private record Repair(boolean dropCmap, java.util.Set<String> keep) {}

    private record Parsed(TrueTypeFont font, boolean repaired, byte[] bytes) {}

    private static Parsed open(byte[] data) throws IOException {
        try {
            TrueTypeFont font = parseSfnt(data);
            try {
                font.getCmap();
                font.getHorizontalMetrics();
            } catch (IOException | RuntimeException e) {
                font.close();
                throw e;
            }
            if (font.getNumberOfGlyphs() > 0) {
                return new Parsed(font, false, data);
            }
            font.close();
            throw new IOException("The TrueType program has no glyphs");
        } catch (IOException | RuntimeException e) {
            java.util.Set<String> withCmap = new java.util.HashSet<>(SfntRepair.CORE);
            withCmap.add("cmap");
            List<Repair> attempts = List.of(new Repair(false, null), new Repair(true, null), new Repair(false, withCmap),
                    new Repair(true, SfntRepair.CORE));
            for (Repair attempt : attempts) {
                byte[] fixed = SfntRepair.repair(data, attempt.dropCmap(), attempt.keep());
                if (fixed != null) {
                    try {
                        TrueTypeFont font = parseSfnt(fixed);
                        font.getCmap();
                        font.getHorizontalMetrics();
                        return new Parsed(font, true, fixed);
                    } catch (IOException | RuntimeException ignored) {
                        PdfFiles.stopIfInterrupted();
                    }
                }
            }
            throw new IOException("The TrueType program cannot be read: " + e.getMessage(), e);
        }
    }

    private static TrueTypeFont parseSfnt(byte[] data) throws IOException {
        boolean otto = data[0] == 'O' && data[1] == 'T' && data[2] == 'T' && data[3] == 'O';
        return (otto ? new OTFParser(true) : new TTFParser(true)).parse(new RandomAccessReadBuffer(data));
    }

    private static int simpleGid(TrueTypeFont ttf, PDFont font, int code) throws IOException {
        CmapTable table = ttf.getCmap();
        CmapSubtable symbol = table == null ? null : table.getSubtable(3, 0);
        CmapSubtable unicode = table == null ? null : table.getSubtable(3, 1);
        CmapSubtable mac = table == null ? null : table.getSubtable(1, 0);
        String name = name(font, code);
        if (unicode != null && name != null) {
            String u = GlyphList.getAdobeGlyphList().toUnicode(name);
            int gid = u == null || u.isEmpty() ? 0 : unicode.getGlyphId(u.codePointAt(0));
            if (gid > 0) {
                return gid;
            }
        }
        if (symbol != null) {
            int gid = symbol.getGlyphId(code);
            if (gid == 0) {
                gid = symbol.getGlyphId(0xF000 + code);
            }
            if (gid > 0) {
                return gid;
            }
        }
        if (mac != null && mac.getGlyphId(code) > 0) {
            return mac.getGlyphId(code);
        }
        return nameToGid(ttf, name);
    }

    private static int nameToGid(TrueTypeFont ttf, String name) throws IOException {
        return name == null || ".notdef".equals(name) ? 0 : ttf.nameToGID(name);
    }

    private static FontKind sniff(byte[] data) {
        if (data == null || data.length < 4) {
            return null;
        }
        int tag = ByteBuffer.wrap(data).getInt(0);
        if (tag == 0x00010000 || tag == 0x74727565 || tag == 0x4F54544F) {
            return FontKind.TRUETYPE;
        }
        if ((data[0] & 0xFF) == 0x80 && data[1] == 1 || data[0] == '%' && data[1] == '!') {
            return FontKind.TYPE1;
        }
        int header = data[2] & 0xFF;
        int offSize = data[3] & 0xFF;
        if (data[0] == 1 && data[1] == 0 && header >= 4 && header < data.length && offSize >= 1 && offSize <= 4) {
            return FontKind.CFF;
        }
        return null;
    }

    private static String name(PDFont font, int code) {
        Encoding enc = font instanceof PDSimpleFont s ? s.getEncoding() : null;
        return enc == null ? null : enc.getName(code);
    }

    private static byte[] convertTrueType(TrueTypeFont ttf, byte[] bytes, String name,
            Map<Integer, Integer> unicodeByGlyph) throws IOException {
        int count = ttf.getNumberOfGlyphs();
        if (count < 1) {
            throw new IOException("The TrueType program has no glyphs");
        }
        TrueTypeWriter w = new TrueTypeWriter(ttf, bytes);
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
            tables.put("OS/2", os2(m, ascent, descent, cmap, style, upem, fsType(ttf)));
        }
        return w.build(FontRebuild.cleanName(name), cmapTable(cmap), tables);
    }

    private static int fsType(TrueTypeFont ttf) throws IOException {
        TTFTable os2 = ttf.getTableMap().get("OS/2");
        if (os2 == null || os2.getLength() < 10) {
            return 0;
        }
        byte[] t = ttf.getTableBytes(os2);
        return t.length < 10 ? 0 : ByteBuffer.wrap(t).getShort(8) & 0xFFFF;
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
        return toTrueType(font, null);
    }

    public static byte[] toTrueType(PDFont font, PDResources resources) throws IOException {
        PdfFiles.stopIfInterrupted();
        try {
            return fromFont(font, resources);
        } catch (RuntimeException e) {
            PdfFiles.stopIfInterrupted();
            throw new IOException("The font cannot be converted: " + e, e);
        }
    }

    private static byte[] fromFont(PDFont font, PDResources resources) throws IOException {
        if (font instanceof PDType3Font t3) {
            return fromType3(t3, resources);
        }
        PDFontDescriptor fd = font.getFontDescriptor();
        if (fd == null) {
            throw new IOException("The font has no descriptor");
        }
        PDStream stream;
        FontKind declared;
        if (fd.getFontFile2() != null) {
            stream = fd.getFontFile2();
            declared = FontKind.TRUETYPE;
        } else if (fd.getFontFile3() != null) {
            stream = fd.getFontFile3();
            String subtype = stream.getCOSObject().getNameAsString(COSName.SUBTYPE);
            declared = "OpenType".equals(subtype) ? FontKind.TRUETYPE
                    : subtype == null || "Type1C".equals(subtype) || "CIDFontType0C".equals(subtype) ? FontKind.CFF
                            : null;
        } else if (fd.getFontFile() != null) {
            stream = fd.getFontFile();
            declared = FontKind.TYPE1;
        } else {
            throw new IOException("The font has no embedded font program");
        }
        byte[] data = bytes(stream);
        FontKind kind = sniff(data);
        if (kind == null) {
            kind = declared;
        }
        if (kind == null) {
            throw new IOException("FontFile3 /" + stream.getCOSObject().getNameAsString(COSName.SUBTYPE)
                    + " is not a supported font program");
        }
        if (kind == FontKind.TRUETYPE) {
            return fromSfnt(data, font, null);
        }
        boolean lengths = kind == FontKind.TYPE1 && declared == FontKind.TYPE1;
        Program p = Program.read(data, kind, lengths ? stream.getCOSObject().getInt(COSName.LENGTH1) : -1,
                lengths ? stream.getCOSObject().getInt(COSName.LENGTH2) : -1);
        return convert(p, unicodeByGlyph(font, p.count(), glyphs(font, p::gidForCid, code -> p.gid(name(font, code)))));
    }

    private static byte[] fromType3(PDType3Font font, PDResources resources) throws IOException {
        try {
            return type3(font, resources);
        } catch (RuntimeException e) {
            throw new IOException("The Type 3 font cannot be converted: " + e.getMessage(), e);
        }
    }

    private static byte[] type3(PDType3Font font, PDResources resources) throws IOException {
        Matrix fm = font.getFontMatrix();
        TrueTypeWriter w = new TrueTypeWriter(UNITS_PER_EM);
        Map<List<Object>, Integer> byProc = new HashMap<>();
        Map<Integer, Integer> gidByCode = new TreeMap<>();
        long budget = MIN_GLYPH_BYTES;
        Type3Outlines.Budget work = new Type3Outlines.Budget();
        for (int code = 0; code < 256; code++) {
            PdfFiles.stopIfInterrupted();
            PDType3CharProc proc = font.getCharProc(code);
            if (proc == null) {
                continue;
            }
            int advance = (int) Math.rint(fm.transformPoint(font.getWidth(code), 0).x * UNITS_PER_EM
                    - fm.transformPoint(0, 0).x * UNITS_PER_EM);
            List<Object> key = List.of(proc.getCOSObject(), advance);
            Integer known = byProc.get(key);
            if (known != null) {
                gidByCode.put(code, known);
                continue;
            }
            GeneralPath path;
            try {
                path = Type3Outlines.glyph(proc, resources, work);
            } catch (IOException | RuntimeException e) {
                PdfFiles.stopIfInterrupted();
                if (e instanceof IOException io) {
                    Decoded.rethrowFatal(io);
                }
                path = new GeneralPath();
            }
            int gid = w.addOutline(path, advance);
            checkGlyphBytes(w, budget);
            byProc.put(key, gid);
            gidByCode.put(code, gid);
        }
        if (gidByCode.isEmpty()) {
            throw new IOException("The Type 3 font has no glyph procedures");
        }
        TreeMap<Integer, Integer> unicode = cmap(w.size(), gid -> null,
                unicodeByGlyph(font, w.size(), code -> gidByCode.getOrDefault(code, 0)), Map.of());
        TreeMap<Integer, Integer> symbol = new TreeMap<>();
        for (Map.Entry<Integer, Integer> e : gidByCode.entrySet()) {
            symbol.put(0xF000 + e.getKey(), e.getValue());
        }
        List<Cmaps.Subtable> subtables = new ArrayList<>();
        subtables.add(new Cmaps.Subtable(3, 0, symbol));
        TreeMap<Integer, Integer> bmp = new TreeMap<>(unicode.headMap(0xFFFF));
        subtables.add(new Cmaps.Subtable(3, 1, bmp));
        if (!unicode.isEmpty() && unicode.lastKey() > 0xFFFF || !Cmaps.fitsFormat4(bmp)) {
            subtables.add(new Cmaps.Subtable(3, 10, unicode));
        }
        TrueTypeWriter.Metrics m = w.metrics();
        int ascent = Math.max(m.yMax(), 800);
        int descent = Math.min(m.yMin(), -200);
        Map<String, byte[]> tables = new HashMap<>();
        tables.put("head", head(m, 0));
        tables.put("hhea", hhea(m, ascent, descent, w.size()));
        tables.put("OS/2", os2(m, ascent, descent, unicode.isEmpty() ? symbol : unicode, 0, UNITS_PER_EM, 0));
        tables.put("post", post(0));
        String name = font.getName() == null || font.getName().isBlank() ? "Type3" : font.getName();
        return w.build(FontRebuild.cleanName(name), Cmaps.table(subtables), tables);
    }

    private static byte[] bytes(PDStream s) throws IOException {
        return Decoded.bytes(s.getCOSObject(), MAX_PROGRAM_BYTES, "The font program");
    }

    private static Map<Integer, Integer> unicodeByGlyph(PDFont font, int count, CodeToGlyph simpleGlyph)
            throws IOException {
        Map<Integer, Integer> out = new HashMap<>();
        if (font instanceof PDType0Font t0) {
            for (int code = 0; code <= MAX_CODE; code++) {
                if ((code & 0xFF) == 0) {
                    PdfFiles.stopIfInterrupted();
                }
                Integer cp = single(t0.toUnicode(code));
                if (cp != null) {
                    int gid = simpleGlyph.gid(code);
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

    private static CodeToGlyph glyphs(PDFont font, CodeToGlyph cidToGid, CodeToGlyph simple) {
        if (font instanceof PDType0Font t0) {
            return code -> cidToGid.gid(t0.codeToCID(code));
        }
        return simple;
    }

    private static CodeToGlyph cidToGidMap(PDType0Font font) throws IOException {
        PDCIDFont cid = font.getDescendantFont();
        if (cid != null && cid.getCOSObject().getDictionaryObject(COSName.CID_TO_GID_MAP) instanceof COSStream s) {
            byte[] map;
            try (InputStream in = s.createInputStream()) {
                map = in.readNBytes(2 * 65_536);
            }
            return c -> c >= 0 && 2 * c + 1 < map.length ? (map[2 * c] & 0xFF) << 8 | map[2 * c + 1] & 0xFF : 0;
        }
        return c -> c;
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
        TreeMap<Integer, Integer> cmap = cmap(program.count(), program::name, unicodeByGlyph, program.own());
        TrueTypeWriter.Metrics m = w.metrics();
        int ascent = Math.max(m.yMax(), 800);
        int descent = Math.min(m.yMin(), -200);
        Map<String, byte[]> tables = new HashMap<>();
        tables.put("head", head(m, program.style()));
        tables.put("hhea", hhea(m, ascent, descent, w.size()));
        tables.put("OS/2", os2(m, ascent, descent, cmap, program.style(), UNITS_PER_EM, program.fsType));
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
        TreeMap<Integer, Integer> bmp = new TreeMap<>(cmap.headMap(0xFFFF));
        subtables.add(new Cmaps.Subtable(3, 1, bmp));
        if (!cmap.isEmpty() && cmap.lastKey() > 0xFFFF || !Cmaps.fitsFormat4(bmp)) {
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
            int style, int upem, int fsType) {
        boolean bold = (style & 1) != 0;
        boolean italic = (style & 2) != 0;
        ByteBuffer b = ByteBuffer.allocate(96);
        b.putShort(0, (short) 4);
        b.putShort(2, (short) m.averageAdvance());
        b.putShort(4, (short) (bold ? 700 : 400));
        b.putShort(6, (short) 5);
        b.putShort(8, (short) fsType);
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

        private int fsType;

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

        Map<Integer, Integer> own() {
            return Map.of();
        }

        int gidForCid(int cid) {
            return cid;
        }

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
            Program program;
            try {
                program = kind == FontKind.CFF ? new Cff(data) : new Type1(data, length1, length2);
            } catch (IOException | RuntimeException e) {
                if (kind == FontKind.CFF) {
                    throw e instanceof IOException io ? io
                            : new IOException("The font program cannot be read: " + e.getMessage(), e);
                }
                try {
                    byte[][] segments = Type1.split(data, length1, length2);
                    program = new RawType1(Type1Lenient.parse(segments[0], segments[1]));
                } catch (IOException | RuntimeException again) {
                    throw new IOException("The font program cannot be read: " + e.getMessage(), e);
                }
            }
            program.sourceBytes = data.length;
            return program;
        }
    }

    private static final class Cff extends Program {

        private final CFFFont font;

        private final byte[][] globalSubrs;

        private final Type2CharStringParser parser;

        Cff(byte[] data) throws IOException {
            List<CFFFont> fonts;
            try {
                fonts = new CFFParser().parse(new RandomAccessReadBuffer(data));
            } catch (IOException | RuntimeException e) {
                byte[] shifted = CffRepair.header(data);
                byte[] base = shifted != null ? shifted : data;
                byte[] reals = CffRepair.realNumbers(base);
                byte[] fixed = reals != null ? reals : shifted;
                if (fixed == null) {
                    throw e;
                }
                fonts = new CFFParser().parse(new RandomAccessReadBuffer(fixed));
            }
            if (fonts.isEmpty()) {
                throw new IOException("The CFF program has no font");
            }
            font = fonts.get(0);
            if (font.getNumCharStrings() < 1) {
                throw new IOException("The CFF program has no glyphs");
            }
            List<byte[]> global = font.getGlobalSubrIndex();
            globalSubrs = global == null ? new byte[0][] : global.toArray(new byte[0][]);
            parser = new Type2CharStringParser(font.getName());
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
        int gidForCid(int cid) {
            return font instanceof CFFCIDFont ? font.getCharset().getGIDForCID(cid) : cid;
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
            Map<String, Object> priv = privateDict(gid);
            byte[][] local = priv != null && priv.get("Subrs") instanceof byte[][] l ? l : null;
            String name = name(gid);
            List<Object> sequence = parser.parse(font.getCharStringBytes().get(gid), globalSubrs, local, name);
            Type1CharStringReader reader = font instanceof CFFType1Font t1 ? t1::getType1CharString : n -> null;
            return new Type2CharString(reader, font.getName(), name, gid, sequence,
                    (int) number(priv == null ? null : priv.get("defaultWidthX")),
                    (int) number(priv == null ? null : priv.get("nominalWidthX")));
        }

        @Override
        GeneralPath rawPath(int gid) throws IOException {
            return Type2Outline.path(font.getCharStringBytes().get(gid), globalSubrs, localSubrs(gid), null, 0,
                    code -> {
                        int accent = gid(StandardEncoding.INSTANCE.getName(code));
                        return accent <= 0 ? null : Type2Outline.path(font.getCharStringBytes().get(accent),
                                globalSubrs, localSubrs(accent), null, 0, null);
                    });
        }

        private byte[][] localSubrs(int gid) {
            Map<String, Object> priv = privateDict(gid);
            return priv != null && priv.get("Subrs") instanceof byte[][] l ? l : null;
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
            Map<String, Object> priv = privateDict(gid);
            return CharStringExpansion.type2(font.getCharStringBytes().get(gid), globalSubrs,
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

    private static final class Cff2Program extends Program {

        private final Cff2 cff2;

        private final int count;

        private final String name;

        private final PostScriptTable post;

        private final HorizontalMetricsTable hmtx;

        private final int unitsPerEm;

        private final Map<Integer, Integer> own;

        private final Map<String, Integer> gids = new HashMap<>();

        Cff2Program(Cff2 cff2, TrueTypeFont ttf, Map<Integer, Integer> own) throws IOException {
            this.cff2 = cff2;
            this.count = Math.min(cff2.count(), Math.max(1, ttf.getNumberOfGlyphs()));
            this.name = ttf.getName();
            this.post = ttf.getPostScript();
            this.hmtx = ttf.getHorizontalMetrics();
            this.unitsPerEm = ttf.getUnitsPerEm() > 0 ? ttf.getUnitsPerEm() : 1000;
            this.own = own;
            for (int gid = 0; post != null && gid < count; gid++) {
                String n = post.getName(gid);
                if (n != null) {
                    gids.putIfAbsent(n, gid);
                }
            }
        }

        @Override
        int count() {
            return count;
        }

        @Override
        String name() {
            return name;
        }

        @Override
        String name(int gid) {
            return post == null ? null : post.getName(gid);
        }

        @Override
        int gid(String glyph) {
            Integer g = glyph == null ? null : gids.get(glyph);
            return g == null ? 0 : g;
        }

        @Override
        GeneralPath rawPath(int gid) throws IOException {
            return cff2.path(gid);
        }

        @Override
        double rawWidth(int gid) {
            return hmtx == null ? 0 : hmtx.getAdvanceWidth(gid);
        }

        @Override
        CharStringExpansion.Result expansion(int gid) throws IOException {
            return CharStringExpansion.type2(cff2.charString(gid), cff2.globalSubrs(), cff2.localSubrs(gid));
        }

        @Override
        List<Number> matrix() {
            double k = 1.0 / unitsPerEm;
            return List.of(k, 0, 0, k, 0, 0);
        }

        @Override
        String weight() {
            return null;
        }

        @Override
        float italicAngle() {
            return post == null ? 0 : post.getItalicAngle();
        }

        @Override
        Map<Integer, Integer> own() {
            return own;
        }
    }

    private static final class RawType1 extends Program {

        private final Type1Lenient font;

        private final List<String> names = new ArrayList<>();

        private final Map<String, Integer> gids = new HashMap<>();

        RawType1(Type1Lenient font) {
            this.font = font;
            names.add(".notdef");
            for (String n : font.charStrings.keySet()) {
                if (!".notdef".equals(n)) {
                    names.add(n);
                }
            }
            for (int i = 0; i < names.size(); i++) {
                gids.put(names.get(i), i);
            }
        }

        @Override
        int count() {
            return names.size();
        }

        @Override
        String name() {
            return font.fontName;
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

        private org.apache.fontbox.cff.Type1CharString charString(String glyph, int depth) throws IOException {
            byte[] bytes = font.charStrings.get(glyph);
            if (bytes == null) {
                bytes = new byte[] {14};
            }
            List<Object> sequence = new org.apache.fontbox.cff.Type1CharStringParser(font.fontName).parse(bytes,
                    font.subrs, glyph);
            return new org.apache.fontbox.cff.Type1CharString(n -> depth > 2 ? null : charString(n, depth + 1),
                    font.fontName, glyph, sequence);
        }

        @Override
        GeneralPath rawPath(int gid) throws IOException {
            return charString(names.get(gid), 0).getPath();
        }

        @Override
        double rawWidth(int gid) throws IOException {
            Double w = Type1Width.of(font.charStrings.get(names.get(gid)));
            return w != null ? w : charString(names.get(gid), 0).getWidth();
        }

        @Override
        CharStringExpansion.Result expansion(int gid) throws IOException {
            byte[] cs = font.charStrings.get(names.get(gid));
            return CharStringExpansion.type1(cs == null ? new byte[] {14} : cs, font.subrs);
        }

        @Override
        List<Number> matrix() {
            return font.fontMatrix;
        }

        @Override
        String weight() {
            return null;
        }

        @Override
        float italicAngle() {
            return 0;
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
                try {
                    return Type1Font.createWithPFB(data);
                } catch (IOException | RuntimeException e) {
                    byte[][] segments = pfbSegments(data);
                    return segments(segments[0], segments[1]);
                }
            }
            byte[][] segments = split(data, length1, length2);
            return segments(segments[0], segments[1]);
        }

        static byte[][] split(byte[] data, int length1, int length2) throws IOException {
            if (data.length > 6 && (data[0] & 0xFF) == 0x80 && data[1] == 1) {
                return pfbSegments(data);
            }
            int split = length1 > 0 && length1 < data.length && isEexecEnd(data, length1) ? length1 : eexecEnd(data);
            if (split < 0) {
                throw new IOException("The Type 1 program has no eexec section");
            }
            int end = length2 > 0 && split + length2 <= data.length ? split + length2 : trailerStart(data, split);
            return new byte[][] {java.util.Arrays.copyOfRange(data, 0, split), java.util.Arrays.copyOfRange(data, split,
                    end)};
        }

        private static Type1Font segments(byte[] clear, byte[] binary) throws IOException {
            try {
                return Type1Font.createWithSegments(clear, binary);
            } catch (IOException | RuntimeException e) {
                String text = new String(clear, StandardCharsets.ISO_8859_1);
                String fixed = text.replaceAll("/(?=[\\s\\[\\]{}()<>])", "/.notdef");
                if (fixed.equals(text)) {
                    throw e;
                }
                return Type1Font.createWithSegments(fixed.getBytes(StandardCharsets.ISO_8859_1), binary);
            }
        }

        private static byte[][] pfbSegments(byte[] data) {
            java.io.ByteArrayOutputStream clear = new java.io.ByteArrayOutputStream();
            java.io.ByteArrayOutputStream binary = new java.io.ByteArrayOutputStream();
            int at = 0;
            while (at < data.length) {
                if (at + 6 > data.length || (data[at] & 0xFF) != 0x80 || data[at + 1] != 1 && data[at + 1] != 2) {
                    (clear.size() == 0 ? clear : binary).write(data, at, data.length - at);
                    break;
                }
                int type = data[at + 1];
                long length = (data[at + 2] & 0xFFL) | (data[at + 3] & 0xFFL) << 8 | (data[at + 4] & 0xFFL) << 16
                        | (data[at + 5] & 0xFFL) << 24;
                int n = (int) Math.min(length, data.length - at - 6);
                (type == 1 && binary.size() == 0 ? clear : binary).write(data, at + 6, n);
                at += 6 + n;
            }
            return new byte[][] {clear.toByteArray(), binary.toByteArray()};
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
