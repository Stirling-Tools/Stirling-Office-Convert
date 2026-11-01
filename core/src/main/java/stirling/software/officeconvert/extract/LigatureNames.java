package stirling.software.officeconvert.extract;

import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.encoding.Encoding;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;

public final class LigatureNames {

    private static final int MAX_PARTS = 8;

    private LigatureNames() {}

    static String text(PDFont font, int code, GlyphList glyphs) {
        if (!(font instanceof PDSimpleFont simple)) {
            return null;
        }
        try {
            Encoding encoding = simple.getEncoding();
            return encoding == null ? null : text(encoding.getName(code), glyphs);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static String text(String name, GlyphList glyphs) {
        if (name == null) {
            return null;
        }
        int dot = name.indexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        if (base.isEmpty() || base.indexOf('_') < 0 && !base.startsWith("uni")) {
            return null;
        }
        String[] parts = base.split("_", MAX_PARTS + 1);
        if (parts.length > MAX_PARTS || base.endsWith("_")) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            String piece = component(part, glyphs);
            if (piece == null) {
                return null;
            }
            text.append(piece);
        }
        return text.toString();
    }

    private static String component(String part, GlyphList glyphs) {
        if (part.isEmpty()) {
            return null;
        }
        if (part.startsWith("uni") && part.length() > 3 && (part.length() - 3) % 4 == 0) {
            StringBuilder b = new StringBuilder();
            for (int i = 3; i < part.length(); i += 4) {
                int cp = hex(part.substring(i, i + 4));
                if (cp < 0 || cp >= 0xD800 && cp <= 0xDFFF) {
                    return null;
                }
                b.append((char) cp);
            }
            return b.toString();
        }
        if (part.length() >= 5 && part.length() <= 7 && part.charAt(0) == 'u') {
            int cp = hex(part.substring(1));
            if (cp >= 0 && cp <= Character.MAX_CODE_POINT && !(cp >= 0xD800 && cp <= 0xDFFF)) {
                return new String(Character.toChars(cp));
            }
        }
        String u = glyphs.toUnicode(part);
        return u == null || u.isEmpty() ? null : u;
    }

    private static int hex(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return -1;
            }
        }
        return s.isEmpty() ? -1 : Integer.parseInt(s, 16);
    }
}
