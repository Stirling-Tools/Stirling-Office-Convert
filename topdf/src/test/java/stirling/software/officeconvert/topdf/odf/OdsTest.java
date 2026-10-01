package stirling.software.officeconvert.topdf.odf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;

class OdsTest {

    @TempDir
    Path dir;

    private static final String NUMBER_STYLES = "<number:number-style style:name=\"N1\"><number:number"
            + " number:decimal-places=\"2\" number:min-decimal-places=\"2\" number:min-integer-digits=\"1\""
            + " number:grouping=\"true\"/></number:number-style><number:date-style style:name=\"N2\"><number:day"
            + " number:style=\"long\"/><number:text>/</number:text><number:month number:style=\"long\"/><number:text>/"
            + "</number:text><number:year number:style=\"long\"/></number:date-style>"
            + "<style:style style:name=\"ce1\" style:family=\"table-cell\" style:data-style-name=\"N1\"/>"
            + "<style:style style:name=\"ce2\" style:family=\"table-cell\" style:data-style-name=\"N2\"/>"
            + "<style:style style:name=\"ce3\" style:family=\"table-cell\"><style:table-cell-properties"
            + " fo:background-color=\"#ffff00\" fo:border=\"0.74pt solid #000000\"/><style:text-properties"
            + " fo:font-weight=\"bold\"/></style:style>";

    private Path ods(String automatic, String tables, String styles) throws IOException {
        return OdfFixtures.write(dir, "book.ods", OdfFixtures.odf(OdfFixtures.SPREADSHEET,
                OdfFixtures.content(automatic, "<office:spreadsheet>" + tables + "</office:spreadsheet>"), styles));
    }

    private String pdfText(Path in) throws IOException {
        Path pdf = dir.resolve("out.pdf");
        OfficeToPdf.convert(in, pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void cachedValuesAreFormattedAndFormulasNeverEvaluated() throws IOException {
        String table = "<table:table table:name=\"Data\"><table:table-row><table:table-cell table:style-name=\"ce1\""
                + " office:value-type=\"float\" office:value=\"1234.5\"><text:p>1,234.50</text:p></table:table-cell>"
                + "<table:table-cell table:style-name=\"ce2\" office:value-type=\"date\" office:date-value=\"2024-03-15\">"
                + "<text:p>15/03/2024</text:p></table:table-cell><table:table-cell table:formula=\"of:=1+1\""
                + " office:value-type=\"float\" office:value=\"7\"><text:p>7</text:p></table:table-cell>"
                + "<table:table-cell office:value-type=\"boolean\" office:boolean-value=\"true\"><text:p>TRUE</text:p>"
                + "</table:table-cell><table:table-cell office:value-type=\"string\"><text:p>Hello</text:p>"
                + "</table:table-cell></table:table-row></table:table>";
        Path p = ods(NUMBER_STYLES, table, null);
        Map<String, String> parts = OdfFixtures.rewrite(p);
        String sheet = parts.get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("<v>1234.5</v>"), sheet);
        assertTrue(sheet.contains("<v>45366</v>"), sheet);
        assertTrue(sheet.contains("<v>7</v>"), sheet);
        assertTrue(sheet.contains("t=\"b\"><v>1</v>"), sheet);
        assertFalse(sheet.contains("<f>"), sheet);
        String styles = parts.get("xl/styles.xml");
        assertTrue(styles.contains("formatCode=\"#,##0.00\""), styles);
        assertTrue(styles.contains("formatCode=\"dd/mm/yyyy\""), styles);
        String text = pdfText(p);
        assertTrue(text.contains("1,234.50") && text.contains("15/03/2024") && text.contains("Hello"), text);
    }

    @Test
    void negativeNumbersUseTheirConditionalSection() throws IOException {
        String auto = "<number:number-style style:name=\"N3P0\"><number:number number:decimal-places=\"0\""
                + " number:min-integer-digits=\"1\"/></number:number-style><number:number-style style:name=\"N3\">"
                + "<style:text-properties fo:color=\"#ff0000\"/><number:text>(</number:text><number:number"
                + " number:decimal-places=\"0\" number:min-integer-digits=\"1\"/><number:text>)</number:text>"
                + "<style:map style:condition=\"value()&gt;=0\" style:apply-style-name=\"N3P0\"/></number:number-style>"
                + "<style:style style:name=\"ce1\" style:family=\"table-cell\" style:data-style-name=\"N3\"/>";
        Path p = ods(auto, "<table:table table:name=\"S\"><table:table-row><table:table-cell table:style-name=\"ce1\""
                + " office:value-type=\"float\" office:value=\"-5\"/></table:table-row></table:table>", null);
        String styles = OdfFixtures.rewrite(p).get("xl/styles.xml");
        assertTrue(styles.contains("formatCode=\"0;[Red](0)\""), styles);
    }

    @Test
    void theWidthOfTheTrailingFillerColumnsIsTheSheetDefault() throws IOException {
        String auto = "<style:style style:name=\"co1\" style:family=\"table-column\"><style:table-column-properties"
                + " style:column-width=\"1in\"/></style:style><style:style style:name=\"co2\" style:family=\"table-column\">"
                + "<style:table-column-properties style:column-width=\"0.5in\"/></style:style>";
        String table = "<table:table table:name=\"S\"><table:table-column table:style-name=\"co1\"/><table:table-column"
                + " table:style-name=\"co2\" table:number-columns-repeated=\"16383\"/><table:table-row><table:table-cell"
                + " office:value-type=\"string\"><text:p>A</text:p></table:table-cell></table:table-row></table:table>";
        String sheet = OdfFixtures.rewrite(ods(auto, table, null)).get("xl/worksheets/sheet1.xml");
        String full = sheet.replaceAll("(?s).*<col min=\"1\" max=\"1\" width=\"([0-9.]+)\".*", "$1");
        String filler = sheet.replaceAll("(?s).*<sheetFormatPr defaultColWidth=\"([0-9.]+)\".*", "$1");
        assertEquals(Double.parseDouble(full) / 2, Double.parseDouble(filler), 0.01, sheet);
    }

    @Test
    void mergesWidthsHiddenRowsAndFormats() throws IOException {
        String auto = NUMBER_STYLES + "<style:style style:name=\"co1\" style:family=\"table-column\">"
                + "<style:table-column-properties style:column-width=\"2in\"/></style:style><style:style"
                + " style:name=\"ro1\" style:family=\"table-row\"><style:table-row-properties style:row-height=\"0.5in\""
                + " style:use-optimal-row-height=\"false\"/></style:style>";
        String table = "<table:table table:name=\"S\"><table:table-column table:style-name=\"co1\"/><table:table-column"
                + " table:number-columns-repeated=\"3\"/><table:table-row table:style-name=\"ro1\"><table:table-cell"
                + " table:style-name=\"ce3\" table:number-columns-spanned=\"2\" office:value-type=\"string\"><text:p>Top"
                + "</text:p></table:table-cell><table:covered-table-cell/></table:table-row><table:table-row"
                + " table:visibility=\"collapse\"><table:table-cell office:value-type=\"string\"><text:p>gone</text:p>"
                + "</table:table-cell></table:table-row><table:table-row table:number-rows-repeated=\"1048574\">"
                + "<table:table-cell table:number-columns-repeated=\"1024\"/></table:table-row></table:table>";
        Map<String, String> parts = OdfFixtures.rewrite(ods(auto, table, null));
        String sheet = parts.get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("<mergeCell ref=\"A1:B1\"/>"), sheet);
        assertTrue(sheet.contains("ht=\"36.0\" customHeight=\"1\""), sheet);
        assertTrue(sheet.contains("hidden=\"1\""), sheet);
        assertTrue(sheet.contains("<col min=\"1\" max=\"1\" width=\""), sheet);
        assertEquals(2, sheet.split("<row ").length - 1, sheet);
        String styles = parts.get("xl/styles.xml");
        assertTrue(styles.contains("<fgColor rgb=\"FFFFFF00\"/>") && styles.contains("<left style=\"thin\">")
                && styles.contains("<b/>"), styles);
    }

    @Test
    void pageStylePrintRangeAndHeaders() throws IOException {
        String styles = OdfFixtures.styles("", "<style:page-layout style:name=\"pm1\"><style:page-layout-properties"
                + " fo:page-width=\"11in\" fo:page-height=\"8.5in\" style:print-orientation=\"landscape\""
                + " fo:margin-top=\"0.5in\" fo:margin-bottom=\"0.5in\" fo:margin-left=\"0.7in\" fo:margin-right=\"0.7in\""
                + " style:scale-to-X=\"1\" style:print=\"grid\"/><style:header-style><style:header-footer-properties"
                + " fo:min-height=\"0.3in\"/></style:header-style></style:page-layout>",
                "<style:master-page style:name=\"Default\" style:page-layout-name=\"pm1\"><style:header>"
                + "<style:region-left><text:p><text:sheet-name>?</text:sheet-name></text:p></style:region-left>"
                + "<style:region-right><text:p>Page <text:page-number>1</text:page-number></text:p></style:region-right>"
                + "</style:header></style:master-page>");
        String table = "<table:table table:name=\"Q1 &amp; Q2\" table:print-ranges=\"'Q1 &amp; Q2'.A1:'Q1 &amp; Q2'.B2\">"
                + "<table:table-header-rows><table:table-row><table:table-cell office:value-type=\"string\"><text:p>H"
                + "</text:p></table:table-cell></table:table-row></table:table-header-rows><table:table-row>"
                + "<table:table-cell office:value-type=\"float\" office:value=\"1\"/></table:table-row></table:table>";
        Path p = ods("", table, styles);
        Map<String, String> parts = OdfFixtures.rewrite(p);
        String sheet = parts.get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("paperSize=\"1\"") && sheet.contains("orientation=\"landscape\""), sheet);
        assertTrue(sheet.contains("fitToWidth=\"1\" fitToHeight=\"0\"") && sheet.contains("<pageSetUpPr fitToPage"),
                sheet);
        assertTrue(sheet.contains("gridLines=\"1\""), sheet);
        assertTrue(sheet.contains("<oddHeader>&amp;L&amp;A&amp;RPage &amp;P</oddHeader>"), sheet);
        assertTrue(sheet.contains("top=\"0.8\"") && sheet.contains("header=\"0.5\""), sheet);
        String book = parts.get("xl/workbook.xml");
        assertTrue(book.contains("_xlnm.Print_Area") && book.contains("'Q1 &amp; Q2'!$A$1:$B$2"), book);
        assertTrue(book.contains("_xlnm.Print_Titles") && book.contains("!$1:$1"), book);
        String text = pdfText(p);
        assertTrue(text.contains("Q1 & Q2") && text.contains("Page 1"), text);
    }

    @Test
    void hiddenSheetsStayHidden() throws IOException {
        String auto = "<style:style style:name=\"ta2\" style:family=\"table\"><style:table-properties"
                + " table:display=\"false\"/></style:style>";
        Path p = ods(auto, "<table:table table:name=\"Shown\"><table:table-row><table:table-cell office:value-type="
                + "\"string\"><text:p>visible</text:p></table:table-cell></table:table-row></table:table><table:table"
                + " table:name=\"Secret\" table:style-name=\"ta2\"><table:table-row><table:table-cell"
                + " office:value-type=\"string\"><text:p>hidden text</text:p></table:table-cell></table:table-row>"
                + "</table:table>", null);
        String text = pdfText(p);
        assertTrue(text.contains("visible") && !text.contains("hidden text"), text);
    }

    @Test
    void columnStyledEmptyCellsDoNotStretchThePrintedArea() throws IOException {
        String table = "<table:table table:name=\"S\"><table:table-column table:default-cell-style-name=\"ce3\"/>"
                + "<table:table-row><table:table-cell table:style-name=\"ce3\" office:value-type=\"string\"><text:p>x"
                + "</text:p></table:table-cell></table:table-row><table:table-row table:number-rows-repeated=\"500\">"
                + "<table:table-cell table:style-name=\"ce3\"/></table:table-row></table:table>";
        String sheet = OdfFixtures.rewrite(ods(NUMBER_STYLES, table, null)).get("xl/worksheets/sheet1.xml");
        assertEquals(1, sheet.split("<row ").length - 1, sheet);
    }

    @Test
    void aLongTailOfColumnStyledRowsIsNotPrinted() throws IOException {
        String table = "<table:table table:name=\"S\"><table:table-column table:default-cell-style-name=\"ce3\"/>"
                + "<table:table-row><table:table-cell office:value-type=\"string\"><text:p>x</text:p>"
                + "</table:table-cell></table:table-row>"
                + "<table:table-row><table:table-cell/></table:table-row>".repeat(150) + "</table:table>";
        String sheet = OdfFixtures.rewrite(ods(NUMBER_STYLES, table, null)).get("xl/worksheets/sheet1.xml");
        assertEquals(1, sheet.split("<row ").length - 1, sheet);
        assertTrue(sheet.contains("<dimension ref=\"A1:A1\"/>"), sheet);
    }

    @Test
    void aCalcHeaderHeightIncludesItsSpacing() throws IOException {
        String styles = OdfFixtures.styles("", "<style:page-layout style:name=\"pm1\"><style:page-layout-properties"
                + " fo:margin-top=\"0.3in\"/><style:header-style><style:header-footer-properties fo:min-height=\"0.45in\""
                + " fo:margin-bottom=\"0.3in\"/></style:header-style></style:page-layout>",
                "<style:master-page style:name=\"Default\" style:page-layout-name=\"pm1\"><style:header><text:p>H"
                + "</text:p></style:header></style:master-page>");
        String sheet = OdfFixtures.rewrite(ods("", "<table:table table:name=\"S\"><table:table-row><table:table-cell"
                + " office:value-type=\"float\" office:value=\"1\"/></table:table-row></table:table>", styles))
                .get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("top=\"0.75\"") && sheet.contains("header=\"0.3\""), sheet);
    }

    @Test
    void coveredCellsKeepTheirOwnLookOverTheColumns() throws IOException {
        String auto = NUMBER_STYLES + "<style:style style:name=\"plain\" style:family=\"table-cell\"/>";
        String table = "<table:table table:name=\"S\"><table:table-column table:number-columns-repeated=\"2\""
                + " table:default-cell-style-name=\"ce3\"/><table:table-row><table:table-cell table:style-name=\"plain\""
                + " table:number-columns-spanned=\"2\" office:value-type=\"string\"><text:p>Title</text:p>"
                + "</table:table-cell><table:covered-table-cell table:style-name=\"plain\"/></table:table-row>"
                + "<table:table-row><table:table-cell office:value-type=\"float\" office:value=\"1\"/><table:table-cell"
                + " office:value-type=\"float\" office:value=\"2\"/></table:table-row></table:table>";
        String sheet = OdfFixtures.rewrite(ods(auto, table, null)).get("xl/worksheets/sheet1.xml");
        assertFalse(sheet.contains("<c r=\"B1\""), sheet);
        assertFalse(sheet.contains(" style=\""), sheet);
        assertTrue(sheet.contains("<c r=\"B2\" s=\""), sheet);
    }

    @Test
    void percentagesScaleTheirValue() throws IOException {
        String auto = "<number:percentage-style style:name=\"N9\"><number:number number:decimal-places=\"1\""
                + " number:min-integer-digits=\"1\"/><number:text>%</number:text></number:percentage-style>"
                + "<style:style style:name=\"ce9\" style:family=\"table-cell\" style:data-style-name=\"N9\"/>";
        String table = "<table:table table:name=\"S\"><table:table-column table:default-cell-style-name=\"ce9\"/>"
                + "<table:table-row><table:table-cell office:value-type=\"percentage\" office:value=\"0.3673\"/>"
                + "</table:table-row></table:table>";
        Path p = ods(auto, table, null);
        assertTrue(pdfText(p).contains("36.7%"));
        assertTrue(OdfFixtures.rewrite(p).get("xl/styles.xml").contains("formatCode=\"0.0%\""));
    }

    @Test
    void embeddedChartsAreDrawnFromTheirOwnTable() throws IOException {
        String chart = "<?xml version=\"1.0\"?><office:document-content " + OdfFixtures.NS
                + " xmlns:chart=\"urn:oasis:names:tc:opendocument:xmlns:chart:1.0\"><office:automatic-styles>"
                + "<style:style style:name=\"s1\" style:family=\"chart\"><style:graphic-properties"
                + " draw:fill-color=\"#ff0000\"/></style:style></office:automatic-styles><office:body><office:chart>"
                + "<chart:chart chart:class=\"chart:bar\"><chart:title><text:p>Scores</text:p></chart:title>"
                + "<chart:legend chart:legend-position=\"bottom\"/><chart:plot-area><chart:axis chart:dimension=\"x\""
                + " chart:name=\"primary-x\"/><chart:axis chart:dimension=\"y\" chart:name=\"primary-y\"><chart:grid"
                + " chart:class=\"major\"/></chart:axis><chart:series chart:style-name=\"s1\""
                + " chart:values-cell-range-address=\"Data.B2:Data.B3\"/></chart:plot-area><table:table"
                + " table:name=\"local-table\"><table:table-header-columns><table:table-column/></table:table-header-columns>"
                + "<table:table-columns><table:table-column/></table:table-columns><table:table-header-rows><table:table-row>"
                + "<table:table-cell/><table:table-cell office:value-type=\"string\"><text:p>Score</text:p>"
                + "</table:table-cell></table:table-row></table:table-header-rows><table:table-rows><table:table-row>"
                + "<table:table-cell office:value-type=\"string\"><text:p>Andy</text:p></table:table-cell><table:table-cell"
                + " office:value-type=\"float\" office:value=\"10\"/></table:table-row><table:table-row><table:table-cell"
                + " office:value-type=\"string\"><text:p>Bruce</text:p></table:table-cell><table:table-cell"
                + " office:value-type=\"float\" office:value=\"20\"/></table:table-row></table:table-rows></table:table>"
                + "</chart:chart></office:chart></office:body></office:document-content>";
        String content = OdfFixtures.content("", "<office:spreadsheet><table:table table:name=\"Data\"><table:shapes>"
                + "<draw:frame svg:x=\"1cm\" svg:y=\"1cm\" svg:width=\"10cm\" svg:height=\"6cm\"><draw:object"
                + " xlink:href=\"./Object 1\"/></draw:frame></table:shapes><table:table-row><table:table-cell"
                + " office:value-type=\"float\" office:value=\"1\"/></table:table-row></table:table></office:spreadsheet>");
        Map<String, byte[]> parts = new java.util.LinkedHashMap<>();
        parts.put("content.xml", content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        parts.put("Object 1/content.xml", chart.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path p = OdfFixtures.write(dir, "chart.ods", OdfFixtures.zip(OdfFixtures.SPREADSHEET, parts));
        Map<String, String> out = OdfFixtures.rewrite(p);
        String part = out.get("xl/charts/chart1.xml");
        assertTrue(part != null && part.contains("<c:barChart>") && part.contains("<c:v>20.0</c:v>")
                && part.contains("FF0000") && part.contains("<c:v>Bruce</c:v>"), String.valueOf(part));
        assertTrue(out.get("xl/drawings/drawing1.xml").contains("<c:chart"), out.get("xl/drawings/drawing1.xml"));
        String text = pdfText(p);
        assertTrue(text.contains("Scores") && text.contains("Bruce"), text);
    }
}
