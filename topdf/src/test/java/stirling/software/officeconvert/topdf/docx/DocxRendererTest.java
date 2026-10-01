package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.Fixtures;

class DocxRendererTest {

    @TempDir
    Path dir;

    private static String longText(int words) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words; i++) {
            sb.append(i % 7 == 0 ? "alpha " : "word").append(i).append(' ');
        }
        return sb.toString().trim();
    }

    @Test
    void rendersParagraphTextAsExtractableText() throws IOException {
        byte[] docx = new DocxDoc().body(DocxDoc.p("Hello Word renderer") + DocxDoc.p("Second paragraph")).bytes();
        DocxDoc.Rendered r = DocxDoc.render(dir, "plain", docx);
        String text = r.text();
        assertTrue(text.contains("Hello Word renderer"), text);
        assertTrue(text.contains("Second paragraph"), text);
        assertEquals(1, r.pages());
        assertEquals(1, r.result().pages());
    }

    @Test
    void pageSizeAndOrientationComeFromTheSection() throws IOException {
        String a4Landscape = "<w:sectPr><w:pgSz w:w=\"16838\" w:h=\"11906\" w:orient=\"landscape\"/>"
                + "<w:pgMar w:top=\"720\" w:right=\"720\" w:bottom=\"720\" w:left=\"720\" w:header=\"360\""
                + " w:footer=\"360\" w:gutter=\"0\"/></w:sectPr>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "a4", new DocxDoc().body(DocxDoc.p("Landscape")).section(a4Landscape)
                .bytes());
        try (PDDocument d = r.open()) {
            PDRectangle box = d.getPage(0).getMediaBox();
            assertEquals(841.9f, box.getWidth(), 0.5f);
            assertEquals(595.3f, box.getHeight(), 0.5f);
        }
    }

    @Test
    void longTextFlowsOntoMorePages() throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            body.append(DocxDoc.p("Paragraph " + i + " " + longText(40)));
        }
        DocxDoc.Rendered r = DocxDoc.render(dir, "long", new DocxDoc().body(body.toString()).bytes());
        assertTrue(r.pages() >= 3, "pages: " + r.pages());
        String all = r.text();
        assertTrue(all.contains("Paragraph 0 "), all);
        assertTrue(all.contains("Paragraph 59 "), all);
    }

    @Test
    void pageBreaksStartNewPages() throws IOException {
        String body = DocxDoc.p("First page") + "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>" + DocxDoc.p("Second page")
                + "<w:p><w:pPr><w:pageBreakBefore/></w:pPr><w:r><w:t>Third page</w:t></w:r></w:p>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "breaks", new DocxDoc().body(body).bytes());
        assertEquals(3, r.pages());
        assertTrue(r.text(2).contains("Second page"));
        assertTrue(r.text(3).contains("Third page"));
    }

    @Test
    void footerFieldsShowComputedPageNumbers() throws IOException {
        String footer = "<w:p><w:r><w:t xml:space=\"preserve\">Page </w:t></w:r>"
                + "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText> PAGE </w:instrText></w:r>"
                + "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>9</w:t></w:r>"
                + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r><w:r><w:t xml:space=\"preserve\"> of </w:t></w:r>"
                + "<w:fldSimple w:instr=\" NUMPAGES \"><w:r><w:t>9</w:t></w:r></w:fldSimple></w:p>";
        String sect = "<w:sectPr><w:footerReference w:type=\"default\" r:id=\"rIdfooter1xml\"/>"
                + "<w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\""
                + " w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/></w:sectPr>";
        String body = DocxDoc.p("One") + "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>" + DocxDoc.p("Two");
        DocxDoc.Rendered r = DocxDoc.render(dir, "footer", new DocxDoc().footer("footer1.xml", footer).body(body)
                .section(sect).bytes());
        assertEquals(2, r.pages());
        assertTrue(r.text(1).contains("Page 1 of 2"), r.text(1));
        assertTrue(r.text(2).contains("Page 2 of 2"), r.text(2));
    }

    @Test
    void listsGetTheirLabels() throws IOException {
        String numbering = "<w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/>"
                + "<w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1.\"/><w:lvlJc w:val=\"left\"/>"
                + "<w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr></w:lvl><w:lvl w:ilvl=\"1\">"
                + "<w:start w:val=\"1\"/><w:numFmt w:val=\"lowerRoman\"/><w:lvlText w:val=\"(%2)\"/>"
                + "<w:pPr><w:ind w:left=\"1440\" w:hanging=\"360\"/></w:pPr></w:lvl></w:abstractNum>"
                + "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>";
        String body = item(0, "Apples") + item(0, "Pears") + item(1, "Nested one") + item(1, "Nested two")
                + item(0, "Plums");
        DocxDoc.Rendered r = DocxDoc.render(dir, "list", new DocxDoc().numbering(numbering).body(body).bytes());
        String text = r.text();
        assertTrue(text.contains("1.") && text.contains("Apples"), text);
        assertTrue(text.contains("2.") && text.contains("Pears"), text);
        assertTrue(text.contains("(ii)") && text.contains("Nested two"), text);
        assertTrue(text.contains("3.") && text.contains("Plums"), text);
    }

    private static String item(int level, String text) {
        return "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"" + level + "\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr>"
                + "<w:r><w:t>" + text + "</w:t></w:r></w:p>";
    }

    @Test
    void tablesRenderEveryCell() throws IOException {
        StringBuilder t = new StringBuilder("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders>"
                + "<w:top w:val=\"single\" w:sz=\"4\"/><w:bottom w:val=\"single\" w:sz=\"4\"/>"
                + "<w:insideH w:val=\"single\" w:sz=\"4\"/></w:tblBorders></w:tblPr>"
                + "<w:tblGrid><w:gridCol w:w=\"3000\"/><w:gridCol w:w=\"3000\"/></w:tblGrid>");
        for (int row = 0; row < 3; row++) {
            t.append("<w:tr>");
            for (int col = 0; col < 2; col++) {
                t.append("<w:tc><w:tcPr><w:tcW w:w=\"3000\" w:type=\"dxa\"/></w:tcPr>")
                        .append(DocxDoc.p("cell" + row + col)).append("</w:tc>");
            }
            t.append("</w:tr>");
        }
        t.append("</w:tbl>");
        DocxDoc.Rendered r = DocxDoc.render(dir, "table", new DocxDoc().body(t + DocxDoc.p("After")).bytes());
        String text = r.text();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 2; col++) {
                assertTrue(text.contains("cell" + row + col), text);
            }
        }
        assertTrue(text.contains("After"));
    }

    @Test
    void longTablesRepeatTheirHeaderRow() throws IOException {
        StringBuilder t = new StringBuilder("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/></w:tblPr>"
                + "<w:tblGrid><w:gridCol w:w=\"4000\"/></w:tblGrid><w:tr><w:trPr><w:tblHeader/></w:trPr><w:tc>"
                + DocxDoc.p("HEADERROW") + "</w:tc></w:tr>");
        for (int row = 0; row < 120; row++) {
            t.append("<w:tr><w:tc>").append(DocxDoc.p("row " + row)).append("</w:tc></w:tr>");
        }
        t.append("</w:tbl>");
        DocxDoc.Rendered r = DocxDoc.render(dir, "header-rows", new DocxDoc().body(t + DocxDoc.p("")).bytes());
        assertTrue(r.pages() >= 2);
        for (int page = 1; page <= r.pages(); page++) {
            assertTrue(r.text(page).contains("HEADERROW"), "page " + page);
        }
        assertTrue(r.text().contains("row 119"));
    }

    @Test
    void hiddenAndDeletedTextIsLeftOutInsertedTextStays() throws IOException {
        String body = "<w:p><w:r><w:t xml:space=\"preserve\">Visible </w:t></w:r>"
                + "<w:r><w:rPr><w:vanish/></w:rPr><w:t>HIDDENTEXT</w:t></w:r>"
                + "<w:del w:id=\"1\" w:author=\"a\"><w:r><w:delText>DELETEDTEXT</w:delText></w:r></w:del>"
                + "<w:ins w:id=\"2\" w:author=\"a\"><w:r><w:t>INSERTED</w:t></w:r></w:ins>"
                + "<w:moveFrom w:id=\"3\" w:author=\"a\"><w:r><w:t>MOVEDAWAY</w:t></w:r></w:moveFrom></w:p>";
        String text = DocxDoc.render(dir, "tracked", new DocxDoc().body(body).bytes()).text();
        assertTrue(text.contains("Visible"), text);
        assertTrue(text.contains("INSERTED"), text);
        assertFalse(text.contains("HIDDENTEXT"), text);
        assertFalse(text.contains("DELETEDTEXT"), text);
        assertFalse(text.contains("MOVEDAWAY"), text);
    }

    @Test
    void fieldsShowTheirCachedResultNeverTheirCode() throws IOException {
        String body = "<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText xml:space=\"preserve\">"
                + " DATE \\@ \"yyyy\" </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>"
                + "<w:r><w:t>CACHEDDATE</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>"
                + "<w:p><w:fldSimple w:instr=\" AUTHOR \"><w:r><w:t>CACHEDAUTHOR</w:t></w:r></w:fldSimple></w:p>";
        String text = DocxDoc.render(dir, "fields", new DocxDoc().body(body).bytes()).text();
        assertTrue(text.contains("CACHEDDATE"), text);
        assertTrue(text.contains("CACHEDAUTHOR"), text);
        assertFalse(text.contains("DATE \\@"), text);
        assertFalse(text.replace("CACHEDAUTHOR", "").contains("AUTHOR"), text);
    }

    @Test
    void fieldsWithoutAResultShowTheDocumentsStoredProperties() throws IOException {
        String body = "<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText xml:space=\"preserve\">"
                + " DATE \\@ \"dddd d MMMM yyyy\" </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r>"
                + "<w:r><w:t xml:space=\"preserve\"> made </w:t></w:r>"
                + "<w:fldSimple w:instr=\" CREATEDATE \\@ &quot;dd/MM/yy HH:mm:ss&quot; \"/></w:p>"
                + "<w:p><w:fldSimple w:instr=\" AUTHOR \\* Upper \\* MERGEFORMAT \"/><w:r><w:t xml:space=\"preserve\">"
                + " | </w:t></w:r><w:fldSimple w:instr=\" TITLE \"/><w:r><w:t xml:space=\"preserve\"> | </w:t></w:r>"
                + "<w:fldSimple w:instr=\" DOCPROPERTY &quot;Classification&quot; \"/><w:r><w:t xml:space=\"preserve\">"
                + " | </w:t></w:r><w:fldSimple w:instr=\" NUMWORDS \"/><w:r><w:t xml:space=\"preserve\"> | </w:t></w:r>"
                + "<w:fldSimple w:instr=\" FILENAME \"/><w:r><w:t xml:space=\"preserve\"> | </w:t></w:r>"
                + "<w:fldSimple w:instr=\" USERNAME \"/><w:r><w:t xml:space=\"preserve\">end</w:t></w:r></w:p>"
                + "<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText xml:space=\"preserve\">"
                + " IF </w:instrText></w:r><w:fldSimple w:instr=\" TITLE \"/><w:r><w:instrText xml:space=\"preserve\">"
                + " = \"x\" \"yes\" \"no\" </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>"
                + "<w:r><w:t>CACHEDIF</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r>"
                + "<w:fldSimple w:instr=\" SAVEDATE \"><w:r><w:t>kept 1999</w:t></w:r></w:fldSimple></w:p>";
        DocxDoc doc = new DocxDoc().body(body);
        doc.zip().put("docProps/core.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\""
                + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><dc:title>Quarterly plan</dc:title>"
                + "<dc:creator>Ann Example</dc:creator><dcterms:created xsi:type=\"dcterms:W3CDTF\">2020-01-02T03:04:05Z"
                + "</dcterms:created><dcterms:modified xsi:type=\"dcterms:W3CDTF\">2021-03-04T13:05:00Z</dcterms:modified>"
                + "</cp:coreProperties>");
        doc.zip().put("docProps/app.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">"
                + "<Words>421</Words></Properties>");
        doc.zip().put("docProps/custom.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/custom-properties\""
                + " xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\"><property"
                + " fmtid=\"{D5CDD505-2E9C-101B-9397-08002B2CF9AE}\" pid=\"2\" name=\"Classification\"><vt:lpwstr>"
                + "OFFICIAL</vt:lpwstr></property></Properties>");
        doc.zip().relationship("/", "rIdCustom", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/"
                + "custom-properties", "docProps/custom.xml", false);
        String text = DocxDoc.render(dir, "stored", doc.bytes()).text().replaceAll("\\s+", " ");
        assertTrue(text.contains("Thursday 4 March 2021 made 02/01/20 03:04:05"), text);
        assertTrue(text.contains("ANN EXAMPLE | Quarterly plan | OFFICIAL | 421 | stored.docx | end"), text);
        assertTrue(text.contains("CACHEDIF") && text.contains("kept 1999") && !text.contains("Quarterly planCACHED"),
                text);
    }

    @Test
    void autonumFieldsWithoutAResultNumberTheirParagraphs() throws IOException {
        StringBuilder body = new StringBuilder();
        for (String switches : new String[] {"", "", " \\s :"}) {
            body.append("<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText xml:space=\"preserve\">")
                    .append(" AUTONUM").append(switches).append(" </w:instrText></w:r><w:r><w:fldChar")
                    .append(" w:fldCharType=\"end\"/></w:r><w:r><w:t xml:space=\"preserve\"> Item</w:t></w:r></w:p>");
        }
        String text = DocxDoc.render(dir, "autonum", new DocxDoc().body(body.toString()).bytes()).text();
        assertTrue(text.contains("1. Item"), text);
        assertTrue(text.contains("2. Item"), text);
        assertTrue(text.contains("3: Item"), text);
        assertFalse(text.contains("AUTONUM"), text);
    }

    @Test
    void aListThatLinksToANumberingStyleTakesThatStylesLevels() throws IOException {
        String numbering = "<w:abstractNum w:abstractNumId=\"0\"><w:styleLink w:val=\"HouseList\"/><w:lvl w:ilvl=\"0\">"
                + "<w:start w:val=\"7\"/><w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1)\"/><w:lvlJc w:val=\"left\"/>"
                + "</w:lvl></w:abstractNum><w:abstractNum w:abstractNumId=\"1\"><w:numStyleLink w:val=\"HouseList\"/>"
                + "</w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num><w:num w:numId=\"2\">"
                + "<w:abstractNumId w:val=\"1\"/></w:num>";
        String styles = "<w:style w:type=\"numbering\" w:styleId=\"HouseList\"><w:name w:val=\"House List\"/><w:pPr>"
                + "<w:numPr><w:numId w:val=\"1\"/></w:numPr></w:pPr></w:style>";
        String body = "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"2\"/></w:numPr></w:pPr><w:r><w:t>Item"
                + "</w:t></w:r></w:p>";
        String text = DocxDoc.render(dir, "stylelink", new DocxDoc().styles(styles).numbering(numbering).body(body)
                .bytes()).text();
        assertTrue(text.contains("7)"), text);
    }

    @Test
    void footnotesAppearOnTheirPage() throws IOException {
        String notes = "<w:footnote w:type=\"separator\" w:id=\"-1\"><w:p><w:r><w:separator/></w:r></w:p></w:footnote>"
                + "<w:footnote w:id=\"1\"><w:p><w:r><w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr>"
                + "<w:footnoteRef/></w:r><w:r><w:t xml:space=\"preserve\"> The note text</w:t></w:r></w:p></w:footnote>";
        String body = "<w:p><w:r><w:t>Body with a note</w:t></w:r><w:r><w:rPr><w:vertAlign w:val=\"superscript\"/>"
                + "</w:rPr><w:footnoteReference w:id=\"1\"/></w:r></w:p>";
        String text = DocxDoc.render(dir, "notes", new DocxDoc().footnotes(notes).body(body).bytes()).text();
        assertTrue(text.contains("Body with a note"), text);
        assertTrue(text.contains("The note text"), text);
        assertTrue(text.indexOf("The note text") > text.indexOf("Body with a note"), text);
    }

    @Test
    void safeHyperlinksBecomeLinkAnnotations() throws IOException {
        String body = "<w:p><w:hyperlink r:id=\"rIdWeb\"><w:r><w:t>Example site</w:t></w:r></w:hyperlink></w:p>";
        DocxDoc doc = new DocxDoc().relationship("rIdWeb", Fixtures.REL + "hyperlink", "https://example.com/page", true)
                .body(body);
        DocxDoc.Rendered r = DocxDoc.render(dir, "links", doc.bytes());
        List<String> uris = new ArrayList<>();
        try (PDDocument d = r.open()) {
            for (PDAnnotation a : d.getPage(0).getAnnotations()) {
                if (a instanceof PDAnnotationLink link && link.getAction() instanceof PDActionURI uri) {
                    uris.add(uri.getURI());
                }
            }
        }
        assertEquals(List.of("https://example.com/page"), uris);
    }

    @Test
    void inlinePicturesAreDrawn() throws IOException {
        byte[] png = Fixtures.png(8, 8, Color.BLUE);
        String body = "<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"914400\" cy=\"914400\"/><wp:docPr id=\"1\""
                + " name=\"p\"/><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
                + "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"1\" name=\"p\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill>"
                + "<a:blip r:embed=\"rIdImg\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm>"
                + "<a:off x=\"0\" y=\"0\"/><a:ext cx=\"914400\" cy=\"914400\"/></a:xfrm><a:prstGeom prst=\"rect\"/>"
                + "</pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "picture", new DocxDoc().media("blue.png", png, "rIdImg").body(body)
                .bytes());
        try (PDDocument d = r.open()) {
            assertTrue(imageCount(d.getPage(0)) >= 1);
        }
    }

    private static int imageCount(PDPage page) throws IOException {
        int n = 0;
        PDResources res = page.getResources();
        for (var name : res.getXObjectNames()) {
            if (res.isImageXObject(name)) {
                n++;
            }
        }
        return n;
    }

    @Test
    void textBoxesShowTheirContent() throws IOException {
        String body = "<w:p><w:r><mc:AlternateContent><mc:Choice Requires=\"wps\"><w:drawing><wp:anchor"
                + " simplePos=\"0\" relativeHeight=\"1\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\""
                + " allowOverlap=\"1\"><wp:simplePos x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"page\">"
                + "<wp:posOffset>2540000</wp:posOffset></wp:positionH><wp:positionV relativeFrom=\"page\">"
                + "<wp:posOffset>2540000</wp:posOffset></wp:positionV><wp:extent cx=\"2540000\" cy=\"635000\"/>"
                + "<wp:wrapNone/><wp:docPr id=\"2\" name=\"tb\"/><a:graphic><a:graphicData"
                + " uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\"><wps:wsp><wps:spPr>"
                + "<a:prstGeom prst=\"rect\"/><a:solidFill><a:srgbClr val=\"DDEEFF\"/></a:solidFill></wps:spPr>"
                + "<wps:txbx><w:txbxContent>" + DocxDoc.p("Inside the box") + "</w:txbxContent></wps:txbx><wps:bodyPr/>"
                + "</wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing></mc:Choice><mc:Fallback>"
                + "<w:t>FALLBACK</w:t></mc:Fallback></mc:AlternateContent></w:r></w:p>";
        String text = DocxDoc.render(dir, "textbox", new DocxDoc().body(body).bytes()).text();
        assertTrue(text.contains("Inside the box"), text);
        assertFalse(text.contains("FALLBACK"), text);
    }

    @Test
    void stylesNumberingAndSpacingAreResolved() throws IOException {
        String styles = "<w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val=\"22\"/></w:rPr></w:rPrDefault>"
                + "</w:docDefaults><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name"
                + " w:val=\"Normal\"/></w:style><w:style w:type=\"paragraph\" w:styleId=\"Big\"><w:basedOn"
                + " w:val=\"Normal\"/><w:pPr><w:spacing w:before=\"480\"/></w:pPr><w:rPr><w:b/><w:caps/>"
                + "<w:sz w:val=\"48\"/></w:rPr></w:style>";
        String body = "<w:p><w:pPr><w:pStyle w:val=\"Big\"/></w:pPr><w:r><w:t>Heading text</w:t></w:r></w:p>"
                + DocxDoc.p("Body");
        String text = DocxDoc.render(dir, "styles", new DocxDoc().styles(styles).body(body).bytes()).text();
        assertTrue(text.contains("HEADING TEXT"), text);
    }

    @Test
    void columnsAndSectionsLayOutEveryParagraph() throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            body.append(DocxDoc.p("Column text " + i + " " + longText(30)));
        }
        body.append("<w:p><w:pPr><w:sectPr><w:type w:val=\"continuous\"/><w:pgSz w:w=\"12240\" w:h=\"15840\"/>"
                + "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\""
                + " w:footer=\"720\" w:gutter=\"0\"/><w:cols w:num=\"2\" w:space=\"720\"/></w:sectPr></w:pPr></w:p>");
        body.append(DocxDoc.p("After the columns"));
        String sect = "<w:sectPr><w:type w:val=\"continuous\"/><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar"
                + " w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\""
                + " w:gutter=\"0\"/></w:sectPr>";
        String text = DocxDoc.render(dir, "columns", new DocxDoc().body(body.toString()).section(sect).bytes()).text();
        for (int i = 0; i < 12; i++) {
            assertTrue(text.contains("Column text " + i + " "), "missing " + i);
        }
        assertTrue(text.contains("After the columns"));
    }

    @Test
    void emptyAndOddDocumentsStillConvert() throws IOException {
        DocxDoc.Rendered empty = DocxDoc.render(dir, "empty", new DocxDoc().body("").bytes());
        assertEquals(1, empty.pages());
        String odd = "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"12\"/><w:numId w:val=\"77\"/></w:numPr><w:ind"
                + " w:left=\"-99999\" w:hanging=\"99999\"/><w:spacing w:line=\"-5\" w:lineRule=\"exact\"/></w:pPr>"
                + "<w:r><w:rPr><w:sz w:val=\"0\"/><w:rFonts w:ascii=\"No Such Font\"/></w:rPr><w:t>Odd</w:t></w:r></w:p>"
                + "<w:tbl><w:tr><w:tc><w:tcPr><w:gridSpan w:val=\"50\"/><w:vMerge/></w:tcPr></w:tc></w:tr></w:tbl>"
                + "<w:p><w:r><w:sym w:font=\"Wingdings\" w:char=\"F0FC\"/><w:tab/><w:br w:type=\"column\"/></w:r></w:p>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "odd", new DocxDoc().body(odd).bytes());
        assertTrue(r.pages() >= 1);
        assertTrue(r.text().contains("Odd"));
    }

    @Test
    void framesDropCapsAndClearBreaksRender() throws IOException {
        String body = "<w:p><w:pPr><w:framePr w:w=\"2000\" w:hSpace=\"180\" w:wrap=\"around\" w:vAnchor=\"page\""
                + " w:hAnchor=\"page\" w:x=\"7000\" w:y=\"2000\"/></w:pPr><w:r><w:t>FRAMED</w:t></w:r></w:p>"
                + "<w:p><w:pPr><w:framePr w:dropCap=\"drop\" w:lines=\"3\" w:wrap=\"around\" w:vAnchor=\"text\""
                + " w:hAnchor=\"text\"/></w:pPr><w:r><w:rPr><w:sz w:val=\"96\"/></w:rPr><w:t>D</w:t></w:r></w:p>"
                + DocxDoc.p("rop cap paragraph " + longText(60))
                + "<w:p><w:pPr><w:tabs><w:tab w:val=\"bar\" w:pos=\"4000\"/></w:tabs></w:pPr><w:r><w:t>Bar</w:t></w:r>"
                + "<w:r><w:br w:clear=\"all\"/><w:t>After clear</w:t></w:r></w:p>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "frames", new DocxDoc().body(body).bytes());
        String text = r.text();
        assertTrue(text.contains("FRAMED"), text);
        assertTrue(text.contains("rop cap paragraph"), text);
        assertTrue(text.contains("After clear"), text);
        assertEquals(1, r.pages());
    }

    @Test
    void textWrapsAroundSquareFloatsOnBothSides() throws IOException {
        byte[] png = Fixtures.png(8, 8, Color.RED);
        String anchor = "<w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"114300\" distR=\"114300\""
                + " simplePos=\"0\" relativeHeight=\"2\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\""
                + " allowOverlap=\"1\"><wp:simplePos x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"column\">"
                + "<wp:align>center</wp:align></wp:positionH><wp:positionV relativeFrom=\"paragraph\"><wp:posOffset>0"
                + "</wp:posOffset></wp:positionV><wp:extent cx=\"1270000\" cy=\"1270000\"/><wp:wrapSquare"
                + " wrapText=\"bothSides\"/><wp:docPr id=\"3\" name=\"p\"/><a:graphic><a:graphicData"
                + " uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:nvPicPr><pic:cNvPr"
                + " id=\"3\" name=\"p\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"rIdImg\"/>"
                + "<a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:prstGeom prst=\"rect\"/></pic:spPr>"
                + "</pic:pic></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r>";
        String body = "<w:p>" + anchor + "<w:r><w:t>" + longText(120) + "</w:t></w:r></w:p>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "wrap", new DocxDoc().media("red.png", png, "rIdImg").body(body)
                .bytes());
        String text = r.text().replaceAll("\s+", " ");
        assertTrue(text.contains("word118"), text);
        assertTrue(text.contains("alpha 0"), text);
    }

    @Test
    void mergedCellsShowTheirContentOnce() throws IOException {
        String cell = "<w:tc><w:tcPr><w:tcW w:w=\"2000\" w:type=\"dxa\"/>%s</w:tcPr>%s</w:tc>";
        String t = "<w:tbl><w:tblPr><w:tblBorders><w:insideH w:val=\"single\" w:sz=\"4\"/><w:insideV w:val=\"single\""
                + " w:sz=\"4\"/></w:tblBorders></w:tblPr><w:tblGrid><w:gridCol w:w=\"2000\"/><w:gridCol w:w=\"2000\"/>"
                + "<w:gridCol w:w=\"2000\"/></w:tblGrid>"
                + "<w:tr>" + String.format(cell, "<w:vMerge w:val=\"restart\"/>", DocxDoc.p("TALL"))
                + String.format(cell, "<w:hMerge w:val=\"restart\"/>", DocxDoc.p("WIDE"))
                + String.format(cell, "<w:hMerge/>", DocxDoc.p("HIDDENMERGE")) + "</w:tr>"
                + "<w:tr>" + String.format(cell, "<w:vMerge/>", DocxDoc.p("GONE")) + String.format(cell, "", DocxDoc.p("b1"))
                + String.format(cell, "", DocxDoc.p("b2")) + "</w:tr></w:tbl>" + DocxDoc.p("end");
        String text = DocxDoc.render(dir, "merged", new DocxDoc().body(t).bytes()).text();
        assertTrue(text.contains("TALL") && text.contains("WIDE") && text.contains("b2"), text);
        assertFalse(text.contains("HIDDENMERGE"), text);
        assertFalse(text.contains("GONE"), text);
    }

    @Test
    void mixedDirectionTextKeepsItsWords() throws IOException {
        String body = DocxDoc.p("The Arabic word \u0645\u0631\u062D\u0628\u0627 means hello")
                + "<w:p><w:pPr><w:bidi/></w:pPr><w:r><w:rPr><w:rtl/></w:rPr><w:t>\u05E9\u05DC\u05D5\u05DD \u05E2\u05D5\u05DC\u05DD"
                + "</w:t></w:r></w:p>";
        String text = DocxDoc.render(dir, "bidi", new DocxDoc().body(body).bytes()).text();
        assertTrue(text.contains("The Arabic word"), text);
        assertTrue(text.contains("means hello"), text);
    }
}
