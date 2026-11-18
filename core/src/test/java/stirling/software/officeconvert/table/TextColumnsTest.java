package stirling.software.officeconvert.table;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.table.PageContent.PdfWord;

class TextColumnsTest {

    private static PdfWord word(String text, float x, float right, float baseline) {
        int n = text.length();
        float[] left = new float[n];
        float[] rightEdges = new float[n];
        String[] glyphs = new String[n];
        for (int i = 0; i < n; i++) {
            left[i] = x + (right - x) * i / n;
            rightEdges[i] = x + (right - x) * (i + 1) / n;
            glyphs[i] = String.valueOf(text.charAt(i));
        }
        return new PdfWord(text, x, right, baseline - 9f, baseline + 2.5f, baseline, 11f, false, false, 0, 2.8f,
                left, rightEdges, glyphs);
    }

    @Test
    void leftAlignedHeadersOverRightAlignedFiguresKeepTheirOwnColumns() {
        List<PdfWord> words = new ArrayList<>();
        words.add(word("Item", 38.4f, 59.4f, 66f));
        words.add(word("Regional", 135.9f, 175.5f, 66f));
        words.add(word("Strategic", 185.8f, 226.9f, 66f));
        String[][] rows = {{"81,729", "9,882"}, {"75,787", "81,372"}, {"41,535", "58,020"}, {"8,106", "8,020"},
            {"80,643", "27,723"}};
        float baseline = 81f;
        for (String[] r : rows) {
            words.add(word("Row", 38.4f, 60f, baseline));
            float w1 = r[0].length() * 5.2f;
            float w2 = r[1].length() * 5.2f;
            words.add(word(r[0], 181.9f - w1, 181.9f, baseline));
            words.add(word(r[1], 232.7f - w2, 232.7f, baseline));
            baseline += 15f;
        }
        List<ChunkedLine> lines = new ArrayList<>();
        for (TextLine line : TextLine.group(words)) {
            lines.add(ChunkedLine.of(line));
        }

        TextColumns columns = TextColumns.find(lines);
        CellLine header = columns.assign(lines.getFirst());

        assertEquals(3, columns.count());
        assertEquals("Regional", header.cell(1).getFirst().text());
        assertEquals("Strategic", header.cell(2).getFirst().text());
    }

    @Test
    void aLoneWordAfterAShortGapStaysInItsCell() {
        List<PdfWord> words = new ArrayList<>();
        float baseline = 66f;
        for (int r = 0; r < 4; r++) {
            words.add(word("Left" + r, 0f, 24f + 2 * r, baseline));
            if (r == 2) {
                words.add(word("extra", 35f, 50f, baseline));
            }
            words.add(word("Right" + r, 100f, 130f, baseline));
            baseline += 15f;
        }
        List<ChunkedLine> lines = chunked(words);

        TextColumns columns = TextColumns.find(lines);

        assertEquals(2, columns.count());
        assertEquals(0, columns.indexOf(42f));
    }

    @Test
    void aHeaderOverAnEmptyColumnIsAColumn() {
        List<PdfWord> words = new ArrayList<>();
        words.add(word("Code", 0f, 30f, 66f));
        words.add(word("Notes", 50f, 75f, 66f));
        words.add(word("Qty", 100f, 130f, 66f));
        float baseline = 81f;
        for (int r = 0; r < 4; r++) {
            words.add(word("A" + r, 0f, 30f, baseline));
            words.add(word(String.valueOf(10 + r), 100f, 130f, baseline));
            baseline += 15f;
        }

        TextColumns columns = TextColumns.find(chunked(words));

        assertEquals(3, columns.count());
        assertEquals(1, columns.indexOf(62f));
    }

    private static List<ChunkedLine> chunked(List<PdfWord> words) {
        List<ChunkedLine> lines = new ArrayList<>();
        for (TextLine line : TextLine.group(words)) {
            lines.add(ChunkedLine.of(line));
        }
        return lines;
    }
}
