package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;

class BackdropsTest {

    private static Line line(String text, float x, float baseline) {
        List<Glyph> glyphs = new ArrayList<>();
        float advance = 5;
        for (char c : text.toCharArray()) {
            glyphs.add(new Glyph(String.valueOf(c), x, advance, baseline, 10, 7.5f, 2.5f, FontInfo.DEFAULT, 0,
                    glyphs.size(), 2.5f, false, false));
            x += advance;
        }
        return LineBuilder.build(glyphs).getFirst();
    }

    @Test
    void shapeBehindAColumnIsABackdrop() {
        Box circle = new Box(200, 90, 400, 200);
        List<Line> lines = List.of(line("the column runs over the shape", 100, 110), line("and on past it once more again", 100, 130));
        assertTrue(Backdrops.isBackdrop(circle, lines));
    }

    @Test
    void diagramHoldingItsLabelsIsNot() {
        Box diagram = new Box(100, 90, 400, 200);
        List<Line> labels = List.of(line("Ingest", 120, 110), line("Parse", 220, 110), line("Export", 320, 150));
        assertFalse(Backdrops.isBackdrop(diagram, labels));
    }
}
