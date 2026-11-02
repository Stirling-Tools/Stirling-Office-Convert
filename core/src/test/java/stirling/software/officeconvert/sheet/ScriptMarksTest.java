package stirling.software.officeconvert.sheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.Word;

class ScriptMarksTest {

    private static final FontInfo FONT = new FontInfo("Arial", false, false, false, false, false, "ArialMT", false);

    private static Glyph g(String text, float x, int level) {
        Glyph g = new Glyph(text, x, 4, 100, level == 0 ? 8 : 5.5f, 6, 2, FONT, 0, (int) x, 2.4f, false, false);
        g.vertAlign = level;
        return g;
    }

    @Test
    void scriptsAreMarkedAndSplitIntoRuns() {
        Line line = new Line(List.of(new Word(List.of(g("x", 0, 0), g("i", 4, -1), g("2", 8, 1)))), new byte[0]);
        String marked = ScriptMarks.mark(line, "xi2");
        assertEquals("xi2", ScriptMarks.strip(marked));
        assertEquals(List.of(new ScriptMarks.Run("x", 0), new ScriptMarks.Run("i", -1), new ScriptMarks.Run("2", 1)),
                ScriptMarks.runs(marked));
    }

    @Test
    void plainTextIsUnchanged() {
        Line line = new Line(List.of(new Word(List.of(g("a", 0, 0), g("b", 4, 0)))), new byte[0]);
        assertEquals("ab", ScriptMarks.mark(line, "ab"));
        assertFalse(ScriptMarks.has("ab"));
    }

    @Test
    void textThatDoesNotMatchTheGlyphsIsLeftUnmarked() {
        Line line = new Line(List.of(new Word(List.of(g("x", 0, 0), g("i", 4, -1)))), new byte[0]);
        assertEquals("yz", ScriptMarks.mark(line, "yz"));
    }

    @Test
    void markersTakeNoWidth() {
        String marked = "x" + ScriptMarks.SUB + "i" + ScriptMarks.END + "y" + ScriptMarks.SUPER + "2" + ScriptMarks.END;
        assertEquals(TextWidth.of("xiy2", 10, false), TextWidth.of(marked, 10, false));
        assertEquals(TextWidth.lines("xiy2", 10, false, 4), TextWidth.lines(marked, 10, false, 4));
    }
}
