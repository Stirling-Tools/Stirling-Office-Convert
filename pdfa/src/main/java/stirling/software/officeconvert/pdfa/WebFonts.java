package stirling.software.officeconvert.pdfa;

import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntFunction;

import org.apache.fontbox.ttf.CmapSubtable;
import org.apache.fontbox.ttf.CmapTable;
import org.apache.fontbox.ttf.HorizontalHeaderTable;
import org.apache.fontbox.ttf.HorizontalMetricsTable;
import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.PostScriptTable;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TTFTable;
import org.apache.fontbox.ttf.TrueTypeFont;
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
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.encoding.Encoding;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;

import stirling.software.officeconvert.extract.PdfFiles;

public final class WebFonts {

    public enum FontKind {
        TYPE1,
        CFF,
        TRUETYPE
    }

    static final int UNITS_PER_EM = 1000;

    private static final int MAX_CODE = 0xFFFF;

    static final int MAX_PROGRAM_BYTES = 64 << 20;

    static final long MIN_GLYPH_BYTES = 16L << 20;

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
            return convert(FontProgram.read(fontProgram, kind, -1, -1), unicodeByGlyph);
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
                FontProgram program = new Cff2Program(new Cff2(ttf.getTableBytes(cff2)), ttf, ownUnicode(ttf,
                        ttf.getNumberOfGlyphs()));
                program.fsType = fsType(ttf);
                return convert(program, font == null ? unicodeByGlyph : unicodeByGlyph(font, program.count(),
                        glyphs(font, cid -> cid, code -> font instanceof PDTrueTypeFont tt && !tt.isDamaged()
                                ? tt.codeToGID(code) : nameToGid(ttf, name(font, code)))));
            }
            if (ttf.getTableMap().get("glyf") == null && cff != null) {
                FontProgram program = FontProgram.read(ttf.getTableBytes(cff), FontKind.CFF, -1, -1);
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
            tables.put("OS/2", SfntTables.os2(m, ascent, descent, cmap, style, upem, fsType(ttf)));
        }
        return w.build(FontRebuild.cleanName(name), SfntTables.cmapTable(cmap), tables);
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
            return Type3TrueType.convert(t3, resources);
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
        FontProgram p = FontProgram.read(data, kind, lengths ? stream.getCOSObject().getInt(COSName.LENGTH1) : -1,
                lengths ? stream.getCOSObject().getInt(COSName.LENGTH2) : -1);
        return convert(p, unicodeByGlyph(font, p.count(), glyphs(font, p::gidForCid, code -> p.gid(name(font, code)))));
    }

    private static byte[] bytes(PDStream s) throws IOException {
        return Decoded.bytes(s.getCOSObject(), MAX_PROGRAM_BYTES, "The font program");
    }

    static Map<Integer, Integer> unicodeByGlyph(PDFont font, int count, CodeToGlyph simpleGlyph)
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

    interface CodeToGlyph {
        int gid(int code) throws IOException;
    }

    private static Integer single(String s) {
        return s != null && !s.isEmpty() && s.codePointCount(0, s.length()) == 1 ? s.codePointAt(0) : null;
    }

    private static byte[] convert(FontProgram program, Map<Integer, Integer> unicodeByGlyph) throws IOException {
        try {
            return outlines(program, unicodeByGlyph);
        } catch (RuntimeException e) {
            throw new IOException("The font program cannot be converted: " + e.getMessage(), e);
        }
    }

    private static byte[] outlines(FontProgram program, Map<Integer, Integer> unicodeByGlyph) throws IOException {
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
        tables.put("head", SfntTables.head(m, program.style()));
        tables.put("hhea", SfntTables.hhea(m, ascent, descent, w.size()));
        tables.put("OS/2", SfntTables.os2(m, ascent, descent, cmap, program.style(), UNITS_PER_EM, program.fsType));
        tables.put("post", SfntTables.post(program.italicAngle()));
        return w.build(FontRebuild.cleanName(program.name()), SfntTables.cmapTable(cmap), tables);
    }

    private static long glyphBudget(long sourceBytes) {
        return Math.max(MIN_GLYPH_BYTES, 8 * sourceBytes);
    }

    static void checkGlyphBytes(TrueTypeWriter w, long budget) throws IOException {
        if (w.glyphBytes() > budget) {
            throw new IOException("The converted glyphs need more than " + budget + " bytes");
        }
    }

    static TreeMap<Integer, Integer> cmap(int count, IntFunction<String> nameOf,
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
}
