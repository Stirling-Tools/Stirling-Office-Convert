package stirling.software.officeconvert.extract;

import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.encoding.Encoding;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;

final class LigatureNames {

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

    static String text(String name, GlyphList glyphs) {
        if (name == null) {
            return null;
        }
        int dot = name.indexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        if (base.indexOf('_') <= 0 || base.endsWith("_")) {
            return null;
        }
        String[] parts = base.split("_", MAX_PARTS + 1);
        if (parts.length > MAX_PARTS) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            String piece = part.isEmpty() ? null : glyphs.toUnicode(part);
            if (piece == null) {
                return null;
            }
            text.append(piece);
        }
        return text.toString();
    }
}
