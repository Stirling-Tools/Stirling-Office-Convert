package stirling.software.officeconvert.topdf.rtf;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class RtfTokenizer {

    static final int EOF = 0;
    static final int OPEN = 1;
    static final int CLOSE = 2;
    static final int WORD = 3;
    static final int SYMBOL = 4;
    static final int TEXT = 5;
    static final int HEX = 6;

    private static final int MAX_NAME = 32;

    private static final int MAX_DIGITS = 10;

    private final InputStream in;

    private final byte[] buf = new byte[1 << 16];

    private int pos;

    private int len;

    private static final int SKIP = -1;

    private final int[] pushed = new int[2];

    private int pushedCount;

    String word;

    int param;

    boolean hasParam;

    char symbol;

    int value;

    private final char[] name = new char[MAX_NAME];

    RtfTokenizer(InputStream in) {
        this.in = in;
    }

    int next() throws IOException {
        while (true) {
            int c = read();
            switch (c) {
                case -1 -> {
                    return EOF;
                }
                case '{' -> {
                    return OPEN;
                }
                case '}' -> {
                    return CLOSE;
                }
                case '\\' -> {
                    int t = control();
                    if (t != SKIP) {
                        return t;
                    }
                }
                case '\r', '\n', 0 -> {
                }
                default -> {
                    value = c;
                    return TEXT;
                }
            }
        }
    }

    private int control() throws IOException {
        int c = read();
        if (c == -1) {
            return EOF;
        }
        if (isLetter(c)) {
            int n = 0;
            while (c != -1 && isLetter(c)) {
                if (n < MAX_NAME) {
                    name[n++] = (char) c;
                }
                c = read();
            }
            word = new String(name, 0, n);
            hasParam = false;
            param = 0;
            boolean negative = false;
            if (c == '-') {
                int d = read();
                if (d >= '0' && d <= '9') {
                    negative = true;
                    c = d;
                } else {
                    unread(d);
                    unread(c);
                    c = SKIP;
                }
            }
            if (c >= '0' && c <= '9') {
                long v = 0;
                int digits = 0;
                while (c >= '0' && c <= '9') {
                    if (digits++ < MAX_DIGITS) {
                        v = v * 10 + (c - '0');
                    }
                    c = read();
                }
                v = negative ? -v : v;
                param = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
                hasParam = true;
            }
            if (c != ' ' && c != -1) {
                unread(c);
            }
            return WORD;
        }
        if (c == '\'') {
            int hc = read();
            int h = hex(hc);
            if (h < 0) {
                unread(hc);
                return SKIP;
            }
            int lc = read();
            int l = hex(lc);
            if (l < 0) {
                unread(lc);
                value = h;
                return HEX;
            }
            value = h << 4 | l;
            return HEX;
        }
        if (c == '\r' || c == '\n') {
            word = "par";
            hasParam = false;
            param = 0;
            return WORD;
        }
        symbol = (char) c;
        return SYMBOL;
    }

    void skipBinary(long n) throws IOException {
        long left = n;
        while (pushedCount > 0 && left > 0) {
            pushedCount--;
            left--;
        }
        while (left > 0) {
            if (pos >= len && !fill()) {
                return;
            }
            int k = (int) Math.min(left, len - pos);
            pos += k;
            left -= k;
        }
    }

    long copyBinary(long n, OutputStream out, long max) throws IOException {
        long left = n;
        long copied = 0;
        while (pushedCount > 0 && left > 0) {
            int c = pushed[--pushedCount];
            if (copied < max) {
                out.write(c);
                copied++;
            }
            left--;
        }
        while (left > 0) {
            if (pos >= len && !fill()) {
                return copied;
            }
            int k = (int) Math.min(left, len - pos);
            int keep = (int) Math.max(0, Math.min(k, max - copied));
            if (keep > 0) {
                out.write(buf, pos, keep);
                copied += keep;
            }
            pos += k;
            left -= k;
        }
        return copied;
    }

    private static boolean isLetter(int c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    private static int hex(int c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private int read() throws IOException {
        if (pushedCount > 0) {
            return pushed[--pushedCount];
        }
        if (pos >= len && !fill()) {
            return -1;
        }
        return buf[pos++] & 0xFF;
    }

    private void unread(int c) {
        if (c >= 0 && pushedCount < pushed.length) {
            pushed[pushedCount++] = c;
        }
    }

    private boolean fill() throws IOException {
        int n = in.read(buf, 0, buf.length);
        if (n <= 0) {
            len = 0;
            pos = 0;
            return false;
        }
        len = n;
        pos = 0;
        return true;
    }
}
