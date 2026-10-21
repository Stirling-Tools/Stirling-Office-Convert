package stirling.software.officeconvert.json;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A strict JSON reader with depth and size limits, and the escaping the writers share. Objects read as maps, arrays
 * as lists, numbers as doubles. */
public final class Json {

    public static final int MAX_DEPTH = 128;

    private final Reader in;
    private final long maxChars;
    private long read;
    private int peeked = -2;
    private final StringBuilder buf = new StringBuilder();

    private Json(Reader in, long maxChars) {
        this.in = in;
        this.maxChars = maxChars;
    }

    public static Object parse(Reader in, long maxChars) throws IOException {
        Json j = new Json(in, maxChars);
        Object v = j.value(0);
        j.skipSpace();
        if (j.peek() != -1) {
            throw j.error("Unexpected text after the JSON value");
        }
        return v;
    }

    public static Object parse(String text) throws IOException {
        return parse(new java.io.StringReader(text), Long.MAX_VALUE);
    }

    public static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029 || Character.isSurrogate(c) && !paired(s, i)) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static boolean paired(String s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) {
            return i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
        }
        return i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
    }

    // The shortest text that reads back as the same float, so a document survives a round trip exactly
    public static void number(StringBuilder sb, float v) {
        if (!Float.isFinite(v)) {
            sb.append("null");
        } else if (v == Math.rint(v) && Math.abs(v) < 1e7) {
            sb.append((long) v);
        } else {
            sb.append(Float.toString(v));
        }
    }

    private Object value(int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw error("The JSON is nested deeper than " + MAX_DEPTH + " levels");
        }
        skipSpace();
        int c = peek();
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> word("true", Boolean.TRUE);
            case 'f' -> word("false", Boolean.FALSE);
            case 'n' -> word("null", null);
            case -1 -> throw error("The JSON ends too early");
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) throws IOException {
        next();
        Map<String, Object> map = new LinkedHashMap<>();
        skipSpace();
        if (peek() == '}') {
            next();
            return Map.of();
        }
        while (true) {
            skipSpace();
            if (peek() != '"') {
                throw error("Expected a quoted name");
            }
            String key = string();
            skipSpace();
            expect(':');
            map.put(key, value(depth + 1));
            skipSpace();
            int c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw error("Expected , or }");
            }
        }
    }

    private List<Object> array(int depth) throws IOException {
        next();
        List<Object> list = new ArrayList<>();
        skipSpace();
        if (peek() == ']') {
            next();
            return List.of();
        }
        while (true) {
            list.add(value(depth + 1));
            skipSpace();
            int c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw error("Expected , or ]");
            }
        }
    }

    private String string() throws IOException {
        next();
        buf.setLength(0);
        while (true) {
            int c = next();
            if (c == '"') {
                return buf.toString();
            }
            if (c == -1 || c < 0x20) {
                throw error("Unterminated string");
            }
            if (c != '\\') {
                buf.append((char) c);
                continue;
            }
            int e = next();
            switch (e) {
                case '"', '\\', '/' -> buf.append((char) e);
                case 'b' -> buf.append('\b');
                case 'f' -> buf.append('\f');
                case 'n' -> buf.append('\n');
                case 'r' -> buf.append('\r');
                case 't' -> buf.append('\t');
                case 'u' -> {
                    int v = 0;
                    for (int i = 0; i < 4; i++) {
                        int d = Character.digit(next(), 16);
                        if (d < 0) {
                            throw error("Bad \\u escape");
                        }
                        v = v * 16 + d;
                    }
                    buf.append((char) v);
                }
                default -> throw error("Bad escape");
            }
        }
    }

    private Double number() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = peek();
            if (c >= '0' && c <= '9' || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                sb.append((char) next());
                if (sb.length() > 64) {
                    throw error("Number too long");
                }
            } else {
                break;
            }
        }
        if (sb.isEmpty()) {
            throw error("Unexpected character");
        }
        try {
            double d = Double.parseDouble(sb.toString());
            if (!Double.isFinite(d)) {
                throw error("Number out of range");
            }
            return d;
        } catch (NumberFormatException e) {
            throw error("Bad number " + sb);
        }
    }

    private Object word(String w, Object v) throws IOException {
        for (int i = 0; i < w.length(); i++) {
            if (next() != w.charAt(i)) {
                throw error("Unexpected word");
            }
        }
        return v;
    }

    private void expect(char c) throws IOException {
        if (next() != c) {
            throw error("Expected " + c);
        }
    }

    private void skipSpace() throws IOException {
        while (true) {
            int c = peek();
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0xFEFF && read == 0) {
                next();
            } else {
                return;
            }
        }
    }

    private int peek() throws IOException {
        if (peeked == -2) {
            peeked = in.read();
        }
        return peeked;
    }

    private int next() throws IOException {
        int c = peek();
        peeked = -2;
        if (c != -1 && ++read > maxChars) {
            throw new IOException("The JSON is larger than the limit of " + maxChars + " characters");
        }
        return c;
    }

    private IOException error(String message) {
        return new IOException("Not valid JSON at character " + read + ": " + message);
    }
}
