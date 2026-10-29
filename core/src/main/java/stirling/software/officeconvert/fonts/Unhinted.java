package stirling.software.officeconvert.fonts;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class Unhinted {

    private static final int GLYF = 0x676C7966;

    private static final int LOCA = 0x6C6F6361;

    private static final int HEAD = 0x68656164;

    private static final int MAXP = 0x6D617870;

    private static final Set<Integer> HINTS = Set.of(tag("fpgm"), tag("prep"), tag("cvt "), tag("hdmx"), tag("LTSH"),
            tag("VDMX"), tag("cvar"));

    private static final int NAME = 0x6E616D65;

    private static final List<String> TRICKY = List.of("cpop", "DFGirl-W6-WIN-BF", "DFGothic-EB", "DFGyoSho-Lt",
            "DFHei", "DFHSGothic-W5", "DFHSMincho-W3", "DFHSMincho-W7", "DFKaiSho-SB", "DFKaiShu", "DFKai-SB", "DFMing",
            "DLC", "HuaTianKaiTi", "HuaTianSongTi", "Ming(for ISO10646)", "MingLiU", "MingMedium", "PMingLiU",
            "MingLi43");

    private static final int ARGS_ARE_WORDS = 0x0001;

    private static final int HAS_SCALE = 0x0008;

    private static final int MORE_COMPONENTS = 0x0020;

    private static final int HAS_XY_SCALE = 0x0040;

    private static final int HAS_TWO_BY_TWO = 0x0080;

    private static final int HAS_INSTRUCTIONS = 0x0100;

    private Unhinted() {}

    public static byte[] strip(byte[] sfnt, String... names) {
        if (sfnt == null) {
            return null;
        }
        for (String name : names) {
            if (tricky(name)) {
                return sfnt;
            }
        }
        try {
            for (String name : names(sfnt)) {
                if (tricky(name)) {
                    return sfnt;
                }
            }
            byte[] out = rebuild(sfnt);
            return out != null && out.length < sfnt.length ? out : sfnt;
        } catch (RuntimeException e) {
            return sfnt;
        }
    }

    public static boolean tricky(String name) {
        if (name == null) {
            return false;
        }
        for (String trick : TRICKY) {
            if (name.contains(trick)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> names(byte[] sfnt) {
        List<String> out = new ArrayList<>();
        if (sfnt.length < 12) {
            return out;
        }
        ByteBuffer in = ByteBuffer.wrap(sfnt);
        int count = in.getShort(4) & 0xFFFF;
        for (int i = 0; i < count && 12 + 16 * i + 16 <= sfnt.length; i++) {
            int at = 12 + 16 * i;
            if (in.getInt(at) != NAME) {
                continue;
            }
            long offset = in.getInt(at + 8) & 0xFFFFFFFFL;
            long length = in.getInt(at + 12) & 0xFFFFFFFFL;
            if (offset + 6 > sfnt.length || offset + length > sfnt.length) {
                return out;
            }
            int table = (int) offset;
            int records = in.getShort(table + 2) & 0xFFFF;
            int strings = table + (in.getShort(table + 4) & 0xFFFF);
            for (int r = 0; r < records && table + 6 + 12 * r + 12 <= offset + length; r++) {
                int rec = table + 6 + 12 * r;
                int platform = in.getShort(rec) & 0xFFFF;
                int id = in.getShort(rec + 6) & 0xFFFF;
                int size = in.getShort(rec + 8) & 0xFFFF;
                int from = strings + (in.getShort(rec + 10) & 0xFFFF);
                if ((id == 1 || id == 4 || id == 6 || id == 16) && from + size <= offset + length) {
                    out.add(new String(sfnt, from, size,
                            platform == 1 ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_16BE));
                }
            }
        }
        return out;
    }

    private static byte[] rebuild(byte[] sfnt) {
        if (sfnt.length < 12) {
            return null;
        }
        ByteBuffer in = ByteBuffer.wrap(sfnt);
        int flavor = in.getInt(0);
        if (flavor != 0x00010000 && flavor != tag("true")) {
            return null;
        }
        int count = in.getShort(4) & 0xFFFF;
        if (count == 0 || 12 + 16L * count > sfnt.length) {
            return null;
        }
        Map<Integer, byte[]> tables = new TreeMap<>(Integer::compareUnsigned);
        for (int i = 0; i < count; i++) {
            int at = 12 + 16 * i;
            int tag = in.getInt(at);
            long offset = in.getInt(at + 8) & 0xFFFFFFFFL;
            long length = in.getInt(at + 12) & 0xFFFFFFFFL;
            if (offset + length > sfnt.length || tables.containsKey(tag)) {
                return null;
            }
            if (!HINTS.contains(tag)) {
                byte[] data = new byte[(int) length];
                System.arraycopy(sfnt, (int) offset, data, 0, (int) length);
                tables.put(tag, data);
            }
        }
        byte[] head = tables.get(HEAD);
        byte[] maxp = tables.get(MAXP);
        if (head == null || head.length < 54) {
            return null;
        }
        if (tables.containsKey(GLYF)) {
            if (maxp == null || maxp.length < 6 || !tables.containsKey(LOCA)) {
                return null;
            }
            int glyphs = ByteBuffer.wrap(maxp).getShort(4) & 0xFFFF;
            if (!glyphs(tables, head, glyphs)) {
                return null;
            }
            if (maxp.length >= 28) {
                ByteBuffer.wrap(maxp).putShort(26, (short) 0);
            }
        }
        return write(flavor, tables, head);
    }

    private static boolean glyphs(Map<Integer, byte[]> tables, byte[] head, int glyphs) {
        byte[] glyf = tables.get(GLYF);
        byte[] loca = tables.get(LOCA);
        ByteBuffer h = ByteBuffer.wrap(head);
        boolean longOffsets = h.getShort(50) != 0;
        if (loca.length < (glyphs + 1) * (longOffsets ? 4 : 2)) {
            return false;
        }
        ByteBuffer l = ByteBuffer.wrap(loca);
        ByteBuffer out = ByteBuffer.allocate(glyf.length + 2 * glyphs);
        int[] offsets = new int[glyphs + 1];
        for (int g = 0; g < glyphs; g++) {
            long start = longOffsets ? l.getInt(4 * g) & 0xFFFFFFFFL : 2L * (l.getShort(2 * g) & 0xFFFF);
            long end = longOffsets ? l.getInt(4 * g + 4) & 0xFFFFFFFFL : 2L * (l.getShort(2 * g + 2) & 0xFFFF);
            offsets[g] = out.position();
            if (end <= start) {
                continue;
            }
            if (end > glyf.length) {
                return false;
            }
            if (!glyph(glyf, (int) start, (int) end, out)) {
                return false;
            }
            if ((out.position() & 1) != 0) {
                out.put((byte) 0);
            }
        }
        offsets[glyphs] = out.position();
        byte[] newGlyf = new byte[out.position()];
        System.arraycopy(out.array(), 0, newGlyf, 0, newGlyf.length);
        boolean shortOffsets = newGlyf.length / 2 <= 0xFFFF;
        ByteBuffer newLoca = ByteBuffer.allocate((glyphs + 1) * (shortOffsets ? 2 : 4));
        for (int offset : offsets) {
            if (shortOffsets) {
                newLoca.putShort((short) (offset / 2));
            } else {
                newLoca.putInt(offset);
            }
        }
        h.putShort(50, (short) (shortOffsets ? 0 : 1));
        tables.put(GLYF, newGlyf);
        tables.put(LOCA, newLoca.array());
        return true;
    }

    private static boolean glyph(byte[] glyf, int start, int end, ByteBuffer out) {
        if (end - start < 10) {
            return false;
        }
        ByteBuffer g = ByteBuffer.wrap(glyf, 0, end);
        int contours = g.getShort(start);
        if (contours >= 0) {
            int lengthAt = start + 10 + 2 * contours;
            if (lengthAt + 2 > end) {
                return false;
            }
            int instructions = g.getShort(lengthAt) & 0xFFFF;
            int rest = lengthAt + 2 + instructions;
            int points = contours == 0 ? 0 : (g.getShort(lengthAt - 2) & 0xFFFF) + 1;
            int last = outlineEnd(glyf, rest, end, points);
            if (last < 0) {
                return false;
            }
            out.put(glyf, start, lengthAt - start);
            out.putShort((short) 0);
            out.put(glyf, rest, last - rest);
            return true;
        }
        int at = start + 10;
        int from = out.position();
        out.put(glyf, start, 10);
        int flags;
        do {
            if (at + 4 > end) {
                return false;
            }
            flags = g.getShort(at) & 0xFFFF;
            int size = 4 + ((flags & ARGS_ARE_WORDS) != 0 ? 4 : 2);
            if ((flags & HAS_SCALE) != 0) {
                size += 2;
            } else if ((flags & HAS_XY_SCALE) != 0) {
                size += 4;
            } else if ((flags & HAS_TWO_BY_TWO) != 0) {
                size += 8;
            }
            if (at + size > end) {
                return false;
            }
            int componentAt = out.position();
            out.put(glyf, at, size);
            out.putShort(componentAt, (short) (flags & ~HAS_INSTRUCTIONS));
            at += size;
        } while ((flags & MORE_COMPONENTS) != 0);
        return out.position() > from;
    }

    private static int outlineEnd(byte[] glyf, int at, int end, int points) {
        int xBytes = 0;
        int yBytes = 0;
        int read = 0;
        while (read < points) {
            if (at >= end) {
                return -1;
            }
            int flag = glyf[at++] & 0xFF;
            int repeat = 1;
            if ((flag & 0x08) != 0) {
                if (at >= end) {
                    return -1;
                }
                repeat += glyf[at++] & 0xFF;
            }
            int x = (flag & 0x02) != 0 ? 1 : (flag & 0x10) != 0 ? 0 : 2;
            int y = (flag & 0x04) != 0 ? 1 : (flag & 0x20) != 0 ? 0 : 2;
            xBytes += x * repeat;
            yBytes += y * repeat;
            read += repeat;
        }
        long last = (long) at + xBytes + yBytes;
        return read == points && last <= end ? (int) last : -1;
    }

    private static byte[] write(int flavor, Map<Integer, byte[]> tables, byte[] head) {
        int count = tables.size();
        long size = 12 + 16L * count;
        for (byte[] t : tables.values()) {
            size += (t.length + 3) & ~3;
        }
        if (size > Integer.MAX_VALUE) {
            return null;
        }
        ByteBuffer.wrap(head).putInt(8, 0);
        int power = Integer.highestOneBit(count);
        ByteBuffer out = ByteBuffer.allocate((int) size);
        out.putInt(flavor).putShort((short) count).putShort((short) (power * 16))
                .putShort((short) Integer.numberOfTrailingZeros(power)).putShort((short) (count * 16 - power * 16));
        int offset = 12 + 16 * count;
        int headAt = -1;
        for (Map.Entry<Integer, byte[]> e : tables.entrySet()) {
            byte[] t = e.getValue();
            out.putInt(e.getKey()).putInt(checksum(t)).putInt(offset).putInt(t.length);
            if (e.getKey() == HEAD) {
                headAt = offset;
            }
            int at = out.position();
            out.position(offset);
            out.put(t);
            out.position(at);
            offset += (t.length + 3) & ~3;
        }
        byte[] font = out.array();
        if (headAt >= 0) {
            ByteBuffer.wrap(font).putInt(headAt + 8, 0xB1B0AFBA - checksum(font));
        }
        return font;
    }

    private static int checksum(byte[] data) {
        int sum = 0;
        for (int i = 0; i < data.length; i += 4) {
            int word = 0;
            for (int j = 0; j < 4; j++) {
                word = word << 8 | (i + j < data.length ? data[i + j] & 0xFF : 0);
            }
            sum += word;
        }
        return sum;
    }

    private static int tag(String name) {
        return name.charAt(0) << 24 | name.charAt(1) << 16 | name.charAt(2) << 8 | name.charAt(3);
    }
}
