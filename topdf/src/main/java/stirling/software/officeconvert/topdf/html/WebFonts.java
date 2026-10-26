package stirling.software.officeconvert.topdf.html;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import stirling.software.officeconvert.topdf.font.FontLibrary;

final class WebFonts {

    static final int MAX_FACES = 32;

    private static final int MAX_TABLES = 256;

    private static final int MAX_FAMILY_CHARS = 63;

    record Face(Map<String, String> declarations, String base) {}

    record Loaded(List<byte[]> fonts, Set<String> families) {
        static final Loaded NONE = new Loaded(List.of(), Set.of());
    }

    private WebFonts() {}

    static Loaded load(List<Face> faces, Linked linked, List<String> warnings) {
        if (faces.isEmpty()) {
            return Loaded.NONE;
        }
        List<byte[]> fonts = new ArrayList<>();
        Set<String> families = new LinkedHashSet<>();
        long total = 0;
        boolean missed = false;
        for (Face face : faces) {
            String family = family(face.declarations().get("font-family"));
            String src = face.declarations().get("src");
            if (family == null || src == null) {
                continue;
            }
            boolean bold = bold(face.declarations().get("font-weight"));
            String style = face.declarations().getOrDefault("font-style", "normal").toLowerCase(Locale.ROOT);
            boolean italic = style.startsWith("italic") || style.startsWith("oblique");
            byte[] font = null;
            for (String part : Css.splitTopLevel(src, ',')) {
                font = source(part.strip(), face.base(), linked);
                if (font != null) {
                    break;
                }
            }
            byte[] renamed = font == null ? null : renamed(font, family, bold, italic);
            if (renamed == null || total + renamed.length > 4L * FontLibrary.MAX_DOCUMENT_FONT_BYTES) {
                missed = true;
                continue;
            }
            total += renamed.length;
            fonts.add(renamed);
            families.add(family);
        }
        if (missed) {
            warnings.add("Some web fonts (@font-face) could not be used: only TrueType, and WOFF holding TrueType,"
                    + " from data: URIs or files the page may read are drawn; other text uses an installed font");
        }
        return fonts.isEmpty() ? Loaded.NONE : new Loaded(List.copyOf(fonts), Set.copyOf(families));
    }

    static String family(String value) {
        if (value == null) {
            return null;
        }
        String f = value.strip();
        if (f.length() >= 2 && (f.charAt(0) == '"' || f.charAt(0) == '\'') && f.charAt(f.length() - 1) == f.charAt(0)) {
            f = f.substring(1, f.length() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < f.length() && sb.length() < MAX_FAMILY_CHARS; i++) {
            char c = f.charAt(i);
            if (c >= 0x20 && c < 0x7F && c != '"' && c != '\'' && c != ',' && c != ';') {
                sb.append(c);
            }
        }
        String out = sb.toString().strip();
        return out.isEmpty() ? null : out;
    }

    private static boolean bold(String weight) {
        if (weight == null) {
            return false;
        }
        String w = weight.strip().toLowerCase(Locale.ROOT);
        if (w.equals("bold") || w.equals("bolder")) {
            return true;
        }
        try {
            return Float.parseFloat(w.split("\\s+")[0]) >= 600;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static byte[] source(String part, String base, Linked linked) {
        String lower = part.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("url(")) {
            return null;
        }
        if (lower.contains("format(") && !(lower.contains("truetype") || lower.contains("opentype")
                || lower.contains("\"woff\"") || lower.contains("'woff'") || lower.contains("(woff)"))) {
            return null;
        }
        String url = Css.firstUrl(part);
        if (url == null) {
            return null;
        }
        byte[] data;
        if (url.regionMatches(true, 0, "data:", 0, 5)) {
            int comma = url.indexOf(',');
            if (comma < 0 || !url.substring(5, comma).toLowerCase(Locale.ROOT).endsWith(";base64")) {
                return null;
            }
            try {
                data = Base64.getMimeDecoder().decode(url.substring(comma + 1));
            } catch (IllegalArgumentException e) {
                return null;
            }
        } else {
            data = linked.read(HtmlRefs.resolve(base, url));
        }
        return data == null ? null : sfnt(data);
    }

    static byte[] sfnt(byte[] data) {
        if (data.length < 12 || data.length > FontLibrary.MAX_DOCUMENT_FONT_BYTES) {
            return null;
        }
        int tag = ByteBuffer.wrap(data).getInt(0);
        if (tag == 0x00010000 || tag == 0x74727565) {
            return data;
        }
        return tag == 0x774F4646 ? woff(data) : null;
    }

    static byte[] woff(byte[] data) {
        try {
            ByteBuffer b = ByteBuffer.wrap(data);
            int flavor = b.getInt(4);
            int numTables = b.getShort(12) & 0xFFFF;
            long total = b.getInt(16) & 0xFFFFFFFFL;
            if (flavor != 0x00010000 && flavor != 0x74727565 || numTables == 0 || numTables > MAX_TABLES
                    || total > FontLibrary.MAX_DOCUMENT_FONT_BYTES || data.length < 44 + 20 * numTables) {
                return null;
            }
            TreeMap<String, byte[]> tables = new TreeMap<>();
            for (int i = 0; i < numTables; i++) {
                int at = 44 + 20 * i;
                String tag = new String(data, at, 4, StandardCharsets.ISO_8859_1);
                long offset = b.getInt(at + 4) & 0xFFFFFFFFL;
                long compLength = b.getInt(at + 8) & 0xFFFFFFFFL;
                long origLength = b.getInt(at + 12) & 0xFFFFFFFFL;
                if (offset + compLength > data.length || origLength > total || compLength > origLength) {
                    return null;
                }
                byte[] table;
                if (compLength == origLength) {
                    table = new byte[(int) origLength];
                    System.arraycopy(data, (int) offset, table, 0, (int) origLength);
                } else {
                    table = inflate(data, (int) offset, (int) compLength, (int) origLength);
                    if (table == null) {
                        return null;
                    }
                }
                tables.put(tag, table);
            }
            return assemble(flavor, tables);
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
    }

    private static byte[] inflate(byte[] data, int offset, int length, int expected) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data, offset, length);
            byte[] out = new byte[expected];
            int n = 0;
            while (n < expected && !inflater.finished()) {
                int got = inflater.inflate(out, n, expected - n);
                if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    return null;
                }
                n += got;
            }
            return n == expected && inflater.finished() ? out : null;
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
    }

    static byte[] renamed(byte[] sfnt, String family, boolean bold, boolean italic) {
        TreeMap<String, byte[]> tables = tables(sfnt);
        if (tables == null || !tables.containsKey("glyf") || !tables.containsKey("head")) {
            return null;
        }
        String sub = bold ? italic ? "Bold Italic" : "Bold" : italic ? "Italic" : "Regular";
        String ps = family.replaceAll("[^A-Za-z0-9]", "") + "-" + sub.replace(" ", "");
        ps = ps.substring(0, Math.min(63, ps.length()));
        tables.put("name", name(family, sub, family + " " + sub, ps));
        byte[] head = tables.get("head").clone();
        if (head.length >= 46) {
            int mac = (head[44] & 0xFF) << 8 | head[45] & 0xFF;
            mac = mac & ~3 | (bold ? 1 : 0) | (italic ? 2 : 0);
            head[44] = (byte) (mac >> 8);
            head[45] = (byte) mac;
        }
        if (head.length >= 12) {
            head[8] = head[9] = head[10] = head[11] = 0;
        }
        tables.put("head", head);
        byte[] os2 = tables.get("OS/2");
        if (os2 != null && os2.length >= 64) {
            os2 = os2.clone();
            int weight = bold ? 700 : 400;
            os2[4] = (byte) (weight >> 8);
            os2[5] = (byte) weight;
            int sel = (os2[62] & 0xFF) << 8 | os2[63] & 0xFF;
            sel &= ~(0x01 | 0x20 | 0x40 | 0x200);
            sel |= bold ? 0x20 : 0;
            sel |= italic ? 0x01 : 0;
            sel |= !bold && !italic ? 0x40 : 0;
            os2[62] = (byte) (sel >> 8);
            os2[63] = (byte) sel;
            tables.put("OS/2", os2);
        }
        int flavor = ByteBuffer.wrap(sfnt).getInt(0);
        byte[] out = assemble(flavor, tables);
        int adjust = (int) (0xB1B0AFBAL - checksum(out, 0, out.length));
        int headAt = tableOffset(out, "head");
        if (headAt >= 0) {
            ByteBuffer.wrap(out).putInt(headAt + 8, adjust);
        }
        return out;
    }

    private static TreeMap<String, byte[]> tables(byte[] sfnt) {
        try {
            ByteBuffer b = ByteBuffer.wrap(sfnt);
            int n = b.getShort(4) & 0xFFFF;
            if (n == 0 || n > MAX_TABLES || sfnt.length < 12 + 16 * n) {
                return null;
            }
            TreeMap<String, byte[]> tables = new TreeMap<>();
            for (int i = 0; i < n; i++) {
                int at = 12 + 16 * i;
                String tag = new String(sfnt, at, 4, StandardCharsets.ISO_8859_1);
                long offset = b.getInt(at + 8) & 0xFFFFFFFFL;
                long length = b.getInt(at + 12) & 0xFFFFFFFFL;
                if (offset + length > sfnt.length) {
                    return null;
                }
                byte[] t = new byte[(int) length];
                System.arraycopy(sfnt, (int) offset, t, 0, (int) length);
                tables.put(tag, t);
            }
            return tables;
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
    }

    private static byte[] assemble(int flavor, TreeMap<String, byte[]> tables) {
        int n = tables.size();
        int size = 12 + 16 * n;
        for (byte[] t : tables.values()) {
            size += (t.length + 3) & ~3;
        }
        ByteBuffer out = ByteBuffer.allocate(size);
        int entrySelector = 31 - Integer.numberOfLeadingZeros(n);
        int searchRange = 16 << entrySelector;
        out.putInt(flavor).putShort((short) n).putShort((short) searchRange).putShort((short) entrySelector)
                .putShort((short) (n * 16 - searchRange));
        int offset = 12 + 16 * n;
        byte[] array = out.array();
        for (Map.Entry<String, byte[]> e : tables.entrySet()) {
            byte[] t = e.getValue();
            out.put(e.getKey().getBytes(StandardCharsets.ISO_8859_1));
            out.putInt((int) checksum(t, 0, t.length));
            out.putInt(offset);
            out.putInt(t.length);
            System.arraycopy(t, 0, array, offset, t.length);
            offset += (t.length + 3) & ~3;
        }
        return array;
    }

    private static int tableOffset(byte[] sfnt, String tag) {
        ByteBuffer b = ByteBuffer.wrap(sfnt);
        int n = b.getShort(4) & 0xFFFF;
        for (int i = 0; i < n; i++) {
            int at = 12 + 16 * i;
            if (new String(sfnt, at, 4, StandardCharsets.ISO_8859_1).equals(tag)) {
                return b.getInt(at + 8);
            }
        }
        return -1;
    }

    private static long checksum(byte[] b, int from, int length) {
        long sum = 0;
        for (int i = 0; i < length; i += 4) {
            long word = 0;
            for (int k = 0; k < 4; k++) {
                word = word << 8 | (i + k < length ? b[from + i + k] & 0xFF : 0);
            }
            sum = (sum + word) & 0xFFFFFFFFL;
        }
        return sum;
    }

    private static byte[] name(String family, String sub, String full, String ps) {
        String[][] records = {{"1", family}, {"2", sub}, {"4", full}, {"6", ps}};
        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        ByteBuffer head = ByteBuffer.allocate(6 + 12 * records.length * 2);
        head.putShort((short) 0).putShort((short) (records.length * 2)).putShort((short) (6 + 12 * records.length * 2));
        int[][] platforms = {{1, 0, 0}, {3, 1, 0x409}};
        for (int[] p : platforms) {
            for (String[] r : records) {
                byte[] bytes = p[0] == 3 ? r[1].getBytes(StandardCharsets.UTF_16BE)
                        : r[1].getBytes(StandardCharsets.ISO_8859_1);
                head.putShort((short) p[0]).putShort((short) p[1]).putShort((short) p[2])
                        .putShort((short) Integer.parseInt(r[0])).putShort((short) bytes.length)
                        .putShort((short) strings.size());
                strings.writeBytes(bytes);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(head.array());
        out.writeBytes(strings.toByteArray());
        return out.toByteArray();
    }
}
