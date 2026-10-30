package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.io.XmlElements;

class DocxBodyBudgetTest {

    private static final String W = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";

    @TempDir
    Path dir;

    private static List<XEl> stream(String body, long[] left) throws IOException {
        String xml = "<w:document " + W + "><w:body>" + body + "<w:sectPr><w:pgSz w:w=\"9000\"/></w:sectPr>"
                + "</w:body></w:document>";
        List<XEl> blocks = new ArrayList<>();
        XTree.streamBody(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), left, blocks::add);
        return blocks;
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aBodyPastItsElementBudgetStopsBuildingButKeepsItsSection() throws IOException {
        long[] left = {40};
        List<XEl> blocks = stream("<w:p><w:r><w:t>x</w:t></w:r></w:p>".repeat(100_000), left);
        assertTrue(left[0] < 0);
        assertEquals(15, blocks.size());
        assertTrue(blocks.get(blocks.size() - 1).is("w:sectPr"));
        assertEquals("9000", blocks.get(blocks.size() - 1).child("w:pgSz").attr("w"));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aTablePastTheBudgetIsCutBetweenItsRows() throws IOException {
        long[] left = {500};
        String row = "<w:tr><w:tc><w:p><w:r><w:t>x</w:t></w:r></w:p></w:tc></w:tr>";
        List<XEl> blocks = stream("<w:tbl>" + row.repeat(50_000) + "</w:tbl>", left);
        assertTrue(left[0] < 0);
        assertEquals(2, blocks.size());
        int rows = blocks.get(0).children("w:tr").size();
        assertTrue(rows > 0 && rows <= 100, String.valueOf(rows));
    }

    @Test
    void aBodyWithinItsBudgetIsReadWhole() throws IOException {
        long[] left = {1000};
        List<XEl> blocks = stream("<w:p><w:r><w:t>x</w:t></w:r></w:p>".repeat(10), left);
        assertEquals(11, blocks.size());
        assertEquals(1000 - 30 - 2, left[0]);
    }

    @Test
    void theBodyBudgetFollowsTheMemoryBudget() {
        long most = XTree.bodyElements();
        assertTrue(most >= XTree.MIN_BODY_ELEMENTS && most <= XTree.MAX_BODY_ELEMENTS, String.valueOf(most));
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void aHeaderWithTooManyXmlElementsIsLeftOutWithAWarning() throws IOException {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i <= XmlElements.MAX_PART; i++) {
            huge.append("<w:p w:rsidR=\"").append(Integer.toHexString(i * 7919))
                    .append("\"/>");
        }
        String sect = "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rIdheaderxml\"/></w:sectPr>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "header", new DocxDoc().header("header.xml", huge.toString())
                .section(sect).body(DocxDoc.p("kept body")).bytes());
        assertTrue(r.text().contains("kept body"), r.text());
        assertTrue(r.result().warnings().stream().anyMatch(w -> w.contains("more than " + XmlElements.MAX_PART
                + " XML elements")), r.result().warnings().toString());
    }
}
