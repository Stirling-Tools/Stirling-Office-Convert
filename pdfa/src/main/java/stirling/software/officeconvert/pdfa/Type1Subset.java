package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.font.encoding.StandardEncoding;

final class Type1Subset {

    record Program(byte[] data, int length1, int length2, int length3) {}

    private record Entry(int start, int end, String key, byte[] charstring, String rd, String tail) {}

    private static final int EEXEC = 55665;

    private static final int CHARSTRING = 4330;

    private static final Pattern LEN_IV = Pattern.compile("/lenIV\\s+(\\d+)");

    private final byte[] plain;

    private final int lenIV;

    private final List<Entry> subrs = new ArrayList<>();

    private final Map<String, Entry> glyphs = new LinkedHashMap<>();

    private Type1Subset(byte[] plain) {
        this.plain = plain;
        Matcher m = LEN_IV.matcher(new String(plain, StandardCharsets.ISO_8859_1));
        this.lenIV = m.find() ? Integer.parseInt(m.group(1)) : 4;
    }

    static Program hollow(byte[] data, int length1, int length2, Set<String> used) {
        if (length1 <= 0 || length2 <= 0 || length1 + length2 > data.length) {
            return null;
        }
        byte[] cipher = Arrays.copyOfRange(data, length1, length1 + length2);
        if (hex(cipher)) {
            return null;
        }
        byte[] plainText = decrypt(cipher, EEXEC, 4);
        Type1Subset t = new Type1Subset(plainText);
        if (t.lenIV < 0 || t.lenIV > 16 || !t.parse()) {
            return null;
        }
        Set<String> keep = t.closure(used);
        Set<Integer> keepSubrs = t.reachable(keep);
        byte[] rebuilt = t.rebuild(keep, keepSubrs);
        byte[] encrypted = encrypt(concat(new byte[4], rebuilt), EEXEC);
        int tail = data.length - length1 - length2;
        ByteArrayOutputStream out = new ByteArrayOutputStream(length1 + encrypted.length + Math.max(0, tail));
        out.write(data, 0, length1);
        out.writeBytes(encrypted);
        out.write(data, length1 + length2, Math.max(0, tail));
        return new Program(out.toByteArray(), length1, encrypted.length, Math.max(0, tail));
    }

    private boolean parse() {
        int at = indexOf("/Subrs", 0);
        if (at >= 0) {
            int[] p = {at + 6};
            int count = (int) number(p);
            if (count < 0 || count > 65_536) {
                return false;
            }
            for (int i = 0; i < count; i++) {
                int start = indexOf("dup", p[0]);
                if (start < 0) {
                    return false;
                }
                p[0] = start + 3;
                long index = number(p);
                Entry e = binary(start, p, String.valueOf(index));
                if (e == null || index != subrs.size()) {
                    return false;
                }
                subrs.add(e);
            }
        }
        int cs = indexOf("/CharStrings", Math.max(0, at));
        if (cs < 0) {
            return false;
        }
        int[] p = {indexOf("begin", cs)};
        if (p[0] < 0) {
            return false;
        }
        p[0] += 5;
        while (true) {
            skipSpace(p);
            if (p[0] >= plain.length || plain[p[0]] != '/') {
                break;
            }
            int start = p[0];
            int nameEnd = start + 1;
            while (nameEnd < plain.length && !delimiter(plain[nameEnd])) {
                nameEnd++;
            }
            String name = new String(plain, start + 1, nameEnd - start - 1, StandardCharsets.ISO_8859_1);
            p[0] = nameEnd;
            Entry e = binary(start, p, name);
            if (e == null) {
                return false;
            }
            glyphs.put(name, e);
        }
        return glyphs.containsKey(".notdef");
    }

    private Entry binary(int start, int[] p, String key) {
        long len = number(p);
        if (len < 0 || len > 65_535) {
            return null;
        }
        int rdStart = p[0];
        skipSpace(p);
        while (p[0] < plain.length && !Character.isWhitespace(plain[p[0]])) {
            p[0]++;
        }
        p[0]++;
        int dataStart = p[0];
        if (dataStart + len > plain.length) {
            return null;
        }
        String rd = new String(plain, rdStart, dataStart - rdStart, StandardCharsets.ISO_8859_1);
        byte[] cs = Arrays.copyOfRange(plain, dataStart, (int) (dataStart + len));
        p[0] = (int) (dataStart + len);
        int tailStart = p[0];
        String token = word(p);
        if ("noaccess".equals(token)) {
            word(p);
        }
        String tail = new String(plain, tailStart, p[0] - tailStart, StandardCharsets.ISO_8859_1);
        return new Entry(start, p[0], key, decrypt(cs, CHARSTRING, lenIV), rd, tail);
    }

    private String word(int[] p) {
        skipSpace(p);
        int start = p[0];
        while (p[0] < plain.length && !Character.isWhitespace(plain[p[0]])) {
            p[0]++;
        }
        return new String(plain, start, p[0] - start, StandardCharsets.ISO_8859_1);
    }

    private Set<String> closure(Set<String> used) {
        Set<String> keep = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>(used);
        todo.push(".notdef");
        while (!todo.isEmpty()) {
            String n = todo.pop();
            Entry e = glyphs.get(n);
            if (e == null || !keep.add(n)) {
                continue;
            }
            int[] accents = seac(e.charstring());
            if (accents != null) {
                for (int code : accents) {
                    String c = StandardEncoding.INSTANCE.getName(code);
                    if (c != null && !keep.contains(c)) {
                        todo.push(c);
                    }
                }
            }
        }
        return keep;
    }

    private static int[] seac(byte[] cs) {
        List<Integer> stack = new ArrayList<>();
        for (int i = 0; i < cs.length; ) {
            int b = cs[i] & 0xFF;
            if (b >= 32) {
                int[] r = decodeNumber(cs, i);
                stack.add(r[0]);
                i = r[1];
            } else if (b == 12 && i + 1 < cs.length) {
                if ((cs[i + 1] & 0xFF) == 6 && stack.size() >= 2) {
                    return new int[] {stack.get(stack.size() - 2), stack.get(stack.size() - 1)};
                }
                i += 2;
                stack.clear();
            } else {
                i++;
                if (b != 10) {
                    stack.clear();
                }
            }
        }
        return null;
    }

    private Set<Integer> reachable(Set<String> keep) {
        Set<Integer> out = new HashSet<>();
        for (int i = 0; i < Math.min(4, subrs.size()); i++) {
            out.add(i);
        }
        Deque<byte[]> todo = new ArrayDeque<>();
        for (String n : keep) {
            Entry e = glyphs.get(n);
            if (e != null) {
                todo.push(e.charstring());
            }
        }
        while (!todo.isEmpty()) {
            Set<Integer> called = calls(todo.pop());
            if (called == null) {
                Set<Integer> all = new HashSet<>();
                for (int i = 0; i < subrs.size(); i++) {
                    all.add(i);
                }
                return all;
            }
            for (int s : called) {
                if (s < 0 || s >= subrs.size()) {
                    return reachableAll();
                }
                if (out.add(s)) {
                    todo.push(subrs.get(s).charstring());
                }
            }
        }
        return out;
    }

    private Set<Integer> reachableAll() {
        Set<Integer> all = new HashSet<>();
        for (int i = 0; i < subrs.size(); i++) {
            all.add(i);
        }
        return all;
    }

    private static Set<Integer> calls(byte[] cs) {
        Set<Integer> out = new HashSet<>();
        Deque<Integer> stack = new ArrayDeque<>();
        Deque<Integer> ps = new ArrayDeque<>();
        for (int i = 0; i < cs.length; ) {
            int b = cs[i] & 0xFF;
            if (b >= 32) {
                int[] r = decodeNumber(cs, i);
                stack.push(r[0]);
                i = r[1];
                continue;
            }
            if (b == 10) {
                if (stack.isEmpty()) {
                    return null;
                }
                out.add(stack.pop());
                i++;
                continue;
            }
            if (b == 12 && i + 1 < cs.length) {
                int op = cs[i + 1] & 0xFF;
                i += 2;
                if (op == 16) {
                    if (stack.size() < 2) {
                        return null;
                    }
                    stack.pop();
                    int n = stack.pop();
                    if (n < 0 || n > stack.size()) {
                        return null;
                    }
                    for (int k = 0; k < n; k++) {
                        ps.push(stack.pop());
                    }
                } else if (op == 17) {
                    stack.push(ps.isEmpty() ? 0 : ps.pop());
                } else {
                    stack.clear();
                }
                continue;
            }
            i++;
            stack.clear();
        }
        return out;
    }

    private static int[] decodeNumber(byte[] cs, int i) {
        int b = cs[i] & 0xFF;
        if (b <= 246) {
            return new int[] {b - 139, i + 1};
        }
        if (b <= 250) {
            return new int[] {(b - 247) * 256 + (i + 1 < cs.length ? cs[i + 1] & 0xFF : 0) + 108, i + 2};
        }
        if (b <= 254) {
            return new int[] {-(b - 251) * 256 - (i + 1 < cs.length ? cs[i + 1] & 0xFF : 0) - 108, i + 2};
        }
        int v = 0;
        for (int k = 1; k <= 4; k++) {
            v = v << 8 | (i + k < cs.length ? cs[i + k] & 0xFF : 0);
        }
        return new int[] {v, i + 5};
    }

    private byte[] rebuild(Set<String> keep, Set<Integer> keepSubrs) {
        List<Entry> edits = new ArrayList<>();
        for (int i = 0; i < subrs.size(); i++) {
            if (!keepSubrs.contains(i)) {
                edits.add(subrs.get(i));
            }
        }
        for (Entry e : glyphs.values()) {
            if (!keep.contains(e.key())) {
                edits.add(e);
            }
        }
        edits.sort((a, b) -> Integer.compare(a.start(), b.start()));
        ByteArrayOutputStream out = new ByteArrayOutputStream(plain.length);
        int at = 0;
        for (Entry e : edits) {
            out.write(plain, at, e.start() - at);
            boolean glyph = glyphs.get(e.key()) == e;
            byte[] body = glyph ? new byte[] {(byte) 139, (byte) 139, 13, 14} : new byte[] {11};
            byte[] enc = encrypt(concat(new byte[lenIV], body), CHARSTRING);
            String head = (glyph ? "/" + e.key() : "dup " + e.key()) + " " + enc.length;
            out.writeBytes(head.getBytes(StandardCharsets.ISO_8859_1));
            out.writeBytes(e.rd().getBytes(StandardCharsets.ISO_8859_1));
            out.writeBytes(enc);
            out.writeBytes(e.tail().getBytes(StandardCharsets.ISO_8859_1));
            at = e.end();
        }
        out.write(plain, at, plain.length - at);
        return out.toByteArray();
    }

    private int indexOf(String s, int from) {
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = Math.max(0, from); i + b.length <= plain.length; i++) {
            for (int k = 0; k < b.length; k++) {
                if (plain[i + k] != b[k]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private long number(int[] p) {
        skipSpace(p);
        int start = p[0];
        while (p[0] < plain.length && (Character.isDigit(plain[p[0]]) || plain[p[0]] == '-')) {
            p[0]++;
        }
        if (p[0] == start || p[0] - start > 9) {
            return -1;
        }
        return Long.parseLong(new String(plain, start, p[0] - start, StandardCharsets.ISO_8859_1));
    }

    private void skipSpace(int[] p) {
        while (p[0] < plain.length && Character.isWhitespace(plain[p[0]])) {
            p[0]++;
        }
    }

    private static boolean delimiter(byte b) {
        return Character.isWhitespace(b) || b == '/' || b == '[' || b == ']' || b == '{' || b == '}' || b == '('
                || b == '<' || b == '>';
    }

    private static boolean hex(byte[] b) {
        for (int i = 0; i < Math.min(4, b.length); i++) {
            if (Character.digit(b[i], 16) < 0) {
                return false;
            }
        }
        return true;
    }

    static byte[] decrypt(byte[] cipher, int key, int skip) {
        int r = key;
        byte[] out = new byte[Math.max(0, cipher.length - skip)];
        for (int i = 0; i < cipher.length; i++) {
            int c = cipher[i] & 0xFF;
            int p = c ^ (r >> 8);
            r = ((c + r) * 52845 + 22719) & 0xFFFF;
            if (i >= skip) {
                out[i - skip] = (byte) p;
            }
        }
        return out;
    }

    static byte[] encrypt(byte[] plain, int key) {
        int r = key;
        byte[] out = new byte[plain.length];
        for (int i = 0; i < plain.length; i++) {
            int c = (plain[i] & 0xFF) ^ (r >> 8);
            out[i] = (byte) c;
            r = ((c + r) * 52845 + 22719) & 0xFFFF;
        }
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
