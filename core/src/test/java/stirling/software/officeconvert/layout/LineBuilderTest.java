package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;

class LineBuilderTest {

    private static final float ADVANCE = 5f;
    private static final float SPACE = 2.5f;

    private static void place(List<Glyph> out, String text, float x, float baseline, float size) {
        float scale = size / 10f;
        for (char c : text.toCharArray()) {
            float w = (c == ' ' ? SPACE : ADVANCE) * scale;
            out.add(new Glyph(String.valueOf(c), x, w, baseline, size, size * 0.75f, size * 0.25f,
                    FontInfo.DEFAULT, 0, out.size(), SPACE * scale, false, false));
            x += w;
        }
    }

    @Test
    void groupsGlyphsIntoWordsAndLines() {
        List<Glyph> glyphs = new ArrayList<>();
        place(glyphs, "second line", 72, 114, 10);
        place(glyphs, "Hello world", 72, 100, 10);

        List<Line> lines = LineBuilder.build(glyphs);

        assertEquals(2, lines.size());
        assertEquals("Hello world", lines.get(0).text());
        assertEquals("second line", lines.get(1).text());
        assertEquals(SPACE, lines.get(0).drawnSpace, 0.01f);
    }

    @Test
    void splitsAtAGutter() {
        List<Glyph> glyphs = new ArrayList<>();
        place(glyphs, "Left column", 72, 100, 10);
        place(glyphs, "Right column", 320, 100, 10);

        List<Line> lines = LineBuilder.build(glyphs);

        assertEquals(2, lines.size());
        assertEquals("Left column", lines.get(0).text());
        assertEquals(320, lines.get(1).x, 0.01f);
    }

    @Test
    void keepsDotLeadersInOneLine() {
        List<Glyph> glyphs = new ArrayList<>();
        place(glyphs, "Introduction", 72, 100, 10);
        place(glyphs, "..........", 160, 100, 10);
        place(glyphs, "7", 260, 100, 10);

        List<Line> lines = LineBuilder.build(glyphs);

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).text().startsWith("Introduction"));
        assertTrue(lines.get(0).text().endsWith("7"));
    }

    private static void placeTracked(List<Glyph> out, String text, float x, float baseline, float tracking) {
        for (char c : text.toCharArray()) {
            float w = c == ' ' ? SPACE : ADVANCE;
            out.add(new Glyph(String.valueOf(c), x, w, baseline, 10, 7.5f, 2.5f, FontInfo.DEFAULT, 0, out.size(), SPACE,
                    false, false));
            x += w + tracking;
        }
    }

    @Test
    void keepsWordsWholeInLetterSpacedText() {
        List<Glyph> glyphs = new ArrayList<>();
        placeTracked(glyphs, "Our wedding will be outdoors", 72, 100, 2f);

        List<Line> lines = LineBuilder.build(glyphs);

        assertEquals(1, lines.size());
        assertEquals("Our wedding will be outdoors", lines.get(0).text());
    }

    @Test
    void marksRaisedSmallGlyphsAsSuperscript() {
        List<Glyph> glyphs = new ArrayList<>();
        place(glyphs, "E=mc", 72, 100, 10);
        place(glyphs, "2", 92, 96, 6);
        place(glyphs, " is famous", 95, 100, 10);

        List<Line> lines = LineBuilder.build(glyphs);

        assertEquals(1, lines.size());
        Glyph two = glyphs.stream().filter(g -> g.text.equals("2")).findFirst().orElseThrow();
        assertEquals(1, two.vertAlign);
    }
}
