package stirling.software.officeconvert.topdf.mail;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class MimeWords {

    private MimeWords() {}

    public static String mediaType(String header) {
        int semi = header.indexOf(';');
        String t = (semi < 0 ? header : header.substring(0, semi)).strip().toLowerCase(Locale.ROOT);
        int space = t.indexOf(' ');
        if (space > 0) {
            t = t.substring(0, space);
        }
        return t.isEmpty() ? null : t;
    }

    public static Map<String, String> params(String header) {
        Map<String, String> plain = new HashMap<>();
        Map<String, TreeMap<Integer, String[]>> parts = new HashMap<>();
        int i = header.indexOf(';');
        int n = header.length();
        int count = 0;
        while (i >= 0 && i < n && count++ < 256) {
            i++;
            while (i < n && (header.charAt(i) == ' ' || header.charAt(i) == '\t' || header.charAt(i) == ';')) {
                i++;
            }
            int eq = i;
            while (eq < n && header.charAt(eq) != '=' && header.charAt(eq) != ';') {
                eq++;
            }
            if (eq >= n || header.charAt(eq) == ';') {
                i = eq < n ? eq : -1;
                continue;
            }
            String name = header.substring(i, eq).strip().toLowerCase(Locale.ROOT);
            int v = eq + 1;
            while (v < n && (header.charAt(v) == ' ' || header.charAt(v) == '\t')) {
                v++;
            }
            String value;
            int end;
            if (v < n && header.charAt(v) == '"') {
                StringBuilder sb = new StringBuilder();
                end = v + 1;
                while (end < n && header.charAt(end) != '"') {
                    char c = header.charAt(end);
                    if (c == '\\' && end + 1 < n) {
                        end++;
                        c = header.charAt(end);
                    }
                    sb.append(c);
                    end++;
                }
                value = sb.toString();
                end = header.indexOf(';', Math.min(n, end + 1));
            } else {
                end = header.indexOf(';', v);
                value = (end < 0 ? header.substring(v) : header.substring(v, end)).strip();
            }
            i = end;
            if (name.isEmpty()) {
                continue;
            }
            int star = name.indexOf('*');
            if (star > 0) {
                String base = name.substring(0, star);
                String rest = name.substring(star + 1);
                boolean encoded = rest.endsWith("*") || rest.isEmpty();
                String num = rest.endsWith("*") ? rest.substring(0, rest.length() - 1) : rest;
                int index;
                try {
                    index = num.isEmpty() ? 0 : Integer.parseInt(num);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (index >= 0 && index < 1000) {
                    parts.computeIfAbsent(base, k -> new TreeMap<>()).put(index,
                            new String[] {value, encoded ? "*" : ""});
                }
            } else {
                plain.putIfAbsent(name, value);
            }
        }
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> e : plain.entrySet()) {
            out.put(e.getKey(), decode(e.getValue(), StandardCharsets.UTF_8));
        }
        for (Map.Entry<String, TreeMap<Integer, String[]>> e : parts.entrySet()) {
            String joined = extended(e.getValue());
            if (joined != null) {
                out.put(e.getKey(), joined);
            }
        }
        return out;
    }

    private static String extended(TreeMap<Integer, String[]> segments) {
        Charset charset = null;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean first = true;
        for (String[] seg : segments.values()) {
            String v = seg[0];
            boolean encoded = !seg[1].isEmpty();
            if (first && encoded) {
                int q1 = v.indexOf('\'');
                int q2 = q1 < 0 ? -1 : v.indexOf('\'', q1 + 1);
                if (q2 > q1) {
                    charset = MailCharsets.forName(v.substring(0, q1));
                    v = v.substring(q2 + 1);
                }
            }
            first = false;
            if (encoded) {
                percent(v, bytes);
            } else {
                byte[] b = v.getBytes(StandardCharsets.ISO_8859_1);
                bytes.write(b, 0, b.length);
            }
        }
        byte[] b = bytes.toByteArray();
        return charset == null ? MailCharsets.decode(b, (Charset) null) : new String(b, charset);
    }

    private static void percent(String v, ByteArrayOutputStream out) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            int hi = c == '%' && i + 2 < v.length() ? Character.digit(v.charAt(i + 1), 16) : -1;
            int lo = hi >= 0 ? Character.digit(v.charAt(i + 2), 16) : -1;
            if (hi >= 0 && lo >= 0) {
                out.write(hi << 4 | lo);
                i += 2;
            } else {
                out.write(c & 0xFF);
            }
        }
    }

    public static String decode(String raw, Charset fallback) {
        if (raw == null) {
            return null;
        }
        String s = MailCharsets.raw(raw, fallback);
        if (s.indexOf("=?") < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        Charset pendingCharset = null;
        int i = 0;
        int n = s.length();
        int lastWordEnd = -1;
        int close = 0;
        while (i < n) {
            int start = s.indexOf("=?", i);
            if (start < 0) {
                flush(out, pending, pendingCharset);
                out.append(s, i, n);
                break;
            }
            int q1 = find(s, "?", start + 2, Math.min(n, start + 82));
            int q2 = q1 < 0 ? -1 : q1 + 2;
            if (q2 > 0 && q2 < n && s.charAt(q2) == '?' && close >= 0 && close <= q2) {
                close = s.indexOf("?=", q2 + 1);
            }
            int end = q2 > 0 && q2 < n && s.charAt(q2) == '?' && close > q2 && close - q2 <= 4_000 ? close : -1;
            if (q1 < 0 || end < 0) {
                flush(out, pending, pendingCharset);
                out.append(s, i, start + 2);
                i = start + 2;
                lastWordEnd = -1;
                continue;
            }
            String gap = s.substring(i, start);
            boolean adjacent = lastWordEnd == i && gap.isBlank();
            if (!adjacent) {
                flush(out, pending, pendingCharset);
                pendingCharset = null;
                out.append(gap);
            }
            String cs = s.substring(start + 2, q1);
            char enc = Character.toUpperCase(s.charAt(q1 + 1));
            String text = s.substring(q2 + 1, end);
            Charset charset = MailCharsets.forName(cs);
            if (charset == null) {
                charset = fallback == null ? MailCharsets.WESTERN : fallback;
            }
            if (pendingCharset != null && !pendingCharset.equals(charset)) {
                flush(out, pending, pendingCharset);
            }
            pendingCharset = charset;
            byte[] word = enc == 'B' ? Mime.base64(text.getBytes(StandardCharsets.ISO_8859_1), 0, text.length())
                    : qword(text);
            pending.write(word, 0, word.length);
            i = end + 2;
            lastWordEnd = i;
        }
        flush(out, pending, pendingCharset);
        return out.toString();
    }

    private static int find(String s, String what, int from, int limit) {
        for (int i = from; i + what.length() <= limit; i++) {
            if (s.startsWith(what, i)) {
                return i;
            }
        }
        return -1;
    }

    private static void flush(StringBuilder out, ByteArrayOutputStream pending, Charset charset) {
        if (pending.size() == 0) {
            return;
        }
        byte[] b = pending.toByteArray();
        pending.reset();
        if (charset == null || charset.equals(MailCharsets.WESTERN) || charset.equals(StandardCharsets.UTF_8)) {
            out.append(MailCharsets.decode(b, charset == null ? MailCharsets.WESTERN : charset));
        } else {
            out.append(new String(b, charset));
        }
    }

    private static byte[] qword(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '_') {
                out.write(' ');
            } else if (c == '=' && i + 2 < text.length() && Character.digit(text.charAt(i + 1), 16) >= 0
                    && Character.digit(text.charAt(i + 2), 16) >= 0) {
                out.write(Character.digit(text.charAt(i + 1), 16) << 4 | Character.digit(text.charAt(i + 2), 16));
                i += 2;
            } else {
                out.write(c & 0xFF);
            }
        }
        return out.toByteArray();
    }

    public static List<String> addresses(String header) {
        List<String> out = new ArrayList<>();
        if (header == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        int angle = 0;
        int paren = 0;
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c == '\\' && quoted && i + 1 < header.length()) {
                cur.append(c).append(header.charAt(++i));
                continue;
            }
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == '<') {
                angle++;
            } else if (!quoted && c == '>' && angle > 0) {
                angle--;
            } else if (!quoted && c == '(') {
                paren++;
            } else if (!quoted && c == ')' && paren > 0) {
                paren--;
            } else if (!quoted && angle == 0 && paren == 0 && (c == ',' || c == ';')) {
                add(out, cur);
                continue;
            }
            cur.append(c);
        }
        add(out, cur);
        return out;
    }

    private static void add(List<String> out, StringBuilder cur) {
        String a = cur.toString().strip();
        cur.setLength(0);
        if (a.isEmpty() || out.size() >= 5_000) {
            return;
        }
        int lt = a.lastIndexOf('<');
        int gt = a.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            String name = unquote(a.substring(0, lt).strip());
            String addr = a.substring(lt + 1, gt).strip();
            if (name.isEmpty() || name.equalsIgnoreCase(addr)) {
                out.add(addr);
            } else {
                out.add(name + " <" + addr + ">");
            }
        } else {
            out.add(a);
        }
    }

    static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        } else if (s.length() >= 2 && s.startsWith("'") && s.endsWith("'")) {
            s = s.substring(1, s.length() - 1);
        }
        return s.strip();
    }
}
