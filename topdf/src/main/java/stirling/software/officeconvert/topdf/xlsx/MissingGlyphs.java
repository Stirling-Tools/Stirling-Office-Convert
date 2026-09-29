package stirling.software.officeconvert.topdf.xlsx;

import java.awt.Color;
import java.io.IOException;

import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.Stroke;

// Characters no installed face covers are left out, but they keep the width Office would give them
final class MissingGlyphs {

    static final double OTHER_EM = 0.6;

    private MissingGlyphs() {}

    static boolean missing(FontFace face, int cp) {
        return !face.covers(cp) && !zeroWidth(cp);
    }

    static boolean any(FontFace face, String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (missing(face, cp)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    static double em(int cp) {
        if (zeroWidth(cp)) {
            return 0;
        }
        int type = Character.getType(cp);
        if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK) {
            return 0;
        }
        if (wide(cp)) {
            return 1;
        }
        if (cp >= 0xFF61 && cp <= 0xFFDC || cp >= 0xFFE8 && cp <= 0xFFEE) {
            return 0.5;
        }
        return OTHER_EM;
    }

    static final double BOX_INSET = 0.1;

    static final double BOX_TOP = 0.8;

    static final double BOX_BOTTOM = 0.05;

    static final double BOX_STROKE = 0.06;

    // Each missing character shows as an empty box, the way a renderer shows a glyph its fonts lack
    static double draw(PdfCanvas canvas, String text, double size, double x, double baseline, Color color)
            throws IOException {
        Stroke stroke = Stroke.solid((float) (size * BOX_STROKE), color);
        double at = x;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            double w = em(cp) * size;
            if (w > 0 && !Character.isSpaceChar(cp)) {
                double inset = Math.min(size * BOX_INSET, w / 4);
                canvas.rect((float) (at + inset), (float) (baseline - size * BOX_TOP), (float) (w - 2 * inset),
                        (float) (size * (BOX_TOP + BOX_BOTTOM)), null, stroke);
            }
            at += w;
        }
        return at - x;
    }

    static double width(String text, double size) {
        double em = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            em += em(cp);
            i += Character.charCount(cp);
        }
        return em * size;
    }

    static boolean zeroWidth(int cp) {
        return Character.isISOControl(cp) || cp == FontFace.SOFT_HYPHEN || Character.getType(cp) == Character.FORMAT
                || cp >= 0xFE00 && cp <= 0xFE0F || cp >= 0xE0100 && cp <= 0xE01EF;
    }

    static boolean wide(int cp) {
        return cp >= 0x1100 && cp <= 0x115F || cp >= 0x2E80 && cp <= 0x303E || cp >= 0x3041 && cp <= 0x33FF
                || cp >= 0x3400 && cp <= 0x4DBF || cp >= 0x4E00 && cp <= 0x9FFF || cp >= 0xA000 && cp <= 0xA4CF
                || cp >= 0xAC00 && cp <= 0xD7A3 || cp >= 0xF900 && cp <= 0xFAFF || cp >= 0xFE30 && cp <= 0xFE4F
                || cp >= 0xFF00 && cp <= 0xFF60 || cp >= 0xFFE0 && cp <= 0xFFE6 || cp >= 0x1F300 && cp <= 0x1F64F
                || cp >= 0x1F900 && cp <= 0x1F9FF || cp >= 0x20000 && cp <= 0x3FFFD;
    }

    static boolean noStart(int cp) {
        return "、。，．：；？！）」』】〕〉》〗〙〛’”ヽヾーァィゥェォッャュョヮヵヶぁぃぅぇぉっゃゅょゎゕゖ々〻‐゠〜～）］｝"
                .indexOf(cp) >= 0 || cp == ')' || cp == ']' || cp == '}' || cp == ',' || cp == '.' || cp == ';'
                || cp == ':' || cp == '!' || cp == '?' || cp == '%';
    }

    static boolean noEnd(int cp) {
        return "（「『【〔〈《〖〘〚‘“（［｛".indexOf(cp) >= 0 || cp == '(' || cp == '[' || cp == '{';
    }

    static boolean breakBetween(int prev, int cp) {
        if (prev == ' ' || cp == ' ' || !(wide(prev) || wide(cp))) {
            return false;
        }
        return !noStart(cp) && !noEnd(prev);
    }
}
