package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.fontbox.ttf.PostScriptTable;
import org.apache.fontbox.ttf.TTFTable;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.font.encoding.Encoding;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;
import org.apache.pdfbox.pdmodel.font.encoding.MacExpertEncoding;
import org.apache.pdfbox.pdmodel.font.encoding.MacOSRomanEncoding;
import org.apache.pdfbox.pdmodel.font.encoding.MacRomanEncoding;
import org.apache.pdfbox.pdmodel.font.encoding.StandardEncoding;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;

final class StrictGlyphs {

    private static final int BULLET = 0x95;

    private static final Map<String, Integer> MAC_OS_ROMAN = new HashMap<>();

    static {
        MacOSRomanEncoding.INSTANCE.getCodeToNameMap().forEach((code, name) -> MAC_OS_ROMAN.putIfAbsent(name, code));
    }

    private final String[] postNames;

    private final boolean byName;

    private final int glyphs;

    private final String[] names;

    private final Map<Integer, Integer> unicode;

    private final Map<Integer, Integer> macRoman;

    private final Map<Integer, Integer> symbol;

    private final int symbolBase;

    private Map<String, Integer> post;

    private StrictGlyphs(COSDictionary font, boolean symbolic, byte[] cmap, int glyphs, String[] postNames)
            throws IOException {
        this.glyphs = glyphs;
        this.postNames = postNames;
        COSBase enc = font.getDictionaryObject(COSName.ENCODING);
        byName = !symbolic && enc != null;
        names = byName ? names(enc) : null;
        Map<Integer, Integer> u = null;
        Map<Integer, Integer> m = null;
        Map<Integer, Integer> s = null;
        int base = -1;
        int count = cmap.length < 4 ? 0 : u16(cmap, 2);
        for (int i = 0; i < count && 4 + 8 * i + 8 <= cmap.length; i++) {
            int platform = u16(cmap, 4 + 8 * i);
            int encoding = u16(cmap, 6 + 8 * i);
            long offset = u32(cmap, 8 + 8 * i);
            if (platform == 3 && encoding == 1 && u == null) {
                u = subtable(cmap, offset, null);
            } else if (platform == 1 && encoding == 0 && m == null) {
                m = subtable(cmap, offset, null);
            } else if (platform == 3 && encoding == 0 && s == null) {
                int[] sample = {-1};
                s = subtable(cmap, offset, sample);
                base = sample[0] < 0 ? -1 : sample[0] & 0xFF00;
            }
        }
        unicode = u;
        macRoman = m;
        symbol = s;
        symbolBase = base;
    }

    static StrictGlyphs of(COSDictionary font, boolean symbolic, TrueTypeFont ttf) {
        try {
            TTFTable t = ttf.getTableMap().get("cmap");
            byte[] cmap = t == null ? new byte[0] : ttf.getTableBytes(t);
            PostScriptTable p = ttf.getPostScript();
            return new StrictGlyphs(font, symbolic, cmap, ttf.getNumberOfGlyphs(), p == null ? null : p.getGlyphNames());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static StrictGlyphs of(COSDictionary font, boolean symbolic, byte[] cmap, int glyphs) {
        try {
            return new StrictGlyphs(font, symbolic, cmap, glyphs, null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    int gid(int code) {
        if (byName) {
            String name = code >= 0 && code < names.length ? names[code] : ".notdef";
            if (name == null || ".notdef".equals(name)) {
                return 0;
            }
            int gid = 0;
            if (unicode != null) {
                int cp = aglCodePoint(name);
                gid = cp < 0 ? 0 : unicode.getOrDefault(cp, 0);
            }
            if (gid == 0 && macRoman != null) {
                Integer mac = MAC_OS_ROMAN.get(name);
                if (mac != null) {
                    gid = macRoman.getOrDefault(mac, 0);
                }
            }
            if (gid == 0) {
                gid = postGid(name);
            }
            return gid > 0 && gid < glyphs ? gid : 0;
        }
        if (symbol != null) {
            boolean allowed = symbolBase == 0 || symbolBase == 0xF000 || symbolBase == 0xF100 || symbolBase == 0xF200;
            return allowed ? symbol.getOrDefault(symbolBase + code, 0) : 0;
        }
        return macRoman == null ? 0 : macRoman.getOrDefault(code, 0);
    }

    boolean hasUnicodeTable() {
        return unicode != null;
    }

    boolean byName() {
        return byName;
    }

    private int postGid(String name) {
        if (post == null) {
            post = new HashMap<>();
            if (postNames != null) {
                for (int g = 0; g < postNames.length; g++) {
                    post.put(postNames[g], g);
                }
            }
        }
        return post.getOrDefault(name, 0);
    }

    static int aglCodePoint(String name) {
        if (name.indexOf('.') > 0 || name.indexOf('_') > 0 || name.matches("uni[0-9A-Fa-f]{4,}|u[0-9A-Fa-f]{4,6}")) {
            return -1;
        }
        String u = GlyphList.getAdobeGlyphList().toUnicode(name);
        return u == null || u.codePointCount(0, u.length()) != 1 ? -1 : u.codePointAt(0);
    }

    private static String[] names(COSBase enc) throws IOException {
        String[] out = new String[256];
        if (enc instanceof COSName n) {
            Encoding base = COSName.WIN_ANSI_ENCODING.equals(n) ? WinAnsiEncoding.INSTANCE
                    : COSName.MAC_ROMAN_ENCODING.equals(n) ? MacRomanEncoding.INSTANCE : null;
            if (base == null) {
                throw new IOException("the validator cannot read this TrueType encoding");
            }
            fill(out, base);
            return out;
        }
        if (!(enc instanceof COSDictionary d)) {
            throw new IOException("the validator cannot read this TrueType encoding");
        }
        COSBase baseName = d.getDictionaryObject(COSName.BASE_ENCODING);
        Encoding base = COSName.WIN_ANSI_ENCODING.equals(baseName) ? WinAnsiEncoding.INSTANCE
                : COSName.MAC_ROMAN_ENCODING.equals(baseName) ? MacRomanEncoding.INSTANCE
                : COSName.MAC_EXPERT_ENCODING.equals(baseName) ? MacExpertEncoding.INSTANCE
                : StandardEncoding.INSTANCE;
        fill(out, base);
        COSArray diff = ContentGraph.array(d.getDictionaryObject(COSName.DIFFERENCES));
        if (diff == null) {
            throw new IOException("the validator cannot read this TrueType encoding");
        }
        int code = -1;
        for (int i = 0; i < diff.size(); i++) {
            COSBase item = diff.getObject(i);
            if (item instanceof COSNumber num) {
                code = num.intValue();
            } else if (item instanceof COSName g && code >= 0) {
                if (code < 256) {
                    out[code] = g.getName();
                }
                code++;
            }
        }
        for (int i = 0; i < 256; i++) {
            if (out[i] == null || ".notdef".equals(out[i])) {
                out[i] = StandardEncoding.INSTANCE.getName(i);
            }
        }
        return out;
    }

    private static void fill(String[] out, Encoding base) {
        for (int i = 0; i < 256; i++) {
            String name = base.getName(i);
            boolean filler = base == WinAnsiEncoding.INSTANCE && "bullet".equals(name) && i != BULLET;
            out[i] = filler ? ".notdef" : name;
        }
    }

    private static Map<Integer, Integer> subtable(byte[] t, long offset, int[] sample) {
        Map<Integer, Integer> map = new HashMap<>();
        if (offset < 0 || offset + 2 > t.length) {
            return map;
        }
        int at = (int) offset;
        int format = u16(t, at);
        if (format == 0) {
            for (int i = 0; i < 256 && at + 6 + i < t.length; i++) {
                put(map, sample, i, t[at + 6 + i] & 0xFF);
            }
        } else if (format == 4 && at + 14 <= t.length) {
            int segCount = u16(t, at + 6) / 2;
            int ends = at + 14;
            int starts = ends + 2 * segCount + 2;
            int deltas = starts + 2 * segCount;
            int ranges = deltas + 2 * segCount;
            if (ranges + 2 * segCount > t.length) {
                return map;
            }
            for (int i = 0; i < segCount; i++) {
                int end = u16(t, ends + 2 * i);
                int start = u16(t, starts + 2 * i);
                int delta = u16(t, deltas + 2 * i);
                int range = u16(t, ranges + 2 * i);
                if (range == 0) {
                    for (int j = start; j <= end; j++) {
                        put(map, sample, j, (delta + j) % 65536);
                    }
                } else if (start != 0xFFFF && end != 0xFFFF) {
                    for (int j = 0; j <= end - start; j++) {
                        long glyphAt = ranges + 2L * segCount + (long) (range / 2 + j + (i - segCount)) * 2L;
                        int glyph = glyphAt >= 0 && glyphAt + 2 <= t.length ? u16(t, (int) glyphAt) : 0;
                        if (glyph != 0) {
                            glyph = (glyph + delta) % 65536;
                        }
                        put(map, sample, j + start, glyph);
                    }
                }
            }
        } else if (format == 6 && at + 10 <= t.length) {
            int first = u16(t, at + 6);
            int count = u16(t, at + 8);
            for (int i = 0; i < count && at + 10 + 2 * i + 2 <= t.length; i++) {
                put(map, sample, first + i, u16(t, at + 10 + 2 * i));
            }
        }
        return map;
    }

    private static void put(Map<Integer, Integer> map, int[] sample, int code, int gid) {
        if (sample != null && sample[0] < 0) {
            sample[0] = code;
        }
        map.put(code, gid);
    }

    private static int u16(byte[] b, int at) {
        return (b[at] & 0xFF) << 8 | b[at + 1] & 0xFF;
    }

    private static long u32(byte[] b, int at) {
        return (long) (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | b[at + 3] & 0xFF;
    }
}
