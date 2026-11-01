package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;

class PictureOverWordsTest {

    private static Line line(String text, float x, float baseline) {
        List<Glyph> glyphs = new ArrayList<>();
        float advance = 5;
        for (char c : text.toCharArray()) {
            glyphs.add(new Glyph(String.valueOf(c), x, advance, baseline, 10, 7.5f, 2.5f, FontInfo.DEFAULT, 0,
                    glyphs.size(), 2.5f, false, false));
            x += advance;
        }
        return LineBuilder.build(glyphs).getFirst();
    }

    private static PageLayout.FloatItem arranged(Box picture, List<Line> lines) {
        List<PageLayout.Item> out = new ArrayList<>();
        PictureArranger.arrange(List.of(new PageLayout.FigureItem(picture, false)), lines, out, new ArrayList<>(),
                false, 0, new Box(0, 0, 612, 792), new float[] {50, 300});
        assertEquals(1, out.size(), out.toString());
        return (PageLayout.FloatItem) out.getFirst();
    }

    @Test
    void aPicturePaintedOverTheWordsOfALineIsLaidOverTheText() {
        Box icon = new Box(133, 566, 160, 592);
        List<Line> lines = List.of(line("1. Introduction", 54, 577), line("Dynamic languages such as these", 54, 592));
        assertTrue(arranged(icon, lines).overlay());
    }

    @Test
    void aPictureBesideItsLinesStillPushesTheTextAside() {
        Box picture = new Box(200, 560, 260, 600);
        List<Line> lines = List.of(line("short line", 54, 575), line("another one", 54, 592));
        assertFalse(arranged(picture, lines).overlay());
    }

    @Test
    void oneWordGrazingAPictureDoesNotStopTheLinesBesideItFromWrapping() {
        Box picture = new Box(200, 560, 300, 640);
        List<Line> lines = List.of(line("left text", 54, 575), line("left text", 54, 590), line("left text", 54, 605),
                line("grazing word", 150, 620));
        assertFalse(arranged(picture, lines).overlay());
    }
}
