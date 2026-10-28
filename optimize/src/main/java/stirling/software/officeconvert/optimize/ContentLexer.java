package stirling.software.officeconvert.optimize;

final class ContentLexer {

    enum Kind {
        NUMBER, NAME, STRING, OPEN_ARRAY, CLOSE_ARRAY, OPEN_DICT, CLOSE_DICT, KEYWORD, OPERATOR
    }

    static final class Malformed extends Exception {
        Malformed(String message) {
            super(message, null, false, false);
        }
    }

    private final byte[] in;

    private int pos;

    private Kind kind;

    private int start;

    private int end;

    private final Bytes string = new Bytes(64);

    private boolean commentAtEnd;

    ContentLexer(byte[] in) {
        this.in = in;
    }

    boolean next() throws Malformed {
        while (pos < in.length) {
            int c = in[pos] & 0xFF;
            if (Lexical.whitespace(c)) {
                pos++;
            } else if (c == '%') {
                while (pos < in.length && in[pos] != '\n' && in[pos] != '\r') {
                    pos++;
                }
                if (pos >= in.length) {
                    commentAtEnd = true;
                }
            } else {
                break;
            }
        }
        if (pos >= in.length) {
            return false;
        }
        start = pos;
        int c = in[pos] & 0xFF;
        switch (c) {
            case '(' -> literal();
            case '<' -> {
                if (pos + 1 < in.length && in[pos + 1] == '<') {
                    pos += 2;
                    kind = Kind.OPEN_DICT;
                } else {
                    hex();
                }
            }
            case '>' -> {
                if (pos + 1 < in.length && in[pos + 1] == '>') {
                    pos += 2;
                    kind = Kind.CLOSE_DICT;
                } else {
                    throw new Malformed("a lone >");
                }
            }
            case '[' -> {
                pos++;
                kind = Kind.OPEN_ARRAY;
            }
            case ']' -> {
                pos++;
                kind = Kind.CLOSE_ARRAY;
            }
            case '/' -> {
                pos++;
                while (pos < in.length && Lexical.regular(in[pos] & 0xFF)) {
                    pos++;
                }
                kind = Kind.NAME;
            }
            case ')', '{', '}' -> throw new Malformed("an unexpected " + (char) c);
            default -> word();
        }
        end = pos;
        return true;
    }

    private void word() throws Malformed {
        while (pos < in.length && Lexical.regular(in[pos] & 0xFF)) {
            pos++;
        }
        int c = in[start] & 0xFF;
        if (c >= '0' && c <= '9' || c == '+' || c == '-' || c == '.') {
            if (!Lexical.isPlainNumber(in, start, pos)) {
                throw new Malformed("a malformed number");
            }
            kind = Kind.NUMBER;
            return;
        }
        end = pos;
        kind = Kind.OPERATOR;
        if (operator("true") || operator("false") || operator("null")) {
            kind = Kind.KEYWORD;
        }
    }

    private void literal() throws Malformed {
        string.reset();
        pos++;
        int depth = 1;
        while (pos < in.length) {
            int c = in[pos++] & 0xFF;
            if (c == '\\') {
                if (pos >= in.length) {
                    break;
                }
                int e = in[pos++] & 0xFF;
                switch (e) {
                    case 'n' -> string.write('\n');
                    case 'r' -> string.write('\r');
                    case 't' -> string.write('\t');
                    case 'b' -> string.write('\b');
                    case 'f' -> string.write('\f');
                    case '\r' -> {
                        if (pos < in.length && in[pos] == '\n') {
                            pos++;
                        }
                    }
                    case '\n' -> {
                    }
                    default -> {
                        if (e >= '0' && e <= '7') {
                            int v = e - '0';
                            for (int k = 0; k < 2 && pos < in.length && in[pos] >= '0' && in[pos] <= '7'; k++) {
                                v = v * 8 + in[pos++] - '0';
                            }
                            string.write(v & 0xFF);
                        } else {
                            string.write(e);
                        }
                    }
                }
            } else if (c == '(') {
                depth++;
                string.write(c);
            } else if (c == ')') {
                if (--depth == 0) {
                    kind = Kind.STRING;
                    return;
                }
                string.write(c);
            } else if (c == '\r') {
                string.write('\n');
                if (pos < in.length && in[pos] == '\n') {
                    pos++;
                }
            } else {
                string.write(c);
            }
        }
        throw new Malformed("an unterminated string");
    }

    private void hex() throws Malformed {
        string.reset();
        pos++;
        int high = -1;
        while (pos < in.length) {
            int c = in[pos++] & 0xFF;
            if (c == '>') {
                if (high >= 0) {
                    string.write(high << 4);
                }
                kind = Kind.STRING;
                return;
            }
            if (Lexical.whitespace(c)) {
                continue;
            }
            int v = Character.digit(c, 16);
            if (v < 0) {
                throw new Malformed("a hex string with a character that is not hex");
            }
            if (high < 0) {
                high = v;
            } else {
                string.write(high << 4 | v);
                high = -1;
            }
        }
        throw new Malformed("an unterminated hex string");
    }

    Kind kind() {
        return kind;
    }

    int start() {
        return start;
    }

    int end() {
        return end;
    }

    byte[] input() {
        return in;
    }

    String text() {
        return new String(in, start, pos - start, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    byte[] stringBytes() {
        return string.toByteArray();
    }

    byte[] nameBytes() throws Malformed {
        Bytes b = new Bytes(end - start);
        for (int i = start + 1; i < end; i++) {
            int c = in[i] & 0xFF;
            if (c == '#' && i + 2 < end && Character.digit(in[i + 1], 16) >= 0
                    && Character.digit(in[i + 2], 16) >= 0) {
                b.write(Character.digit(in[i + 1], 16) << 4 | Character.digit(in[i + 2], 16));
                i += 2;
            } else {
                b.write(c);
            }
        }
        return b.toByteArray();
    }

    boolean commentAtEnd() {
        return commentAtEnd;
    }

    boolean operator(String op) {
        int len = end - start;
        if (kind != Kind.OPERATOR || len != op.length()) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (in[start + i] != op.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
