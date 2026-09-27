package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;

class OcrTextTest {

    private static Glyph glyph(String text, float width, float size) {
        return new Glyph(text, 0, width, 100, size, size * 0.75f, size * 0.25f, FontInfo.DEFAULT, 0, 0, size * 0.25f,
                false, false);
    }

    @Test
    void dropsMisreadVerticalMarks() {
        List<Glyph> kept = OcrText.glyphs(List.of(glyph("a", 5, 10), glyph("N", 2, 479), glyph(" ", 0.5f, 10)));

        assertEquals(List.of("a", " "), kept.stream().map(g -> g.text).toList());
    }
}
