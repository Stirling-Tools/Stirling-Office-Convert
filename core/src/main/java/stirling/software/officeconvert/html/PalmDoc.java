package stirling.software.officeconvert.html;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

final class PalmDoc {

    private static final int WINDOW = 2047;

    private static final int LONGEST = 10;

    private static final int CANDIDATES = 64;

    private PalmDoc() {}

    static byte[] compress(byte[] in, int from, int to) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(to - from);
        int[] head = new int[1 << 12];
        int[] previous = new int[to - from];
        Arrays.fill(head, -1);
        int i = from;
        while (i < to) {
            int bestLength = 0;
            int bestDistance = 0;
            if (i + 3 <= to) {
                int h = hash(in, i);
                int tries = 0;
                for (int j = head[h]; j >= 0 && i - (j + from) <= WINDOW && tries < CANDIDATES;
                        j = previous[j], tries++) {
                    int at = j + from;
                    int n = 0;
                    while (n < LONGEST && i + n < to && at + n < i && in[at + n] == in[i + n]) {
                        n++;
                    }
                    if (n > bestLength) {
                        bestLength = n;
                        bestDistance = i - at;
                        if (n == LONGEST) {
                            break;
                        }
                    }
                }
            }
            if (bestLength >= 3) {
                int code = 0x8000 | bestDistance << 3 | (bestLength - 3);
                out.write(code >>> 8);
                out.write(code & 0xFF);
                for (int k = 0; k < bestLength; k++) {
                    remember(in, i + k, from, to, head, previous);
                }
                i += bestLength;
                continue;
            }
            int c = in[i] & 0xFF;
            int next = i + 1 < to ? in[i + 1] & 0xFF : -1;
            if (c == ' ' && next >= 0x40 && next <= 0x7F) {
                out.write(next ^ 0x80);
                remember(in, i, from, to, head, previous);
                remember(in, i + 1, from, to, head, previous);
                i += 2;
            } else if (c == 0 || c >= 0x09 && c <= 0x7F) {
                out.write(c);
                remember(in, i, from, to, head, previous);
                i++;
            } else {
                int n = 1;
                while (n < 8 && i + n < to && binary(in[i + n] & 0xFF)) {
                    n++;
                }
                out.write(n);
                out.write(in, i, n);
                for (int k = 0; k < n; k++) {
                    remember(in, i + k, from, to, head, previous);
                }
                i += n;
            }
        }
        return out.toByteArray();
    }

    private static boolean binary(int c) {
        return c >= 1 && c <= 8 || c >= 0x80;
    }

    private static int hash(byte[] in, int i) {
        return ((in[i] & 0xFF) * 31 * 31 + (in[i + 1] & 0xFF) * 31 + (in[i + 2] & 0xFF)) & 0xFFF;
    }

    private static void remember(byte[] in, int i, int from, int to, int[] head, int[] previous) {
        if (i + 3 > to) {
            return;
        }
        int h = hash(in, i);
        previous[i - from] = head[h];
        head[h] = i - from;
    }
}
