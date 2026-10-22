package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.junit.jupiter.api.Test;

class WebFontsTrueTypeTest {

    private static final String TEXT = "ABCxyz";

    private static TrueTypeFont parse(byte[] ttf) throws Exception {
        return new TTFParser().parse(new RandomAccessReadBuffer(ttf));
    }

    private static int gid(TrueTypeFont lib, int cp) throws Exception {
        return lib.getUnicodeCmapLookup().getGlyphId(cp);
    }

    private static byte[] stripped(TrueTypeFont lib, byte[] cmap) throws Exception {
        TreeMap<String, byte[]> tables = new TreeMap<>();
        for (String tag : List.of("glyf", "loca", "head", "hhea", "hmtx", "maxp", "fpgm", "prep", "cvt ")) {
            if (lib.getTableMap().get(tag) != null) {
                tables.put(tag, lib.getTableBytes(lib.getTableMap().get(tag)));
            }
        }
        if (cmap != null) {
            tables.put("cmap", cmap);
        }
        return Sfnt.write(tables);
    }

    private static TreeMap<Integer, Integer> codes(TrueTypeFont lib, int base) throws Exception {
        TreeMap<Integer, Integer> m = new TreeMap<>();
        for (char c : TEXT.toCharArray()) {
            m.put(base + c, gid(lib, c));
        }
        return m;
    }

    private static COSStream stream(PDDocument d, byte[] data) throws Exception {
        COSStream s = d.getDocument().createCOSStream();
        try (OutputStream o = s.createOutputStream(COSName.FLATE_DECODE)) {
            o.write(data);
        }
        return s;
    }

    private static COSStream toUnicode(PDDocument d, int bytes, Map<Integer, Character> map) throws Exception {
        String range = bytes == 1 ? "<00> <FF>" : "<0000> <FFFF>";
        StringBuilder b = new StringBuilder("/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n1 begincodespacerange "
                + range + " endcodespacerange\n" + map.size() + " beginbfchar\n");
        String fmt = bytes == 1 ? "<%02X> <%04X>\n" : "<%04X> <%04X>\n";
        map.forEach((k, v) -> b.append(String.format(fmt, k, (int) v)));
        b.append("endbfchar\nendcmap CMapName currentdict /CMap defineresource pop end end\n");
        return stream(d, b.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static COSDictionary simple(PDDocument d, COSName file, COSStream program, int flags) {
        COSDictionary fd = new COSDictionary();
        fd.setItem(COSName.TYPE, COSName.FONT_DESC);
        fd.setName(COSName.FONT_NAME, "ABCDEF+Subset");
        fd.setInt(COSName.FLAGS, flags);
        fd.setItem(file, program);
        COSDictionary f = new COSDictionary();
        f.setItem(COSName.TYPE, COSName.FONT);
        f.setItem(COSName.SUBTYPE, COSName.TRUE_TYPE);
        f.setName(COSName.BASE_FONT, "ABCDEF+Subset");
        f.setItem(COSName.FONT_DESC, fd);
        return f;
    }

    private static void assertWebFont(byte[] ttf, TrueTypeFont lib) throws Exception {
        assertTrue(TrueTypeSanity.sane(ttf));
        try (TrueTypeFont t = parse(ttf)) {
            assertEquals(lib.getNumberOfGlyphs(), t.getNumberOfGlyphs());
            for (char c : TEXT.toCharArray()) {
                assertEquals(gid(lib, c), t.getUnicodeCmapLookup().getGlyphId(c), String.valueOf(c));
                assertEquals(lib.getAdvanceWidth(gid(lib, c)), t.getAdvanceWidth(gid(lib, c)));
            }
            for (String tag : List.of("name", "OS/2", "post", "fpgm", "prep", "cvt ")) {
                assertNotNull(t.getTableMap().get(tag), tag);
            }
            assertNotNull(t.getCmap().getSubtable(3, 1));
            assertNull(t.getCmap().getSubtable(3, 0));
            assertEquals(lib.getUnitsPerEm(), t.getUnitsPerEm());
        }
        assertTrue(Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(ttf)).canDisplayUpTo(TEXT) < 0);
    }

    @Test
    void symbolicSubsetWithOnlyASymbolCmapGetsAUnicodeOne() throws Exception {
        try (TrueTypeFont lib = Samples.liberation(); PDDocument d = new PDDocument()) {
            byte[] program = stripped(lib, Cmaps.table(List.of(new Cmaps.Subtable(3, 0, codes(lib, 0xF000)))));
            COSDictionary f = simple(d, COSName.FONT_FILE2, stream(d, program), 4);
            Map<Integer, Character> tu = new TreeMap<>();
            for (char c : TEXT.toCharArray()) {
                tu.put((int) c, c);
            }
            f.setItem(COSName.TO_UNICODE, toUnicode(d, 1, tu));
            assertWebFont(WebFonts.toTrueType(PDFontFactory.createFont(f)), lib);
        }
    }

    @Test
    void macRomanSubsetWithoutToUnicodeMapsThroughItsEncoding() throws Exception {
        try (TrueTypeFont lib = Samples.liberation(); PDDocument d = new PDDocument()) {
            byte[] program = stripped(lib, Cmaps.table(List.of(new Cmaps.Subtable(1, 0, codes(lib, 0)))));
            COSDictionary f = simple(d, COSName.FONT_FILE2, stream(d, program), 32);
            f.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING);
            PDFont font = PDFontFactory.createFont(f);
            assertTrue(font instanceof PDTrueTypeFont);
            assertWebFont(WebFonts.toTrueType(font), lib);
        }
    }

    @Test
    void cidFontType2FollowsItsCidToGidMap() throws Exception {
        try (TrueTypeFont lib = Samples.liberation(); PDDocument d = new PDDocument()) {
            byte[] program = stripped(lib, null);
            COSDictionary fd = new COSDictionary();
            fd.setItem(COSName.TYPE, COSName.FONT_DESC);
            fd.setName(COSName.FONT_NAME, "ABCDEF+Subset");
            fd.setInt(COSName.FLAGS, 4);
            fd.setItem(COSName.FONT_FILE2, stream(d, program));
            byte[] map = new byte[2 * (TEXT.length() + 1)];
            Map<Integer, Character> tu = new TreeMap<>();
            for (int i = 0; i < TEXT.length(); i++) {
                int g = gid(lib, TEXT.charAt(i));
                map[2 * (i + 1)] = (byte) (g >> 8);
                map[2 * (i + 1) + 1] = (byte) g;
                tu.put(i + 1, TEXT.charAt(i));
            }
            COSDictionary cid = new COSDictionary();
            cid.setItem(COSName.TYPE, COSName.FONT);
            cid.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE2);
            cid.setName(COSName.BASE_FONT, "ABCDEF+Subset");
            COSDictionary info = new COSDictionary();
            info.setString(COSName.REGISTRY, "Adobe");
            info.setString(COSName.ORDERING, "Identity");
            info.setInt(COSName.SUPPLEMENT, 0);
            cid.setItem(COSName.CIDSYSTEMINFO, info);
            cid.setItem(COSName.FONT_DESC, fd);
            cid.setItem(COSName.CID_TO_GID_MAP, stream(d, map));
            COSDictionary t0 = new COSDictionary();
            t0.setItem(COSName.TYPE, COSName.FONT);
            t0.setItem(COSName.SUBTYPE, COSName.TYPE0);
            t0.setName(COSName.BASE_FONT, "ABCDEF+Subset");
            t0.setItem(COSName.ENCODING, COSName.IDENTITY_H);
            COSArray kids = new COSArray();
            kids.add(cid);
            t0.setItem(COSName.DESCENDANT_FONTS, kids);
            t0.setItem(COSName.TO_UNICODE, toUnicode(d, 2, tu));
            assertWebFont(WebFonts.toTrueType(PDFontFactory.createFont(t0)), lib);
        }
    }

    @Test
    void openTypeFontFile3WithTrueTypeOutlinesConverts() throws Exception {
        try (TrueTypeFont lib = Samples.liberation(); PDDocument d = new PDDocument()) {
            byte[] program = stripped(lib, Cmaps.table(List.of(new Cmaps.Subtable(1, 0, codes(lib, 0)))));
            COSStream s = stream(d, program);
            s.setItem(COSName.SUBTYPE, COSName.getPDFName("OpenType"));
            COSDictionary f = simple(d, COSName.FONT_FILE3, s, 32);
            f.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING);
            assertWebFont(WebFonts.toTrueType(PDFontFactory.createFont(f)), lib);
        }
    }

    @Test
    void openTypeFontFile3WithCffOutlinesUsesTheCffTable() throws Exception {
        byte[] cff = FontPrograms.cidCff("TestCid");
        int glyphs = FontPrograms.NAMES.size() + 1;
        TreeMap<String, byte[]> tables = new TreeMap<>();
        tables.put("CFF ", cff);
        java.nio.ByteBuffer head = java.nio.ByteBuffer.allocate(54);
        head.putInt(0, 0x00010000);
        head.putInt(12, 0x5F0F3CF5);
        head.putShort(18, (short) 1000);
        tables.put("head", head.array());
        java.nio.ByteBuffer hhea = java.nio.ByteBuffer.allocate(36);
        hhea.putInt(0, 0x00010000);
        hhea.putShort(34, (short) glyphs);
        tables.put("hhea", hhea.array());
        java.nio.ByteBuffer maxp = java.nio.ByteBuffer.allocate(6);
        maxp.putInt(0, 0x00005000);
        maxp.putShort(4, (short) glyphs);
        tables.put("maxp", maxp.array());
        java.nio.ByteBuffer hmtx = java.nio.ByteBuffer.allocate(4 * glyphs);
        for (int i = 0; i < glyphs; i++) {
            hmtx.putShort(4 * i, (short) 500);
        }
        tables.put("hmtx", hmtx.array());
        tables.put("post", java.nio.ByteBuffer.allocate(32).putInt(0, 0x00030000).array());
        byte[] otf = Sfnt.write(tables);
        otf[0] = 'O';
        otf[1] = 'T';
        otf[2] = 'T';
        otf[3] = 'O';
        byte[] ttf = WebFonts.toTrueType(otf, WebFonts.FontKind.TRUETYPE, null);
        try (TrueTypeFont t = parse(ttf)) {
            assertEquals(glyphs, t.getNumberOfGlyphs());
            assertEquals(500, t.getAdvanceWidth(3));
            assertNotNull(t.getTableMap().get("glyf"));
        }
    }

    @Test
    void bareTrueTypeBytesKeepTheirOwnUnicodeCmap() throws Exception {
        try (TrueTypeFont lib = Samples.liberation()) {
            TreeMap<Integer, Integer> uni = new TreeMap<>();
            for (char c : TEXT.toCharArray()) {
                uni.put((int) c, gid(lib, c));
            }
            byte[] ttf = WebFonts.toTrueType(stripped(lib, Cmaps.table(List.of(new Cmaps.Subtable(3, 1, uni)))),
                    WebFonts.FontKind.TRUETYPE, null);
            assertWebFont(ttf, lib);
        }
    }
}
