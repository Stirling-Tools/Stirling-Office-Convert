package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.Allocation;
import stirling.software.officeconvert.topdf.testing.TestFonts;

class DocxListsAndLabelsTest {

    private static final String FONT = "Liberation Sans";

    private static final String DEFAULTS = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"" + FONT
            + "\" w:hAnsi=\"" + FONT + "\"/><w:sz w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>"
            + "<w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";

    private static final String LEVELS = "<w:abstractNum w:abstractNumId=\"0\">" + level(0, "%1.", 720)
            + level(1, "%1.%2", 1440) + level(2, "%1.%2.%3", 2880) + "</w:abstractNum>";

    @TempDir
    Path dir;

    private static String level(int ilvl, String text, int left) {
        return "<w:lvl w:ilvl=\"" + ilvl + "\"><w:start w:val=\"1\"/><w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\""
                + text + "\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"" + left + "\" w:hanging=\"720\"/>"
                + "</w:pPr></w:lvl>";
    }

    private static String para(String pPr, String text) {
        return "<w:p><w:pPr>" + pPr + "</w:pPr><w:r><w:t xml:space=\"preserve\">" + text + "</w:t></w:r></w:p>";
    }

    private static String numbered(int numId, int ilvl, String text) {
        return para("<w:numPr><w:ilvl w:val=\"" + ilvl + "\"/><w:numId w:val=\"" + numId + "\"/></w:numPr>", text);
    }

    @Test
    void aLevelStartOverrideNumbersTheParentLevelsAListHasNotUsedYet() throws IOException {
        String numbering = LEVELS + "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num><w:num w:numId=\"2\">"
                + "<w:abstractNumId w:val=\"0\"/><w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"15\"/>"
                + "</w:lvlOverride><w:lvlOverride w:ilvl=\"1\"><w:startOverride w:val=\"12\"/></w:lvlOverride>"
                + "<w:lvlOverride w:ilvl=\"2\"><w:startOverride w:val=\"1\"/></w:lvlOverride></w:num>";
        String body = numbered(1, 0, "Opening") + numbered(2, 2, "First") + numbered(2, 2, "Second")
                + numbered(2, 0, "Next");
        String text = DocxDoc.render(dir, "override", new DocxDoc().styles(DEFAULTS).numbering(numbering)
                .body(body).bytes()).text();
        assertTrue(text.contains("15.12.1"), text);
        assertTrue(text.contains("15.12.2"), text);
        assertTrue(text.contains("16."), text);
        assertFalse(text.contains("14.11"), text);
    }

    @Test
    void numberingSetByAStyleOutranksTheIndentsOfTheStylesItIsBasedOn() throws IOException {
        String numbering = LEVELS + "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>";
        String styles = DEFAULTS + "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List"
                + " Paragraph\"/><w:pPr><w:ind w:left=\"720\"/></w:pPr></w:style><w:style w:type=\"paragraph\""
                + " w:styleId=\"Deep\"><w:name w:val=\"Deep\"/><w:basedOn w:val=\"ListParagraph\"/><w:pPr><w:numPr>"
                + "<w:ilvl w:val=\"2\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr></w:style><w:style w:type=\"paragraph\""
                + " w:styleId=\"Own\"><w:name w:val=\"Own\"/><w:basedOn w:val=\"ListParagraph\"/><w:pPr><w:numPr>"
                + "<w:ilvl w:val=\"2\"/><w:numId w:val=\"1\"/></w:numPr><w:ind w:left=\"1440\" w:hanging=\"720\"/>"
                + "</w:pPr></w:style>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "styled", new DocxDoc().styles(styles).numbering(numbering)
                .body(para("<w:pStyle w:val=\"Deep\"/>", "Inherited") + para("<w:pStyle w:val=\"Own\"/>", "Own"))
                .bytes());
        assertEquals(72 + 144, r.word("Inherited").x(), 0.5f);
        assertEquals(72 + 72, r.word("Own").x(), 0.5f);
    }

    @Test
    void everyRepeatedHeaderRowAtTheTopTakesTheFirstRowStyle() throws IOException {
        TestFonts.assumeInstalled("Calibri", true, TestFonts.CALIBRI);
        String styles = DEFAULTS.replace(FONT, "Calibri") + "<w:style w:type=\"table\" w:styleId=\"Grid\"><w:name w:val=\"Grid\"/>"
                + "<w:tblStylePr w:type=\"firstRow\"><w:rPr><w:b/></w:rPr></w:tblStylePr></w:style>";
        String header = "<w:trPr><w:tblHeader/></w:trPr>";
        String table = "<w:tbl><w:tblPr><w:tblStyle w:val=\"Grid\"/><w:tblLook w:firstRow=\"1\"/></w:tblPr>"
                + "<w:tblGrid><w:gridCol w:w=\"4000\"/></w:tblGrid><w:tr>" + header + "<w:tc>" + DocxDoc.p("Caption")
                + "</w:tc></w:tr><w:tr>" + header + "<w:tc>" + DocxDoc.p("Heading") + "</w:tc></w:tr><w:tr><w:tc>"
                + DocxDoc.p("Body") + "</w:tc></w:tr></w:tbl>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "headers", new DocxDoc().styles(styles).body(table).bytes());
        Map<String, String> fonts = fonts(r);
        assertTrue(fonts.get("Caption").contains("Bold"), fonts.toString());
        assertTrue(fonts.get("Heading").contains("Bold"), fonts.toString());
        assertFalse(fonts.get("Body").contains("Bold"), fonts.toString());
    }

    @Test
    void aPointLabelWithItsOwnTextReplacesTheGeneratedLabel() throws IOException {
        String chart = "<c:chartSpace xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\""
                + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><c:chart><c:autoTitleDeleted"
                + " val=\"1\"/><c:plotArea><c:layout/><c:pieChart><c:varyColors val=\"1\"/><c:ser><c:idx val=\"0\"/>"
                + "<c:order val=\"0\"/><c:dLbls><c:dLbl><c:idx val=\"0\"/><c:tx><c:rich><a:bodyPr/><a:p><a:r><a:t>"
                + "Written</a:t></a:r></a:p><a:p><a:r><a:t>Label</a:t></a:r></a:p></c:rich></c:tx><c:showVal val=\"1\"/>"
                + "</c:dLbl><c:dLbl><c:idx val=\"1\"/><c:delete val=\"1\"/></c:dLbl><c:separator>; </c:separator>"
                + "<c:showVal val=\"1\"/><c:showCatName val=\"1\"/></c:dLbls><c:cat><c:strRef><c:f>S!$A$1</c:f>"
                + "<c:strCache><c:ptCount val=\"3\"/><c:pt idx=\"0\"><c:v>Alpha</c:v></c:pt><c:pt idx=\"1\"><c:v>Beta"
                + "</c:v></c:pt><c:pt idx=\"2\"><c:v>Gamma</c:v></c:pt></c:strCache></c:strRef></c:cat><c:val><c:numRef>"
                + "<c:f>S!$B$1</c:f><c:numCache><c:formatCode>General</c:formatCode><c:ptCount val=\"3\"/><c:pt"
                + " idx=\"0\"><c:v>5</c:v></c:pt><c:pt idx=\"1\"><c:v>3</c:v></c:pt><c:pt idx=\"2\"><c:v>2</c:v></c:pt>"
                + "</c:numCache></c:numRef></c:val></c:ser></c:pieChart></c:plotArea></c:chart></c:chartSpace>";
        DocxDoc doc = new DocxDoc().chart("chart1.xml", "rIdChart", chart)
                .body("<w:p>" + DocxDoc.chartRun("rIdChart") + "</w:p>");
        String text = DocxDoc.render(dir, "pie", doc.bytes()).text();
        assertTrue(text.contains("Written"), text);
        assertTrue(text.contains("Label"), text);
        assertTrue(text.contains("Gamma; 2"), text);
        assertFalse(text.contains("Alpha"), text);
        assertFalse(text.contains("Beta"), text);
    }

    @Test
    void aKeepWithNextChainLongerThanAPageStaysWhereItStarts() throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 38; i++) {
            body.append(para("", "Filler" + i));
        }
        body.append(para("<w:keepNext/>", "Contents"));
        for (int i = 0; i < 70; i++) {
            body.append(para("<w:keepNext/>", "Entry" + i));
        }
        body.append(para("", "After"));
        DocxDoc.Rendered r = DocxDoc.render(dir, "chain", new DocxDoc().styles(DEFAULTS).body(body.toString())
                .bytes());
        assertEquals(1, r.word("Contents").page());
        assertEquals(1, r.word("Entry0").page());
        assertEquals(r.word("Entry69").page(), r.word("After").page());
    }

    @Test
    void aLabelTextSharedByEveryPointIsDrawnWithinABudget() throws IOException {
        StringBuilder points = new StringBuilder();
        for (int i = 0; i < 4_000; i++) {
            points.append("<c:pt idx=\"").append(i).append("\"><c:v>").append(i % 7 + 1).append("</c:v></c:pt>");
        }
        String chart = "<c:chartSpace xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\""
                + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><c:chart><c:autoTitleDeleted"
                + " val=\"1\"/><c:plotArea><c:layout/><c:barChart><c:barDir val=\"col\"/><c:grouping val=\"clustered\"/>"
                + "<c:ser><c:idx val=\"0\"/><c:order val=\"0\"/><c:dLbls><c:tx><c:rich><a:bodyPr/>"
                + "<a:p><a:r><a:t>Tall</a:t></a:r></a:p>" + "<a:p><a:r><a:t>x</a:t></a:r></a:p>".repeat(4_000)
                + "</c:rich></c:tx><c:showVal val=\"1\"/></c:dLbls><c:val><c:numRef><c:numCache><c:ptCount"
                + " val=\"4000\"/>" + points + "</c:numCache></c:numRef></c:val></c:ser><c:axId val=\"1\"/>"
                + "<c:axId val=\"2\"/></c:barChart><c:catAx><c:axId val=\"1\"/><c:crossAx val=\"2\"/></c:catAx>"
                + "<c:valAx><c:axId val=\"2\"/><c:crossAx val=\"1\"/></c:valAx></c:plotArea></c:chart></c:chartSpace>";
        DocxDoc doc = new DocxDoc().chart("chart1.xml", "rIdChart", chart)
                .body("<w:p>" + DocxDoc.chartRun("rIdChart") + "</w:p><w:p><w:r><w:t>After</w:t></w:r></w:p>");
        Allocation.Measured m = Allocation.measure(() -> DocxDoc.render(dir, "labels", doc.bytes()));
        assertEquals(null, m.failure());
        assertTrue(m.megabytes() < 1_500, m.megabytes() + " MB allocated");
    }

    @Test
    void aLongSeparatorSharedByEveryPointIsDrawnWithinABudget() throws IOException {
        StringBuilder points = new StringBuilder();
        for (int i = 0; i < 4_000; i++) {
            points.append("<c:pt idx=\"").append(i).append("\"><c:v>").append(i % 7 + 1).append("</c:v></c:pt>");
        }
        String chart = "<c:chartSpace xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\""
                + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><c:chart><c:autoTitleDeleted"
                + " val=\"1\"/><c:plotArea><c:layout/><c:barChart><c:barDir val=\"col\"/><c:grouping val=\"clustered\"/>"
                + "<c:ser><c:idx val=\"0\"/><c:order val=\"0\"/><c:tx><c:v>Name</c:v></c:tx><c:dLbls><c:separator>"
                + "x".repeat(1_000_000) + "</c:separator><c:showVal val=\"1\"/><c:showSerName val=\"1\"/></c:dLbls>"
                + "<c:val><c:numRef><c:numCache><c:ptCount val=\"4000\"/>" + points + "</c:numCache></c:numRef></c:val>"
                + "</c:ser><c:axId val=\"1\"/><c:axId val=\"2\"/></c:barChart><c:catAx><c:axId val=\"1\"/><c:crossAx"
                + " val=\"2\"/></c:catAx><c:valAx><c:axId val=\"2\"/><c:crossAx val=\"1\"/></c:valAx></c:plotArea>"
                + "</c:chart></c:chartSpace>";
        DocxDoc doc = new DocxDoc().chart("chart1.xml", "rIdChart", chart)
                .body("<w:p>" + DocxDoc.chartRun("rIdChart") + "</w:p><w:p><w:r><w:t>After</w:t></w:r></w:p>");
        Allocation.Measured m = Allocation.measure(() -> DocxDoc.render(dir, "separator", doc.bytes()));
        assertEquals(null, m.failure());
        assertTrue(m.megabytes() < 1_500, m.megabytes() + " MB allocated");
    }

    private static Map<String, String> fonts(DocxDoc.Rendered r) throws IOException {
        Map<String, String> out = new HashMap<>();
        try (PDDocument d = r.open()) {
            PDFTextStripper s = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    if (!positions.isEmpty()) {
                        out.put(text.strip(), positions.get(0).getFont().getName());
                    }
                }
            };
            s.getText(d);
        }
        return out;
    }
}
