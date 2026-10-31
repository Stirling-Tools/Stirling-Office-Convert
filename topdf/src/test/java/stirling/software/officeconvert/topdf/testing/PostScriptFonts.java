package stirling.software.officeconvert.topdf.testing;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class PostScriptFonts {

    public static final int ADVANCE = 600;

    public static final int UNUSED_GLYPHS = 40;

    private PostScriptFonts() {}

    public static byte[] font(String family, int weight, boolean cidKeyed, int... codePoints) {
        return sfnt(tables(family, weight, cidKeyed, codePoints));
    }

    public static byte[] font(String family, byte[] cff, int glyphs, int... codePoints) {
        Map<String, byte[]> t = metrics(family, 400, codePoints, glyphs);
        t.put("CFF ", cff);
        return sfnt(t);
    }

    public static byte[] accentedCff(String name) {
        List<byte[]> charStrings = List.of(cs(op(14)),
                cs(num(ADVANCE), num(50), num(0), op(21), num(250), num(700), op(5), num(250), num(-700), op(5),
                        op(14)),
                cs(num(ADVANCE), num(250), num(750), op(21), num(100), num(0), op(5), num(0), num(100), op(5),
                        num(-100), num(0), op(5), op(14)),
                cs(num(ADVANCE), num(60), num(0), num(65), num(194), op(14)));
        return nameKeyedCff(name, charStrings, new int[] {34, 125, 171}, new byte[0]);
    }

    public static byte[] largePrivateCff(String name, int glyphs) {
        List<byte[]> charStrings = new ArrayList<>();
        int[] sids = new int[glyphs - 1];
        charStrings.add(cs(op(14)));
        for (int g = 1; g < glyphs; g++) {
            charStrings.add(cs(num(ADVANCE), num(50), num(0), op(21), num(400), num(0), op(5), num(0), num(700),
                    op(5), op(14)));
            sids[g - 1] = 33 + g;
        }
        byte[] priv = new byte[70_001];
        Arrays.fill(priv, (byte) 139);
        priv[priv.length - 1] = 6;
        return nameKeyedCff(name, charStrings, sids, priv);
    }

    private static byte[] nameKeyedCff(String name, List<byte[]> charStrings, int[] sids, byte[] priv) {
        byte[] nameIndex = index(List.of(name.getBytes(StandardCharsets.US_ASCII)));
        ByteArrayOutputStream charset = new ByteArrayOutputStream();
        charset.write(0);
        for (int sid : sids) {
            charset.write(sid >> 8);
            charset.write(sid);
        }
        byte[] charStringIndex = index(charStrings);
        int charsetAt = 4 + nameIndex.length + 2 + 1 + 2 + 23 + 2 + 2;
        int charStringsAt = charsetAt + charset.size();
        int privateAt = charStringsAt + charStringIndex.length;
        byte[] top = cs(int5(charsetAt), op(15), int5(charStringsAt), op(17), int5(priv.length), int5(privateAt),
                op(18));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {1, 0, 4, 4});
        out.writeBytes(nameIndex);
        out.writeBytes(index(List.of(top)));
        out.writeBytes(new byte[] {0, 0, 0, 0});
        out.writeBytes(charset.toByteArray());
        out.writeBytes(charStringIndex);
        out.writeBytes(priv);
        return out.toByteArray();
    }

    public static byte[] collection(byte[]... fonts) {
        List<Map<String, byte[]>> faces = new ArrayList<>();
        for (byte[] f : fonts) {
            faces.add(read(f));
        }
        int header = 12 + 4 * faces.size();
        int at = header;
        int[] dirs = new int[faces.size()];
        for (int i = 0; i < faces.size(); i++) {
            dirs[i] = at;
            at += 12 + 16 * faces.get(i).size();
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer head = ByteBuffer.allocate(header).putInt(0x74746366).putInt(0x00010000).putInt(faces.size());
        for (int d : dirs) {
            head.putInt(d);
        }
        out.writeBytes(head.array());
        int data = at;
        for (Map<String, byte[]> face : faces) {
            ByteBuffer dir = directory(face.size());
            for (Map.Entry<String, byte[]> e : face.entrySet()) {
                dir.put(e.getKey().getBytes(StandardCharsets.ISO_8859_1)).putInt(0).putInt(data + body.size())
                        .putInt(e.getValue().length);
                body.writeBytes(e.getValue());
                body.writeBytes(new byte[(4 - e.getValue().length % 4) % 4]);
            }
            out.writeBytes(dir.array());
        }
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    private static Map<String, byte[]> read(byte[] font) {
        ByteBuffer b = ByteBuffer.wrap(font);
        int n = b.getShort(4) & 0xFFFF;
        Map<String, byte[]> out = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            int rec = 12 + 16 * i;
            String tag = new String(font, rec, 4, StandardCharsets.ISO_8859_1);
            byte[] t = new byte[b.getInt(rec + 12)];
            System.arraycopy(font, b.getInt(rec + 8), t, 0, t.length);
            out.put(tag, t);
        }
        return out;
    }

    private static ByteBuffer directory(int n) {
        return ByteBuffer.allocate(12 + 16 * n).putInt(0x4F54544F).putShort((short) n).putShort((short) 0)
                .putShort((short) 0).putShort((short) 0);
    }

    private static byte[] sfnt(Map<String, byte[]> t) {
        int n = t.size();
        int at = 12 + 16 * n;
        ByteBuffer dir = directory(n);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> e : t.entrySet()) {
            dir.put(e.getKey().getBytes(StandardCharsets.ISO_8859_1)).putInt(0).putInt(at + body.size())
                    .putInt(e.getValue().length);
            body.writeBytes(e.getValue());
            body.writeBytes(new byte[(4 - e.getValue().length % 4) % 4]);
        }
        ByteArrayOutputStream font = new ByteArrayOutputStream();
        font.writeBytes(dir.array());
        font.writeBytes(body.toByteArray());
        return font.toByteArray();
    }

    private static Map<String, byte[]> tables(String family, int weight, boolean cidKeyed, int[] codePoints) {
        int glyphs = 1 + codePoints.length + UNUSED_GLYPHS;
        Map<String, byte[]> t = metrics(family, weight, codePoints, glyphs);
        t.put("CFF ", cff(family.replace(" ", ""), glyphs, cidKeyed));
        return t;
    }

    private static Map<String, byte[]> metrics(String family, int weight, int[] codePoints, int glyphs) {
        Map<String, byte[]> t = new TreeMap<>();
        t.put("head", ByteBuffer.allocate(54).putInt(0x00010000).putInt(0x00010000).putInt(0).putInt(0x5F0F3CF5)
                .putShort((short) 0).putShort((short) 1000).putLong(0).putLong(0).putShort((short) 0)
                .putShort((short) -200).putShort((short) 1000).putShort((short) 800).putShort((short) 0)
                .putShort((short) 8).putShort((short) 2).putShort((short) 0).putShort((short) 0).array());
        t.put("hhea", ByteBuffer.allocate(36).putInt(0x00010000).putShort((short) 800).putShort((short) -200)
                .putShort((short) 0).putShort((short) 1000).put(new byte[6]).putShort((short) 1)
                .put(new byte[14]).putShort((short) glyphs).array());
        t.put("maxp", ByteBuffer.allocate(6).putInt(0x00005000).putShort((short) glyphs).array());
        ByteBuffer hmtx = ByteBuffer.allocate(4 * glyphs);
        for (int g = 0; g < glyphs; g++) {
            hmtx.putShort((short) ADVANCE).putShort((short) 50);
        }
        t.put("hmtx", hmtx.array());
        TreeMap<Integer, Integer> map = new TreeMap<>();
        for (int i = 0; i < codePoints.length; i++) {
            map.put(codePoints[i], i + 1);
        }
        t.put("cmap", cmap(map));
        t.put("name", name(family, weight >= 600 ? "Bold" : "Regular"));
        t.put("OS/2", os2(weight));
        t.put("post", ByteBuffer.allocate(32).putInt(0x00030000).putInt(0).putShort((short) -100)
                .putShort((short) 50).array());
        return t;
    }

    private static byte[] os2(int weight) {
        ByteBuffer b = ByteBuffer.allocate(96);
        b.putShort(0, (short) 4).putShort(2, (short) ADVANCE).putShort(4, (short) weight).putShort(6, (short) 5);
        b.putShort(62, (short) (weight >= 600 ? 0x20 : 0x40));
        b.putShort(64, (short) 0x20).putShort(66, (short) 0xFFFF);
        b.putShort(68, (short) 800).putShort(70, (short) -200).putShort(72, (short) 0);
        b.putShort(74, (short) 1000).putShort(76, (short) 200);
        b.putShort(86, (short) 500).putShort(88, (short) 700);
        return b.array();
    }

    private static byte[] name(String family, String style) {
        String[][] names = {{"1", family}, {"2", style}, {"4", family + " " + style},
                {"6", family.replace(" ", "") + "-" + style}};
        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        ByteBuffer head = ByteBuffer.allocate(6 + 12 * names.length);
        head.putShort((short) 0).putShort((short) names.length).putShort((short) (6 + 12 * names.length));
        for (String[] n : names) {
            byte[] s = n[1].getBytes(StandardCharsets.UTF_16BE);
            head.putShort((short) 3).putShort((short) 1).putShort((short) 0x409).putShort(Short.parseShort(n[0]))
                    .putShort((short) s.length).putShort((short) strings.size());
            strings.writeBytes(s);
        }
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.writeBytes(head.array());
        all.writeBytes(strings.toByteArray());
        return all.toByteArray();
    }

    private static byte[] cmap(TreeMap<Integer, Integer> map) {
        int groups = map.size();
        ByteBuffer b = ByteBuffer.allocate(12 + 16 + 12 * groups);
        b.putShort((short) 0).putShort((short) 1).putShort((short) 3).putShort((short) 10).putInt(12);
        b.putShort((short) 12).putShort((short) 0).putInt(16 + 12 * groups).putInt(0).putInt(groups);
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            b.putInt(e.getKey()).putInt(e.getKey()).putInt(e.getValue());
        }
        return b.array();
    }

    private static byte[] cff(String name, int glyphs, boolean cidKeyed) {
        List<byte[]> strings = new ArrayList<>();
        strings.add("Adobe".getBytes(StandardCharsets.US_ASCII));
        strings.add("Identity".getBytes(StandardCharsets.US_ASCII));
        List<byte[]> charStrings = new ArrayList<>();
        charStrings.add(cs(num(ADVANCE), num(50), num(0), op(21), num(400), num(0), op(5), num(0), num(700), op(5),
                num(-400), num(0), op(5), op(14)));
        for (int g = 1; g < glyphs; g++) {
            int h = 300 + 10 * g;
            if (g % 3 == 0) {
                charStrings.add(cs(num(ADVANCE), num(0), num(50), op(18), op(19), new byte[] {(byte) 0x80}, num(50),
                        num(0), op(21), num(400), num(0), op(5), num(h), num(-107), op(29), op(14)));
            } else if (g % 3 == 1) {
                charStrings.add(cs(num(ADVANCE), num(50), num(0), op(21), num(400), num(0), op(5), num(h),
                        num(-107), op(10), op(14)));
            } else {
                charStrings.add(cs(num(ADVANCE), num(50), num(0), op(21), num(400), num(0), op(5), num(h),
                        num(-106), op(10), op(14)));
            }
        }
        List<byte[]> globals = new ArrayList<>();
        globals.add(cs(num(0), op(7), num(-400), num(0), op(5), op(11)));
        globals.add(cs(num(0), num(500), op(5), op(11)));
        List<byte[]> locals = new ArrayList<>();
        locals.add(cs(num(0), op(7), num(-400), num(0), op(5), op(11)));
        locals.add(cs(num(-107), op(29), op(11)));
        for (int i = 0; i < 20; i++) {
            locals.add(cs(num(0), num(i), op(5), op(11)));
        }
        byte[] subrs = index(locals);
        byte[] priv = cs(num(0), op(20), num(0), op(21), int5(10), op(19));
        byte[] charset;
        ByteArrayOutputStream c = new ByteArrayOutputStream();
        c.write(0);
        for (int g = 1; g < glyphs; g++) {
            int id = cidKeyed ? 2 * g + 5 : 393 + g - 1;
            c.write(id >> 8);
            c.write(id);
        }
        charset = c.toByteArray();
        for (int g = 1; g < glyphs && !cidKeyed; g++) {
            strings.add(("g" + g).getBytes(StandardCharsets.US_ASCII));
        }
        byte[] nameIndex = index(List.of(name.getBytes(StandardCharsets.US_ASCII)));
        byte[] stringIndex = index(strings);
        byte[] globalIndex = index(globals);
        byte[] charStringIndex = index(charStrings);
        int fds = cidKeyed ? 2 : 0;
        byte[] probe = top(cidKeyed, 0, 0, 0, 0, 0, glyphs);
        int at = 4 + nameIndex.length + index(List.of(probe)).length + stringIndex.length + globalIndex.length;
        int charsetAt = at;
        at += charset.length;
        int selectAt = at;
        byte[] select = new byte[0];
        if (cidKeyed) {
            int split = glyphs / 2;
            select = ByteBuffer.allocate(11).put((byte) 3).putShort((short) 2).putShort((short) 0).put((byte) 0)
                    .putShort((short) split).put((byte) 1).putShort((short) glyphs).array();
        }
        at += select.length;
        int charStringsAt = at;
        at += charStringIndex.length;
        int fdArrayAt = at;
        byte[] fdArray = new byte[0];
        if (cidKeyed) {
            List<byte[]> probeFds = new ArrayList<>();
            for (int i = 0; i < fds; i++) {
                probeFds.add(cs(int5(0), int5(0), op(18)));
            }
            at += index(probeFds).length;
            List<byte[]> dicts = new ArrayList<>();
            int privateAt = at;
            for (int i = 0; i < fds; i++) {
                dicts.add(cs(int5(priv.length), int5(privateAt), op(18)));
                privateAt += priv.length + subrs.length;
            }
            fdArray = index(dicts);
        }
        int privateAt = at;
        byte[] topIndex = index(List.of(top(cidKeyed, charsetAt, selectAt, charStringsAt, fdArrayAt, privateAt,
                glyphs, priv.length)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {1, 0, 4, 4});
        out.writeBytes(nameIndex);
        out.writeBytes(topIndex);
        out.writeBytes(stringIndex);
        out.writeBytes(globalIndex);
        out.writeBytes(charset);
        out.writeBytes(select);
        out.writeBytes(charStringIndex);
        out.writeBytes(fdArray);
        for (int i = 0; i < Math.max(1, fds); i++) {
            out.writeBytes(priv);
            out.writeBytes(subrs);
        }
        return out.toByteArray();
    }

    private static byte[] top(boolean cidKeyed, int charset, int select, int charStrings, int fdArray, int priv,
            int glyphs) {
        return top(cidKeyed, charset, select, charStrings, fdArray, priv, glyphs, 0);
    }

    private static byte[] top(boolean cidKeyed, int charset, int select, int charStrings, int fdArray, int priv,
            int glyphs, int privateSize) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        if (cidKeyed) {
            o.writeBytes(cs(num(391), num(392), num(0), op(1230)));
            o.writeBytes(cs(int5(2 * glyphs + 10), op(1234)));
        }
        o.writeBytes(cs(int5(charset), op(15)));
        o.writeBytes(cs(int5(charStrings), op(17)));
        if (cidKeyed) {
            o.writeBytes(cs(int5(fdArray), op(1236)));
            o.writeBytes(cs(int5(select), op(1237)));
        } else {
            o.writeBytes(cs(int5(privateSize), int5(priv), op(18)));
        }
        return o.toByteArray();
    }

    private static byte[] cs(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.writeBytes(p);
        }
        return o.toByteArray();
    }

    private static byte[] num(int v) {
        if (v >= -107 && v <= 107) {
            return new byte[] {(byte) (v + 139)};
        }
        return new byte[] {28, (byte) (v >> 8), (byte) v};
    }

    private static byte[] int5(int v) {
        return new byte[] {29, (byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    private static byte[] op(int op) {
        return op >= 1200 ? new byte[] {12, (byte) (op - 1200)} : new byte[] {(byte) op};
    }

    private static byte[] index(List<byte[]> items) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(items.size() >> 8);
        o.write(items.size());
        if (items.isEmpty()) {
            return o.toByteArray();
        }
        int total = 1;
        for (byte[] b : items) {
            total += b.length;
        }
        int offSize = total < 1 << 8 ? 1 : total < 1 << 16 ? 2 : 3;
        o.write(offSize);
        int off = 1;
        for (int i = 0; i <= items.size(); i++) {
            for (int k = offSize - 1; k >= 0; k--) {
                o.write(off >> 8 * k);
            }
            if (i < items.size()) {
                off += items.get(i).length;
            }
        }
        for (byte[] b : items) {
            o.writeBytes(b);
        }
        return o.toByteArray();
    }
}
