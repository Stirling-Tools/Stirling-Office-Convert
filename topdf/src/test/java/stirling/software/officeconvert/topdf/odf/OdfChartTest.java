package stirling.software.officeconvert.topdf.odf;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.docx.Charts;
import stirling.software.officeconvert.topdf.io.SecureXml;

class OdfChartTest {

    private static String chart(String plotStyle) throws IOException {
        return chart(plotStyle, "North");
    }

    private static String chart(String plotStyle, String north) throws IOException {
        String xml = "<?xml version=\"1.0\"?><office:document-content " + OdfFixtures.NS
                + " xmlns:chart=\"urn:oasis:names:tc:opendocument:xmlns:chart:1.0\"><office:automatic-styles>"
                + "<style:style style:name=\"p1\" style:family=\"chart\"><style:chart-properties " + plotStyle + "/>"
                + "</style:style></office:automatic-styles><office:body><office:chart><chart:chart chart:class=\"chart:line\">"
                + "<chart:plot-area chart:style-name=\"p1\"><chart:series/><chart:series/></chart:plot-area>"
                + "<table:table table:name=\"local-table\"><table:table-header-columns><table:table-column/>"
                + "</table:table-header-columns><table:table-columns><table:table-column table:number-columns-repeated=\"3\"/>"
                + "</table:table-columns><table:table-header-rows><table:table-row><table:table-cell/>"
                + cell("1984") + cell("1985") + cell("1986") + "</table:table-row></table:table-header-rows><table:table-rows>"
                + "<table:table-row>" + cell(north) + number(1) + number(2) + number(3) + "</table:table-row>"
                + "<table:table-row>" + cell("South") + number(4) + number(5) + number(6) + "</table:table-row>"
                + "</table:table-rows></table:table></chart:chart></office:chart></office:body></office:document-content>";
        Element root = SecureXml.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
        return OdfChart.part(root, root, new WorkBudget(0));
    }

    private static String cell(String text) {
        return "<table:table-cell office:value-type=\"string\"><text:p>" + text + "</text:p></table:table-cell>";
    }

    private static String number(int v) {
        return "<table:table-cell office:value-type=\"float\" office:value=\"" + v + "\"/>";
    }

    @Test
    void seriesTakenFromRowsUseEachRowAsASeries() throws IOException {
        String part = chart("chart:series-source=\"rows\"");
        assertTrue(part.contains("<c:v>North</c:v>") && part.contains("<c:v>South</c:v>")
                && part.contains("<c:v>1985</c:v>") && part.indexOf("<c:v>4.0</c:v>") < part.indexOf("<c:v>6.0</c:v>")
                && part.indexOf("<c:v>South</c:v>") < part.indexOf("<c:v>4.0</c:v>"), part);
    }

    @Test
    void seriesTakenFromColumnsUseEachColumnAsASeries() throws IOException {
        String part = chart("chart:series-source=\"columns\"");
        assertTrue(part.contains("<c:v>1984</c:v>") && part.contains("<c:v>North</c:v>")
                && part.indexOf("<c:v>1984</c:v>") < part.indexOf("<c:v>1.0</c:v>"), part);
    }

    private static String styled(String cls, String plotProps) throws IOException {
        String xml = "<?xml version=\"1.0\"?><office:document-content " + OdfFixtures.NS
                + " xmlns:chart=\"urn:oasis:names:tc:opendocument:xmlns:chart:1.0\"><office:automatic-styles>"
                + "<style:style style:name=\"p1\" style:family=\"chart\"><style:chart-properties " + plotProps + "/>"
                + "</style:style><style:style style:name=\"w\" style:family=\"chart\"><style:graphic-properties"
                + " draw:fill=\"solid\" draw:fill-color=\"#d9d9d9\"/></style:style><style:style style:name=\"g\""
                + " style:family=\"chart\"><style:graphic-properties svg:stroke-color=\"#b3b3b3\"/></style:style>"
                + "</office:automatic-styles><office:body><office:chart><chart:chart svg:width=\"10cm\""
                + " svg:height=\"5cm\" chart:class=\"chart:" + cls + "\"><chart:plot-area chart:style-name=\"p1\">"
                + "<chart:coordinate-region svg:x=\"1cm\" svg:y=\"0.5cm\" svg:width=\"8cm\" svg:height=\"4cm\"/>"
                + "<chart:axis chart:dimension=\"x\" chart:name=\"primary-x\"/><chart:axis chart:dimension=\"y\""
                + " chart:name=\"primary-y\"><chart:grid chart:style-name=\"g\" chart:class=\"major\"/></chart:axis>"
                + "<chart:series/><chart:series/><chart:wall chart:style-name=\"w\"/></chart:plot-area>"
                + "<table:table table:name=\"local-table\"><table:table-header-columns><table:table-column/>"
                + "</table:table-header-columns><table:table-header-rows><table:table-row><table:table-cell/>"
                + cell("North") + cell("South") + "</table:table-row></table:table-header-rows><table:table-rows>"
                + "<table:table-row>" + cell("Q1") + number(1) + number(2) + "</table:table-row>"
                + "<table:table-row>" + cell("Q2") + number(3) + number(4) + "</table:table-row>"
                + "</table:table-rows></table:table></chart:chart></office:chart></office:body></office:document-content>";
        Element root = SecureXml.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
        return OdfChart.part(root, root, new WorkBudget(0));
    }

    @Test
    void theWallGridAndPlotRectangleCarryOver() throws IOException {
        String part = styled("bar", "chart:vertical=\"false\"");
        assertTrue(part.contains("<c:x val=\"0.1\"/><c:y val=\"0.1\"/><c:w val=\"0.8\"/><c:h val=\"0.8\"/>"), part);
        assertTrue(part.contains("<c:majorGridlines><c:spPr><a:ln w=\"9525\"><a:solidFill><a:srgbClr val=\"B3B3B3\"/>"),
                part);
        assertTrue(part.contains("<a:srgbClr val=\"D9D9D9\"/></a:solidFill><a:ln><a:noFill/></a:ln></c:spPr></c:plotArea>"),
                part);
        assertTrue(part.indexOf("<c:v>North</c:v>") < part.indexOf("<c:v>South</c:v>"), part);
        assertFalse(part.contains(Charts.REVERSED_ORDER), part);
    }

    @Test
    void clusteredHorizontalBarsListTheLastSeriesFirst() throws IOException {
        String part = styled("bar", "chart:vertical=\"true\"");
        assertTrue(part.indexOf("<c:v>South</c:v>") < part.indexOf("<c:v>North</c:v>"), part);
        String stacked = styled("bar", "chart:vertical=\"true\" chart:stacked=\"true\"");
        assertTrue(stacked.indexOf("<c:v>North</c:v>") < stacked.indexOf("<c:v>South</c:v>"), stacked);
    }

    @Test
    void piesAndOverlappingAreasDrawInLibreOfficeOrder() throws IOException {
        assertTrue(styled("circle", "").contains(Charts.REVERSED_ORDER));
        assertTrue(styled("area", "").contains(Charts.REVERSED_ORDER));
        assertFalse(styled("area", "chart:stacked=\"true\"").contains(Charts.REVERSED_ORDER));
        String ring = styled("ring", "");
        assertTrue(ring.contains(Charts.REVERSED_ORDER) && ring.contains("<c:holeSize val=\"33\"/>"), ring);
        assertFalse(ring.contains("D9D9D9"), ring);
    }

    @Test
    void longCategoryTextIsCappedWithoutSplittingASurrogatePair() throws IOException {
        String north = "c".repeat(OdfChart.MAX_TEXT_CHARS - 1) + "\uD83D\uDE00" + "c".repeat(30_000);
        String part = chart("chart:series-source=\"columns\"", north);
        assertTrue(part.contains("<c:v>" + "c".repeat(OdfChart.MAX_TEXT_CHARS - 1) + "</c:v>"), "category kept");
        assertFalse(part.contains("\uD83D"), "no half surrogate pair");
        assertTrue(part.length() < 4 * OdfChart.MAX_TEXT_CHARS + 10_000, "chart xml " + part.length());
    }
}
