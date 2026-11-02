package stirling.software.officeconvert.extract;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.fontbox.ttf.GlyphData;
import org.apache.fontbox.ttf.GlyphTable;
import org.apache.fontbox.ttf.OS2WindowsMetricsTable;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

final class OutlineScale {

    private static final String FLAT_CAPITALS = "HEFIKLTNMZXUVWYBDPR";

    private static final int SAMPLE = 7;

    private static final int AGREEING = 3;

    private static final float AGREEMENT = 0.03f;

    private static final float TOLERANCE = 0.08f;

    private static final float LOW = 0.5f;

    private static final float HIGH = 1.6f;

    private static final int SIMPLE_CODES = 256;

    private static final int MAX_CODES = 0x10000;

    private OutlineScale() {}

    static float of(PDFont font) {
        if (font == null || !font.isEmbedded()) {
            return 1f;
        }
        try {
            return scale(font);
        } catch (IOException | RuntimeException e) {
            return 1f;
        }
    }

    private static float scale(PDFont font) throws IOException {
        TrueTypeFont ttf = trueType(font);
        if (ttf == null) {
            return 1f;
        }
        OS2WindowsMetricsTable os2 = ttf.getOS2Windows();
        GlyphTable glyphs = ttf.getGlyph();
        if (os2 == null || os2.getVersion() < 2 || os2.getCapHeight() <= 0 || glyphs == null) {
            return 1f;
        }
        List<Integer> tops = new ArrayList<>();
        int limit = font instanceof PDType0Font ? Math.min(MAX_CODES, Math.max(SIMPLE_CODES, 2 * ttf.getNumberOfGlyphs()))
                : SIMPLE_CODES;
        for (int code = 0; code < limit && tops.size() < SAMPLE; code++) {
            String text = font.toUnicode(code);
            if (text == null || text.length() != 1 || FLAT_CAPITALS.indexOf(text.charAt(0)) < 0) {
                continue;
            }
            int gid = gid(font, code);
            GlyphData glyph = gid > 0 && gid < ttf.getNumberOfGlyphs() ? glyphs.getGlyph(gid) : null;
            if (glyph != null && glyph.getNumberOfContours() > 0 && glyph.getYMaximum() > 0) {
                tops.add((int) glyph.getYMaximum());
            }
        }
        if (tops.size() < AGREEING) {
            return 1f;
        }
        tops.sort(null);
        int middle = tops.get(tops.size() / 2);
        long agreeing = tops.stream().filter(t -> Math.abs(t - middle) <= middle * AGREEMENT).count();
        if (agreeing < AGREEING) {
            return 1f;
        }
        float ratio = middle / (float) os2.getCapHeight();
        boolean off = ratio < 1 - TOLERANCE || ratio > 1 + TOLERANCE;
        return off && ratio >= LOW && ratio <= HIGH ? ratio : 1f;
    }

    private static TrueTypeFont trueType(PDFont font) {
        if (font instanceof PDTrueTypeFont tt) {
            return tt.getTrueTypeFont();
        }
        if (font instanceof PDType0Font t0 && t0.getDescendantFont() instanceof PDCIDFontType2 cid) {
            return cid.getTrueTypeFont();
        }
        return null;
    }

    private static int gid(PDFont font, int code) throws IOException {
        if (font instanceof PDTrueTypeFont tt) {
            return tt.codeToGID(code);
        }
        return ((PDCIDFontType2) ((PDType0Font) font).getDescendantFont()).codeToGID(code);
    }
}
