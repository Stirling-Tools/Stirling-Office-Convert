package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;

class BreakUnitsTest {

    private static final FontInfo FONT = new FontInfo("Arial", false, false, false, false, false, "NotoSansMyanmar", true);

    private static Word word(String text) {
        List<Glyph> glyphs = new ArrayList<>();
        float x = 72;
        for (int i = 0; i < text.length(); i++) {
            glyphs.add(new Glyph(String.valueOf(text.charAt(i)), x, 5, 100, 10, 7.5f, 2.5f, FONT, 0, i, 3, false, false));
            x += 5;
        }
        return new Word(glyphs);
    }

    @Test
    void myanmarAndKhmerBreakAfterTheirFirstSyllable() {
        assertEquals(10f, BreakUnits.syllable(word("\u101C\u1030\u1010\u102D\u102F\u1004\u103A\u1038")), 0.01f);
        assertEquals(5f, BreakUnits.syllable(word("\u1798\u1793\u17BB\u179F\u17D2\u179F")), 0.01f);
        assertEquals(15f, BreakUnits.syllable(word("\u179F\u17D2\u179F\u1798")), 0.01f);
        assertEquals(15f, BreakUnits.first(word("\u0E17\u0E35\u0E48\u0E01\u0E32\u0E23")), 0.01f);
    }
}
