package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocxWrapSpacingTest {

    private static final String FONT = "Liberation Sans";

    private static final String DEFAULTS = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"" + FONT
            + "\" w:hAnsi=\"" + FONT + "\"/><w:sz w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>"
            + "<w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";

    private static final String LETTER = "<w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\""
            + " w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/>";

    @TempDir
    Path dir;

    private static String para(String pPr, String runs) {
        return "<w:p><w:pPr>" + pPr + "</w:pPr>" + runs + "</w:p>";
    }

    private static String run(String text) {
        return "<w:r><w:t xml:space=\"preserve\">" + text + "</w:t></w:r>";
    }

    private static String fillers(int n, String prefix) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(DocxDoc.p(prefix + i));
        }
        return b.toString();
    }

    private static String shape(String horz, long x, String vert, long y, long cx, long cy, String wrap) {
        return "<w:r><w:drawing><wp:anchor simplePos=\"0\" relativeHeight=\"1\" behindDoc=\"0\" locked=\"0\""
                + " layoutInCell=\"1\" allowOverlap=\"1\" distL=\"0\" distR=\"0\" distT=\"0\" distB=\"0\"><wp:simplePos"
                + " x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"" + horz + "\"><wp:posOffset>" + x
                + "</wp:posOffset></wp:positionH><wp:positionV relativeFrom=\"" + vert + "\"><wp:posOffset>" + y
                + "</wp:posOffset></wp:positionV><wp:extent cx=\"" + cx + "\" cy=\"" + cy + "\"/>" + wrap
                + "<wp:docPr id=\"2\" name=\"box\"/><a:graphic><a:graphicData"
                + " uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\"><wps:wsp><wps:spPr>"
                + "<a:prstGeom prst=\"rect\"/><a:solidFill><a:srgbClr val=\"DDEEFF\"/></a:solidFill></wps:spPr>"
                + "<wps:bodyPr/></wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r>";
    }

    private DocxDoc.Rendered render(String name, String settings, String body) throws IOException {
        DocxDoc doc = new DocxDoc().styles(DEFAULTS).body(body);
        if (settings != null) {
            doc.part("settings.xml", "settings",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml",
                    "<w:settings " + DocxDoc.NS + ">" + settings + "</w:settings>");
        }
        return DocxDoc.render(dir, name, doc.bytes());
    }

    @Test
    void withoutHtmlParagraphSpacingTheSpaceAfterAndTheSpaceBeforeAddUp() throws IOException {
        String body = para("<w:spacing w:after=\"120\"/>", run("Ann")) + para("<w:spacing w:before=\"240\"/>", run("Bob"));
        DocxDoc.Rendered html = render("htmlspacing", null, body);
        DocxDoc.Rendered fixed = render("fixedspacing", "<w:compat><w:doNotUseHTMLParagraphAutoSpacing/></w:compat>",
                body);
        float larger = html.word("Bob").y() - html.word("Ann").y();
        float sum = fixed.word("Bob").y() - fixed.word("Ann").y();
        assertEquals(6, sum - larger, 0.2, "the 6 pt after is added to the 12 pt before");
    }

    @Test
    void aLargeSpaceOpeningALineDoesNotMakeItTaller() throws IOException {
        String big = "<w:r><w:rPr><w:sz w:val=\"72\"/></w:rPr><w:t xml:space=\"preserve\"> </w:t></w:r>";
        String body = DocxDoc.p("Ann") + "<w:p>" + big + run("Bob") + "</w:p>" + DocxDoc.p("Cat") + DocxDoc.p("Dan");
        DocxDoc.Rendered r = render("bigspace", null, body);
        float plain = r.word("Dan").y() - r.word("Cat").y();
        float firstBaseline = 72 + (1854 + 67) / 2048f * 10;
        assertEquals(firstBaseline + plain, r.word("Bob").y(), 0.3, "the space does not raise the line");
        assertEquals(plain, r.word("Cat").y() - r.word("Bob").y(), 0.2, "nor deepen it");
    }

    private DocxDoc.Rendered headed(String name, String text) throws IOException {
        String header = "<w:p>" + shape("margin", 0, "paragraph", -254000, 1270000, 762000,
                "<wp:wrapSquare wrapText=\"bothSides\"/>") + run(text) + "</w:p>";
        String sect = "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rIdh1xml\"/>" + LETTER + "</w:sectPr>";
        return DocxDoc.render(dir, name, new DocxDoc().styles(DEFAULTS).header("h1.xml", header)
                .body(DocxDoc.p("Body")).section(sect).bytes());
    }

    @Test
    void headerTextThatCannotSitBesideItsObjectGoesBelowItAndPushesTheBodyDown() throws IOException {
        DocxDoc.Rendered beside = headed("headbeside", "Title");
        DocxDoc.Rendered below = headed("headbelow", "_".repeat(90));
        assertEquals(72 + 9, beside.word("Body").y(), 1.5, "a short line fits beside the object");
        assertTrue(below.word("Body").y() > 95, "the long line goes under the object: " + below.word("Body").y());
    }

    @Test
    void aTightWrapKeepsTextClearOfItsPolygonOnly() throws IOException {
        String polygon = "<wp:wrapPolygon edited=\"0\"><wp:start x=\"0\" y=\"0\"/><wp:lineTo x=\"0\" y=\"21600\"/>"
                + "<wp:lineTo x=\"10800\" y=\"21600\"/><wp:lineTo x=\"10800\" y=\"0\"/><wp:lineTo x=\"0\" y=\"0\"/>"
                + "</wp:wrapPolygon>";
        String tight = "<w:p>" + shape("margin", 0, "paragraph", 0, 1828800, 914400,
                "<wp:wrapTight wrapText=\"bothSides\">" + polygon + "</wp:wrapTight>") + run("Tight words") + "</w:p>";
        String square = "<w:p>" + shape("margin", 0, "paragraph", 0, 1828800, 914400,
                "<wp:wrapSquare wrapText=\"bothSides\"/>") + run("Square words") + "</w:p>";
        DocxDoc.Rendered r = render("tightwrap", null, tight + fillers(8, "F") + square);
        assertEquals(72 + 72, r.word("Tight").x(), 1, "text starts at the polygon's right edge");
        assertEquals(72 + 144, r.word("Square").x(), 1, "text starts at the box's right edge");
    }

    @Test
    void aTableRowSplitsAboveAFooterObjectInsteadOfMovingPastIt() throws IOException {
        String footer = "<w:p>" + shape("page", 5080000, "page", 8636000, 1905000, 1270000,
                "<wp:wrapSquare wrapText=\"largest\"/>") + "</w:p>";
        StringBuilder cell = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            cell.append(DocxDoc.p("L" + i));
        }
        String table = "<w:tbl><w:tblGrid><w:gridCol w:w=\"9000\"/></w:tblGrid><w:tr><w:tc><w:tcPr><w:tcW w:w=\"9000\""
                + " w:type=\"dxa\"/></w:tcPr>" + cell + "</w:tc></w:tr></w:tbl>";
        String sect = "<w:sectPr><w:footerReference w:type=\"default\" r:id=\"rIdf1xml\"/>" + LETTER + "</w:sectPr>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "rowfoot", new DocxDoc().styles(DEFAULTS).footer("f1.xml", footer)
                .body(fillers(30, "F") + table).section(sect).bytes());
        assertEquals(1, r.word("L0").page(), "the row starts on the first page");
        assertEquals(2, r.word("L29").page(), "and ends on the next");
        for (DocxDoc.Word w : r.words()) {
            if (w.page() == 1 && w.text().startsWith("L")) {
                assertTrue(w.y() < 680, w.text() + " ends above the footer object: " + w.y());
            }
        }
    }

    @Test
    void aTableRowMovesBelowAHeaderObjectReachingIntoTheBody() throws IOException {
        String header = "<w:p>" + shape("page", 914400, "page", 762000, 2921000, 1270000,
                "<wp:wrapSquare wrapText=\"bothSides\"/>") + "</w:p>";
        String table = "<w:tbl><w:tblGrid><w:gridCol w:w=\"9000\"/></w:tblGrid><w:tr><w:tc>" + DocxDoc.p("Cell")
                + "</w:tc></w:tr></w:tbl>";
        String sect = "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rIdh1xml\"/>" + LETTER + "</w:sectPr>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "rowhead", new DocxDoc().styles(DEFAULTS).header("h1.xml", header)
                .body(table).section(sect).bytes());
        assertTrue(r.word("Cell").y() > 160, "the row starts below the header's object: " + r.word("Cell").y());
    }
}
