package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Arrays;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HiddenRowsTest {

    @TempDir
    Path dir;

    private static final String STYLES = "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
            + "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill"
            + " patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFFFF00\"/>"
            + "</patternFill></fill></fills><borders count=\"3\"><border/><border><top style=\"thick\"/></border><border"
            + " diagonalDown=\"1\"><diagonal style=\"thick\"/></border></borders><cellStyleXfs count=\"1\"><xf"
            + " numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"4\"><xf"
            + " numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"0\""
            + " fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFill=\"1\"/><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\""
            + " borderId=\"1\" xfId=\"0\" applyBorder=\"1\"/><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"2\""
            + " xfId=\"0\" applyBorder=\"1\"/></cellXfs>";

    private static String text(int r) {
        return "<row r=\"" + r + "\"><c r=\"A" + r + "\" t=\"inlineStr\"><is><t>Row" + r + "</t></is></c></row>";
    }

    private int[] pixels(String name, String sheet) throws Exception {
        byte[] xlsx = new RawXlsx().styles(STYLES).sheet("S", sheet).bytes();
        XlsxTesting.Converted c = XlsxTesting.convert(dir, name, xlsx);
        assertEquals(1, c.pages().size());
        try (PDDocument doc = Loader.loadPDF(dir.resolve(name + ".pdf").toFile())) {
            BufferedImage img = new PDFRenderer(doc).renderImage(0, 1.5f);
            return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        }
    }

    private static boolean yellow(int[] px) {
        for (int p : px) {
            if ((p & 0xFFFFFF) == 0xFFFF00) {
                return true;
            }
        }
        return false;
    }

    @Test
    void blankCellsOfHiddenRowsDrawNothing() throws Exception {
        StringBuilder styledRows = new StringBuilder();
        StringBuilder bareRows = new StringBuilder();
        for (int r = 3; r <= 20_000; r++) {
            styledRows.append("<row r=\"").append(r).append("\" hidden=\"1\"><c r=\"B").append(r)
                    .append("\" s=\"1\"/><c r=\"C").append(r).append("\" s=\"3\"/></row>");
            bareRows.append("<row r=\"").append(r).append("\" hidden=\"1\"/>");
        }
        int[] plain = pixels("plain.xlsx", "<sheetData>" + text(1) + text(2) + "</sheetData>");
        int[] styled = pixels("styled.xlsx", "<sheetData>" + text(1) + text(2) + styledRows + text(20_001)
                + "</sheetData>");
        int[] shown = pixels("shown.xlsx", "<sheetData>" + text(1) + text(2) + bareRows + text(20_001)
                + "</sheetData>");
        assertFalse(yellow(styled));
        assertFalse(Arrays.equals(plain, shown));
        assertArrayEquals(shown, styled);
    }

    @Test
    void aHiddenRowsTopEdgeStillMeetsTheNextVisibleRow() throws Exception {
        String row3 = "<row r=\"3\"><c r=\"A3\" t=\"inlineStr\"><is><t>Row3</t></is></c>";
        int[] hidden = pixels("edge.xlsx", "<sheetData>" + text(1) + "<row r=\"2\" hidden=\"1\"><c r=\"B2\""
                + " s=\"2\"/></row>" + row3 + "</row></sheetData>");
        int[] moved = pixels("moved.xlsx", "<sheetData>" + text(1) + "<row r=\"2\" hidden=\"1\"/>" + row3
                + "<c r=\"B3\" s=\"2\"/></row></sheetData>");
        assertArrayEquals(moved, hidden);
    }

    @Test
    void hiddenRowsOfAZeroHeightSheetKeepOnlyWhatShows() throws Exception {
        StringBuilder rows = new StringBuilder(text(1));
        for (int r = 2; r <= 5_000; r++) {
            rows.append("<row r=\"").append(r).append("\" hidden=\"1\"><c r=\"B").append(r).append("\" s=\"1\"/></row>");
        }
        String sheet = "<sheetFormatPr defaultRowHeight=\"15\" zeroHeight=\"1\"/><sheetData>" + rows + "</sheetData>";
        int[] px = pixels("zero.xlsx", sheet);
        assertFalse(yellow(px));
    }

    @Test
    void bandsAskForEachSizeOnce() {
        int[] calls = new int[1];
        Band b = new Band(2, 11, i -> {
            calls[0]++;
            return i == 5 ? 0 : i;
        });
        assertEquals(10, calls[0]);
        assertEquals(0, b.size(5), 0);
        assertEquals(7, b.size(7), 0);
        assertEquals(10, calls[0]);
        assertEquals(12, b.size(12), 0);
        assertEquals(11, calls[0]);
        assertEquals(2 + 3 + 4 + 6, b.start(7), 1e-9);
        assertTrue(b.length() > b.start(11));
    }
}
