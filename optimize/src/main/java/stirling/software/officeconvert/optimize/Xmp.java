package stirling.software.officeconvert.optimize;

import java.nio.charset.StandardCharsets;

final class Xmp {

    private static final byte[] END = "<?xpacket end=".getBytes(StandardCharsets.US_ASCII);

    private static final int MIN_PADDING = 16;

    private Xmp() {}

    static byte[] withoutPadding(byte[] xmp) {
        int end = lastIndexOf(xmp, END);
        if (end < 0) {
            return xmp;
        }
        int start = end;
        while (start > 0 && whitespace(xmp[start - 1])) {
            start--;
        }
        if (end - start < MIN_PADDING || start == 0) {
            return xmp;
        }
        byte[] out = new byte[xmp.length - (end - start) + 1];
        System.arraycopy(xmp, 0, out, 0, start);
        out[start] = '\n';
        System.arraycopy(xmp, end, out, start + 1, xmp.length - end);
        return out;
    }

    private static boolean whitespace(byte b) {
        return b == ' ' || b == '\n' || b == '\r' || b == '\t';
    }

    private static int lastIndexOf(byte[] data, byte[] needle) {
        for (int i = data.length - needle.length; i >= 0; i--) {
            int k = 0;
            while (k < needle.length && data[i + k] == needle[k]) {
                k++;
            }
            if (k == needle.length) {
                return i;
            }
        }
        return -1;
    }
}
