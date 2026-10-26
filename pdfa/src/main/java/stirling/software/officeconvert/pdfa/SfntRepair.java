package stirling.software.officeconvert.pdfa;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.TreeMap;

final class SfntRepair {

    private SfntRepair() {}

    static final java.util.Set<String> CORE = java.util.Set.of("head", "hhea", "hmtx", "maxp", "loca", "glyf", "cvt ",
            "fpgm", "prep", "gasp");

    static byte[] repair(byte[] data) {
        return repair(data, false, null);
    }

    static byte[] repair(byte[] data, boolean dropCmap, java.util.Set<String> keep) {
        if (data == null || data.length < 12) {
            return null;
        }
        ByteBuffer in = ByteBuffer.wrap(data);
        int count = Math.min(in.getShort(4) & 0xFFFF, (data.length - 12) / 16);
        int directoryEnd = 12 + 16 * count;
        int shift = shift(data, in, count);
        TreeMap<String, byte[]> tables = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            int at = 12 + 16 * i;
            String tag = new String(data, at, 4, StandardCharsets.ISO_8859_1);
            long offset = (in.getInt(at + 8) & 0xFFFFFFFFL) + shift;
            long length = in.getInt(at + 12) & 0xFFFFFFFFL;
            if (offset < directoryEnd || offset >= data.length) {
                continue;
            }
            length = Math.min(length, data.length - offset);
            if (keep != null && !keep.contains(tag)) {
                continue;
            }
            tables.putIfAbsent(tag, Arrays.copyOfRange(data, (int) offset, (int) (offset + length)));
        }
        byte[] glyf = tables.get("glyf");
        byte[] loca = tables.get("loca");
        if (glyf == null || loca == null || loca.length < 4) {
            return null;
        }
        byte[] maxp = tables.get("maxp");
        int declared = maxp != null && maxp.length >= 6 ? ByteBuffer.wrap(maxp).getShort(4) & 0xFFFF : -1;
        byte[] head = tables.get("head");
        boolean longOffsets;
        int format = head != null && head.length >= 54 ? ByteBuffer.wrap(head).getShort(50) : -1;
        if (format == 0 || format == 1) {
            longOffsets = format == 1;
        } else {
            longOffsets = declared > 0 ? loca.length >= 4 * (declared + 1) : plausibleLong(loca, glyf.length);
        }
        int entries = longOffsets ? loca.length / 4 : loca.length / 2;
        int glyphs = entries - 1;
        if (declared > 0) {
            glyphs = Math.min(glyphs, declared);
        }
        if (glyphs < 1) {
            return null;
        }
        if (glyphs == 1) {
            int step = longOffsets ? 4 : 2;
            byte[] longer = Arrays.copyOf(loca, 3 * step);
            System.arraycopy(loca, step, longer, 2 * step, step);
            tables.put("loca", longer);
            glyphs = 2;
            declared = -1;
        }
        if (head == null || head.length < 54) {
            tables.put("head", head(longOffsets));
        } else if (format != 0 && format != 1) {
            byte[] h = head.clone();
            ByteBuffer.wrap(h).putShort(50, (short) (longOffsets ? 1 : 0));
            tables.put("head", h);
        }
        int upem = ByteBuffer.wrap(tables.get("head")).getShort(18) & 0xFFFF;
        if (maxp == null || maxp.length < 32 || declared != glyphs) {
            byte[] m = new byte[32];
            if (maxp != null) {
                System.arraycopy(maxp, 0, m, 0, Math.min(32, maxp.length));
            }
            ByteBuffer b = ByteBuffer.wrap(m);
            b.putInt(0, 0x00010000);
            b.putShort(4, (short) glyphs);
            tables.put("maxp", m);
        }
        byte[] hmtx = tables.get("hmtx");
        int hmtxLength = hmtx == null ? 0 : hmtx.length;
        byte[] hhea = tables.get("hhea");
        int declaredMetrics;
        if (hhea == null || hhea.length < 36) {
            declaredMetrics = Math.max(1, Math.min(glyphs, (hmtxLength - 2 * glyphs) / 2));
            byte[] h = new byte[36];
            ByteBuffer b = ByteBuffer.wrap(h);
            b.putInt(0, 0x00010000);
            b.putShort(4, (short) (upem * 4 / 5));
            b.putShort(6, (short) (-upem / 5));
            b.putShort(10, (short) upem);
            b.putShort(32, (short) 0);
            hhea = h;
        } else {
            hhea = hhea.clone();
            declaredMetrics = ByteBuffer.wrap(hhea).getShort(34) & 0xFFFF;
        }
        int metrics = Math.max(1, Math.min(Math.min(glyphs, declaredMetrics), hmtxLength / 4));
        if (hmtxLength < 4) {
            hmtx = new byte[4];
            ByteBuffer.wrap(hmtx).putShort(0, (short) (upem / 2));
            metrics = 1;
        }
        ByteBuffer.wrap(hhea).putShort(34, (short) metrics);
        int needed = 4 * metrics + 2 * (glyphs - metrics);
        if (hmtx.length < needed) {
            hmtx = Arrays.copyOf(hmtx, needed);
        }
        tables.put("hhea", hhea);
        tables.put("hmtx", hmtx);
        if (dropCmap || !cmapReadable(tables.get("cmap"))) {
            tables.remove("cmap");
        }
        return Sfnt.write(tables);
    }

    private static int shift(byte[] data, ByteBuffer in, int count) {
        for (int i = 0; i < count; i++) {
            int at = 12 + 16 * i;
            if (!"head".equals(new String(data, at, 4, StandardCharsets.ISO_8859_1))) {
                continue;
            }
            long offset = in.getInt(at + 8) & 0xFFFFFFFFL;
            for (int delta : new int[] {0, 1, -1, 2, -2, 3, -3}) {
                long magic = offset + delta + 12;
                if (magic >= 0 && magic + 4 <= data.length && in.getInt((int) magic) == 0x5F0F3CF5) {
                    return delta;
                }
            }
        }
        return 0;
    }

    private static boolean plausibleLong(byte[] loca, int glyfLength) {
        ByteBuffer b = ByteBuffer.wrap(loca);
        long previous = 0;
        for (int i = 0; i + 4 <= loca.length; i += 4) {
            long v = b.getInt(i) & 0xFFFFFFFFL;
            if (v < previous || v > glyfLength) {
                return false;
            }
            previous = v;
        }
        return true;
    }

    private static boolean cmapReadable(byte[] cmap) {
        if (cmap == null || cmap.length < 4) {
            return false;
        }
        ByteBuffer b = ByteBuffer.wrap(cmap);
        int n = b.getShort(2) & 0xFFFF;
        if (4 + 8 * n > cmap.length) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            long offset = b.getInt(4 + 8 * i + 4) & 0xFFFFFFFFL;
            if (offset + 4 > cmap.length) {
                return false;
            }
            int format = b.getShort((int) offset) & 0xFFFF;
            long length = format >= 8 ? (offset + 8 <= cmap.length ? b.getInt((int) offset + 4) & 0xFFFFFFFFL : -1)
                    : b.getShort((int) offset + 2) & 0xFFFF;
            if (length < 0 || offset + length > cmap.length) {
                return false;
            }
        }
        return true;
    }

    private static byte[] head(boolean longOffsets) {
        ByteBuffer b = ByteBuffer.allocate(54);
        b.putInt(0, 0x00010000);
        b.putInt(4, 0x00010000);
        b.putInt(12, 0x5F0F3CF5);
        b.putShort(16, (short) 0x000B);
        b.putShort(18, (short) 1000);
        b.putShort(46, (short) 8);
        b.putShort(48, (short) 2);
        b.putShort(50, (short) (longOffsets ? 1 : 0));
        return b.array();
    }
}
