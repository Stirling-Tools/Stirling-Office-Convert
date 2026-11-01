package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.extract.PageGraphics.Rule;

class FramedUnderlineTest {

    private static Line line(String text, float x, float baseline, float size) {
        List<Glyph> glyphs = new ArrayList<>();
        float width = size * 0.5f;
        for (char c : text.toCharArray()) {
            glyphs.add(new Glyph(String.valueOf(c), x, width, baseline, size, size * 0.75f, size * 0.25f,
                    FontInfo.DEFAULT, 0, glyphs.size(), width / 2, false, false));
            x += width;
        }
        return LineBuilder.build(glyphs).getFirst();
    }

    private static boolean underlined(Line line) {
        return line.words.stream().flatMap(w -> w.glyphs.stream()).allMatch(g -> g.underline);
    }

    @Test
    void aRuleJustBelowLargeTextIsItsUnderline() {
        Line text = line("C-1-1", 100, 200, 60);
        List<Rule> rules = new ArrayList<>(List.of(new Rule(true, 214, 98, 260, 1, 0)));
        Decorations.underlines(List.of(text), rules);
        assertTrue(underlined(text));
        assertTrue(rules.isEmpty());
    }

    @Test
    void theBottomEdgeOfABoxAroundTheTextStaysAnEdge() {
        Line text = line("C-1-1", 100, 200, 60);
        List<Rule> rules = new ArrayList<>(List.of(new Rule(true, 214, 98, 260, 1, 0),
                new Rule(false, 98, 150, 214, 1, 0), new Rule(false, 260, 150, 214, 1, 0),
                new Rule(true, 150, 98, 260, 1, 0)));
        Decorations.underlines(List.of(text), rules);
        assertFalse(underlined(text));
        assertEquals(4, rules.size());
    }
}
