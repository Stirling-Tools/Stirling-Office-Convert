package stirling.software.officeconvert.build;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.layout.DocStats;
import stirling.software.officeconvert.layout.LineBuilder;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;

class CondensedDisplayTextTest {

    private static final FontInfo ARIAL = new FontInfo("Arial", false, false, false, false, false, "ArialMT", false);

    private static final FontInfo CALIBRI = new FontInfo("Calibri", false, false, false, false, false, "Calibri", false);

    private static float spacing(FontInfo font, float size, float width, float advance) {
        List<Glyph> glyphs = new ArrayList<>();
        float x = 72;
        for (int i = 0; i < 6; i++) {
            glyphs.add(new Glyph("1", x, width, 400, size, 0.7f * size, 0.2f * size, font, 0, i, 0.28f * size, false, false));
            x += advance;
        }
        Paragraph p = new Paragraph();
        new RunBuilder(true, new DocStats()).fill(p, LineBuilder.build(glyphs), 0, 72, 540, size);
        for (Inline in : p.inlines) {
            if (in instanceof Inline.Text t) {
                return t.style().spacing();
            }
        }
        return Float.NaN;
    }

    @Test
    void largeMeasuredTextSetTighterThanItsWidthsCondensesByTheFullAmount() {
        assertEquals(-4f, spacing(ARIAL, 100, 55.6f, 51.6f), 0.001f);
    }

    @Test
    void largeTrackedTextCondensesByTheFullAmount() {
        assertEquals(-4f, spacing(CALIBRI, 100, 50f, 46f), 0.001f);
    }

    @Test
    void bodyTextKeepsTheTwoPointLimit() {
        assertEquals(-2f, spacing(CALIBRI, 11, 6f, 3f), 0.001f);
    }
}
