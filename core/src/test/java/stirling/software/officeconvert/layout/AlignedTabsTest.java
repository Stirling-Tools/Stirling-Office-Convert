package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;

class AlignedTabsTest {

    private static final FontInfo FONT = new FontInfo("Arial", false, false, false, false, false, "ArialMT", false);

    @Test
    void threeWordLinesTabToAnAlignedColumn() {
        List<Line> lines = new ArrayList<>();
        for (int row = 0; row < 3; row++) {
            float baseline = 100 + row * 14;
            lines.add(LineGroups.fromWords(List.of(word("Item", 72, baseline), word("one", 95, baseline),
                    word("Blue", 122, baseline))));
        }
        for (Line l : LineGroups.alignedTabs(lines)) {
            assertEquals("Item one\tBlue", l.text());
        }
    }

    private static Word word(String text, float x, float baseline) {
        List<Glyph> glyphs = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            glyphs.add(new Glyph(String.valueOf(text.charAt(i)), x + i * 5, 5, baseline, 10, 7.5f, 2.5f, FONT, 0, i, 3,
                    false, false));
        }
        return new Word(glyphs);
    }
}
