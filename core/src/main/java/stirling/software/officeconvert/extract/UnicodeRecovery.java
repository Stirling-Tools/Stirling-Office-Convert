package stirling.software.officeconvert.extract;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.fontbox.ttf.CmapSubtable;
import org.apache.fontbox.ttf.CmapTable;
import org.apache.fontbox.ttf.GlyphData;
import org.apache.fontbox.ttf.PostScriptTable;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.fontbox.ttf.WGL4Names;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;
import org.apache.pdfbox.text.TextPosition;

final class UnicodeRecovery {

    private static final int[][] UNICODE_CMAPS = {
        {CmapTable.PLATFORM_WINDOWS, CmapTable.ENCODING_WIN_UNICODE_BMP},
        {CmapTable.PLATFORM_WINDOWS, CmapTable.ENCODING_WIN_UNICODE_FULL},
        {CmapTable.PLATFORM_UNICODE, CmapTable.ENCODING_UNICODE_2_0_BMP},
        {CmapTable.PLATFORM_UNICODE, CmapTable.ENCODING_UNICODE_2_0_FULL},
        {CmapTable.PLATFORM_UNICODE, CmapTable.ENCODING_UNICODE_1_1}
    };

    private static final int MAX_LOOKUPS = 64;

    private final FontResolver fonts;
    private final Map<COSDictionary, Lookup> lookups = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<COSDictionary, Lookup> eldest) {
            return size() > MAX_LOOKUPS;
        }
    };

    UnicodeRecovery(FontResolver fonts) {
        this.fonts = fonts;
    }

    String text(TextPosition tp, String unicode) {
        if (unicode == null || tp.getCharacterCodes().length != 1) {
            return unicode;
        }
        boolean pua = privateUse(unicode);
        boolean mark = !pua && unicode.codePointCount(0, unicode.length()) == 1;
        if (!pua && !mark) {
            return unicode;
        }
        PDFont font = tp.getFont();
        FontInfo info = fonts.resolve(font);
        if (pua && (info.symbolic() || info.substituted())) {
            return unicode;
        }
        Lookup lookup = lookups.computeIfAbsent(font.getCOSObject(), k -> Lookup.of(font));
        if (lookup == null) {
            return unicode;
        }
        int code = tp.getCharacterCodes()[0];
        if (mark) {
            String drawn = lookup.encoded(code, unicode);
            if (!drawn.isEmpty()) {
                return drawn;
            }
            return !Character.isWhitespace(unicode.codePointAt(0)) && lookup.blank(code) ? " " : unicode;
        }
        String found = lookup.text(code);
        return found == null ? unicode : found;
    }

    static boolean privateUse(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0xE000 || c > 0xF8FF) {
                return false;
            }
        }
        return true;
    }

    static Map<Integer, String> programText(PDType0Font font, int codes) {
        Lookup lookup = Lookup.of(font);
        Map<Integer, String> out = new java.util.TreeMap<>();
        if (lookup == null) {
            return out;
        }
        for (int code = 1; code < codes; code++) {
            String text = lookup.text(code);
            if (text != null && !text.isEmpty() && !privateUse(text) && !Character.isISOControl(text.codePointAt(0))) {
                out.put(code, text);
            }
        }
        return out;
    }

    private record Lookup(PDFont font, TrueTypeFont ttf, CmapSubtable cmap, PostScriptTable post,
            boolean standardOrder, Map<Integer, Boolean> blanks, Map<Integer, String> drawn) {

        static Lookup of(PDFont font) {
            try {
                TrueTypeFont ttf = trueType(font);
                if (ttf == null) {
                    return null;
                }
                return new Lookup(font, ttf, unicodeCmap(ttf), ttf.getPostScript(), standardOrder(ttf), new HashMap<>(), new HashMap<>());
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        String encoded(int code, String unicode) {
            return drawn.computeIfAbsent(code, c -> {
                try {
                    if (font instanceof PDType0Font t0 && cmap != null) {
                        return composite(t0, c, unicode);
                    }
                    if (!(font instanceof PDTrueTypeFont tt) || cmap == null || tt.getEncoding() == null) {
                        return "";
                    }
                    String name = tt.getEncoding().getName(c);
                    String u = name == null ? null : GlyphList.getAdobeGlyphList().toUnicode(name);
                    if (u == null || u.codePointCount(0, u.length()) != 1 || presentationForm(u.codePointAt(0))) {
                        return "";
                    }
                    int gid = tt.codeToGID(c);
                    return gid > 0 && cmap.getGlyphId(u.codePointAt(0)) == gid ? u : "";
                } catch (IOException | RuntimeException e) {
                    return "";
                }
            });
        }

        private String composite(PDType0Font t0, int code, String unicode) throws IOException {
            int gid = t0.codeToGID(code);
            int said = cmap.getGlyphId(unicode.codePointAt(0));
            if (gid <= 0 || said <= 0 || said == gid) {
                return "";
            }
            List<Integer> own = cmap.getCharCodes(gid);
            if (own == null || own.isEmpty()) {
                return "";
            }
            int cp = own.getFirst();
            return Character.isLetterOrDigit(cp) && !presentationForm(cp) && unshaped(cp) && unshaped(unicode.codePointAt(0))
                    ? Character.toString(cp) : "";
        }

        private static boolean unshaped(int cp) {
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            return script == Character.UnicodeScript.LATIN || script == Character.UnicodeScript.GREEK
                    || script == Character.UnicodeScript.CYRILLIC || script == Character.UnicodeScript.COMMON;
        }

        private static boolean presentationForm(int cp) {
            return cp >= 0xFB00 && cp <= 0xFDFF || cp >= 0xFE70 && cp <= 0xFEFF;
        }

        boolean blank(int code) {
            return blanks.computeIfAbsent(code, c -> {
                try {
                    int gid = gid(c);
                    if (gid <= 0 || ttf.getGlyph() == null) {
                        return false;
                    }
                    GlyphData data = ttf.getGlyph().getGlyph(gid);
                    return data == null || data.getPath().getPathIterator(null).isDone();
                } catch (IOException | RuntimeException e) {
                    return false;
                }
            });
        }

        private int gid(int code) throws IOException {
            return font instanceof PDType0Font t0 ? t0.codeToGID(code) : ((PDTrueTypeFont) font).codeToGID(code);
        }

        String text(int code) {
            try {
                int gid = font instanceof PDType0Font t0 ? t0.codeToGID(code) : ((PDTrueTypeFont) font).codeToGID(code);
                if (gid <= 0) {
                    return null;
                }
                if (cmap != null) {
                    List<Integer> codes = cmap.getCharCodes(gid);
                    if (codes != null && !codes.isEmpty()) {
                        return Character.toString(codes.getFirst());
                    }
                }
                String name = post == null ? null : post.getName(gid);
                if ((name == null || name.startsWith("glyph")) && standardOrder && gid < WGL4Names.NUMBER_OF_MAC_GLYPHS) {
                    name = WGL4Names.getGlyphName(gid);
                }
                String text = name == null ? null : GlyphList.getAdobeGlyphList().toUnicode(name);
                return text == null || privateUse(text) ? null : text;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        private static TrueTypeFont trueType(PDFont font) throws IOException {
            if (font instanceof PDTrueTypeFont tt && tt.isEmbedded()) {
                return tt.getTrueTypeFont();
            }
            if (font instanceof PDType0Font t0 && t0.getDescendantFont() instanceof PDCIDFontType2 cid && t0.isEmbedded()) {
                return cid.getTrueTypeFont();
            }
            return null;
        }

        private static CmapSubtable unicodeCmap(TrueTypeFont ttf) throws IOException {
            CmapTable table = ttf.getCmap();
            if (table == null) {
                return null;
            }
            for (int[] id : UNICODE_CMAPS) {
                CmapSubtable sub = table.getSubtable(id[0], id[1]);
                if (sub != null) {
                    return sub;
                }
            }
            return null;
        }

        private static boolean standardOrder(TrueTypeFont ttf) throws IOException {
            if (ttf.getNumberOfGlyphs() < WGL4Names.NUMBER_OF_MAC_GLYPHS || ttf.getGlyph() == null) {
                return false;
            }
            GlyphData space = ttf.getGlyph().getGlyph(3);
            float em = ttf.getAdvanceWidth(3) / (float) ttf.getUnitsPerEm();
            return (space == null || space.getNumberOfContours() == 0) && em > 0.2f && em < 0.45f;
        }
    }
}
