package stirling.software.officeconvert.optimize;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.fontbox.ttf.GlyphData;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;

final class Sfnt {

    private static final Set<String> UNUSED = Set.of("DSIG", "meta");

    private static final Set<String> RENDERING = Set.of("head", "hhea", "maxp", "hmtx", "loca", "glyf", "cvt ",
            "fpgm", "prep", "gasp", "vhea", "vmtx", "post");

    private static final Set<String> LOOKUP = Set.of("cmap", "OS/2");

    private static final int MAX_COMPONENT_DEPTH = 64;

    private static final int TRUE = 0x74727565;

    private static final int MIN_SAVING = 256;

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
        if (size + MIN_SAVING > font.length) {
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

    static byte[] subset(byte[] font, BitSet used, boolean simple) {
        List<Table> tables = tables(font);
        if (tables == null || !glyphsInside(font, tables)) {
            return null;
        }
        Table head = find(tables, "head");
        Table maxp = find(tables, "maxp");
        Table loca = find(tables, "loca");
        Table glyf = find(tables, "glyf");
        Table hhea = find(tables, "hhea");
        Table hmtx = find(tables, "hmtx");
        if (loca == null || glyf == null || hhea == null || hhea.length < 36 || hmtx == null) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(font);
        boolean longOffsets = b.getShort(head.offset + 50) != 0;
        int glyphs = b.getShort(maxp.offset + 4) & 0xFFFF;
        long[] offsets = new long[glyphs + 1];
        for (int g = 0; g <= glyphs; g++) {
            offsets[g] = longOffsets ? b.getInt(loca.offset + 4 * g) & 0xFFFFFFFFL
                    : (b.getShort(loca.offset + 2 * g) & 0xFFFFL) * 2;
        }
        BitSet keep = closure(font, glyf.offset, offsets, used, glyphs);
        long size = 0;
        for (int g = keep.nextSetBit(0); g >= 0; g = keep.nextSetBit(g + 1)) {
            long length = Math.max(0, offsets[g + 1] - offsets[g]);
            size += length + (length & 1);
        }
        if (size > Integer.MAX_VALUE - 16) {
            return null;
        }
        boolean shortLoca = size <= 0x1FFFE;
        byte[] newGlyf = new byte[(int) size];
        ByteBuffer newLoca = ByteBuffer.allocate((glyphs + 1) * (shortLoca ? 2 : 4));
        int at = 0;
        for (int g = 0; g < glyphs; g++) {
            if (shortLoca) {
                newLoca.putShort((short) (at / 2));
            } else {
                newLoca.putInt(at);
            }
            long length = offsets[g + 1] - offsets[g];
            if (keep.get(g) && length > 0) {
                System.arraycopy(font, glyf.offset + (int) offsets[g], newGlyf, at, (int) length);
                at += (int) (length + (length & 1));
            }
        }
        if (shortLoca) {
            newLoca.putShort((short) (at / 2));
        } else {
            newLoca.putInt(at);
        }
        int metrics = b.getShort(hhea.offset + 34) & 0xFFFF;
        byte[] newHmtx = Arrays.copyOfRange(font, hmtx.offset, hmtx.offset + hmtx.length);
        for (int g = keep.nextClearBit(0); g < glyphs; g = keep.nextClearBit(g + 1)) {
            if (g == metrics - 1) {
                continue;
            }
            int from = g < metrics ? 4 * g : 4 * metrics + 2 * (g - metrics);
            int to = Math.min(newHmtx.length, from + (g < metrics ? 4 : 2));
            if (from < to) {
                Arrays.fill(newHmtx, from, to, (byte) 0);
            }
        }
        byte[] newHead = Arrays.copyOfRange(font, head.offset, head.offset + head.length);
        ByteBuffer.wrap(newHead).putShort(50, (short) (shortLoca ? 0 : 1));
        TreeMap<String, byte[]> out = new TreeMap<>();
        for (Table t : tables) {
            if (RENDERING.contains(t.tag) || simple && LOOKUP.contains(t.tag)) {
                out.put(t.tag, Arrays.copyOfRange(font, t.offset, t.offset + t.length));
            }
        }
        out.put("head", newHead);
        out.put("loca", newLoca.array());
        out.put("glyf", newGlyf);
        out.put("hmtx", newHmtx);
        if (!simple && out.containsKey("post")) {
            byte[] post = new byte[32];
            System.arraycopy(out.get("post"), 0, post, 0, Math.min(16, out.get("post").length));
            ByteBuffer.wrap(post).putInt(0, 0x00030000);
            out.put("post", post);
        }
        byte[] result = assemble(b.getInt(0), out);
        return sameGlyphs(font, result, keep) ? result : null;
    }

    static byte[] complete(byte[] font) {
        if (font.length < 12) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(font);
        int version = b.getInt(0);
        int n = b.getShort(4) & 0xFFFF;
        if (version != 0x00010000 && version != TRUE || n == 0 || 12L + 16L * n > font.length) {
            return null;
        }
        List<Table> whole = new ArrayList<>();
        Set<String> tags = new HashSet<>();
        for (int i = 0; i < n; i++) {
            int r = 12 + 16 * i;
            String tag = new String(font, r, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            long offset = b.getInt(r + 8) & 0xFFFFFFFFL;
            long length = b.getInt(r + 12) & 0xFFFFFFFFL;
            if (!tags.add(tag)) {
                return null;
            }
            if (offset >= 12L + 16L * n && offset + length <= font.length && !UNUSED.contains(tag)) {
                whole.add(new Table(tag, (int) offset, (int) length));
            }
        }
        for (String needed : List.of("head", "hhea", "maxp", "hmtx", "loca", "glyf")) {
            if (find(whole, needed) == null) {
                return null;
            }
        }
        if (!glyphsInside(font, whole)) {
            return null;
        }
        TreeMap<String, byte[]> out = new TreeMap<>();
        for (Table t : whole) {
            out.put(t.tag, Arrays.copyOfRange(font, t.offset, t.offset + t.length));
        }
        byte[] result = assemble(version, out);
        try (TrueTypeFont parsed = new TTFParser(true).parse(new RandomAccessReadBuffer(result))) {
            return parsed.getNumberOfGlyphs() > 0 && parsed.getGlyph() != null ? result : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static BitSet closure(byte[] font, int glyf, long[] offsets, BitSet used, int glyphs) {
        BitSet keep = new BitSet(glyphs);
        keep.set(0);
        ArrayDeque<int[]> todo = new ArrayDeque<>();
        todo.push(new int[] {0, 0});
        for (int g = used.nextSetBit(0); g >= 0 && g < glyphs; g = used.nextSetBit(g + 1)) {
            if (!keep.get(g)) {
                keep.set(g);
                todo.push(new int[] {g, 0});
            }
        }
        ByteBuffer b = ByteBuffer.wrap(font);
        while (!todo.isEmpty()) {
            int[] next = todo.pop();
            int g = next[0];
            long start = offsets[g];
            long end = offsets[g + 1];
            if (end - start < 10 || next[1] > MAX_COMPONENT_DEPTH || b.getShort(glyf + (int) start) >= 0) {
                continue;
            }
            int at = glyf + (int) start + 10;
            int limit = glyf + (int) end;
            while (at + 4 <= limit) {
                int flags = b.getShort(at) & 0xFFFF;
                int component = b.getShort(at + 2) & 0xFFFF;
                if (component < glyphs && !keep.get(component)) {
                    keep.set(component);
                    todo.push(new int[] {component, next[1] + 1});
                }
                at += 4 + ((flags & 0x0001) != 0 ? 4 : 2) + ((flags & 0x0008) != 0 ? 2
                        : (flags & 0x0040) != 0 ? 4 : (flags & 0x0080) != 0 ? 8 : 0);
                if ((flags & 0x0020) == 0) {
                    break;
                }
            }
        }
        return keep;
    }

    private static byte[] assemble(int version, TreeMap<String, byte[]> tables) {
        int n = tables.size();
        long size = 12L + 16L * n;
        for (byte[] t : tables.values()) {
            size += t.length + 3L & ~3L;
        }
        byte[] out = new byte[(int) size];
        ByteBuffer b = ByteBuffer.wrap(out);
        int entry = 31 - Integer.numberOfLeadingZeros(n);
        b.putInt(0, version);
        b.putShort(4, (short) n);
        b.putShort(6, (short) (16 << entry));
        b.putShort(8, (short) entry);
        b.putShort(10, (short) (n * 16 - (16 << entry)));
        int at = 12 + 16 * n;
        int r = 12;
        int headAt = -1;
        for (Map.Entry<String, byte[]> e : tables.entrySet()) {
            byte[] data = e.getValue();
            System.arraycopy(data, 0, out, at, data.length);
            if (e.getKey().equals("head")) {
                headAt = at;
                b.putInt(at + 8, 0);
            }
            for (int k = 0; k < 4; k++) {
                out[r + k] = (byte) e.getKey().charAt(k);
            }
            b.putInt(r + 4, checksum(out, at, data.length));
            b.putInt(r + 8, at);
            b.putInt(r + 12, data.length);
            r += 16;
            at += data.length + 3 & ~3;
        }
        if (headAt >= 0) {
            b.putInt(headAt + 8, 0xB1B0AFBA - checksum(out, 0, out.length));
        }
        return out;
    }

    private static boolean sameGlyphs(byte[] original, byte[] subset, BitSet keep) {
        try (TrueTypeFont a = new TTFParser(true).parse(new RandomAccessReadBuffer(original));
                TrueTypeFont b = new TTFParser(true).parse(new RandomAccessReadBuffer(subset))) {
            int glyphs = a.getNumberOfGlyphs();
            if (glyphs != b.getNumberOfGlyphs() || a.getUnitsPerEm() != b.getUnitsPerEm()
                    || (a.getGlyph() == null) != (b.getGlyph() == null)) {
                return false;
            }
            for (int g = keep.nextSetBit(0); g >= 0 && g < glyphs; g = keep.nextSetBit(g + 1)) {
                if (a.getAdvanceWidth(g) != b.getAdvanceWidth(g)) {
                    return false;
                }
                if (a.getGlyph() == null) {
                    continue;
                }
                GlyphData ga = a.getGlyph().getGlyph(g);
                GlyphData gb = b.getGlyph().getGlyph(g);
                if ((ga == null) != (gb == null) || ga != null && (ga.getNumberOfContours() != gb.getNumberOfContours()
                        || ga.getDescription().getPointCount() != gb.getDescription().getPointCount()
                        || !ga.getBoundingBox().toString().equals(gb.getBoundingBox().toString()))) {
                    return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
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
