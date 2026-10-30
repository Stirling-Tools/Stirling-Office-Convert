package stirling.software.officeconvert.topdf.testing;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.imageio.ImageIO;

import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Assumptions;

import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontLibrary;

public final class TestFonts {

    public static final String BUNDLED = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";

    private TestFonts() {}

    public static final String[] CALIBRI = {"Calibri", "Carlito"};

    public static void assumeInstalled(String requested, boolean bold, String... families) {
        FontFace face = FontLibrary.system().find(requested, bold, false);
        boolean found = Arrays.stream(families).anyMatch(f -> f.equalsIgnoreCase(face.family()))
                && !(bold && face.syntheticBold());
        Assumptions.assumeTrue(found, requested + (bold ? " Bold" : "") + " or a metric twin is not installed here");
    }

    public static byte[] bundled() {
        try (InputStream in = PDDocument.class.getResourceAsStream(BUNDLED)) {
            if (in == null) {
                throw new IllegalStateException("PDFBox no longer ships " + BUNDLED);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] renamed(String family) {
        return renamed(bundled(), family, false);
    }

    public static byte[] damagedGlyph(String family, int codePoint) {
        byte[] ttf = renamed(family);
        try (TrueTypeFont font = new TTFParser().parse(new RandomAccessReadBuffer(ttf))) {
            int glyph = font.getUnicodeCmapLookup().getGlyphId(codePoint);
            long loca = font.getTableMap().get("loca").getOffset();
            ByteBuffer b = ByteBuffer.wrap(ttf);
            if (font.getHeader().getIndexToLocFormat() == 0) {
                b.putShort((int) loca + 2 * (glyph + 1), (short) 0);
            } else {
                b.putInt((int) loca + 4 * (glyph + 1), 0);
            }
            return ttf;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] withEastAsianGlyphs(String family) {
        return withGlyphs(family, 'A', 0x3042, 0x4E00, 0xAC00);
    }

    public static byte[] withGlyphs(String family, char source, int... codePoints) {
        byte[] ttf = renamed(family);
        try (TrueTypeFont font = new TTFParser().parse(new RandomAccessReadBuffer(ttf))) {
            int glyph = font.getUnicodeCmapLookup().getGlyphId(source);
            TreeMap<Integer, Integer> map = new TreeMap<>();
            for (int c = 0x20; c < 0x7F; c++) {
                map.put(c, font.getUnicodeCmapLookup().getGlyphId(c));
            }
            for (int c : codePoints) {
                map.put(c, glyph);
            }
            return replaced(ttf, "cmap", cmap(map));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] withLineMetrics(byte[] ttf, int ascent, int descent) {
        try (TrueTypeFont font = new TTFParser().parse(new RandomAccessReadBuffer(ttf))) {
            byte[] out = ttf.clone();
            ByteBuffer b = ByteBuffer.wrap(out);
            int hhea = (int) font.getTableMap().get("hhea").getOffset();
            int os2 = (int) font.getTableMap().get("OS/2").getOffset();
            b.putShort(hhea + 4, (short) ascent).putShort(hhea + 6, (short) -descent).putShort(hhea + 8, (short) 0);
            b.putShort(os2 + 68, (short) ascent).putShort(os2 + 70, (short) -descent).putShort(os2 + 72, (short) 0);
            b.putShort(os2 + 74, (short) ascent).putShort(os2 + 76, (short) descent);
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] cmap(TreeMap<Integer, Integer> map) {
        int segments = map.size() + 1;
        ByteBuffer b = ByteBuffer.allocate(12 + 16 + 8 * segments);
        b.putShort((short) 0).putShort((short) 1).putShort((short) 3).putShort((short) 1).putInt(12);
        b.putShort((short) 4).putShort((short) (16 + 8 * segments)).putShort((short) 0)
                .putShort((short) (2 * segments)).putShort((short) 0).putShort((short) 0).putShort((short) 0);
        for (int c : map.keySet()) {
            b.putShort((short) c);
        }
        b.putShort((short) 0xFFFF).putShort((short) 0);
        for (int c : map.keySet()) {
            b.putShort((short) c);
        }
        b.putShort((short) 0xFFFF);
        for (var e : map.entrySet()) {
            b.putShort((short) (e.getValue() - e.getKey()));
        }
        b.putShort((short) 1);
        for (int i = 0; i < segments; i++) {
            b.putShort((short) 0);
        }
        return b.array();
    }

    private static byte[] replaced(byte[] ttf, String table, byte[] data) {
        ByteBuffer in = ByteBuffer.wrap(ttf);
        int tables = in.getShort(4) & 0xFFFF;
        for (int i = 0; i < tables; i++) {
            int at = 12 + 16 * i;
            if (new String(ttf, at, 4, StandardCharsets.ISO_8859_1).equals(table)) {
                int offset = (ttf.length + 3) & ~3;
                byte[] out = Arrays.copyOf(ttf, offset + data.length);
                System.arraycopy(data, 0, out, offset, data.length);
                ByteBuffer.wrap(out).putInt(at + 8, offset).putInt(at + 12, data.length);
                return out;
            }
        }
        throw new IllegalArgumentException("The font has no " + table + " table");
    }

    public static byte[] renamedCff(String family) {
        return renamed(bundled(), family, true);
    }

    static byte[] renamed(byte[] ttf, String family, boolean otto) {
        ByteBuffer in = ByteBuffer.wrap(ttf);
        int tables = in.getShort(4) & 0xFFFF;
        int record = -1;
        for (int i = 0; i < tables; i++) {
            int at = 12 + 16 * i;
            String tag = new String(ttf, at, 4, StandardCharsets.ISO_8859_1);
            if (tag.equals("name")) {
                record = at;
            }
        }
        if (record < 0) {
            throw new IllegalArgumentException("The font has no name table");
        }
        byte[] name = nameTable(family);
        int offset = (ttf.length + 3) & ~3;
        byte[] out = Arrays.copyOf(ttf, offset + name.length);
        System.arraycopy(name, 0, out, offset, name.length);
        ByteBuffer b = ByteBuffer.wrap(out);
        b.putInt(record + 8, offset);
        b.putInt(record + 12, name.length);
        if (otto) {
            b.put(0, (byte) 'O').put(1, (byte) 'T').put(2, (byte) 'T').put(3, (byte) 'O');
        }
        return out;
    }

    private static byte[] nameTable(String family) {
        String ps = family.replace(" ", "");
        String[][] names = {{"1", family}, {"2", "Regular"}, {"4", family}, {"6", ps}};
        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        ByteBuffer head = ByteBuffer.allocate(6 + 12 * names.length);
        head.putShort((short) 0).putShort((short) names.length).putShort((short) (6 + 12 * names.length));
        for (String[] n : names) {
            byte[] s = n[1].getBytes(StandardCharsets.UTF_16BE);
            head.putShort((short) 3).putShort((short) 1).putShort((short) 0x409).putShort(Short.parseShort(n[0]))
                    .putShort((short) s.length).putShort((short) strings.size());
            strings.writeBytes(s);
        }
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.writeBytes(head.array());
        all.writeBytes(strings.toByteArray());
        return all.toByteArray();
    }

    private static byte[] png(int rgb) throws IOException {
        BufferedImage img = new BufferedImage(136, 128, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 136; x++) {
                img.setRGB(x, y, 0xFF000000 | rgb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static byte[] bytes(ByteBuffer b) {
        byte[] out = new byte[b.position()];
        b.flip().get(out);
        return out;
    }

    public static byte[] colourEmoji(int... colours) throws IOException {
        int glyphs = colours.length + 1;
        Map<String, byte[]> t = new TreeMap<>();
        t.put("head", bytes(ByteBuffer.allocate(54).putInt(0x00010000).putInt(0x00010000).putInt(0)
                .putInt(0x5F0F3CF5).putShort((short) 0).putShort((short) 2048).putLong(0).putLong(0)
                .putShort((short) 0).putShort((short) -500).putShort((short) 2550).putShort((short) 1900)
                .putShort((short) 0).putShort((short) 8).putShort((short) 2).putShort((short) 0)
                .putShort((short) 0)));
        t.put("hhea", bytes(ByteBuffer.allocate(36).putInt(0x00010000).putShort((short) 1900)
                .putShort((short) -500).putShort((short) 0).putShort((short) 2550).put(new byte[22])
                .putShort((short) glyphs)));
        t.put("maxp", bytes(ByteBuffer.allocate(6).putInt(0x00005000).putShort((short) glyphs)));
        ByteBuffer hmtx = ByteBuffer.allocate(4 * glyphs);
        for (int g = 0; g < glyphs; g++) {
            hmtx.putShort((short) 2550).putShort((short) 0);
        }
        t.put("hmtx", bytes(hmtx));
        int segs = colours.length + 1;
        ByteBuffer cmap = ByteBuffer.allocate(12 + 16 + 8 * segs);
        cmap.putShort((short) 0).putShort((short) 1).putShort((short) 3).putShort((short) 1).putInt(12);
        cmap.putShort((short) 4).putShort((short) (16 + 8 * segs)).putShort((short) 0).putShort((short) (2 * segs))
                .putShort((short) 2).putShort((short) 0).putShort((short) 0);
        int[] codes = {0x2705, 0x2B50};
        for (int i = 0; i < colours.length; i++) {
            cmap.putShort((short) codes[i]);
        }
        cmap.putShort((short) 0xFFFF).putShort((short) 0);
        for (int i = 0; i < colours.length; i++) {
            cmap.putShort((short) codes[i]);
        }
        cmap.putShort((short) 0xFFFF);
        for (int i = 0; i < colours.length; i++) {
            cmap.putShort((short) (i + 1 - codes[i]));
        }
        cmap.putShort((short) 1);
        for (int i = 0; i < segs; i++) {
            cmap.putShort((short) 0);
        }
        t.put("cmap", bytes(cmap));
        byte[] family = "Test Color Emoji".getBytes(StandardCharsets.UTF_16BE);
        ByteBuffer name = ByteBuffer.allocate(6 + 12 * 3 + family.length);
        name.putShort((short) 0).putShort((short) 3).putShort((short) (6 + 12 * 3));
        for (int id : new int[] {1, 4, 6}) {
            name.putShort((short) 3).putShort((short) 1).putShort((short) 0x409).putShort((short) id)
                    .putShort((short) family.length).putShort((short) 0);
        }
        t.put("name", bytes(name.put(family)));
        t.put("post", bytes(ByteBuffer.allocate(32).putInt(0x00030000)));
        ByteArrayOutputStream cbdt = new ByteArrayOutputStream();
        cbdt.write(new byte[] {0, 3, 0, 0});
        List<Integer> offsets = new ArrayList<>();
        for (int c : colours) {
            offsets.add(cbdt.size() - 4);
            byte[] p = png(c);
            cbdt.write(new byte[] {(byte) 128, (byte) 136, 0, 101, (byte) 136});
            cbdt.write(ByteBuffer.allocate(4).putInt(p.length).array());
            cbdt.write(p);
        }
        offsets.add(cbdt.size() - 4);
        t.put("CBDT", cbdt.toByteArray());
        ByteBuffer cblc = ByteBuffer.allocate(8 + 48 + 8 + 8 + 4 * offsets.size());
        cblc.putInt(0x00030000).putInt(1).putInt(56).putInt(16 + 4 * offsets.size()).putInt(1).putInt(0)
                .put(new byte[24]).putShort((short) 1).putShort((short) colours.length).put((byte) 109)
                .put((byte) 109).put((byte) 32).put((byte) 1);
        cblc.putShort((short) 1).putShort((short) colours.length).putInt(8);
        cblc.putShort((short) 1).putShort((short) 17).putInt(4);
        offsets.forEach(cblc::putInt);
        t.put("CBLC", bytes(cblc));
        int n = t.size();
        int at = 12 + 16 * n;
        ByteArrayOutputStream font = new ByteArrayOutputStream();
        ByteBuffer dir = ByteBuffer.allocate(12 + 16 * n).putInt(0x00010000).putShort((short) n)
                .putShort((short) 0).putShort((short) 0).putShort((short) 0);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> e : t.entrySet()) {
            dir.put(e.getKey().getBytes(StandardCharsets.ISO_8859_1)).putInt(0).putInt(at + body.size())
                    .putInt(e.getValue().length);
            body.write(e.getValue());
            body.write(new byte[(4 - e.getValue().length % 4) % 4]);
        }
        font.write(dir.array());
        font.write(body.toByteArray());
        return font.toByteArray();
    }
}
