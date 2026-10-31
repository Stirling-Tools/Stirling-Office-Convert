package stirling.software.officeconvert.pdfa;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

final class SfntTables {

    private SfntTables() {}

    static byte[] cmapTable(TreeMap<Integer, Integer> cmap) {
        List<Cmaps.Subtable> subtables = new ArrayList<>();
        TreeMap<Integer, Integer> bmp = new TreeMap<>(cmap.headMap(0xFFFF));
        subtables.add(new Cmaps.Subtable(3, 1, bmp));
        if (!cmap.isEmpty() && cmap.lastKey() > 0xFFFF || !Cmaps.fitsFormat4(bmp)) {
            subtables.add(new Cmaps.Subtable(3, 10, cmap));
        }
        return Cmaps.table(subtables);
    }

    static byte[] head(TrueTypeWriter.Metrics m, int style) {
        ByteBuffer b = ByteBuffer.allocate(54);
        b.putInt(0, 0x00010000);
        b.putInt(4, 0x00010000);
        b.putInt(12, 0x5F0F3CF5);
        b.putShort(16, (short) 0x000B);
        b.putShort(18, (short) WebFonts.UNITS_PER_EM);
        b.putShort(36, (short) m.xMin());
        b.putShort(38, (short) m.yMin());
        b.putShort(40, (short) m.xMax());
        b.putShort(42, (short) m.yMax());
        b.putShort(44, (short) style);
        b.putShort(46, (short) 8);
        b.putShort(48, (short) 2);
        b.putShort(50, (short) 1);
        return b.array();
    }

    static byte[] hhea(TrueTypeWriter.Metrics m, int ascent, int descent, int glyphs) {
        ByteBuffer b = ByteBuffer.allocate(36);
        b.putInt(0, 0x00010000);
        b.putShort(4, (short) ascent);
        b.putShort(6, (short) descent);
        b.putShort(10, (short) m.maxAdvance());
        b.putShort(12, (short) m.minLsb());
        b.putShort(14, (short) m.minRsb());
        b.putShort(16, (short) m.maxExtent());
        b.putShort(18, (short) 1);
        b.putShort(34, (short) glyphs);
        return b.array();
    }

    static byte[] os2(TrueTypeWriter.Metrics m, int ascent, int descent, TreeMap<Integer, Integer> cmap,
            int style, int upem, int fsType) {
        boolean bold = (style & 1) != 0;
        boolean italic = (style & 2) != 0;
        ByteBuffer b = ByteBuffer.allocate(96);
        b.putShort(0, (short) 4);
        b.putShort(2, (short) m.averageAdvance());
        b.putShort(4, (short) (bold ? 700 : 400));
        b.putShort(6, (short) 5);
        b.putShort(8, (short) fsType);
        b.putShort(10, (short) (650 * upem / 1000));
        b.putShort(12, (short) (600 * upem / 1000));
        b.putShort(16, (short) (75 * upem / 1000));
        b.putShort(18, (short) (650 * upem / 1000));
        b.putShort(20, (short) (600 * upem / 1000));
        b.putShort(24, (short) (350 * upem / 1000));
        b.putShort(26, (short) (50 * upem / 1000));
        b.putShort(28, (short) (300 * upem / 1000));
        b.put(58, "SPDF".getBytes(StandardCharsets.US_ASCII));
        b.putShort(62, (short) ((bold ? 0x20 : 0) | (italic ? 0x01 : 0) | (bold || italic ? 0 : 0x40)));
        b.putShort(64, (short) (cmap.isEmpty() ? 0x20 : Math.min(0xFFFF, cmap.firstKey())));
        b.putShort(66, (short) (cmap.isEmpty() ? 0x7E : Math.min(0xFFFF, cmap.lastKey())));
        b.putShort(68, (short) ascent);
        b.putShort(70, (short) descent);
        b.putShort(74, (short) Math.max(0, m.yMax()));
        b.putShort(76, (short) Math.max(0, -m.yMin()));
        b.putInt(78, 1);
        b.putShort(86, (short) (500 * upem / 1000));
        b.putShort(88, (short) (700 * upem / 1000));
        b.putShort(92, (short) 32);
        return b.array();
    }

    static byte[] post(float italicAngle) {
        ByteBuffer b = ByteBuffer.allocate(32);
        b.putInt(0, 0x00030000);
        b.putInt(4, Math.round(italicAngle * 65_536f));
        b.putShort(8, (short) -100);
        b.putShort(10, (short) 50);
        return b.array();
    }
}
