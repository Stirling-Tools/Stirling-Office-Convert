package stirling.software.officeconvert.topdf.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.TreeMap;

import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;

public final class TestFonts {

    public static final String BUNDLED = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";

    private TestFonts() {}

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
}
