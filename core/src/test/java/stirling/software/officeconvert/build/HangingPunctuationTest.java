package stirling.software.officeconvert.build;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.layout.LineBuilder;

class HangingPunctuationTest {

    private static final FontInfo FONT = new FontInfo("SimSun", false, false, false, false, false, "SimSun", false);

    @Test
    void eastAsianLinesThatNeverEndInPunctuationKeepItIn() {
        assertTrue(RunBuilder.keepsPunctuationIn(LineBuilder.build(glyphs("人人生而自由在尊严和权利上", "一律平等。"))));
    }

    @Test
    void aLineEndingInPunctuationLetsItHang() {
        assertFalse(RunBuilder.keepsPunctuationIn(LineBuilder.build(glyphs("人人生而自由在尊严和权利。", "一律平等。"))));
    }

    @Test
    void latinParagraphsAreLeftAlone() {
        assertFalse(RunBuilder.keepsPunctuationIn(LineBuilder.build(glyphs("Everyone has the right", "to life."))));
    }

    private static List<Glyph> glyphs(String... lines) {
        List<Glyph> out = new ArrayList<>();
        float y = 100;
        for (String line : lines) {
            float x = 72;
            for (char c : line.toCharArray()) {
                float w = c == ' ' ? 3f : c < 0x3000 ? 5f : 10f;
                out.add(new Glyph(String.valueOf(c), x, w, y, 10, 8.8f, 1.2f, FONT, 0, out.size(), 3f, false, false));
                x += w;
            }
            y += 14;
        }
        return out;
    }
}
