package stirling.software.officeconvert.topdf.ofd;

import java.util.Arrays;

final class OfdSeals {

    static final int MAX_DEPTH = 24;

    static final int MAX_NODES = 100_000;

    private byte[] best;

    private int nodes;

    private OfdSeals() {}

    static byte[] picture(byte[] der) {
        if (der == null || der.length < 8) {
            return null;
        }
        OfdSeals s = new OfdSeals();
        s.walk(der, 0, der.length, 0);
        return s.best;
    }

    private void walk(byte[] b, int from, int to, int depth) {
        int i = from;
        while (i < to && depth <= MAX_DEPTH && nodes++ < MAX_NODES) {
            int tag = b[i] & 0xFF;
            if ((tag & 0x1F) == 0x1F) {
                return;
            }
            if (i + 1 >= to) {
                return;
            }
            int len = b[i + 1] & 0xFF;
            int start = i + 2;
            if (len == 0x80) {
                return;
            }
            if (len > 0x80) {
                int n = len & 0x7F;
                if (n > 4 || start + n > to) {
                    return;
                }
                long l = 0;
                for (int k = 0; k < n; k++) {
                    l = l << 8 | (b[start + k] & 0xFF);
                }
                start += n;
                if (l > to - start) {
                    return;
                }
                len = (int) l;
            }
            if (start + len > to) {
                return;
            }
            boolean constructed = (tag & 0x20) != 0;
            if (constructed) {
                walk(b, start, start + len, depth + 1);
            } else if (tag == 0x04 && picture(b, start, len)) {
                if (best == null || len > best.length) {
                    best = Arrays.copyOfRange(b, start, start + len);
                }
            }
            i = start + len;
        }
    }

    static boolean picture(byte[] b, int at, int len) {
        if (len < 8) {
            return false;
        }
        int a = b[at] & 0xFF;
        int c = b[at + 1] & 0xFF;
        return a == 0x89 && c == 'P' && b[at + 2] == 'N' && b[at + 3] == 'G'
                || a == 0xFF && c == 0xD8
                || a == 'G' && c == 'I' && b[at + 2] == 'F'
                || a == 'B' && c == 'M'
                || a == 'P' && c == 'K' && b[at + 2] == 3 && b[at + 3] == 4;
    }

    static boolean zip(byte[] b) {
        return b != null && b.length > 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4;
    }
}
