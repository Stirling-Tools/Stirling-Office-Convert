package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.extract.PageData;
import stirling.software.officeconvert.extract.PageGraphics.Fill;
import stirling.software.officeconvert.table.TableDetection;

class PageAnalyzerFixesTest {

    private static final FontInfo TIMES = new FontInfo("Times New Roman", false, false, true, false, false, "NimbusRomNo9L-Regu", true);

    private static Glyph glyph(String text, float x, float baseline, float size, int seq) {
        return new Glyph(text, x, size * 0.7f, baseline, size, size * 0.7f, size * 0.2f, TIMES, 0, seq, size * 0.25f,
                false, false);
    }

    @Test
    void aDropCapKeepsALinkThatCoversOnlyWhereItWasDrawn() {
        List<Glyph> glyphs = new ArrayList<>();
        glyphs.add(glyph("T", 72, 130, 40, 0));
        for (float baseline : new float[] {110, 122, 130}) {
            float x = 101;
            for (char c : "he quick brown fox".toCharArray()) {
                glyphs.add(glyph(String.valueOf(c), x, baseline, 10, glyphs.size()));
                x += 7;
            }
        }
        PageData.Link link = new PageData.Link(72, 115, 100, 135, "https://example.com/", -1);
        List<Glyph> out = PageAnalyzer.foldDropCaps(glyphs, List.of(link));
        assertNotSame(glyphs.getFirst(), out.getFirst(), "the cap was folded");
        assertEquals("https://example.com/", out.getFirst().link);
    }

    private static TableDetection.Found table(float left, float top, float right, float bottom, int fill) {
        TableDetection.FoundCell cell = new TableDetection.FoundCell(0, 0, 1, 1, left, top, right, bottom, false, false,
                false, false, fill, null, null, "x");
        return new TableDetection.Found(1, 1, new float[] {left, right}, false, 0, 0f, List.of(cell), left, top, right,
                bottom);
    }

    @Test
    void cellShadingWithPaddingIsDroppedButAWiderPanelIsKept() {
        TableDetection.Found t = table(100, 100, 300, 140, 0xEEEEEE);
        Fill padded = new Fill(96, 96, 304, 144, 0xEEEEEE);
        Fill panel = new Fill(40, 60, 560, 400, 0xEEEEEE);
        List<Fill> out = PageAnalyzer.withoutCellShading(List.of(padded, panel), List.of(t));
        assertEquals(List.of(panel), out);
    }
}
