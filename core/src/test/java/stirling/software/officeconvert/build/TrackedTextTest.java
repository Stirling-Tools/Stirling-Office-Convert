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

class TrackedTextTest {

    private static final FontInfo CALIBRI = new FontInfo("Calibri", false, false, false, false, false, "Calibri", false);

    private static float spacing(float advance) {
        List<Glyph> glyphs = new ArrayList<>();
        float x = 72;
        for (String word : "Annual Operations Report".split(" ")) {
            for (char c : word.toCharArray()) {
                glyphs.add(new Glyph(String.valueOf(c), x, 14f, 100, 28, 20f, 6f, CALIBRI, 0, glyphs.size(), 6.3f, false,
                        false));
                x += advance;
            }
            x += 6.3f;
        }
        Paragraph p = new Paragraph();
        new RunBuilder(true, new DocStats()).fill(p, LineBuilder.build(glyphs), 0, 72, 540, 28);
        for (Inline in : p.inlines) {
            if (in instanceof Inline.Text t) {
                return t.style().spacing();
            }
        }
        return Float.NaN;
    }

    @Test
    void glyphsSetHalfAPointCloserThanTheirWidthsKeepThatSpacing() {
        assertEquals(-0.5f, spacing(13.5f), 0.001f);
    }

    @Test
    void glyphsSetAtTheirWidthsKeepNone() {
        assertEquals(0f, spacing(14f), 0.001f);
    }

    @Test
    void aSlightPairKerningIsNotTracking() {
        assertEquals(0f, spacing(13.9f), 0.001f);
    }
}
