package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;

class FormLeadersTest {

    private static void place(List<Glyph> out, String text, float x, float gap) {
        for (char c : text.toCharArray()) {
            if (c != ' ') {
                out.add(new Glyph(String.valueOf(c), x, 4f, 100, 8, 6, 2, FontInfo.DEFAULT, 0, out.size(), 2.2f, false, false));
            }
            x += c == ' ' ? gap : 4f;
        }
    }

    @Test
    void spacedDotsDoNotGlueALabelToItsText() {
        List<Glyph> glyphs = new ArrayList<>();
        place(glyphs, "z", 100, 2.2f);
        place(glyphs, "Add lines", 115, 2.2f);
        place(glyphs, ". . . . . . . . . . . . . . . . . . . .", 160, 4.4f);

        Line line = LineBuilder.join(LineBuilder.build(glyphs));

        assertEquals("z", line.words.getFirst().text);
        assertEquals("Add", line.words.get(1).text);
    }

    @Test
    void trailingDotsBecomeALeaderToTheLastDot() {
        List<Glyph> glyphs = new ArrayList<>();
        place(glyphs, "Wages", 100, 2.2f);
        place(glyphs, ". . . . . .", 140, 4.4f);
        Line line = LineBuilder.join(LineBuilder.build(glyphs));

        Line led = Leaders.trailing(line);

        assertEquals(List.of("Wages", "."), led.words.stream().map(w -> w.text).toList());
        assertEquals(Line.LEADER, led.gaps[1]);
        assertEquals(line.right, led.right, 0.01f);
    }

    @Test
    void linesWithoutTrailingDotsStayAsTheyAre() {
        List<Glyph> glyphs = new ArrayList<>();
        place(glyphs, "Last name", 100, 2.2f);
        Line line = LineBuilder.join(LineBuilder.build(glyphs));

        assertSame(line, Leaders.trailing(line));
    }
}
