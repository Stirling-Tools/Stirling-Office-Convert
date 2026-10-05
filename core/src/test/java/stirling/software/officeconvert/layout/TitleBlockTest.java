package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;

class TitleBlockTest {

    private static final FontInfo TIMES = new FontInfo("Times New Roman", false, false, true, false, false, "NimbusRomNo9L-Regu", true);

    private static Word word(String text, float x, float baseline, float size, int vertAlign) {
        List<Glyph> glyphs = new ArrayList<>();
        for (char c : text.toCharArray()) {
            Glyph g = new Glyph(String.valueOf(c), x, size * 0.5f, baseline, size, size * 0.7f, size * 0.2f, TIMES, 0, 0,
                    size * 0.25f, false, false);
            g.vertAlign = vertAlign;
            glyphs.add(g);
            x += size * 0.5f;
        }
        return new Word(glyphs);
    }

    private static List<Line> lines(String... rows) {
        List<Glyph> glyphs = new ArrayList<>();
        for (int r = 0; r < rows.length; r++) {
            float x = 72;
            for (char c : rows[r].toCharArray()) {
                glyphs.add(new Glyph(String.valueOf(c), x, 5f, 100 + 12 * r, 10, 7f, 2f, TIMES, 0, glyphs.size(), 2.5f,
                        false, false));
                x += 5f;
            }
        }
        return LineBuilder.build(glyphs);
    }

    @Test
    void aSecondFootnoteMarkStaysOnItsAuthorLine() {
        Word name = word("Gomez", 100, 294, 10, 0);
        Word star = word("*", name.right, 290, 7, 1);
        Word dagger = word("+", star.right + 3.2f, 290, 7, 1);
        Word affiliation = word("Toronto", 100, 305, 10, 0);
        List<Line> rows = LineGroups.linesOf(List.of(dagger, affiliation, star, name));
        assertEquals(2, rows.size());
        assertEquals("Gomez * +", rows.getFirst().text());
        assertEquals("Toronto", rows.getLast().text());
    }

    @Test
    void aLoneRaisedMarkFarFromTextKeepsItsOwnLine() {
        Word name = word("Gomez", 100, 294, 10, 0);
        Word far = word("+", 300, 290, 7, 1);
        assertEquals(2, LineGroups.linesOf(List.of(name, far)).size());
    }

    @Test
    void compoundHyphensAtALineEndAreKept() {
        DocStats stats = new DocStats();
        assertTrue(stats.keepsHyphen("the WMT 2014 English-", "to-German"));
        assertTrue(stats.keepsHyphen("a new state-of-the-", "art"));
        assertFalse(stats.keepsHyphen("sequence trans-", "duction"));
    }

    @Test
    void protrudingPunctuationDoesNotWidenTheMeasure() {
        List<Line> rows = lines("aaaaaaaaaa aaaaaaaaaa", "aaaaaaaaaa aaaaaaaaa-", "aaaaaaaaaa aaaaaaaaaa", "aaaa");
        float max = 0;
        for (Line l : rows) {
            max = Math.max(max, l.right);
        }
        float edge = Protrusion.edge(rows, max + 1.5f);
        assertEquals(rows.getFirst().right, edge, 0.01f);
        assertEquals(max + 3f, Protrusion.edge(rows, max + 3f), 0.01f, "too far out to be protrusion");
    }
}
