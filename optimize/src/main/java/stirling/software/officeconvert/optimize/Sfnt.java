package stirling.software.officeconvert.optimize;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.fontbox.ttf.GlyphData;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;

final class Sfnt {

    private static final Set<String> UNUSED = Set.of("DSIG", "meta");

    private static final int TRUE = 0x74727565;

    private record Table(String tag, int offset, int length) {}

    private Sfnt() {}

    static byte[] tidy(byte[] font) {
        List<Table> tables = tables(font);
        if (tables == null || !glyphsInside(font, tables)) {
            return null;
        }
        List<Table> kept = new ArrayList<>();
        long size = 12;
        for (Table t : tables) {
            if (!UNUSED.contains(t.tag)) {
                kept.add(t);
                size += 16 + (t.length + 3L & ~3L);
            }
        }
        if (size >= font.length) {
            return null;
        }
        byte[] out = new byte[(int) size];
        ByteBuffer b = ByteBuffer.wrap(out);
        int n = kept.size();
        int entry = 31 - Integer.numberOfLeadingZeros(n);
        b.putInt(0, ByteBuffer.wrap(font).getInt(0));
        b.putShort(4, (short) n);
        b.putShort(6, (short) (16 << entry));
        b.putShort(8, (short) entry);
        b.putShort(10, (short) (n * 16 - (16 << entry)));
        List<Table> byOffset = new ArrayList<>(kept);
        byOffset.sort(Comparator.comparingInt(Table::offset));
        int at = 12 + 16 * n;
        int[] placed = new int[n];
        int headAt = -1;
        for (Table t : byOffset) {
            System.arraycopy(font, t.offset, out, at, t.length);
            placed[kept.indexOf(t)] = at;
            if (t.tag.equals("head")) {
                headAt = at;
                b.putInt(at + 8, 0);
            }
            at += t.length + 3 & ~3;
        }
        if (headAt < 0) {
            return null;
        }
        List<Table> byTag = new ArrayList<>(kept);
        byTag.sort(Comparator.comparing(Table::tag));
        for (int i = 0; i < n; i++) {
            Table t = byTag.get(i);
            int p = placed[kept.indexOf(t)];
            int r = 12 + 16 * i;
            for (int k = 0; k < 4; k++) {
                out[r + k] = (byte) t.tag.charAt(k);
            }
            b.putInt(r + 4, checksum(out, p, t.length));
            b.putInt(r + 8, p);
            b.putInt(r + 12, t.length);
        }
        b.putInt(headAt + 8, 0xB1B0AFBA - checksum(out, 0, out.length));
        return sameFont(font, out) ? out : null;
    }

    private static List<Table> tables(byte[] font) {
        if (font.length < 12) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(font);
        int version = b.getInt(0);
        if (version != 0x00010000 && version != TRUE) {
            return null;
        }
        int n = b.getShort(4) & 0xFFFF;
        if (n == 0 || 12L + 16L * n > font.length) {
            return null;
        }
        List<Table> tables = new ArrayList<>(n);
        Set<String> tags = new HashSet<>();
        for (int i = 0; i < n; i++) {
            int r = 12 + 16 * i;
            String tag = new String(font, r, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            long offset = b.getInt(r + 8) & 0xFFFFFFFFL;
            long length = b.getInt(r + 12) & 0xFFFFFFFFL;
            if (offset < 12L + 16L * n || offset + length > font.length || !tags.add(tag)) {
                return null;
            }
            tables.add(new Table(tag, (int) offset, (int) length));
        }
        return tables;
    }

    private static boolean glyphsInside(byte[] font, List<Table> tables) {
        Table head = find(tables, "head");
        Table maxp = find(tables, "maxp");
        Table loca = find(tables, "loca");
        Table glyf = find(tables, "glyf");
        if (head == null || head.length < 54) {
            return false;
        }
        if (loca == null && glyf == null) {
            return true;
        }
        if (loca == null || glyf == null || maxp == null || maxp.length < 6) {
            return false;
        }
        ByteBuffer b = ByteBuffer.wrap(font);
        boolean longOffsets = b.getShort(head.offset + 50) != 0;
        int glyphs = b.getShort(maxp.offset + 4) & 0xFFFF;
        int width = longOffsets ? 4 : 2;
        if ((long) (glyphs + 1) * width > loca.length) {
            return false;
        }
        for (int g = 0; g <= glyphs; g++) {
            long end = longOffsets ? b.getInt(loca.offset + 4 * g) & 0xFFFFFFFFL
                    : (b.getShort(loca.offset + 2 * g) & 0xFFFFL) * 2;
            if (end > glyf.length) {
                return false;
            }
        }
        return true;
    }

    private static Table find(List<Table> tables, String tag) {
        for (Table t : tables) {
            if (t.tag.equals(tag)) {
                return t;
            }
        }
        return null;
    }

    private static int checksum(byte[] data, int from, int length) {
        int sum = 0;
        int end = from + length;
        for (int i = from; i < end; i += 4) {
            int v = 0;
            for (int k = 0; k < 4; k++) {
                v = v << 8 | (i + k < end ? data[i + k] & 0xFF : 0);
            }
            sum += v;
        }
        return sum;
    }

    private static boolean sameFont(byte[] original, byte[] tidied) {
        try (TrueTypeFont a = new TTFParser(true).parse(new RandomAccessReadBuffer(original));
                TrueTypeFont b = new TTFParser(true).parse(new RandomAccessReadBuffer(tidied))) {
            int glyphs = a.getNumberOfGlyphs();
            if (glyphs != b.getNumberOfGlyphs() || a.getUnitsPerEm() != b.getUnitsPerEm()) {
                return false;
            }
            for (int g = 0; g < glyphs; g++) {
                if (a.getAdvanceWidth(g) != b.getAdvanceWidth(g)) {
                    return false;
                }
            }
            if (a.getGlyph() != null) {
                for (int g : new int[] {0, glyphs - 1}) {
                    GlyphData ga = a.getGlyph().getGlyph(g);
                    GlyphData gb = b.getGlyph().getGlyph(g);
                    if ((ga == null) != (gb == null) || ga != null
                            && !ga.getBoundingBox().toString().equals(gb.getBoundingBox().toString())) {
                        return false;
                    }
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
