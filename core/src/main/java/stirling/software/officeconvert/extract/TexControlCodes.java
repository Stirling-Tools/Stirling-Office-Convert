package stirling.software.officeconvert.extract;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.encoding.DictionaryEncoding;
import org.apache.pdfbox.text.TextPosition;

import stirling.software.officeconvert.extract.GlyphCollector.RawGlyph;

// pdfTeX bitmap fonts name glyphs a<code> with no ToUnicode; codes below 0x20 hold ligatures (OT1, T1) or a bullet (OMS).
final class TexControlCodes {

    private static final char BASE = '\uF700';
    private static final String[] LIGATURES = {"ff", "fi", "fl", "ffi", "ffl"};

    private TexControlCodes() {}

    static String placeholder(TextPosition tp, String unicode) {
        int[] codes = tp.getCharacterCodes();
        if (codes == null || codes.length != 1 || !(tp.getFont() instanceof PDType3Font font) || !controlCode(codes[0])
                || unicode != null && !(unicode.length() == 1 && (unicode.charAt(0) < 0x20 || unicode.charAt(0) >= 0x80 && unicode.charAt(0) <= 0x9F))) {
            return null;
        }
        return pkFont(font, codes[0]) ? String.valueOf((char) (BASE + codes[0])) : null;
    }

    private static boolean controlCode(int code) {
        return code >= 0x0B && code <= 0x1F || code == 0x88;
    }

    private static boolean pkFont(PDFont font, int code) {
        if (font.getCOSObject().containsKey(COSName.TO_UNICODE)) {
            return false;
        }
        try {
            String name = ((PDType3Font) font).getEncoding().getName(code);
            return ("a" + code).equals(name);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean pkFont(PDFont font) {
        if (!(font instanceof PDType3Font t3) || font.getCOSObject().containsKey(COSName.TO_UNICODE)
                || !(t3.getEncoding() instanceof DictionaryEncoding enc) || enc.getDifferences().isEmpty()) {
            return false;
        }
        for (Map.Entry<Integer, String> e : enc.getDifferences().entrySet()) {
            if (!".notdef".equals(e.getValue()) && !("a" + e.getKey()).equals(e.getValue())) {
                return false;
            }
        }
        return true;
    }

    static void resolve(List<RawGlyph> glyphs, Set<Glyph> placeholders) {
        for (int i = 0; i < glyphs.size(); i++) {
            Glyph g = glyphs.get(i).glyph();
            if (!placeholders.contains(g) || g.text.length() != 1 || g.text.charAt(0) < BASE || g.text.charAt(0) > BASE + 0x88) {
                continue;
            }
            int code = g.text.charAt(0) - BASE;
            boolean before = letterAt(glyphs, i - 1, g, true);
            boolean after = letterAt(glyphs, i + 1, g, false);
            String text = text(code, before, after);
            if (text == null) {
                glyphs.remove(i--);
            } else {
                RawGlyph r = glyphs.get(i);
                glyphs.set(i, new RawGlyph(g.withText(text), r.direction(), r.invisible(), r.originX(), r.originY()));
            }
        }
    }

    private static String text(int code, boolean before, boolean after) {
        boolean ligature = code >= 0x0B && code <= 0x0F || code >= 0x1B && code <= 0x1F;
        if (ligature) {
            return before || after ? LIGATURES[(code & 0x0F) - 0x0B] : code == 0x0F ? "•" : null;
        }
        return switch (code) {
            case 0x10 -> before && after ? "ı" : "“";
            case 0x11 -> before && after ? "ȷ" : "”";
            case 0x15 -> "–";
            case 0x16 -> "—";
            case 0x88 -> before || after ? "Ł" : "•";
            default -> null;
        };
    }

    private static boolean letterAt(List<RawGlyph> glyphs, int k, Glyph g, boolean before) {
        if (k < 0 || k >= glyphs.size()) {
            return false;
        }
        Glyph n = glyphs.get(k).glyph();
        float gap = before ? g.x - n.right() : n.x - g.right();
        if (n.text.isEmpty() || Math.abs(n.baseline - g.baseline) > 0.2f * g.size || gap > 0.15f * g.size || gap < -0.5f * g.size) {
            return false;
        }
        int c = before ? n.text.codePointBefore(n.text.length()) : n.text.codePointAt(0);
        return Character.isLetter(c);
    }
}
