package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.junit.jupiter.api.Test;

class WebFontsCff2Test {

    private static byte[] index2(byte[]... items) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(ByteBuffer.allocate(4).putInt(items.length).array());
        if (items.length == 0) {
            return o.toByteArray();
        }
        o.write(4);
        int off = 1;
        for (int i = 0; i <= items.length; i++) {
            o.writeBytes(ByteBuffer.allocate(4).putInt(off).array());
            if (i < items.length) {
                off += items[i].length;
            }
        }
        for (byte[] b : items) {
            o.writeBytes(b);
        }
        return o.toByteArray();
    }

    private static byte[] ops(Object... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (Object p : parts) {
            if (p instanceof Integer i) {
                o.writeBytes(FontPrograms.num2(i));
            } else {
                o.writeBytes((byte[]) p);
            }
        }
        return o.toByteArray();
    }

    private static byte[] op(int... b) {
        byte[] out = new byte[b.length];
        for (int i = 0; i < b.length; i++) {
            out[i] = (byte) b[i];
        }
        return out;
    }

    static byte[] cff2(boolean brokenSubr) {
        byte[][] glyphs = {
            ops(0, 0, op(21), 500, 500, -500, op(6)),
            ops(100, 0, op(21), 400, 700, -400, op(6)),
            ops(100, 0, op(21), 300, 50, 1, op(16), 650, -300, op(6)),
            ops(brokenSubr ? 90 : -107, op(10))
        };
        byte[] subrs = index2(ops(100, 0, op(21), 200, 200, -200, op(6)));
        byte[] gsubrs = index2();
        ByteBuffer store = ByteBuffer.allocate(32);
        store.putShort((short) 30).putShort((short) 1).putInt(12).putShort((short) 1).putInt(22);
        store.putShort((short) 1).putShort((short) 1).putShort((short) 0).putShort((short) 0x4000)
                .putShort((short) 0x4000);
        store.putShort((short) 0).putShort((short) 0).putShort((short) 1).putShort((short) 0);
        byte[] vstore = java.util.Arrays.copyOf(store.array(), 32);
        byte[] charstrings = index2(glyphs);
        int top = 19;
        int gsubrsAt = 5 + top;
        int vstoreAt = gsubrsAt + gsubrs.length;
        int charstringsAt = vstoreAt + vstore.length;
        int fdArrayAt = charstringsAt + charstrings.length;
        byte[] fdProbe = index2(ops(FontPrograms.int5(0), FontPrograms.int5(0), op(18)));
        int privateAt = fdArrayAt + fdProbe.length;
        byte[] priv = ops(FontPrograms.int5(6), op(19));
        byte[] fdArray = index2(ops(FontPrograms.int5(priv.length), FontPrograms.int5(privateAt), op(18)));
        byte[] topDict = ops(FontPrograms.int5(charstringsAt), op(17), FontPrograms.int5(fdArrayAt), op(12, 36),
                FontPrograms.int5(vstoreAt), op(24));
        return FontPrograms.concat(op(2, 0, 5, 0, top), topDict, gsubrs, vstore, charstrings, fdArray, priv, subrs);
    }

    static byte[] openType(byte[] cff2) {
        return openType("CFF2", cff2, null);
    }

    private static byte[] openType(String tag, byte[] program, byte[] os2) {
        TreeMap<String, byte[]> tables = new TreeMap<>();
        ByteBuffer head = ByteBuffer.allocate(54);
        head.putInt(0, 0x00010000).putInt(12, 0x5F0F3CF5).putShort(18, (short) 1000).putShort(42, (short) 700);
        tables.put("head", head.array());
        ByteBuffer hhea = ByteBuffer.allocate(36);
        hhea.putInt(0, 0x00010000).putShort(4, (short) 800).putShort(6, (short) -200).putShort(34, (short) 4);
        tables.put("hhea", hhea.array());
        tables.put("maxp", ByteBuffer.allocate(6).putInt(0x00005000).putShort((short) 4).array());
        ByteBuffer hmtx = ByteBuffer.allocate(16);
        for (int adv : new int[] {500, 600, 700, 800}) {
            hmtx.putShort((short) adv).putShort((short) 0);
        }
        tables.put("hmtx", hmtx.array());
        tables.put("cmap", Cmaps.table(List.of(new Cmaps.Subtable(3, 1, new TreeMap<>(Map.of(0x41, 1, 0x42, 2,
                0x43, 3))))));
        tables.put("post", ByteBuffer.allocate(32).putInt(0x00030000).array());
        tables.put(tag, program);
        if (os2 != null) {
            tables.put("OS/2", os2);
        }
        byte[] font = Sfnt.write(tables);
        ByteBuffer.wrap(font).putInt(0, 0x4F54544F);
        return font;
    }

    @Test
    void variableCff2OutlinesConvertAtTheirDefaultInstance() throws Exception {
        byte[] ttf = WebFonts.toTrueType(openType(cff2(false)), WebFonts.FontKind.TRUETYPE, null);
        assertTrue(TrueTypeSanity.sane(ttf));
        try (TrueTypeFont t = new TTFParser().parse(new RandomAccessReadBuffer(ttf))) {
            assertEquals(4, t.getNumberOfGlyphs());
            double[][] boxes = {{100, 0, 500, 700}, {100, 0, 400, 650}, {100, 0, 300, 200}};
            for (int i = 0; i < 3; i++) {
                int gid = t.getUnicodeCmapLookup().getGlyphId('A' + i);
                assertEquals(i + 1, gid);
                assertEquals(600 + 100 * i, t.getAdvanceWidth(gid));
                Rectangle2D box = t.getGlyph().getGlyph(gid).getPath().getBounds2D();
                assertEquals(boxes[i][0], box.getMinX(), 1, "glyph " + gid);
                assertEquals(boxes[i][1], box.getMinY(), 1, "glyph " + gid);
                assertEquals(boxes[i][2], box.getMaxX(), 1, "glyph " + gid);
                assertTrue(box.getMaxY() >= boxes[i][3] - 1, "glyph " + gid);
            }
        }
    }

    @Test
    void aBrokenCff2GlyphIsDrawnEmptyAndAnUnreadableTableFails() throws Exception {
        byte[] ttf = WebFonts.toTrueType(openType(cff2(true)), WebFonts.FontKind.TRUETYPE, null);
        try (TrueTypeFont t = new TTFParser().parse(new RandomAccessReadBuffer(ttf))) {
            assertEquals(4, t.getNumberOfGlyphs());
            assertTrue(t.getGlyph().getGlyph(1).getPath().getBounds2D().getWidth() > 0);
        }
        byte[] bad = cff2(false);
        bad[0] = 3;
        assertThrows(IOException.class, () -> WebFonts.toTrueType(openType(bad), WebFonts.FontKind.TRUETYPE, null));
        assertThrows(IOException.class, () -> WebFonts.toTrueType(openType(java.util.Arrays.copyOf(cff2(false), 30)),
                WebFonts.FontKind.TRUETYPE, null));
    }

    private static byte[] randomGlyph(java.util.Random r, boolean cff1, boolean spec) {
        java.util.function.IntSupplier n = () -> r.nextInt(201) - 100;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        java.util.function.Consumer<int[]> args = a -> {
            for (int v : a) {
                o.writeBytes(FontPrograms.num2(v));
            }
        };
        args.accept(new int[] {300 + n.getAsInt(), 300 + n.getAsInt()});
        o.write(21);
        for (int k = 0; k < 12; k++) {
            int choice = r.nextInt(14);
            switch (choice) {
                case 0 -> { args.accept(new int[] {n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt()}); o.write(5); }
                case 1 -> { args.accept(new int[] {n.getAsInt(), n.getAsInt(), n.getAsInt()}); o.write(6); }
                case 2 -> { args.accept(new int[] {n.getAsInt(), n.getAsInt()}); o.write(7); }
                case 3 -> { args.accept(new int[] {n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt()}); o.write(8); }
                case 4 -> { args.accept(new int[] {n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt()}); o.write(24); }
                case 5 -> { args.accept(new int[] {n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt()}); o.write(25); }
                case 6 -> { args.accept(new int[] {n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt()}); o.write(26); }
                case 7 -> { args.accept(new int[] {n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt()}); o.write(27); }
                case 8 -> { args.accept(new int[] {n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt()}); o.write(30); }
                case 9 -> { args.accept(new int[] {n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt()}); o.write(31); }
                case 10 -> { args.accept(new int[] {n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), 50}); o.write(12); o.write(35); }
                case 11 -> { args.accept(new int[] {n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt()}); o.write(12); o.write(34); }
                case 12 -> {
                    int[] v = {n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(),
                        n.getAsInt(), n.getAsInt(), n.getAsInt()};
                    if (spec) {
                        args.accept(new int[] {v[0], v[1], v[2], v[3], v[4], 0, v[5], 0, v[6], v[7], v[8],
                            -(v[1] + v[3] + v[7])});
                        o.write(8);
                    } else {
                        args.accept(v);
                        o.write(12);
                        o.write(36);
                    }
                }
                default -> { args.accept(new int[] {n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt(), n.getAsInt()}); o.write(12); o.write(37); }
            }
            if (k < 11 && r.nextInt(5) == 0) {
                args.accept(new int[] {n.getAsInt(), n.getAsInt()});
                o.write(21);
            }
        }
        if (cff1) {
            o.write(14);
        }
        return o.toByteArray();
    }

    private static byte[] cff1(byte[][] glyphs) {
        byte[] name = FontPrograms.index(new byte[][] {"R".getBytes(java.nio.charset.StandardCharsets.US_ASCII)});
        byte[] strings = FontPrograms.index(new byte[0][]);
        byte[] gsubrs = FontPrograms.index(new byte[0][]);
        ByteArrayOutputStream charset = new ByteArrayOutputStream();
        charset.write(0);
        for (int i = 1; i < glyphs.length; i++) {
            charset.write(0);
            charset.write(i);
        }
        byte[] charstrings = FontPrograms.index(glyphs);
        byte[] priv = ops(0, op(20), 0, op(21));
        int topLength = FontPrograms.index(new byte[][] {topDict(0, 0, 0, 0)}).length;
        int charsetAt = 4 + name.length + topLength + strings.length + gsubrs.length;
        int charstringsAt = charsetAt + charset.size();
        int privateAt = charstringsAt + charstrings.length;
        byte[] top = FontPrograms.index(new byte[][] {topDict(charsetAt, charstringsAt, priv.length, privateAt)});
        return FontPrograms.concat(op(1, 0, 4, 4), name, top, strings, gsubrs, charset.toByteArray(), charstrings, priv);
    }

    private static byte[] topDict(int charset, int charstrings, int privSize, int privAt) {
        return ops(FontPrograms.int5(charset), op(15), FontPrograms.int5(charstrings), op(17),
                FontPrograms.int5(privSize), FontPrograms.int5(privAt), op(18));
    }

    @Test
    void everyCff2PathOperatorDrawsWhatFontboxDrawsForTheSameCff1Glyph() throws Exception {
        java.util.Random r = new java.util.Random(42);
        int count = 60;
        byte[][] one = new byte[count][];
        byte[][] two = new byte[count][];
        byte[][] ours = new byte[count][];
        for (int i = 0; i < count; i++) {
            long seed = r.nextLong();
            one[i] = randomGlyph(new java.util.Random(seed), true, true);
            two[i] = randomGlyph(new java.util.Random(seed), false, false);
            ours[i] = randomGlyph(new java.util.Random(seed), true, false);
        }
        org.apache.fontbox.cff.CFFType1Font reference = (org.apache.fontbox.cff.CFFType1Font) new org.apache.fontbox.cff
                .CFFParser().parse(new RandomAccessReadBuffer(cff1(one))).get(0);
        byte[] table = cff2(false);
        Cff2 mine = new Cff2(withCharStrings(table, two));
        TrueTypeFont converted = new TTFParser().parse(new RandomAccessReadBuffer(WebFonts.toTrueType(cff1(ours),
                WebFonts.FontKind.CFF, Map.of())));
        for (int gid = 0; gid < count; gid++) {
            java.awt.geom.GeneralPath expected = reference.getType2CharString(gid).getPath();
            java.awt.geom.GeneralPath actual = mine.path(gid);
            Rectangle2D a = expected.getBounds2D();
            Rectangle2D b = actual.getBounds2D();
            assertEquals(a.getMinX(), b.getMinX(), 0.01, "glyph " + gid);
            assertEquals(a.getMinY(), b.getMinY(), 0.01, "glyph " + gid);
            assertEquals(a.getMaxX(), b.getMaxX(), 0.01, "glyph " + gid);
            assertEquals(a.getMaxY(), b.getMaxY(), 0.01, "glyph " + gid);
            Rectangle2D c = tight(converted.getGlyph().getGlyph(gid).getPath());
            Rectangle2D t = tight(expected);
            assertEquals(t.getMinX(), c.getMinX(), 2, "converted glyph " + gid);
            assertEquals(t.getMinY(), c.getMinY(), 2, "converted glyph " + gid);
            assertEquals(t.getMaxX(), c.getMaxX(), 2, "converted glyph " + gid);
            assertEquals(t.getMaxY(), c.getMaxY(), 2, "converted glyph " + gid);
            for (double x = a.getMinX(); x < a.getMaxX(); x += a.getWidth() / 23) {
                for (double y = a.getMinY(); y < a.getMaxY(); y += a.getHeight() / 23) {
                    assertEquals(expected.contains(x, y), actual.contains(x, y), "glyph " + gid + " at " + x + "," + y);
                }
            }
        }
    }

    private static Rectangle2D tight(java.awt.Shape shape) {
        Rectangle2D r = null;
        double[] c = new double[6];
        for (java.awt.geom.PathIterator it = new java.awt.geom.FlatteningPathIterator(shape.getPathIterator(null), 0.05);
                !it.isDone(); it.next()) {
            if (it.currentSegment(c) != java.awt.geom.PathIterator.SEG_CLOSE) {
                if (r == null) {
                    r = new Rectangle2D.Double(c[0], c[1], 0, 0);
                } else {
                    r.add(c[0], c[1]);
                }
            }
        }
        return r == null ? new Rectangle2D.Double() : r;
    }

    private static byte[] withCharStrings(byte[] unused, byte[][] glyphs) {
        byte[] gsubrs = index2();
        byte[] charstrings = index2(glyphs);
        int top = 13;
        int gsubrsAt = 5 + top;
        int charstringsAt = gsubrsAt + gsubrs.length;
        int fdArrayAt = charstringsAt + charstrings.length;
        byte[] fdArray = index2(ops(0, 0, op(18)));
        byte[] topDict = ops(FontPrograms.int5(charstringsAt), op(17), FontPrograms.int5(fdArrayAt), op(12, 36));
        return FontPrograms.concat(op(2, 0, 5, 0, top), topDict, gsubrs, charstrings, fdArray);
    }

    @Test
    void anIndexOffsetWithItsTopBitSetIsRejectedWithoutAllocating() {
        ByteBuffer table = ByteBuffer.allocate(19);
        table.put(new byte[] {2, 0, 5, 0, 0}).putInt(1).put((byte) 4).putInt(0x80000010).putInt(1).put((byte) 0);
        assertThrows(IOException.class, () -> new Cff2(table.array()));
        ByteBuffer wide = ByteBuffer.allocate(19);
        wide.put(new byte[] {2, 0, 5, 0, 0}).putInt(1).put((byte) 7).putInt(1).putInt(2).put((byte) 0);
        assertThrows(IOException.class, () -> new Cff2(wide.array()));
        ByteBuffer many = ByteBuffer.allocate(64);
        many.put(new byte[] {2, 0, 5, 0, 0}).putInt(50).put((byte) 1);
        assertThrows(IOException.class, () -> new Cff2(many.array()));
    }

    @Test
    void fdSelectRangesThatDoNotIncreaseAreRejectedQuickly() {
        int glyphs = 300_000;
        int ranges = 600_000;
        int charStringsAt = 25 + 4;
        int fdArrayAt = charStringsAt + 5 + glyphs + 1;
        int fdSelectAt = fdArrayAt + 4;
        ByteBuffer t = ByteBuffer.allocate(fdSelectAt + 5 + ranges * 6 + 4);
        t.put(new byte[] {2, 0, 5, 0, 20});
        t.put((byte) 29).putInt(charStringsAt).put((byte) 17);
        t.put((byte) 29).putInt(fdArrayAt).put((byte) 12).put((byte) 36);
        t.put((byte) 29).putInt(fdSelectAt).put((byte) 12).put((byte) 37);
        t.putInt(0);
        t.putInt(glyphs).put((byte) 1);
        for (int i = 0; i <= glyphs; i++) {
            t.put((byte) 1);
        }
        t.putInt(0);
        t.put((byte) 4).putInt(ranges);
        for (int r = 0; r < ranges; r++) {
            t.putInt(r % 2 == 0 ? 0 : glyphs).putShort((short) 0);
        }
        t.putInt(glyphs);
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertThrows(IOException.class,
                () -> new Cff2(t.array())));
    }
    @Test
    void theSourceEmbeddingPermissionsSurviveConversion() throws Exception {
        ByteBuffer os2 = ByteBuffer.allocate(96);
        os2.putShort(0, (short) 4).putShort(8, (short) 0x0002);
        byte[][] programs = {cff2(false), cff1(new byte[][] {op(14), op(14), op(14), op(14)})};
        String[] tags = {"CFF2", "CFF "};
        for (int i = 0; i < programs.length; i++) {
            byte[] ttf = WebFonts.toTrueType(openType(tags[i], programs[i], os2.array()), WebFonts.FontKind.TRUETYPE,
                    null);
            try (TrueTypeFont t = new TTFParser().parse(new RandomAccessReadBuffer(ttf))) {
                assertEquals(0x0002, t.getOS2Windows().getFsType(), tags[i]);
            }
        }
    }
}
