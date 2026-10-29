package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.model.Paragraph.Align;

class ParagraphMeasureTest {

    private static final FontInfo CALIBRI = new FontInfo("Calibri", false, false, true, false, false, "Calibri", true);

    private static final float RIGHT = 192;

    private static void row(List<Glyph> out, int r, float gap, String... words) {
        float x = 72;
        for (String w : words) {
            for (char c : w.toCharArray()) {
                float width = c == ',' ? 2.2f : 5f;
                out.add(new Glyph(String.valueOf(c), x, width, 100 + 12 * r, 10, 7f, 2f, CALIBRI, 0, out.size(), 2.5f,
                        false, false));
                x += width;
            }
            x += gap;
        }
    }

    private static ParaDraft measure(List<Glyph> glyphs) {
        List<Line> lines = LineBuilder.build(glyphs);
        ParaDraft p = new ParaDraft(72, 200);
        p.lines.addAll(lines);
        new ParagraphMeasure(new DocStats()).measure(new LineRoom(lines, 72, 200, List.of()), p);
        return p;
    }

    @Test
    void aCommaHangingPastTheMeasureKeepsTheParagraphJustified() {
        List<Glyph> glyphs = new ArrayList<>();
        row(glyphs, 0, 2.5f, "aaaaa", "aaaa", "aaaa", "aaaa", "aaaaa");
        row(glyphs, 1, 2.5f, "aaaa", "aaaaa", "aaaa", "aaaaa", "aaaa");
        row(glyphs, 2, 3.75f, "aaaa", "aaaa", "aaaaa", "aaaa", "aaaa,");
        row(glyphs, 3, 2.5f, "aaaa", "aaa");
        ParaDraft p = measure(glyphs);
        assertEquals(RIGHT, p.lines.get(0).right, 0.01f);
        assertEquals(RIGHT + 2.2f, p.lines.get(2).right, 0.01f);
        assertEquals(Align.JUSTIFY, p.align);
    }

    @Test
    void aWordRunningPastTheMeasureStillMakesItRagged() {
        List<Glyph> glyphs = new ArrayList<>();
        row(glyphs, 0, 2.5f, "aaaaa", "aaaa", "aaaa", "aaaa", "aaaaa");
        row(glyphs, 1, 2.5f, "aaaa", "aaaaa", "aaaa", "aaaaa", "aaaa");
        row(glyphs, 2, 3.05f, "aaaaa", "aaaa", "aaaa", "aaaa", "aaaaa");
        row(glyphs, 3, 2.5f, "aaaa", "aaa");
        ParaDraft p = measure(glyphs);
        assertEquals(RIGHT + 2.2f, p.lines.get(2).right, 0.01f);
        assertEquals(Align.LEFT, p.align);
    }
}
