package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Type1Lenient {

    private static final Pattern NUMBER = Pattern.compile("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?");

    private static final Pattern ENTRY = Pattern.compile("\\G\\s*(?:(?:N[PD]|\\||\\|-|noaccess|def|put|readonly"
            + "|executeonly|dup\\s+(\\d+))\\s*)*?(?:/([^\\s/\\[\\]{}()<>%]*)\\s+)?(\\d+)\\s+(\\S+) ");

    final String fontName;

    final List<Number> fontMatrix;

    final Map<String, byte[]> charStrings = new LinkedHashMap<>();

    final List<byte[]> subrs = new ArrayList<>();

    private Type1Lenient(String fontName, List<Number> fontMatrix) {
        this.fontName = fontName;
        this.fontMatrix = fontMatrix;
    }

    static Type1Lenient parse(byte[] clear, byte[] binary) throws IOException {
        String text = new String(clear, StandardCharsets.ISO_8859_1);
        Matcher name = Pattern.compile("/FontName\\s*/([^\\s/\\[\\]{}()<>%]+)").matcher(text);
        Type1Lenient font = new Type1Lenient(name.find() ? name.group(1) : "Type1", matrix(text));
        byte[] plain = decrypt(hex(binary), 55665, 4);
        String p = new String(plain, StandardCharsets.ISO_8859_1);
        Matcher len = Pattern.compile("/lenIV\\s+(-?\\d+)").matcher(p);
        int lenIV = len.find() ? Integer.parseInt(len.group(1)) : 4;
        int subrsAt = p.indexOf("/Subrs");
        int charsAt = p.indexOf("/CharStrings");
        if (charsAt < 0) {
            throw new IOException("The Type 1 program has no CharStrings");
        }
        if (subrsAt >= 0 && subrsAt < charsAt) {
            Matcher count = NUMBER.matcher(p);
            int at = subrsAt + 6;
            if (count.find(at)) {
                at = p.indexOf("array", count.end());
                at = at < 0 ? count.end() : at + 5;
                Map<Integer, byte[]> found = new LinkedHashMap<>();
                int max = -1;
                for (byte[][] entry : entries(plain, p, at, charsAt, true)) {
                    int index = Integer.parseInt(new String(entry[0], StandardCharsets.ISO_8859_1));
                    found.put(index, charString(entry[1], lenIV));
                    max = Math.max(max, index);
                }
                for (int i = 0; i <= max && i < 65_536; i++) {
                    font.subrs.add(found.getOrDefault(i, new byte[] {11}));
                }
            }
        }
        int start = p.indexOf("begin", charsAt);
        start = start < 0 ? charsAt + 12 : start + 5;
        for (byte[][] entry : entries(plain, p, start, plain.length, false)) {
            String glyph = new String(entry[0], StandardCharsets.ISO_8859_1);
            font.charStrings.putIfAbsent(glyph, charString(entry[1], lenIV));
        }
        if (font.charStrings.isEmpty()) {
            throw new IOException("The Type 1 program has no readable glyphs");
        }
        return font;
    }

    private static List<byte[][]> entries(byte[] plain, String p, int from, int to, boolean numbered) {
        List<byte[][]> out = new ArrayList<>();
        Matcher m = ENTRY.matcher(p);
        int at = from;
        while (at < to) {
            m.region(at, p.length());
            if (!m.lookingAt()) {
                break;
            }
            String key = numbered ? m.group(1) : m.group(2);
            int length = Integer.parseInt(m.group(3));
            int data = m.end();
            if (key == null || length < 0 || data + length > plain.length) {
                break;
            }
            byte[] bytes = new byte[length];
            System.arraycopy(plain, data, bytes, 0, length);
            out.add(new byte[][] {key.getBytes(StandardCharsets.ISO_8859_1), bytes});
            at = data + length;
        }
        return out;
    }

    private static byte[] charString(byte[] encrypted, int lenIV) {
        return lenIV < 0 ? encrypted : decrypt(encrypted, 4330, lenIV);
    }

    private static byte[] decrypt(byte[] data, int key, int skip) {
        int r = key;
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(0, data.length - skip));
        for (int i = 0; i < data.length; i++) {
            int c = data[i] & 0xFF;
            int plain = c ^ (r >> 8);
            r = ((c + r) * 52845 + 22719) & 0xFFFF;
            if (i >= skip) {
                out.write(plain);
            }
        }
        return out.toByteArray();
    }

    private static byte[] hex(byte[] binary) {
        int checked = 0;
        for (int i = 0; i < binary.length && checked < 4; i++) {
            int c = binary[i];
            if (c == ' ' || c == '\r' || c == '\n' || c == '\t') {
                continue;
            }
            if (Character.digit(c, 16) < 0) {
                return binary;
            }
            checked++;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(binary.length / 2);
        int high = -1;
        for (byte b : binary) {
            int d = Character.digit(b, 16);
            if (d < 0) {
                continue;
            }
            if (high < 0) {
                high = d;
            } else {
                out.write(high << 4 | d);
                high = -1;
            }
        }
        return out.toByteArray();
    }

    private static List<Number> matrix(String text) {
        Matcher m = Pattern.compile("/FontMatrix\\s*[\\[{]([^\\]}]*)[\\]}]").matcher(text);
        if (m.find()) {
            List<Number> values = new ArrayList<>();
            Matcher n = NUMBER.matcher(m.group(1));
            while (n.find()) {
                try {
                    values.add(Double.parseDouble(n.group()));
                } catch (NumberFormatException e) {
                    return List.of(0.001, 0, 0, 0.001, 0, 0);
                }
            }
            if (values.size() == 6) {
                return values;
            }
        }
        return List.of(0.001, 0, 0, 0.001, 0, 0);
    }
}
