package stirling.software.officeconvert.build;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.LineBuilder;

class PhraseBreakTest {

    private static final FontInfo FONT = new FontInfo("Myanmar Text", false, false, false, false, false, "NotoSansMyanmar", false);

    @Test
    void aShortMyanmarLineEndedAtAPhraseSpace() {
        String syllables = "\u1000\u1001\u1002\u1003\u1004".repeat(6);
        List<Line> lines = LineBuilder.build(glyphs(syllables, syllables.substring(0, 20), syllables.substring(0, 10)));
        assertFalse(RunBuilder.phraseBreak(lines, 1), "a full line broke inside a phrase");
        assertTrue(RunBuilder.phraseBreak(lines, 2), "the next syllable would have fitted, so a space ended the line");
    }

    @Test
    void tibetanLinesEndingInAShadAreFollowedByASpace() {
        String tsheg = "\u0F40\u0F0B".repeat(20);
        List<Line> lines = LineBuilder.build(glyphs(tsheg + "\u0F0D", tsheg, "\u0F40\u0F0D"));
        assertTrue(RunBuilder.phraseBreak(lines, 1));
        assertFalse(RunBuilder.phraseBreak(lines, 2));
    }

    @Test
    void thaiLinesShortenedBesideAPictureGetNoPhraseSpace() {
        String word = "\u0E20\u0E32\u0E29\u0E32\u0E44\u0E17\u0E22";
        String narrow = word.repeat(3);
        String wide = word.repeat(6);
        List<Line> lines = LineBuilder.build(glyphs(narrow, narrow, narrow, narrow, narrow, narrow, wide, narrow, wide, word));
        lines.subList(0, 6).forEach(l -> l.narrowed = true);
        for (int li = 1; li <= 7; li++) {
            assertFalse(RunBuilder.phraseBreak(lines, li), "join " + li + " is only a narrower wrap");
        }
        assertTrue(RunBuilder.phraseBreak(lines, 8), "a short line between two full ones ended at a phrase");
    }

    @Test
    void centredThaiLinesGetNoPhraseSpace() {
        String word = "\u0E20\u0E32\u0E29\u0E32\u0E44\u0E17\u0E22";
        List<Glyph> glyphs = new ArrayList<>();
        int[] counts = {6, 3, 6, 4, 1};
        for (int li = 0; li < counts.length; li++) {
            String line = word.repeat(counts[li]);
            float x = 306 - line.length() * 5f;
            for (char c : line.toCharArray()) {
                glyphs.add(new Glyph(String.valueOf(c), x, 10f, 100 + 20 * li, 12, 9f, 3f, FONT, 0, glyphs.size(), 3f, false, false));
                x += 10f;
            }
        }
        List<Line> lines = LineBuilder.build(glyphs);
        for (int li = 2; li < lines.size(); li++) {
            assertFalse(RunBuilder.phraseBreak(lines, li), "centred join " + li);
        }
    }

    @Test
    void chineseLinesNeverGetAPhraseSpace() {
        String han = "\u4EBA\u4EBA\u751F\u800C\u81EA\u7531".repeat(6);
        assertFalse(RunBuilder.phraseBreak(LineBuilder.build(glyphs(han, han.substring(0, 6), han.substring(0, 3))), 2));
    }

    private static List<Glyph> glyphs(String... lines) {
        List<Glyph> out = new ArrayList<>();
        float y = 100;
        for (String line : lines) {
            float x = 72;
            for (char c : line.toCharArray()) {
                out.add(new Glyph(String.valueOf(c), x, 10f, y, 12, 9f, 3f, FONT, 0, out.size(), 3f, false, false));
                x += 10f;
            }
            y += 20;
        }
        return out;
    }
}
