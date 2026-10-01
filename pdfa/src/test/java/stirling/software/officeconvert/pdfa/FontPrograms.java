package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.fontbox.cff.CFFStandardString;

final class FontPrograms {

    record Type1(byte[] data, int length1, int length2, int length3) {}

    static final List<String> NAMES = List.of("space", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L",
            "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "a", "b", "c", "d", "e", "f", "g",
            "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z", "acute", "Aacute");

    private FontPrograms() {}

    static int width(String name) {
        return "space".equals(name) ? 250 : 400 + (name.hashCode() & 0xFF);
    }

    static Type1 type1(String fontName, int subrPadding) {
        StringBuilder clear = new StringBuilder();
        clear.append("%!PS-AdobeFont-1.0: ").append(fontName).append(" 001\n")
                .append("12 dict begin\n/FontInfo 2 dict dup begin /FullName (").append(fontName)
                .append(") readonly def end readonly def\n/FontName /").append(fontName).append(" def\n")
                .append("/Encoding StandardEncoding def\n/PaintType 0 def\n/FontType 1 def\n")
                .append("/FontMatrix [0.001 0 0 0.001 0 0] readonly def\n/FontBBox {0 -10 900 760} readonly def\n")
                .append("currentdict end\ncurrentfile eexec\n");
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        write(plain, "dup /Private 9 dict dup begin\n/RD{string currentfile exch readstring pop}executeonly def\n"
                + "/ND{noaccess def}executeonly def\n/NP{noaccess put}executeonly def\n/lenIV 4 def\n"
                + "/BlueValues [] def\n/MinFeature{16 16}def\n/password 5839 def\n");
        int subrs = 5 + subrPadding;
        write(plain, "/Subrs " + subrs + " array\n");
        for (int i = 0; i < subrs; i++) {
            byte[] cs = i == 4 ? concat(num(0), num(700), new byte[] {5}, num(400), num(0), new byte[] {5, 11})
                    : i >= 5 ? concat(repeat(num(1), 40), new byte[] {11}) : new byte[] {11};
            byte[] enc = charstring(cs);
            write(plain, "dup " + i + " " + enc.length + " RD ");
            plain.writeBytes(enc);
            write(plain, " NP\n");
        }
        write(plain, "ND\n2 index /CharStrings " + (NAMES.size() + 1) + " dict dup begin\n");
        glyph(plain, ".notdef", concat(num(0), num(250), new byte[] {13, 14}));
        for (String n : NAMES) {
            byte[] body;
            if ("space".equals(n)) {
                body = concat(num(0), num(250), new byte[] {13, 14});
            } else if ("Aacute".equals(n)) {
                body = concat(num(0), num(width("A")), new byte[] {13}, num(0), num(100), num(200), num(65), num(194),
                        new byte[] {12, 6});
            } else {
                int w = width(n);
                body = concat(num(0), num(w), new byte[] {13}, num(50), num(0), new byte[] {21}, num(4),
                        new byte[] {10}, num(-400), num(0), new byte[] {5, 9},
                        repeat(concat(num(10), num(0), new byte[] {5}, num(-10), num(0), new byte[] {5}), 30),
                        new byte[] {14});
            }
            glyph(plain, n, body);
        }
        write(plain, "end\nend\nreadonly put\nnoaccess put\ndup/FontName get exch definefont pop\n"
                + "mark currentfile closefile\n");
        byte[] encrypted = eexec(plain.toByteArray());
        String tail = ("0".repeat(64) + "\n").repeat(8) + "cleartomark\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] head = clear.toString().getBytes(StandardCharsets.ISO_8859_1);
        out.writeBytes(head);
        out.writeBytes(encrypted);
        out.writeBytes(tail.getBytes(StandardCharsets.ISO_8859_1));
        return new Type1(out.toByteArray(), head.length, encrypted.length, tail.length());
    }

    private static void glyph(ByteArrayOutputStream plain, String name, byte[] body) {
        byte[] enc = charstring(body);
        write(plain, "/" + name + " " + enc.length + " RD ");
        plain.writeBytes(enc);
        write(plain, " ND\n");
    }

    static byte[] num(int v) {
        if (v >= -107 && v <= 107) {
            return new byte[] {(byte) (v + 139)};
        }
        if (v >= 108 && v <= 1131) {
            int w = v - 108;
            return new byte[] {(byte) (w / 256 + 247), (byte) (w % 256)};
        }
        if (v <= -108 && v >= -1131) {
            int w = -v - 108;
            return new byte[] {(byte) (w / 256 + 251), (byte) (w % 256)};
        }
        return new byte[] {(byte) 255, (byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.writeBytes(p);
        }
        return o.toByteArray();
    }

    static byte[] repeat(byte[] b, int n) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (int i = 0; i < n; i++) {
            o.writeBytes(b);
        }
        return o.toByteArray();
    }

    static byte[] charstring(byte[] plain) {
        return encrypt(concat(new byte[4], plain), 4330);
    }

    static byte[] eexec(byte[] plain) {
        return encrypt(concat(new byte[4], plain), 55665);
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

    private static void write(ByteArrayOutputStream o, String s) {
        o.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    static byte[] cff(String fontName) {
        int n = NAMES.size() + 1;
        byte[][] glyphs = new byte[n][];
        glyphs[0] = concat(num2(250), new byte[] {14});
        for (int i = 1; i < n; i++) {
            String name = NAMES.get(i - 1);
            if ("space".equals(name)) {
                glyphs[i] = concat(num2(250), new byte[] {14});
            } else if ("Aacute".equals(name)) {
                glyphs[i] = concat(num2(width("A")), num2(0), num2(200), num2(65), num2(194), new byte[] {14});
            } else {
                java.util.Random r = new java.util.Random(name.hashCode());
                ByteArrayOutputStream noise = new ByteArrayOutputStream();
                for (int k = 0; k < 60; k++) {
                    int dx = r.nextInt(400) - 200;
                    noise.writeBytes(concat(num2(dx), num2(r.nextInt(400) - 200), new byte[] {5}));
                }
                glyphs[i] = concat(num2(width(name)), num2(50), num2(0), new byte[] {21}, num2(300), num2(0),
                        new byte[] {5}, num2(0), num2(700), new byte[] {5}, num2(-300), num2(0), new byte[] {5},
                        noise.toByteArray(), new byte[] {14});
            }
        }
        byte[] name = index(new byte[][] {fontName.getBytes(StandardCharsets.US_ASCII)});
        byte[] strings = index(new byte[0][]);
        byte[] gsubrs = index(new byte[0][]);
        ByteArrayOutputStream charset = new ByteArrayOutputStream();
        charset.write(0);
        for (String g : NAMES) {
            int sid = sid(g);
            charset.write(sid >> 8);
            charset.write(sid);
        }
        byte[] charstrings = index(glyphs);
        byte[] priv = concat(num2(0), new byte[] {20}, num2(0), new byte[] {21});
        byte[] probe = index(new byte[][] {top(0, 0, 0, 0)});
        int charsetAt = 4 + name.length + probe.length + strings.length + gsubrs.length;
        int charstringsAt = charsetAt + charset.size();
        int privateAt = charstringsAt + charstrings.length;
        byte[] topIndex = index(new byte[][] {top(charsetAt, charstringsAt, priv.length, privateAt)});
        return concat(new byte[] {1, 0, 4, 4}, name, topIndex, strings, gsubrs, charset.toByteArray(), charstrings,
                priv);
    }

    private static byte[] top(int charset, int charstrings, int privateSize, int privateAt) {
        return concat(num2(0), num2(-10), num2(900), num2(760), new byte[] {5}, int5(charset), new byte[] {15},
                int5(charstrings), new byte[] {17}, int5(privateSize), int5(privateAt), new byte[] {18});
    }

    private static int sid(String name) {
        for (int i = 0; i < 391; i++) {
            if (name.equals(CFFStandardString.getName(i))) {
                return i;
            }
        }
        throw new IllegalArgumentException(name);
    }

    static byte[] num2(int v) {
        if (v >= -107 && v <= 107) {
            return new byte[] {(byte) (v + 139)};
        }
        return new byte[] {28, (byte) (v >> 8), (byte) v};
    }

    static byte[] int5(int v) {
        return new byte[] {29, (byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    static byte[] index(byte[][] items) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(items.length >> 8);
        o.write(items.length);
        if (items.length == 0) {
            return o.toByteArray();
        }
        int total = 1;
        for (byte[] b : items) {
            total += b.length;
        }
        int offSize = total < 256 ? 1 : total < 65536 ? 2 : 3;
        o.write(offSize);
        int off = 1;
        for (int i = 0; i <= items.length; i++) {
            for (int k = offSize - 1; k >= 0; k--) {
                o.write(off >> (8 * k));
            }
            if (i < items.length) {
                off += items[i].length;
            }
        }
        for (byte[] b : items) {
            o.writeBytes(b);
        }
        return o.toByteArray();
    }
}
