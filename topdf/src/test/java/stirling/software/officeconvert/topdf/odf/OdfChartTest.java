package stirling.software.officeconvert.topdf.odf;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.io.SecureXml;

class OdfChartTest {

    private static String chart(String plotStyle) throws IOException {
        String xml = "<?xml version=\"1.0\"?><office:document-content " + OdfFixtures.NS
                + " xmlns:chart=\"urn:oasis:names:tc:opendocument:xmlns:chart:1.0\"><office:automatic-styles>"
                + "<style:style style:name=\"p1\" style:family=\"chart\"><style:chart-properties " + plotStyle + "/>"
                + "</style:style></office:automatic-styles><office:body><office:chart><chart:chart chart:class=\"chart:line\">"
                + "<chart:plot-area chart:style-name=\"p1\"><chart:series/><chart:series/></chart:plot-area>"
                + "<table:table table:name=\"local-table\"><table:table-header-columns><table:table-column/>"
                + "</table:table-header-columns><table:table-columns><table:table-column table:number-columns-repeated=\"3\"/>"
                + "</table:table-columns><table:table-header-rows><table:table-row><table:table-cell/>"
                + cell("1984") + cell("1985") + cell("1986") + "</table:table-row></table:table-header-rows><table:table-rows>"
                + "<table:table-row>" + cell("North") + number(1) + number(2) + number(3) + "</table:table-row>"
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
}
