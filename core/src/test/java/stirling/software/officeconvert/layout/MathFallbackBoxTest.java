package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;

class MathFallbackBoxTest {

    private static final FontInfo CAMBRIA = new FontInfo("Cambria Math", false, false, true, false, false, "CambriaMath", true);
    private static final FontInfo CMEX = new FontInfo("Cambria Math", false, false, true, false, true, "CMEX10", true);
    private static final FontInfo CMR = new FontInfo("Times New Roman", false, false, true, false, false, "CMR10", true);

    private static Word word(String text, float x, float size, FontInfo font, boolean rebuilt) {
        List<Glyph> glyphs = new ArrayList<>();
        for (char c : text.toCharArray()) {
            Glyph g = new Glyph(String.valueOf(c), x, size * 0.8f, 200, size, size * 0.75f, size * 0.25f, font, 0, 0,
                    size * 0.25f, false, false);
            if (rebuilt) {
                g.math = "<m:r/>";
            }
            glyphs.add(g);
            x += size * 0.8f;
        }
        return new Word(glyphs);
    }

    private static Line line(Word... words) {
        return new Line(List.of(words), new byte[words.length]);
    }

    @Test
    void theFallbackBoxLeavesARebuiltEquationOnTheSameLineOut() {
        Line l = line(word("x+y", 72, 14, CMR, true), word("∮∯", 200, 14, CAMBRIA, false));
        List<Box> boxes = MathFinder.displayMath(List.of(l), 11);
        assertEquals(1, boxes.size());
        assertTrue(boxes.getFirst().x() > 150, boxes.toString());
    }

    @Test
    void aLoneDisplayIntegralStillGetsAFallback() {
        Line l = line(word("∫", 200, 30, CMEX, false));
        assertEquals(1, MathFinder.displayMath(List.of(l), 11).size());
    }

    @Test
    void aLoneInlineSymbolGetsNoFallback() {
        Line l = line(word("∫", 200, 30 * 0.4f, CMEX, false));
        assertTrue(MathFinder.displayMath(List.of(l), 11).isEmpty());
    }
}
