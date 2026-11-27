package stirling.software.officeconvert.topdf.mail;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Mime {

    static final int MAX_PARTS = 10_000;

    static final int MAX_DEPTH = 24;

    static final int MAX_HEADER_BYTES = 1 << 20;

    static final int MAX_TOTAL_HEADER_BYTES = 16 << 20;

    static final int MAX_HEADERS = 1_000;

    private Mime() {}

    public static final class Part {
        private final byte[] source;
        private final int from;
        private final int to;
        private final List<String[]> headers;
        private final String type;
        private final Map<String, String> params;
        private final List<Part> children = new ArrayList<>();
        private Part message;
        private byte[] decoded;

        private Part(byte[] source, int from, int to, List<String[]> headers, String defaultType) {
            this.source = source;
            this.from = from;
            this.to = to;
            this.headers = headers;
            String ct = header("Content-Type");
            String t = ct == null ? null : MimeWords.mediaType(ct);
            if (t == null || t.indexOf('/') < 0) {
                t = defaultType;
            }
            this.type = t;
            this.params = ct == null ? Map.of() : MimeWords.params(ct);
        }

        public String type() {
            return type;
        }

        public String param(String name) {
            return params.get(name.toLowerCase(Locale.ROOT));
        }

        public List<Part> children() {
            return children;
        }

        public Part message() {
            return message;
        }

        public String header(String name) {
            for (String[] h : headers) {
                if (h[0].equalsIgnoreCase(name)) {
                    return h[1];
                }
            }
            return null;
        }

        public String decodedHeader(String name, Charset fallback) {
            String raw = header(name);
            return raw == null ? null : MimeWords.decode(raw, fallback);
        }

        public boolean multipart() {
            return type.startsWith("multipart/");
        }

        public String disposition() {
            String d = header("Content-Disposition");
            String t = d == null ? null : MimeWords.mediaType(d);
            return t == null ? "" : t;
        }

        public String fileName() {
            String d = header("Content-Disposition");
            String name = d == null ? null : MimeWords.params(d).get("filename");
            if (name == null || name.isBlank()) {
                name = params.get("name");
            }
            if (name == null || name.isBlank()) {
                return null;
            }
            return name.strip();
        }

        public String contentId() {
            String id = header("Content-ID");
            if (id == null) {
                return null;
            }
            id = id.strip();
            if (id.startsWith("<") && id.endsWith(">") && id.length() > 1) {
                id = id.substring(1, id.length() - 1);
            }
            return id.isBlank() ? null : id.strip();
        }

        public String location() {
            String l = header("Content-Location");
            return l == null || l.isBlank() ? null : MimeWords.decode(l, StandardCharsets.UTF_8).replaceAll("\\s+", "");
        }

        public String charsetName() {
            return params.get("charset");
        }

        public int encodedLength() {
            return to - from;
        }

        public byte[] body() {
            if (decoded == null) {
                String cte = header("Content-Transfer-Encoding");
                String e = cte == null ? "" : cte.strip().toLowerCase(Locale.ROOT);
                if (e.equals("base64")) {
                    decoded = base64(source, from, to);
                } else if (e.equals("quoted-printable")) {
                    decoded = quotedPrintable(source, from, to);
                } else {
                    byte[] b = new byte[to - from];
                    System.arraycopy(source, from, b, 0, b.length);
                    decoded = b;
                }
            }
            return decoded;
        }

        public String text() {
            byte[] b = body();
            String name = charsetName();
            if ((name == null || name.isBlank()) && "text/html".equals(type())) {
                name = MailCharsets.metaCharset(b);
            }
            return MailCharsets.decode(b, name);
        }
    }

    public static Part parse(byte[] bytes) {
        int[] count = {0, MAX_TOTAL_HEADER_BYTES};
        int start = skipEnvelope(bytes);
        return parse(bytes, start, bytes.length, 0, "text/plain", count);
    }

    private static int skipEnvelope(byte[] b) {
        int i = 0;
        while (i < b.length && (b[i] == '\r' || b[i] == '\n' || b[i] == ' ' || b[i] == '\t')) {
            i++;
        }
        if (i + 5 <= b.length && b[i] == 'F' && b[i + 1] == 'r' && b[i + 2] == 'o' && b[i + 3] == 'm'
                && b[i + 4] == ' ') {
            int eol = lineEnd(b, i, b.length);
            return next(b, eol, b.length);
        }
        return i;
    }

    private static Part parse(byte[] b, int from, int to, int depth, String defaultType, int[] count) {
        count[0]++;
        List<String[]> headers = new ArrayList<>();
        int body = headers(b, from, to, headers, count);
        Part part = new Part(b, body, to, headers, defaultType);
        if (depth >= MAX_DEPTH || count[0] >= MAX_PARTS) {
            return part;
        }
        if (part.multipart()) {
            String boundary = part.param("boundary");
            if (boundary != null && !boundary.isEmpty()) {
                String childDefault = part.type().equals("multipart/digest") ? "message/rfc822" : "text/plain";
                splitParts(b, body, to, boundary.getBytes(StandardCharsets.ISO_8859_1), depth, childDefault, count,
                        part.children);
            }
        } else if (part.type().equals("message/rfc822") || part.type().equals("message/global")) {
            String cte = part.header("Content-Transfer-Encoding");
            String e = cte == null ? "" : cte.strip().toLowerCase(Locale.ROOT);
            if (e.equals("base64") || e.equals("quoted-printable")) {
                byte[] inner = part.body();
                part.message = parse(inner, 0, inner.length, depth + 1, "text/plain", count);
            } else {
                part.message = parse(b, body, to, depth + 1, "text/plain", count);
            }
        }
        return part;
    }

    private static void splitParts(byte[] b, int from, int to, byte[] boundary, int depth, String childDefault,
            int[] count, List<Part> out) {
        int partStart = -1;
        int i = from;
        while (i < to) {
            int eol = lineEnd(b, i, to);
            if (delimiter(b, i, eol, boundary)) {
                boolean close = i + 2 + boundary.length + 1 < eol && b[i + 2 + boundary.length] == '-'
                        && b[i + 3 + boundary.length] == '-';
                if (partStart >= 0) {
                    int end = trimLineBreak(b, partStart, i);
                    out.add(parse(b, partStart, end, depth + 1, childDefault, count));
                    if (count[0] >= MAX_PARTS) {
                        return;
                    }
                }
                if (close) {
                    return;
                }
                partStart = next(b, eol, to);
            }
            i = next(b, eol, to);
        }
        if (partStart >= 0 && partStart < to) {
            out.add(parse(b, partStart, to, depth + 1, childDefault, count));
        }
    }

    private static boolean delimiter(byte[] b, int i, int eol, byte[] boundary) {
        if (eol - i < boundary.length + 2 || b[i] != '-' || b[i + 1] != '-') {
            return false;
        }
        for (int k = 0; k < boundary.length; k++) {
            if (b[i + 2 + k] != boundary[k]) {
                return false;
            }
        }
        for (int k = i + 2 + boundary.length; k < eol; k++) {
            byte c = b[k];
            if (c != ' ' && c != '\t' && c != '-') {
                return false;
            }
        }
        return true;
    }

    private static int trimLineBreak(byte[] b, int start, int end) {
        if (end > start && b[end - 1] == '\n') {
            end--;
            if (end > start && b[end - 1] == '\r') {
                end--;
            }
        }
        return end;
    }

    static int lineEnd(byte[] b, int i, int to) {
        while (i < to && b[i] != '\n' && b[i] != '\r') {
            i++;
        }
        return i;
    }

    static int next(byte[] b, int eol, int to) {
        if (eol < to && b[eol] == '\r') {
            eol++;
        }
        if (eol < to && b[eol] == '\n') {
            eol++;
        }
        return eol;
    }

    private static int headers(byte[] b, int from, int to, List<String[]> out, int[] count) {
        int i = from;
        StringBuilder name = null;
        ByteArrayOutputStream value = new ByteArrayOutputStream();
        int budget = Math.min(MAX_HEADER_BYTES, Math.max(0, count[1]));
        while (i < to) {
            int eol = lineEnd(b, i, to);
            if (eol == i) {
                flush(name, value, out, count);
                return next(b, eol, to);
            }
            if ((b[i] == ' ' || b[i] == '\t') && name != null) {
                if (budget > 0) {
                    value.write(' ');
                    int s = i;
                    while (s < eol && (b[s] == ' ' || b[s] == '\t')) {
                        s++;
                    }
                    budget--;
                    int n = Math.min(eol - s, budget);
                    value.write(b, s, n);
                    budget -= n;
                }
            } else {
                int colon = -1;
                for (int k = i; k < eol && k - i < 256; k++) {
                    if (b[k] == ':') {
                        colon = k;
                        break;
                    }
                    if (b[k] <= ' ' || b[k] >= 127) {
                        break;
                    }
                }
                if (colon <= i) {
                    if (out.isEmpty() && name == null) {
                        return i;
                    }
                    flush(name, value, out, count);
                    return i;
                }
                flush(name, value, out, count);
                name = new StringBuilder(new String(b, i, colon - i, StandardCharsets.ISO_8859_1));
                value.reset();
                int s = colon + 1;
                while (s < eol && (b[s] == ' ' || b[s] == '\t')) {
                    s++;
                }
                int n = Math.max(0, Math.min(eol - s, budget));
                value.write(b, s, n);
                budget -= n;
            }
            i = next(b, eol, to);
        }
        flush(name, value, out, count);
        return to;
    }

    private static void flush(StringBuilder name, ByteArrayOutputStream value, List<String[]> out, int[] count) {
        if (name != null && out.size() < MAX_HEADERS && count[1] > 0) {
            byte[] v = value.toByteArray();
            count[1] -= name.length() + v.length + 64;
            out.add(new String[] {name.toString().strip(), new String(v, StandardCharsets.ISO_8859_1)});
        }
        value.reset();
    }

    static byte[] base64(byte[] b, int from, int to) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(16, (to - from) * 3 / 4));
        int acc = 0;
        int bits = 0;
        for (int i = from; i < to; i++) {
            int c = b[i] & 0xFF;
            int v;
            if (c >= 'A' && c <= 'Z') {
                v = c - 'A';
            } else if (c >= 'a' && c <= 'z') {
                v = c - 'a' + 26;
            } else if (c >= '0' && c <= '9') {
                v = c - '0' + 52;
            } else if (c == '+' || c == '-') {
                v = 62;
            } else if (c == '/' || c == '_') {
                v = 63;
            } else if (c == '=') {
                acc = 0;
                bits = 0;
                continue;
            } else {
                continue;
            }
            acc = acc << 6 | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write(acc >> bits & 0xFF);
            }
        }
        return out.toByteArray();
    }

    static byte[] quotedPrintable(byte[] b, int from, int to) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(to - from);
        int i = from;
        while (i < to) {
            int eol = lineEnd(b, i, to);
            int end = eol;
            while (end > i && (b[end - 1] == ' ' || b[end - 1] == '\t')) {
                end--;
            }
            boolean soft = false;
            for (int k = i; k < end; k++) {
                byte c = b[k];
                if (c == '=') {
                    if (k == end - 1) {
                        soft = true;
                        break;
                    }
                    int hi = k + 2 < end ? Character.digit(b[k + 1], 16) : -1;
                    int lo = hi >= 0 ? Character.digit(b[k + 2], 16) : -1;
                    if (hi >= 0 && lo >= 0) {
                        out.write(hi << 4 | lo);
                        k += 2;
                    } else {
                        out.write('=');
                    }
                } else {
                    out.write(c);
                }
            }
            int nxt = next(b, eol, to);
            if (!soft && nxt > eol) {
                out.write('\r');
                out.write('\n');
            }
            i = nxt;
        }
        return out.toByteArray();
    }
}
