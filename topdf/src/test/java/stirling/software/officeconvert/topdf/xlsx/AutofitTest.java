package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.font.FontLibrary;

class AutofitTest {

    @TempDir
    Path dir;

    private static final String STYLES = "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
            + "<font><sz val=\"20\"/><name val=\"Calibri\"/></font></fonts><fills count=\"1\"><fill><patternFill"
            + " patternType=\"none\"/></fill></fills><borders count=\"1\"><border/></borders><cellStyleXfs"
            + " count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs"
            + " count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\""
            + " fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>";

    private Map<Integer, Float> baselines(String name, String sheet) throws Exception {
        byte[] xlsx = new RawXlsx().styles(STYLES).sheet("S", sheet).bytes();
        XlsxTesting.convert(dir, name, xlsx);
        Map<Integer, Float> out = new TreeMap<>();
        try (PDDocument doc = Loader.loadPDF(dir.resolve(name + ".pdf").toFile())) {
            new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    if (text.startsWith("Row")) {
                        out.put(Integer.parseInt(text.substring(3).trim().split(" ")[0]),
                                positions.get(0).getYDirAdj());
                    }
                }
            }.getText(doc);
        }
        return out;
    }

    private static String row(int r, String attrs, String extra) {
        return "<row r=\"" + r + "\"" + attrs + "><c r=\"A" + r + "\" t=\"inlineStr\"><is><t>Row" + r
                + "</t></is></c>" + extra + "</row>";
    }

    private static double printed(double pt) {
        PrintMetrics m = new PrintMetrics(FontMeasure.of(FontLibrary.system(), "Calibri", false, false), 11);
        double factor = m.rowFactor(15);
        return Math.floor(pt * 600 / 72 * factor + 1e-6) * PrintMetrics.PX;
    }

    @Test
    void storedThickEdgeFlagsAddAPixelToAnAutofitRow() throws Exception {
        Map<Integer, Float> y = baselines("thick.xlsx", "<sheetFormatPr defaultRowHeight=\"15\"/><sheetData>"
                + row(1, "", "") + row(2, " thickBot=\"1\"", "") + row(3, " thickTop=\"1\" thickBot=\"1\"", "")
                + row(4, "", "") + row(5, " ht=\"15\" thickBot=\"1\"", "") + row(6, "", "") + "</sheetData>");
        assertEquals(6, y.size(), y.toString());
        assertEquals(printed(15.75), y.get(2) - y.get(1), 0.02, y.toString());
        assertEquals(printed(16.5), y.get(3) - y.get(2), 0.02, y.toString());
        assertEquals(printed(15), y.get(4) - y.get(3), 0.02, y.toString());
        assertEquals(printed(15), y.get(5) - y.get(4), 0.02, y.toString());
    }

    @Test
    void largeFontsInSingleRowMergesAndBlankCellsSetTheHeight() throws Exception {
        String big = "<c r=\"B%d\" s=\"1\" t=\"inlineStr\"><is><t>Big</t></is></c>";
        String sheet = "<sheetFormatPr defaultRowHeight=\"15\"/><sheetData>" + row(1, "", "")
                + row(2, "", String.format(big, 2)) + row(3, "", "<c r=\"C3\" s=\"1\"/>")
                + row(4, "", String.format(big, 4)) + row(5, "", "") + row(6, "", "") + "</sheetData>"
                + "<mergeCells count=\"2\"><mergeCell ref=\"B2:C2\"/><mergeCell ref=\"B4:B5\"/></mergeCells>";
        Map<Integer, Float> y = baselines("merged.xlsx", sheet);
        double line = FontMeasure.of(FontLibrary.system(), "Calibri", false, false).screenLinePx(20) * 0.75;
        assertTrue(line > 20, "line " + line);
        assertEquals(2 * printed(line), y.get(3) - y.get(1), 0.02, y.toString());
        assertEquals(2 * printed(15), y.get(5) - y.get(3), 0.02, y.toString());
        assertEquals(printed(15), y.get(6) - y.get(5), 0.02, y.toString());
    }

    @Test
    void blankCellsInsideATallMergeDoNotGrowItsRows() throws Exception {
        String sheet = "<sheetFormatPr defaultRowHeight=\"15\"/><sheetData>" + row(1, "", "") + row(2, "", "")
                + row(3, "", "<c r=\"C3\" s=\"1\"/>") + row(4, "", "") + row(5, "", "") + "</sheetData>"
                + "<mergeCells count=\"1\"><mergeCell ref=\"B2:C4\"/></mergeCells>";
        Map<Integer, Float> y = baselines("tallmerge.xlsx", sheet);
        assertEquals(3 * printed(15), y.get(5) - y.get(2), 0.02, y.toString());
    }

    @Test
    void fontsThatAskForTypoMetricsUseThemOnScreen() {
        FontMeasure aptos = FontMeasure.of(FontLibrary.of(List.of()), "Aptos Narrow", false, false);
        assertEquals(20, aptos.screenLinePx(11));
        assertEquals(25, aptos.screenLinePx(14));
        assertEquals(28, aptos.screenLinePx(16));
        assertEquals(32, aptos.screenLinePx(18));
        assertEquals(35, aptos.screenLinePx(20));
        FontMeasure calibri = FontMeasure.of(FontLibrary.of(List.of()), "Calibri", false, false);
        assertEquals(20, calibri.screenLinePx(11));
        assertEquals(28, calibri.screenLinePx(16));
    }

    @Test
    void italicTextKeepsRoomForItsSlant() {
        FontMeasure calibri = FontMeasure.of(FontLibrary.of(List.of()), "Calibri", true, true);
        assertEquals(2, Grid.italicOverhang(calibri, 8));
        assertEquals(3, Grid.italicOverhang(calibri, 11));
        assertTrue(Grid.italicOverhang(calibri, 20) > Grid.italicOverhang(calibri, 11));
    }
}
