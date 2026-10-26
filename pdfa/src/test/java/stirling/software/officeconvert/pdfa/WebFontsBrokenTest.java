package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

class WebFontsBrokenTest {

    private static byte[] font() throws IOException {
        TrueTypeWriter w = new TrueTypeWriter(1000);
        for (int i = 0; i < 3; i++) {
            GeneralPath p = new GeneralPath();
            p.append(new Rectangle2D.Double(100, 0, 300 + 50 * i, 700), false);
            w.addOutline(p, 600 + i);
        }
        TreeMap<Integer, Integer> cmap = new TreeMap<>(Map.of(0x41, 1, 0x42, 2, 0x43, 3));
        return w.build("Broken", Cmaps.table(List.of(new Cmaps.Subtable(3, 1, cmap))));
    }

    private static int[] table(byte[] f, String tag) {
        ByteBuffer b = ByteBuffer.wrap(f);
        int n = b.getShort(4) & 0xFFFF;
        for (int i = 0; i < n; i++) {
            int at = 12 + 16 * i;
            if (new String(f, at, 4, StandardCharsets.US_ASCII).equals(tag)) {
                return new int[] {at, b.getInt(at + 8), b.getInt(at + 12)};
            }
        }
        throw new IllegalArgumentException(tag);
    }

    private static byte[] broken(Consumer<byte[]> damage) throws IOException {
        byte[] f = font();
        damage.accept(f);
        return f;
    }

    private static void assertTolerated(String label, byte[] program) {
        for (WebFonts.FontKind kind : WebFonts.FontKind.values()) {
            try {
                byte[] out = WebFonts.toTrueType(program, kind, null);
                assertTrue(TrueTypeSanity.sane(out), label + " " + kind + " gave an insane font");
            } catch (IOException expected) {
            } catch (RuntimeException | StackOverflowError e) {
                fail(label + " " + kind + " threw " + e);
            }
        }
    }

    @Test
    void damagedTablesConvertOrFailCleanly() throws Exception {
        List<Object[]> cases = new ArrayList<>();
        cases.add(new Object[] {"loca truncated", broken(f -> ByteBuffer.wrap(f).putInt(table(f, "loca")[0] + 12, 4))});
        cases.add(new Object[] {"loca past glyf", broken(f -> ByteBuffer.wrap(f).putInt(table(f, "loca")[1] + 8,
                0x7FFF0000))});
        cases.add(new Object[] {"hmtx short", broken(f -> ByteBuffer.wrap(f).putShort(table(f, "hhea")[1] + 34,
                (short) 1))});
        cases.add(new Object[] {"hmtx count huge", broken(f -> ByteBuffer.wrap(f).putShort(table(f, "hhea")[1] + 34,
                (short) -1))});
        cases.add(new Object[] {"no hhea", broken(f -> f[table(f, "hhea")[0]] = 'x')});
        cases.add(new Object[] {"no head", broken(f -> f[table(f, "head")[0]] = 'x')});
        cases.add(new Object[] {"no maxp", broken(f -> f[table(f, "maxp")[0]] = 'x')});
        cases.add(new Object[] {"no glyf", broken(f -> f[table(f, "glyf")[0]] = 'x')});
        cases.add(new Object[] {"no cmap", broken(f -> f[table(f, "cmap")[0]] = 'x')});
        cases.add(new Object[] {"cmap offset past end", broken(f -> ByteBuffer.wrap(f).putInt(table(f, "cmap")[1] + 8,
                0x7FFFFFF0))});
        cases.add(new Object[] {"zero glyphs", broken(f -> ByteBuffer.wrap(f).putShort(table(f, "maxp")[1] + 4,
                (short) 0))});
        cases.add(new Object[] {"glyph contour count huge", broken(f -> ByteBuffer.wrap(f).putShort(
                table(f, "glyf")[1] + ByteBuffer.wrap(f).getInt(table(f, "loca")[1] + 4), (short) 0x7FFF))});
        cases.add(new Object[] {"table length past end", broken(f -> ByteBuffer.wrap(f).putInt(table(f, "glyf")[0] + 12,
                0x7FFFFFFF))});
        cases.add(new Object[] {"truncated file", java.util.Arrays.copyOf(font(), 100)});
        cases.add(new Object[] {"zero glyph CFF", FontPrograms.concat(new byte[] {1, 0, 4, 1}, FontPrograms.index(
                new byte[][] {"Z".getBytes(StandardCharsets.US_ASCII)}), FontPrograms.index(new byte[][] {
                        FontPrograms.concat(FontPrograms.int5(0), new byte[] {17})}), FontPrograms.index(new byte[0][]),
                FontPrograms.index(new byte[0][]))});
        cases.add(new Object[] {"empty Type 1", ("%!PS-AdobeFont-1.0: Z\n/FontName /Z def\n/CharStrings 0 dict "
                + "currentfile eexec\n").getBytes(StandardCharsets.US_ASCII)});
        java.util.Set<String> unrecoverable = java.util.Set.of("no glyf", "truncated file", "zero glyph CFF",
                "empty Type 1");
        for (Object[] c : cases) {
            String label = (String) c[0];
            assertTolerated(label, (byte[]) c[1]);
            if (unrecoverable.contains(label) || label.startsWith("glyph contour") || label.equals("loca past glyf")) {
                continue;
            }
            byte[] out = WebFonts.toTrueType((byte[]) c[1], WebFonts.FontKind.TRUETYPE, null);
            try (org.apache.fontbox.ttf.TrueTypeFont t = new org.apache.fontbox.ttf.TTFParser()
                    .parse(new org.apache.pdfbox.io.RandomAccessReadBuffer(out))) {
                int gid = label.contains("cmap") ? 2 : t.getUnicodeCmapLookup().getGlyphId('B');
                org.junit.jupiter.api.Assertions.assertEquals(2, gid, label);
                org.junit.jupiter.api.Assertions.assertEquals(350, t.getGlyph().getGlyph(gid).getPath().getBounds2D()
                        .getWidth(), 1, label);
            }
        }
    }

    @Test
    void aDamagedProgramInASimpleFontStillMapsItsCodes() throws Exception {
        byte[] program = broken(f -> f[table(f, "hhea")[0]] = 'x');
        try (org.apache.pdfbox.pdmodel.PDDocument d = new org.apache.pdfbox.pdmodel.PDDocument()) {
            org.apache.pdfbox.cos.COSStream file = d.getDocument().createCOSStream();
            try (java.io.OutputStream o = file.createOutputStream()) {
                o.write(program);
            }
            org.apache.pdfbox.cos.COSDictionary fd = new org.apache.pdfbox.cos.COSDictionary();
            fd.setItem(org.apache.pdfbox.cos.COSName.TYPE, org.apache.pdfbox.cos.COSName.FONT_DESC);
            fd.setName(org.apache.pdfbox.cos.COSName.FONT_NAME, "Broken");
            fd.setInt(org.apache.pdfbox.cos.COSName.FLAGS, 32);
            fd.setItem(org.apache.pdfbox.cos.COSName.FONT_FILE2, file);
            org.apache.pdfbox.cos.COSDictionary f = new org.apache.pdfbox.cos.COSDictionary();
            f.setItem(org.apache.pdfbox.cos.COSName.TYPE, org.apache.pdfbox.cos.COSName.FONT);
            f.setItem(org.apache.pdfbox.cos.COSName.SUBTYPE, org.apache.pdfbox.cos.COSName.TRUE_TYPE);
            f.setName(org.apache.pdfbox.cos.COSName.BASE_FONT, "Broken");
            f.setItem(org.apache.pdfbox.cos.COSName.ENCODING, org.apache.pdfbox.cos.COSName.WIN_ANSI_ENCODING);
            f.setItem(org.apache.pdfbox.cos.COSName.FONT_DESC, fd);
            byte[] out = WebFonts.toTrueType(org.apache.pdfbox.pdmodel.font.PDFontFactory.createFont(f));
            try (org.apache.fontbox.ttf.TrueTypeFont t = new org.apache.fontbox.ttf.TTFParser()
                    .parse(new org.apache.pdfbox.io.RandomAccessReadBuffer(out))) {
                for (char c : "ABC".toCharArray()) {
                    org.junit.jupiter.api.Assertions.assertEquals(c - 'A' + 1, t.getUnicodeCmapLookup().getGlyphId(c));
                    org.junit.jupiter.api.Assertions.assertEquals(600 + c - 'A', t.getAdvanceWidth(c - 'A' + 1));
                }
            }
        }
    }

    private static byte[] decrypt(byte[] data, int key) {
        int r = key;
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            int c = data[i] & 0xFF;
            out[i] = (byte) (c ^ (r >> 8));
            r = ((c + r) * 52845 + 22719) & 0xFFFF;
        }
        return java.util.Arrays.copyOfRange(out, 4, out.length);
    }

    @Test
    void type1ProgramsWhosePrivateDictionaryFontboxRejectsStillConvert() throws Exception {
        FontPrograms.Type1 t1 = FontPrograms.type1("Damaged", 3);
        byte[] data = t1.data();
        byte[] clear = java.util.Arrays.copyOfRange(data, 0, t1.length1());
        String plain = new String(decrypt(java.util.Arrays.copyOfRange(data, t1.length1(), t1.length1() + t1.length2()),
                55665), StandardCharsets.ISO_8859_1);
        String damaged = plain.replace("/BlueValues [] def", "/BlueValues [] def\n/Junk ) ] } def\n");
        org.junit.jupiter.api.Assertions.assertNotEquals(plain, damaged);
        byte[] encrypted = FontPrograms.eexec(damaged.getBytes(StandardCharsets.ISO_8859_1));
        byte[] tail = java.util.Arrays.copyOfRange(data, t1.length1() + t1.length2(), data.length);
        byte[] broken = FontPrograms.concat(clear, encrypted, tail);
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> org.apache.fontbox.type1.Type1Font.createWithSegments(clear, encrypted));
        byte[] expected = WebFonts.toTrueType(data, WebFonts.FontKind.TYPE1, null);
        byte[] actual = WebFonts.toTrueType(broken, WebFonts.FontKind.TYPE1, null);
        try (org.apache.fontbox.ttf.TrueTypeFont e = new org.apache.fontbox.ttf.TTFParser()
                .parse(new org.apache.pdfbox.io.RandomAccessReadBuffer(expected));
                org.apache.fontbox.ttf.TrueTypeFont a = new org.apache.fontbox.ttf.TTFParser()
                        .parse(new org.apache.pdfbox.io.RandomAccessReadBuffer(actual))) {
            org.junit.jupiter.api.Assertions.assertEquals(e.getNumberOfGlyphs(), a.getNumberOfGlyphs());
            for (int gid = 0; gid < e.getNumberOfGlyphs(); gid++) {
                org.junit.jupiter.api.Assertions.assertEquals(e.getAdvanceWidth(gid), a.getAdvanceWidth(gid), "gid " + gid);
                org.junit.jupiter.api.Assertions.assertEquals(e.getGlyph().getGlyph(gid).getPath().getBounds2D(),
                        a.getGlyph().getGlyph(gid).getPath().getBounds2D(), "gid " + gid);
            }
            for (char c : "AQg".toCharArray()) {
                org.junit.jupiter.api.Assertions.assertEquals(e.getUnicodeCmapLookup().getGlyphId(c),
                        a.getUnicodeCmapLookup().getGlyphId(c));
            }
        }
    }

    @Test
    void compositeGlyphsThatReferToThemselvesAreBrokenOff() throws Exception {
        java.util.TreeMap<String, byte[]> t = new java.util.TreeMap<>();
        ByteBuffer head = ByteBuffer.allocate(54);
        head.putInt(0, 0x00010000).putInt(12, 0x5F0F3CF5).putShort(18, (short) 1000).putShort(50, (short) 1);
        t.put("head", head.array());
        ByteBuffer hhea = ByteBuffer.allocate(36);
        hhea.putInt(0, 0x00010000).putShort(34, (short) 3);
        t.put("hhea", hhea.array());
        ByteBuffer maxp = ByteBuffer.allocate(32);
        maxp.putInt(0, 0x00010000).putShort(4, (short) 3);
        t.put("maxp", maxp.array());
        t.put("hmtx", ByteBuffer.allocate(12).putShort((short) 500).putShort((short) 0).putShort((short) 600)
                .putShort((short) 0).putShort((short) 700).putShort((short) 0).array());
        ByteBuffer self = ByteBuffer.allocate(16);
        self.putShort((short) -1).putShort((short) 0).putShort((short) 0).putShort((short) 100).putShort((short) 100);
        self.putShort((short) 0x0002).putShort((short) 1).put((byte) 0).put((byte) 0);
        ByteBuffer loop = ByteBuffer.allocate(16);
        loop.putShort((short) -1).putShort((short) 0).putShort((short) 0).putShort((short) 100).putShort((short) 100);
        loop.putShort((short) 0x0002).putShort((short) 1).put((byte) 0).put((byte) 0);
        t.put("glyf", FontPrograms.concat(self.array(), loop.array()));
        t.put("loca", ByteBuffer.allocate(16).putInt(0).putInt(0).putInt(16).putInt(32).array());
        t.put("cmap", Cmaps.table(List.of(new Cmaps.Subtable(3, 1, new TreeMap<>(Map.of(0x41, 1, 0x42, 2))))));
        t.put("post", ByteBuffer.allocate(32).putInt(0x00030000).array());
        byte[] out = WebFonts.toTrueType(Sfnt.write(t), WebFonts.FontKind.TRUETYPE, null);
        try (org.apache.fontbox.ttf.TrueTypeFont f = new org.apache.fontbox.ttf.TTFParser()
                .parse(new org.apache.pdfbox.io.RandomAccessReadBuffer(out))) {
            byte[] glyf = f.getTableBytes(f.getTableMap().get("glyf"));
            long[] loca = f.getIndexToLocation().getOffsets();
            for (int gid = 0; gid < f.getNumberOfGlyphs(); gid++) {
                if (loca[gid + 1] > loca[gid] && ByteBuffer.wrap(glyf).getShort((int) loca[gid]) < 0) {
                    int child = ByteBuffer.wrap(glyf).getShort((int) loca[gid] + 12) & 0xFFFF;
                    org.junit.jupiter.api.Assertions.assertNotEquals(gid, child);
                    org.junit.jupiter.api.Assertions.assertTrue(loca[child + 1] == loca[child]
                            || ByteBuffer.wrap(glyf).getShort((int) loca[child]) >= 0, "gid " + gid);
                }
            }
            org.junit.jupiter.api.Assertions.assertEquals(600, f.getAdvanceWidth(1));
        }
    }

    @Test
    void cffWithAPaddedHeaderOrUnreadableRealsStillConverts() throws Exception {
        byte[] expected = WebFonts.toTrueType(FontPrograms.cff("Padded"), WebFonts.FontKind.CFF, Map.of());
        byte[] padded = FontPrograms.cff("Padded", new byte[0], false, 8);
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> new org.apache.fontbox.cff.CFFParser()
                .parse(new org.apache.pdfbox.io.RandomAccessReadBuffer(padded)));
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, WebFonts.toTrueType(padded, WebFonts.FontKind.CFF,
                Map.of()));
        byte[] badReal = FontPrograms.cff("Padded", FontPrograms.concat(new byte[] {30, (byte) 0xC0, (byte) 0xFF},
                new byte[] {12, 9}), false, 0);
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> new org.apache.fontbox.cff.CFFParser()
                .parse(new org.apache.pdfbox.io.RandomAccessReadBuffer(badReal)));
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, WebFonts.toTrueType(badReal, WebFonts.FontKind.CFF,
                Map.of()));
    }
}
