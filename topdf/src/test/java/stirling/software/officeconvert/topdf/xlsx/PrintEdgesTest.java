package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.font.FontLibrary;

class PrintEdgesTest {

    @TempDir
    Path dir;

    @Test
    void columnsPrintAtTheirWholeScreenPixelsScaledToThePrinter() {
        PrintMetrics m = new PrintMetrics(FontMeasure.of(FontLibrary.of(List.of()), "Calibri", false, false), 11);
        assertEquals(7, m.screenDigit());
        assertEquals(47, m.printerDigit());
        assertEquals(52, m.screenColumnPixels(7.49), 1e-9);
        assertEquals(349 * PrintMetrics.PX, m.columnPoints(7.49), 1e-9);
        assertEquals(2760 * PrintMetrics.PX, m.columnPoints(58.65), 1e-9);
        assertEquals(430 * PrintMetrics.PX, m.columnPoints(9.140625), 1e-9);
    }

    @Test
    void fitToWidthKeepsTheClosingPixelsClearOfTheMargin() {
        double a4 = 595.2756 - 72 - PrintMetrics.ORIGIN;
        assertEquals(4351 * PrintMetrics.PX, Paginator.fitWidth(a4), 1e-9);
        double landscape = 841.8898 - 72 - PrintMetrics.ORIGIN;
        assertEquals(6406 * PrintMetrics.PX, Paginator.fitWidth(landscape), 1e-9);
    }

    @Test
    void fitToWidthDropsAPercentWhenTheLastColumnWouldTouchTheMargin() throws Exception {
        String cols = "<cols><col min=\"1\" max=\"1\" width=\"58.65\" customWidth=\"1\"/><col min=\"2\" max=\"2\""
                + " width=\"34.82\" customWidth=\"1\"/></cols>";
        String sheet = "<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>" + cols + "<sheetData><row r=\"1\"><c r=\"A1\""
                + " t=\"inlineStr\"><is><t>Left</t></is></c><c r=\"B1\" t=\"inlineStr\"><is><t>Right</t></is></c></row>"
                + "</sheetData><pageMargins left=\"0.5\" right=\"0.5\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\""
                + " footer=\"0.3\"/><pageSetup paperSize=\"9\" orientation=\"portrait\" fitToWidth=\"1\""
                + " fitToHeight=\"0\"/>";
        byte[] xlsx = new RawXlsx().sheet("S", sheet).bytes();
        XlsxTesting.Converted c = XlsxTesting.convert(dir, "fit.xlsx", xlsx);
        assertEquals(1, c.pages().size());
        try (PDDocument doc = Loader.loadPDF(dir.resolve("fit.xlsx.pdf").toFile())) {
            float[] x = new float[2];
            new org.apache.pdfbox.text.PDFTextStripper() {
                @Override
                protected void writeString(String text, List<org.apache.pdfbox.text.TextPosition> p) {
                    x[text.startsWith("Left") ? 0 : 1] = p.get(0).getXDirAdj();
                }
            }.getText(doc);
            double columnA = 2760 * PrintMetrics.PX;
            assertEquals(columnA * 0.98, x[1] - x[0], 0.2, "text starts " + x[0] + " and " + x[1]);
        }
    }

    private int strokes(String name, String setup) throws IOException {
        String styles = "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills count=\"1\">"
                + "<fill><patternFill patternType=\"none\"/></fill></fills><borders count=\"2\"><border/><border><left"
                + " style=\"thin\"/><right style=\"thin\"/><top style=\"thin\"/><bottom style=\"thin\"/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf"
                + " numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\"/></cellXfs>";
        String sheet = "<sheetData><row r=\"1\"><c r=\"A1\" s=\"1\" t=\"inlineStr\"><is><t>Boxed</t></is></c></row>"
                + "</sheetData>" + setup;
        byte[] xlsx = new RawXlsx().styles(styles).sheet("S", sheet).bytes();
        XlsxTesting.convert(dir, name, xlsx);
        int n = 0;
        try (PDDocument doc = Loader.loadPDF(dir.resolve(name + ".pdf").toFile())) {
            PDFStreamParser parser = new PDFStreamParser(doc.getPage(0));
            for (Object token : parser.parse()) {
                if (token instanceof Operator op && op.getName().equals("S")) {
                    n++;
                }
            }
        }
        return n;
    }

    @Test
    void thinBordersGetExcelsHairlineOnlyAtFullSize() throws Exception {
        int full = strokes("full.xlsx", "<pageSetup paperSize=\"9\" scale=\"100\"/>");
        int scaled = strokes("scaled.xlsx", "<pageSetup paperSize=\"9\" scale=\"90\"/>");
        assertTrue(full >= 4, "strokes at 100%: " + full);
        assertEquals(0, scaled);
    }

    @Test
    void aStrictSheetKeepsItsPageSetup() throws Exception {
        String cols = "<cols><col min=\"1\" max=\"2\" width=\"60\" customWidth=\"1\"/></cols>";
        String sheet = "<worksheet xmlns=\"http://purl.oclc.org/ooxml/spreadsheetml/main\" xmlns:r=\""
                + "http://purl.oclc.org/ooxml/officeDocument/relationships\"><sheetPr><pageSetUpPr fitToPage=\"1\"/>"
                + "</sheetPr>" + cols + "<sheetData><row r=\"1\">" + RawXlsx.inline("A1", "Left")
                + RawXlsx.inline("B1", "Right") + "</row></sheetData><pageSetup paperSize=\"9\" fitToHeight=\"0\"/>"
                + "</worksheet>";
        XlsxTesting.Converted c = XlsxTesting.convert(dir, "strict.xlsx", new RawXlsx().sheet("S", sheet).bytes());
        assertEquals(1, c.pages().size());
        try (PDDocument doc = Loader.loadPDF(dir.resolve("strict.xlsx.pdf").toFile())) {
            float[] x = new float[2];
            new org.apache.pdfbox.text.PDFTextStripper() {
                @Override
                protected void writeString(String text, List<org.apache.pdfbox.text.TextPosition> p) {
                    x[text.startsWith("Left") ? 0 : 1] = p.get(0).getXDirAdj();
                }
            }.getText(doc);
            assertTrue(x[1] - x[0] > 200 && x[1] < 500, "text starts " + x[0] + " and " + x[1]);
        }
    }
}
