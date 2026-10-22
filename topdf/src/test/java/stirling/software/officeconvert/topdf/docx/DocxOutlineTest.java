package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PageMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.Outlines;

class DocxOutlineTest {

    @TempDir
    Path dir;

    private static final String STYLES = "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">"
            + "<w:name w:val=\"Normal\"/></w:style>"
            + heading("Heading1", "heading 1", "<w:outlineLvl w:val=\"0\"/><w:numPr><w:numId w:val=\"1\"/></w:numPr>")
            + heading("Heading2", "heading 2", "<w:outlineLvl w:val=\"1\"/>")
            + heading("Heading3", "heading 3", "<w:outlineLvl w:val=\"2\"/>")
            + heading("Heading4", "heading 4", "")
            + "<w:style w:type=\"paragraph\" w:styleId=\"Sub\"><w:name w:val=\"Sub\"/><w:basedOn w:val=\"Heading2\"/>"
            + "</w:style>";

    private static final String NUMBERING = "<w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:start"
            + " w:val=\"1\"/><w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1.\"/></w:lvl></w:abstractNum>"
            + "<w:abstractNum w:abstractNumId=\"1\"><w:lvl w:ilvl=\"0\"><w:numFmt w:val=\"bullet\"/><w:lvlText"
            + " w:val=\"•\"/></w:lvl></w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>"
            + "<w:num w:numId=\"2\"><w:abstractNumId w:val=\"1\"/></w:num>";

    private static String heading(String id, String name, String pPr) {
        return "<w:style w:type=\"paragraph\" w:styleId=\"" + id + "\"><w:name w:val=\"" + name + "\"/><w:basedOn"
                + " w:val=\"Normal\"/><w:pPr>" + pPr + "</w:pPr><w:rPr><w:b/></w:rPr></w:style>";
    }

    private static String p(String style, String pPr, String runs) {
        String ps = style == null ? "" : "<w:pStyle w:val=\"" + style + "\"/>";
        return "<w:p><w:pPr>" + ps + pPr + "</w:pPr>" + runs + "</w:p>";
    }

    private static String r(String text) {
        return "<w:r><w:t xml:space=\"preserve\">" + DocxDoc.escape(text) + "</w:t></w:r>";
    }

    private static String styled(String style, String text) {
        return p(style, "", r(text));
    }

    private List<String> outline(String name, DocxDoc doc) throws IOException {
        DocxDoc.Rendered r = DocxDoc.render(dir, name, doc.styles(STYLES).numbering(NUMBERING).bytes());
        return Outlines.titles(r.pdf());
    }

    @Test
    void headingsBecomeBookmarksNestedByOutlineLevel() throws IOException {
        String body = styled("Heading1", "Introduction") + styled("Heading2", "Scope")
                + styled(null, "Body text") + styled("Heading3", "Detail") + styled("Heading1", "Method")
                + styled("Heading3", "Skipped a level") + styled("Sub", "Inherited from Heading 2")
                + p(null, "<w:outlineLvl w:val=\"1\"/>", r("Direct level"))
                + p("Heading2", "<w:outlineLvl w:val=\"9\"/>", r("Body level override"))
                + styled("Heading4", "No outline level");
        assertEquals(List.of("1 p1 1. Introduction", "2 p1 Scope", "3 p1 Detail", "1 p1 2. Method",
                "2 p1 Skipped a level", "2 p1 Inherited from Heading 2", "2 p1 Direct level"),
                outline("levels", new DocxDoc().body(body)));
    }

    @Test
    void titlesAreTheVisibleTextWithWhitespaceCollapsed() throws IOException {
        String body = p("Heading2", "", r("  Tabs") + "<w:r><w:tab/><w:t>and</w:t></w:r><w:r><w:br/><w:t>breaks"
                + "</w:t></w:r>" + r("   spaced  ")) + p("Heading2", "", r("Shown") + "<w:r><w:rPr><w:vanish/>"
                + "</w:rPr><w:t>Hidden</w:t></w:r>" + r(" text"))
                + p("Heading2", "<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"2\"/></w:numPr>", r("Bulleted"))
                + styled("Heading2", "") + styled("Heading2", "   ");
        assertEquals(List.of("1 p1 Tabs and breaks spaced", "1 p1 Shown text", "1 p1 Bulleted"),
                outline("titles", new DocxDoc().body(body)));
    }

    @Test
    void bookmarksPointAtThePageAndLineOfTheirHeading() throws IOException {
        String body = styled("Heading1", "First") + styled(null, "Text")
                + "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>" + styled(null, "More") + styled("Heading2", "Second");
        DocxDoc.Rendered r = DocxDoc.render(dir, "pages", new DocxDoc().body(body).styles(STYLES)
                .numbering(NUMBERING).bytes());
        List<Outlines.Mark> marks = Outlines.read(r.pdf());
        assertEquals(2, marks.size(), marks.toString());
        assertEquals(1, marks.get(0).page());
        assertEquals(2, marks.get(1).page());
        assertEquals(720, marks.get(0).top(), 2);
        assertTrue(marks.get(1).top() < marks.get(0).top() - 5, marks.toString());
        DocxDoc.Word second = r.words().stream().filter(w -> w.text().equals("Second")).findFirst().orElseThrow();
        assertEquals(792 - second.y(), marks.get(1).top(), 14);
    }

    @Test
    void headingsInTablesAndTextBoxesCountButNotThoseInHeaders() throws IOException {
        String table = "<w:tbl><w:tblGrid><w:gridCol w:w=\"4000\"/></w:tblGrid><w:tr><w:tc>"
                + styled("Heading2", "In a table") + "</w:tc></w:tr></w:tbl>";
        String header = "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rIdheader1xml\"/><w:pgSz w:w=\"12240\""
                + " w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\""
                + " w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/></w:sectPr>";
        DocxDoc doc = new DocxDoc().body(styled("Heading1", "Top") + table + styled("Heading2", "After"))
                .section(header).header("header1.xml", styled("Heading1", "Running head"));
        assertEquals(List.of("1 p1 1. Top", "2 p1 In a table", "2 p1 After"), outline("places", doc));
    }

    @Test
    void aDocumentWithoutHeadingsHasNoOutline() throws IOException {
        DocxDoc.Rendered r = DocxDoc.render(dir, "plain", new DocxDoc().body(DocxDoc.p("Just text")).bytes());
        try (PDDocument d = r.open()) {
            assertNull(d.getDocumentCatalog().getDocumentOutline());
            assertNotEquals(PageMode.USE_OUTLINES, d.getDocumentCatalog().getPageMode());
        }
    }
}
