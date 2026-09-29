package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
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

class RowHeightTest {

    @TempDir
    Path dir;

    private static final String STYLES = "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
            + "<font><sz val=\"8\"/><name val=\"Arial\"/></font></fonts><fills count=\"1\"><fill><patternFill"
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
                        out.put(Integer.parseInt(text.substring(3).trim()), positions.get(0).getYDirAdj());
                    }
                }
            }.getText(doc);
        }
        return out;
    }

    private static String rows(int from, int to, String attrs) {
        StringBuilder b = new StringBuilder();
        for (int r = from; r <= to; r++) {
            b.append("<row r=\"").append(r).append('"').append(attrs).append("><c r=\"A").append(r)
                    .append("\" s=\"1\" t=\"inlineStr\"><is><t>Row").append(r).append("</t></is></c></row>");
        }
        return b.toString();
    }

    private static double factor() {
        PrintMetrics m = new PrintMetrics(FontMeasure.of(FontLibrary.system(), "Calibri", false, false), 11);
        return m.rowFactor(m.estimatedScreenRowPt());
    }

    @Test
    void theSheetsOwnDefaultHeightIsPrintedWithTheStandardFontsScale() throws Exception {
        Map<Integer, Float> y = baselines("small.xlsx", "<sheetFormatPr defaultRowHeight=\"11.25\"/><sheetData>"
                + rows(1, 6, "") + "</sheetData>");
        assertEquals(6, y.size(), y.toString());
        double expected = Math.floor(11.25 * 600 / 72 * factor() + 1e-6) * PrintMetrics.PX;
        assertTrue(expected < 11.5, "expected " + expected);
        for (int r = 2; r <= 6; r++) {
            assertEquals(expected, y.get(r) - y.get(r - 1), 0.02, "row " + r + " in " + y);
        }
    }

    @Test
    void wrappedTextTallerThanItsRowStartsAtTheTop() throws Exception {
        String sheet = "<cols><col min=\"1\" max=\"1\" width=\"6\" customWidth=\"1\"/></cols><sheetData>"
                + "<row r=\"1\" ht=\"15\" customHeight=\"1\"><c r=\"A1\" s=\"2\" t=\"inlineStr\"><is><t>Row1 alpha beta"
                + " gamma delta epsilon</t></is></c></row>" + rows(2, 2, "") + "</sheetData>";
        String styles = STYLES.replace("<cellXfs count=\"2\">", "<cellXfs count=\"3\">").replace("</cellXfs>",
                "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\">"
                        + "<alignment wrapText=\"1\"/></xf></cellXfs>");
        byte[] xlsx = new RawXlsx().styles(styles).sheet("S", sheet).bytes();
        XlsxTesting.convert(dir, "wrapped.xlsx", xlsx);
        Map<String, Float> y = new TreeMap<>();
        try (PDDocument doc = Loader.loadPDF(dir.resolve("wrapped.xlsx.pdf").toFile())) {
            new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    y.put(text.trim().split(" ")[0], positions.get(0).getYDirAdj());
                }
            }.getText(doc);
        }
        assertTrue(y.containsKey("Row1") && y.containsKey("Row2"), y.toString());
        assertTrue(y.get("Row1") < y.get("Row2") && y.get("Row2") - y.get("Row1") < 16, y.toString());
    }

    @Test
    void bottomAlignedTextSitsAboveAMediumBottomEdge() throws Exception {
        String styles = STYLES.replace("<borders count=\"1\"><border/></borders>", "<borders count=\"2\"><border/>"
                + "<border><bottom style=\"medium\"/></border></borders>").replace("<cellXfs count=\"2\">",
                        "<cellXfs count=\"3\">").replace("</cellXfs>", "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\""
                                + " borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyBorder=\"1\"/></cellXfs>");
        String sheet = "<sheetData>" + rows(1, 1, " ht=\"15\" customHeight=\"1\"")
                + rows(2, 2, " ht=\"15\" customHeight=\"1\"").replace("s=\"1\"", "s=\"2\"")
                + rows(3, 3, " ht=\"15\" customHeight=\"1\"") + "</sheetData>";
        byte[] xlsx = new RawXlsx().styles(styles).sheet("S", sheet).bytes();
        XlsxTesting.convert(dir, "edge.xlsx", xlsx);
        Map<Integer, Float> y = new TreeMap<>();
        try (PDDocument doc = Loader.loadPDF(dir.resolve("edge.xlsx.pdf").toFile())) {
            new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    if (text.startsWith("Row")) {
                        y.put(Integer.parseInt(text.substring(3).trim()), positions.get(0).getYDirAdj());
                    }
                }
            }.getText(doc);
        }
        double row = Math.floor(15 * 600 / 72.0 * factor() + 1e-6) * PrintMetrics.PX;
        assertEquals(row - BlockPainter.BORDER_LIFT, y.get(2) - y.get(1), 0.02, y.toString());
        assertEquals(row + BlockPainter.BORDER_LIFT, y.get(3) - y.get(2), 0.02, y.toString());
    }

    @Test
    void printedRowHeightsDropThePartialDevicePixel() throws Exception {
        Map<Integer, Float> y = baselines("tall.xlsx", "<sheetFormatPr defaultRowHeight=\"15\"/><sheetData>"
                + rows(1, 5, " ht=\"24\" customHeight=\"1\"") + "</sheetData>");
        double device = 24 * 600 / 72.0 * factor();
        double expected = Math.floor(device + 1e-6) * PrintMetrics.PX;
        List<Double> pitches = new ArrayList<>();
        for (int r = 2; r <= 5; r++) {
            pitches.add((double) (y.get(r) - y.get(r - 1)));
        }
        for (double p : pitches) {
            assertEquals(expected, p, 0.02, pitches.toString());
        }
    }
}
