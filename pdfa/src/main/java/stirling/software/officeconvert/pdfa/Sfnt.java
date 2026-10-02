package stirling.software.officeconvert.pdfa;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

final class Sfnt {

    private Sfnt() {}

    static byte[] write(TreeMap<String, byte[]> tables) {
        int n = tables.size();
        int headerSize = 12 + 16 * n;
        int total = headerSize;
        for (byte[] t : tables.values()) {
            total += pad(t.length);
        }
        ByteBuffer out = ByteBuffer.allocate(total);
        int entrySelector = 31 - Integer.numberOfLeadingZeros(n);
        int searchRange = (1 << entrySelector) * 16;
        out.putInt(0x00010000);
        out.putShort((short) n);
        out.putShort((short) searchRange);
        out.putShort((short) entrySelector);
        out.putShort((short) (n * 16 - searchRange));
        int offset = headerSize;
        int headOffset = -1;
        for (Map.Entry<String, byte[]> e : tables.entrySet()) {
            byte[] data = e.getValue();
            out.put(e.getKey().getBytes(StandardCharsets.US_ASCII));
            out.putInt((int) checksum(data));
            out.putInt(offset);
            out.putInt(data.length);
            if ("head".equals(e.getKey())) {
                headOffset = offset;
            }
            offset += pad(data.length);
        }
        for (byte[] data : tables.values()) {
            out.put(data);
            for (int i = data.length; i < pad(data.length); i++) {
                out.put((byte) 0);
            }
        }
        byte[] bytes = out.array();
        if (headOffset >= 0) {
            long adjust = (0xB1B0AFBAL - checksum(bytes)) & 0xFFFFFFFFL;
            ByteBuffer.wrap(bytes).putInt(headOffset + 8, (int) adjust);
        }
        return bytes;
    }

    static int pad(int length) {
        return (length + 3) & ~3;
    }

    static long checksum(byte[] data) {
        long sum = 0;
        for (int i = 0; i < data.length; i += 4) {
            long v = 0;
            for (int k = 0; k < 4; k++) {
                v = (v << 8) | (i + k < data.length ? data[i + k] & 0xFF : 0);
            }
            sum = (sum + v) & 0xFFFFFFFFL;
        }
        return sum;
    }
}
