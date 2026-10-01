package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaginationTest {

    @TempDir
    Path dir;

    private static String rows(int n) {
        StringBuilder b = new StringBuilder("<sheetData>");
        for (int r = 1; r <= n; r++) {
            b.append("<row r=\"").append(r).append("\"><c r=\"A").append(r).append("\" t=\"inlineStr\"><is><t>row")
                    .append(r).append("</t></is></c></row>");
        }
        return b.append("</sheetData>").toString();
    }

    private static String fitted(String fit) {
        return "<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>" + rows(6) + "<pageSetup paperSize=\"9\" " + fit
                + "/><rowBreaks count=\"1\" manualBreakCount=\"1\"><brk id=\"3\" max=\"16383\" man=\"1\"/></rowBreaks>";
    }

    @Test
    void fitToWidthOnlyKeepsTheManualRowBreaks() throws Exception {
        XlsxTesting.Converted c = XlsxTesting.convert(dir, "auto.xlsx",
                new RawXlsx().sheet("S", fitted("fitToHeight=\"0\"")).bytes());
        assertEquals(2, c.pages().size());
        assertTrue(c.pages().get(0).contains("row3") && !c.pages().get(0).contains("row4"), c.pages().get(0));
    }

    @Test
    void fitToAWholePageIgnoresTheManualRowBreaks() throws Exception {
        XlsxTesting.Converted c = XlsxTesting.convert(dir, "whole.xlsx",
                new RawXlsx().sheet("S", fitted("fitToHeight=\"1\"")).bytes());
        assertEquals(1, c.pages().size());
    }

    @Test
    void rowsStopFivePrinterPixelsShortOfTheBottomMargin() throws Exception {
        double printable = 841.8898 - 2 * 0.75 * 72 - PrintMetrics.ORIGIN;
        double row = 149 * PrintMetrics.PX;
        List<Paginator.Span> spans = Paginator.spans(0, 99, i -> row, -1, -1, Paginator.rowRoom(printable, 1), null,
                Integer.MAX_VALUE);
        assertEquals(new Paginator.Span(0, 39), spans.get(0));
    }

    @Test
    void wrappedTextInAColumnWiderThanThePageWrapsAtThePageEdge() throws Exception {
        String styles = "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills count=\"1\">"
                + "<fill><patternFill patternType=\"none\"/></fill></fills><borders count=\"1\"><border/></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\">"
                + "<alignment wrapText=\"1\"/></xf></cellXfs>";
        String words = "lorem ipsum dolor sit amet ".repeat(40);
        String sheet = "<cols><col min=\"1\" max=\"1\" width=\"200\" customWidth=\"1\"/></cols><sheetData><row r=\"1\""
                + " ht=\"300\" customHeight=\"1\"><c r=\"A1\" s=\"1\" t=\"inlineStr\"><is><t>" + words
                + "</t></is></c></row></sheetData><pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\""
                + " header=\"0.3\" footer=\"0.3\"/><pageSetup paperSize=\"9\"/>";
        XlsxTesting.convert(dir, "capped.xlsx", new RawXlsx().styles(styles).sheet("S", sheet).bytes());
        float[] right = new float[1];
        try (PDDocument doc = Loader.loadPDF(dir.resolve("capped.xlsx.pdf").toFile())) {
            assertEquals(1, doc.getNumberOfPages());
            new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    for (TextPosition t : positions) {
                        right[0] = Math.max(right[0], t.getXDirAdj() + t.getWidthDirAdj());
                    }
                }
            }.getText(doc);
        }
        assertTrue(right[0] < 595.28f - 0.7f * 72, "text reaches " + right[0]);
    }
}
