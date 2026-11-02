package stirling.software.officeconvert.table;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.table.PageContent.PdfWord;
import stirling.software.officeconvert.table.PageContent.Ruling;

class RuledColumnGutterTest {

    private static final float[] XS = {40f, 180f, 300f, 400f};

    private static PdfWord word(String text, float x, float baseline) {
        int n = text.length();
        float right = x + n * 5f;
        float[] left = new float[n];
        float[] rightEdges = new float[n];
        String[] glyphs = new String[n];
        for (int i = 0; i < n; i++) {
            left[i] = x + 5f * i;
            rightEdges[i] = x + 5f * (i + 1);
            glyphs[i] = String.valueOf(text.charAt(i));
        }
        return new PdfWord(text, x, right, baseline - 7f, baseline + 2f, baseline, 9f, false, false, 0, 2.5f, left,
                rightEdges, glyphs);
    }

    private static List<TableDetection.Found> detect(String header) {
        List<PdfWord> words = new ArrayList<>();
        List<Ruling> hs = new ArrayList<>();
        List<Ruling> vs = new ArrayList<>();
        int rows = 6;
        float top = 100f;
        float height = 20f;
        for (int r = 0; r <= rows; r++) {
            hs.add(new Ruling(top + r * height, XS[0], XS[XS.length - 1], 0.5f, 0xE0E0E0));
        }
        for (float x : XS) {
            vs.add(new Ruling(x, top, top + rows * height, 0.5f, 0xE0E0E0));
        }
        float baseline = top + 14f;
        words.add(word("Name", 45f, baseline));
        words.add(word("Formula", 185f, baseline));
        words.add(word(header, 335f, baseline));
        for (int r = 1; r < rows; r++) {
            baseline += height;
            words.add(word("Row" + r, 45f, baseline));
            words.add(word("x" + r, 185f, baseline));
            words.add(word("Kind", 305f, baseline));
            words.add(word("#Tag", 360f, baseline));
        }
        return TableDetection.detect(new PageContent(1, 600f, 800f, words, hs, vs, List.of()));
    }

    @Test
    void aHeaderAcrossTheGapKeepsTheRuledColumnWhole() {
        List<TableDetection.Found> found = detect("Type");
        assertEquals(1, found.size());
        assertEquals(3, found.getFirst().cols());
    }

    @Test
    void anOpenGapStillSplitsTheRuledColumn() {
        List<TableDetection.Found> found = detect("T");
        assertEquals(1, found.size());
        assertEquals(4, found.getFirst().cols());
    }
}
