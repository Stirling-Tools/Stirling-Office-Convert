package stirling.software.officeconvert.topdf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class PalmDatabase {

    static final int HEADER = 78;

    static final String DAMAGED = "The eBook is damaged: its record table does not fit the file";

    private final byte[] data;
    private final int[] offsets;

    PalmDatabase(byte[] data) throws IOException {
        this.data = data;
        if (data.length < HEADER) {
            throw new IOException(DAMAGED);
        }
        int n = u16(data, 76);
        if (n == 0 || HEADER + 8L * n > data.length) {
            throw new IOException(DAMAGED);
        }
        offsets = new int[n + 1];
        long previous = HEADER + 8L * n;
        for (int i = 0; i < n; i++) {
            long off = u32(data, HEADER + 8 * i);
            if (off < previous || off > data.length) {
                throw new IOException(DAMAGED);
            }
            offsets[i] = (int) off;
            previous = off;
        }
        offsets[n] = data.length;
    }

    static String type(byte[] head) {
        return head.length < 68 ? "" : new String(head, 60, 8, StandardCharsets.ISO_8859_1);
    }

    String name() {
        int end = 0;
        while (end < 32 && data[end] != 0) {
            end++;
        }
        return new String(data, 0, end, StandardCharsets.ISO_8859_1).replace('_', ' ').strip();
    }

    String type() {
        return type(data);
    }

    int count() {
        return offsets.length - 1;
    }

    boolean has(int index) {
        return index >= 0 && index < count();
    }

    byte[] record(int index) throws IOException {
        if (!has(index)) {
            throw new IOException("The eBook is damaged: it refers to record " + index + " of " + count());
        }
        return Arrays.copyOfRange(data, offsets[index], offsets[index + 1]);
    }

    boolean startsWith(int index, String magic) {
        if (!has(index) || offsets[index + 1] - offsets[index] < magic.length()) {
            return false;
        }
        for (int i = 0; i < magic.length(); i++) {
            if ((data[offsets[index] + i] & 0xFF) != magic.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    boolean picture(int index) {
        return startsWith(index, "ÿØ") || startsWith(index, "\u0089PNG") || startsWith(index, "GIF8")
                || startsWith(index, "BM");
    }

    static int u16(byte[] b, int i) {
        return (b[i] & 0xFF) << 8 | (b[i + 1] & 0xFF);
    }

    static long u32(byte[] b, int i) {
        return (long) (b[i] & 0xFF) << 24 | (b[i + 1] & 0xFF) << 16 | (b[i + 2] & 0xFF) << 8 | (b[i + 3] & 0xFF);
    }

    static int index(byte[] b, int i) {
        if (i + 4 > b.length) {
            return -1;
        }
        long v = u32(b, i);
        return v > Integer.MAX_VALUE ? -1 : (int) v;
    }
}
