package stirling.software.officeconvert.topdf.text;

import java.io.IOException;
import java.io.Reader;

final class TextScanner {

    static final int END = -1;

    static final int LINE = -2;

    static final int PAGE = -3;

    static final int TAB = -4;

    private final Reader in;

    private final char[] buf = new char[1 << 16];

    private int pos;

    private int len;

    TextScanner(Reader in) {
        this.in = in;
    }

    int next() throws IOException {
        while (true) {
            int c = read();
            if (c < 0) {
                return END;
            }
            switch (c) {
                case '\n' -> {
                    return LINE;
                }
                case '\r' -> {
                    if (peek() == '\n') {
                        pos++;
                    }
                    return LINE;
                }
                case '\f' -> {
                    return PAGE;
                }
                case '\t' -> {
                    return TAB;
                }
                default -> {
                }
            }
            if (Character.isHighSurrogate((char) c)) {
                int low = peek();
                if (low >= 0 && Character.isLowSurrogate((char) low)) {
                    pos++;
                    int cp = Character.toCodePoint((char) c, (char) low);
                    if (visible(cp)) {
                        return cp;
                    }
                }
                continue;
            }
            if (visible(c)) {
                return c;
            }
        }
    }

    static boolean visible(int cp) {
        if (cp < 0x20 || cp >= 0x7F && cp <= 0x9F) {
            return false;
        }
        if (cp >= 0xD800 && cp <= 0xDFFF) {
            return false;
        }
        return cp != 0xFFFE && cp != 0xFFFF && (cp & 0xFFFE) != 0xFFFE;
    }

    private int read() throws IOException {
        if (pos >= len && !fill()) {
            return -1;
        }
        return buf[pos++];
    }

    private int peek() throws IOException {
        if (pos >= len && !fill()) {
            return -1;
        }
        return buf[pos];
    }

    private boolean fill() throws IOException {
        int n = in.read(buf, 0, buf.length);
        while (n == 0) {
            n = in.read(buf, 0, buf.length);
        }
        pos = 0;
        len = Math.max(0, n);
        return n > 0;
    }
}
