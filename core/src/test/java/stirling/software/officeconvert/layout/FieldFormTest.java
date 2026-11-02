package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.extract.PageData;
import stirling.software.officeconvert.extract.PageGraphics;

class FieldFormTest {

    private static Line line(String text, float x, float baseline) {
        List<Glyph> glyphs = new ArrayList<>();
        for (char c : text.toCharArray()) {
            glyphs.add(new Glyph(String.valueOf(c), x, 5, baseline, 10, 7.5f, 2.5f, FontInfo.DEFAULT, 0,
                    glyphs.size(), 2.5f, false, false));
            x += 5;
        }
        return LineBuilder.build(glyphs).getFirst();
    }

    private static PageData page(List<PageData.Widget> widgets) {
        return new PageData(0, 612, 792, 0, List.of(), List.of(), List.of(),
                new PageGraphics(List.of(), List.of(), List.of(), List.of()), List.of(), widgets);
    }

    private static List<PageData.Widget> fields(boolean notes) {
        List<PageData.Widget> out = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            out.add(new PageData.Widget(72 + i * 80, 100, 142 + i * 80, 130, false, notes));
        }
        return out;
    }

    private static List<Line> values() {
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            out.add(line("Value" + i, 80 + i * 80, 120));
        }
        out.add(line("Label", 72, 60));
        return out;
    }

    @Test
    void pageWhoseTextIsMostlyFieldValuesIsAForm() {
        assertTrue(FormPage.looksLikeForm(page(fields(false)), values(), List.of()));
    }

    @Test
    void textInsideOtherAnnotationsDoesNotMakeAForm() {
        assertFalse(FormPage.looksLikeForm(page(fields(true)), values(), List.of()));
    }

    @Test
    void fieldsBesideBodyTextDoNotMakeAForm() {
        List<Line> lines = new ArrayList<>(values());
        for (int i = 0; i < 12; i++) {
            lines.add(line("An ordinary sentence of running body text goes here", 72, 300 + i * 14));
        }
        assertFalse(FormPage.looksLikeForm(page(fields(false)), lines, List.of()));
    }
}
