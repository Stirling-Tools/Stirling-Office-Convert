package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.Fixtures;

class DocxTableEdgeTest {

    private static final String FONT = "Liberation Sans";

    private static final String DEFAULTS = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"" + FONT
            + "\" w:hAnsi=\"" + FONT + "\"/><w:sz w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>"
            + "<w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";

    @TempDir
    Path dir;

    record Stroke(float x0, float y0, float x1, float y1, float width, float[] dash) {

        boolean horizontal() {
            return Math.abs(y0 - y1) < 0.01f && Math.abs(x1 - x0) > 1;
        }
    }

    record Picture(float x, float y, float w, float h) {}

    // Stroked segments and drawn images of one page, top-down in points
    private static final class Ink extends PDFGraphicsStreamEngine {

        final List<Stroke> strokes = new ArrayList<>();
        final List<Picture> pictures = new ArrayList<>();
        private final float height;
        private final List<float[]> path = new ArrayList<>();
        private Point2D.Float current = new Point2D.Float();

        Ink(PDPage page) {
            super(page);
            height = page.getMediaBox().getHeight();
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            path.clear();
        }

        @Override
        public void drawImage(PDImage image) {
            Matrix m = getGraphicsState().getCurrentTransformationMatrix();
            pictures.add(new Picture(m.getTranslateX(), height - m.getTranslateY() - m.getScaleY(), m.getScaleX(),
                    m.getScaleY()));
        }

        @Override
        public void clip(int windingRule) {
        }

        @Override
        public void moveTo(float x, float y) {
            current = new Point2D.Float(x, y);
        }

        @Override
        public void lineTo(float x, float y) {
            path.add(new float[] {current.x, current.y, x, y});
            current = new Point2D.Float(x, y);
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            current = new Point2D.Float(x3, y3);
        }

        @Override
        public Point2D getCurrentPoint() {
            return current;
        }

        @Override
        public void closePath() {
        }

        @Override
        public void endPath() {
            path.clear();
        }

        @Override
        public void strokePath() {
            float w = getGraphicsState().getLineWidth();
            float[] dash = getGraphicsState().getLineDashPattern().getDashArray();
            for (float[] s : path) {
                strokes.add(new Stroke(s[0], height - s[1], s[2], height - s[3], w, dash));
            }
            path.clear();
        }

        @Override
        public void fillPath(int windingRule) {
            path.clear();
        }

        @Override
        public void fillAndStrokePath(int windingRule) {
            strokePath();
        }

        @Override
        public void shadingFill(COSName shadingName) {
        }
    }

    private static Ink ink(DocxDoc.Rendered r, int page) throws IOException {
        try (PDDocument d = r.open()) {
            Ink ink = new Ink(d.getPage(page - 1));
            ink.processPage(d.getPage(page - 1));
            return ink;
        }
    }

    private static String fillers(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(DocxDoc.p("F" + i));
        }
        return b.toString();
    }

    private static String border(String side, String style, int size) {
        return "<w:" + side + " w:val=\"" + style + "\" w:sz=\"" + size + "\" w:space=\"0\" w:color=\"000000\"/>";
    }

    private static String table(String borders, String rows) {
        return "<w:tbl><w:tblPr><w:tblW w:w=\"4000\" w:type=\"dxa\"/><w:tblBorders>" + borders + "</w:tblBorders>"
                + "</w:tblPr><w:tblGrid><w:gridCol w:w=\"4000\"/></w:tblGrid>" + rows + "</w:tbl>";
    }

    private static String row(String tcPr, String content) {
        return "<w:tr><w:tc><w:tcPr><w:tcW w:w=\"4000\" w:type=\"dxa\"/>" + tcPr + "</w:tcPr>" + content
                + "</w:tc></w:tr>";
    }

    private DocxDoc.Rendered render(String name, String body) throws IOException {
        return DocxDoc.render(dir, name, new DocxDoc().styles(DEFAULTS).body(body).bytes());
    }

    private static List<Stroke> heavy(Ink ink, float width) {
        List<Stroke> out = new ArrayList<>();
        for (Stroke s : ink.strokes) {
            if (s.horizontal() && Math.abs(s.width() - width) < 0.01f) {
                out.add(s);
            }
        }
        return out;
    }

    @Test
    void anAutofitTableWithoutCellWidthsIsSizedFromItsText() throws IOException {
        String longText = "Plenty of words that run far wider than the whole text column of a letter page when set on"
                + " one line";
        String cells = "<w:tr><w:tc><w:p><w:r><w:t>Short</w:t></w:r></w:p></w:tc><w:tc>" + DocxDoc.p("Next " + longText)
                + "</w:tc></w:tr><w:tr><w:tc>" + DocxDoc.p("Tiny") + "</w:tc><w:tc>" + DocxDoc.p("Cell") + "</w:tc></w:tr>";
        String grid = "<w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/></w:tblPr><w:tblGrid><w:gridCol w:w=\"4000\"/>"
                + "<w:gridCol w:w=\"4000\"/></w:tblGrid>";
        String small = "<w:tbl>" + grid + "<w:tr><w:tc>" + DocxDoc.p("Ab") + "</w:tc><w:tc>" + DocxDoc.p("Cd")
                + "</w:tc></w:tr></w:tbl>";
        DocxDoc.Rendered r = render("autofit", "<w:tbl>" + grid + cells + "</w:tbl>" + DocxDoc.p("Gap") + small);
        // The first column is as wide as its longest line, Short, plus the two 5.4 pt cell margins
        assertEquals(r.word("Short").x() + 23.9f + 10.8f, r.word("Next").x(), 1.5f);
        assertEquals(r.word("Next").x(), r.word("Cell").x(), 0.1f);
        assertTrue(r.word("line").y() > r.word("Next").y(), "the long text wraps inside the column");
        assertEquals(r.word("Ab").x() + 12.2f + 10.8f, r.word("Cd").x(), 1.5f);
    }

    @Test
    void aPageBreakInsideATableClosesAndReopensItWithItsOuterBorders() throws IOException {
        String borders = border("top", "single", 18) + border("bottom", "single", 18) + border("insideH", "dotted", 4);
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            rows.append(row("", DocxDoc.p("R" + i)));
        }
        // 52 lines leave 50 pt: rows R0 to R3 would take 49.75 pt, but R3 would leave no room for the bottom border
        DocxDoc.Rendered r = render("edges", fillers(52) + table(borders, rows.toString()));
        assertEquals(1, r.word("R2").page());
        assertEquals(2, r.word("R3").page(), "the last row on a page must hold the table's bottom border");
        List<Stroke> closing = heavy(ink(r, 1), 2.25f);
        assertEquals(2, closing.size(), "the table's top border and its bottom border at the page end: " + closing);
        assertTrue(closing.get(1).y0() > r.word("R2").y(), "the bottom border closes the page under R2");
        List<Stroke> opening = heavy(ink(r, 2), 2.25f);
        assertTrue(opening.size() >= 2, "the continued table opens with its top border: " + opening);
        assertTrue(opening.get(0).y0() < r.word("R3").y() - 8, "the top border sits above R3");
        // The heavier top border takes the row's room, so R3's text sits lower than a dotted border would leave it
        assertEquals(72 + 2.25f + 9.05f, r.word("R3").y(), 0.6f);
    }

    @Test
    void aRowSplitAcrossPagesDrawsTheBordersAtTheBreak() throws IOException {
        String all = border("top", "single", 8) + border("bottom", "single", 8) + border("left", "single", 8)
                + border("right", "single", 8) + border("insideH", "single", 8);
        String cell = "<w:p><w:pPr><w:widowControl w:val=\"0\"/></w:pPr><w:r><w:t>L0</w:t></w:r><w:r><w:br/>"
                + "<w:t>L1</w:t></w:r><w:r><w:br/><w:t>L2</w:t></w:r><w:r><w:br/><w:t>L3</w:t></w:r><w:r><w:br/>"
                + "<w:t>L4</w:t></w:r></w:p>";
        DocxDoc.Rendered r = render("splitrow", fillers(54) + table(all, row("", cell)));
        int lastOnFirst = r.word("L1").page() == 1 ? 1 : 0;
        assertEquals(1, r.word("L0").page());
        assertEquals(2, r.word("L4").page());
        String below = lastOnFirst == 1 ? "L1" : "L0";
        boolean closed = false;
        for (Stroke s : heavy(ink(r, 1), 1)) {
            closed |= s.y0() > r.word(below).y() && s.y0() < r.word(below).y() + 8;
        }
        assertTrue(closed, "the part left on the page ends with a border");
        String first = lastOnFirst == 1 ? "L2" : "L1";
        boolean opened = false;
        for (Stroke s : heavy(ink(r, 2), 1)) {
            opened |= s.y0() < r.word(first).y() && s.y0() > r.word(first).y() - 14;
        }
        assertTrue(opened, "the rest of the row starts with a border");
    }

    @Test
    void twoRowsShareOneBorderAndDottedBordersUseEvenDots() throws IOException {
        String cellBorders = "<w:tcBorders>" + border("top", "dotted", 4) + border("bottom", "dotted", 4)
                + "</w:tcBorders>";
        DocxDoc.Rendered r = render("shared", table("", row(cellBorders, DocxDoc.p("Upper"))
                + row(cellBorders, DocxDoc.p("Lower"))));
        float top = r.word("Upper").y();
        float bottom = r.word("Lower").y();
        List<Stroke> between = new ArrayList<>();
        for (Stroke s : ink(r, 1).strokes) {
            if (s.horizontal() && s.y0() > top + 1 && s.y0() < bottom - 6) {
                between.add(s);
            }
        }
        assertEquals(1, between.size(), "one border between the rows: " + between);
        assertArrayEquals(new float[] {0.5f, 0.5f}, between.get(0).dash(), 0.001f);
    }

    @Test
    void aMergedCellThatCannotKeepALineBesideItsFirstRowMovesItsTextToTheNextPage() throws IOException {
        String grid = "<w:tbl><w:tblPr><w:tblW w:w=\"6000\" w:type=\"dxa\"/></w:tblPr><w:tblGrid><w:gridCol"
                + " w:w=\"3000\"/><w:gridCol w:w=\"3000\"/></w:tblGrid>";
        String merged = "<w:p><w:r><w:t>M0</w:t></w:r><w:r><w:br/><w:t>M1</w:t></w:r><w:r><w:br/><w:t>M2</w:t>"
                + "</w:r></w:p>";
        String rows = "<w:tr><w:tc><w:tcPr><w:tcW w:w=\"3000\" w:type=\"dxa\"/><w:vMerge w:val=\"restart\"/>"
                + "</w:tcPr>" + merged + "</w:tc><w:tc><w:tcPr><w:tcW w:w=\"3000\" w:type=\"dxa\"/></w:tcPr>"
                + DocxDoc.p("A") + "</w:tc></w:tr><w:tr><w:tc><w:tcPr><w:tcW w:w=\"3000\" w:type=\"dxa\"/><w:vMerge/>"
                + "</w:tcPr><w:p/></w:tc><w:tc><w:tcPr><w:tcW w:w=\"3000\" w:type=\"dxa\"/></w:tcPr>" + DocxDoc.p("B")
                + "</w:tc></w:tr></w:tbl>";
        // 55 lines leave 15.5 pt: row A fits, and one line of the merged text beside it would be a widow
        DocxDoc.Rendered r = render("mergedcarry", fillers(55) + grid + rows);
        assertEquals(1, r.word("A").page());
        assertEquals(2, r.word("M0").page(), "the merged text moves whole instead of running off the page");
        assertEquals(2, r.word("M2").page());
        assertTrue(r.word("M2").y() < 120, "and continues at the top of the next page: " + r.word("M2"));
    }

    @Test
    void anObjectAnchoredInAHeaderFrameIsPlacedFromTheFrame() throws IOException {
        long size = 36 * 12700L;
        String anchor = "<w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\""
                + " relativeHeight=\"2\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">"
                + "<wp:simplePos x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"page\"><wp:posOffset>" + 20 * 12700
                + "</wp:posOffset></wp:positionH><wp:positionV relativeFrom=\"page\"><wp:posOffset>" + 10 * 12700
                + "</wp:posOffset></wp:positionV><wp:extent cx=\"" + size + "\" cy=\"" + size + "\"/><wp:wrapNone/>"
                + "<wp:docPr id=\"2\" name=\"a\"/><a:graphic><a:graphicData"
                + " uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:nvPicPr><pic:cNvPr"
                + " id=\"1\" name=\"p\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"rIdLogo\"/>"
                + "<a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/>"
                + "<a:ext cx=\"" + size + "\" cy=\"" + size + "\"/></a:xfrm><a:prstGeom prst=\"rect\"/></pic:spPr>"
                + "</pic:pic></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r>";
        String frame = "<w:p><w:pPr><w:framePr w:w=\"400\" w:wrap=\"around\" w:hAnchor=\"page\" w:vAnchor=\"page\""
                + " w:x=\"1000\" w:y=\"600\"/></w:pPr>" + anchor + "</w:p><w:p/>";
        DocxDoc doc = new DocxDoc().styles(DEFAULTS).body(DocxDoc.p("Body"))
                .section("<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rIdheader1xml\"/><w:pgSz"
                        + " w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\""
                        + " w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/></w:sectPr>")
                .header("header1.xml", frame);
        doc.zip().put("word/media/logo.png", Fixtures.png(4, 4, Color.BLUE));
        doc.zip().defaultType("png", "image/png");
        doc.zip().relationship("/word/header1.xml", "rIdLogo", Fixtures.REL + "image", "media/logo.png", false);
        DocxDoc.Rendered r = DocxDoc.render(dir, "headerframe", doc.bytes());
        List<Picture> pictures = ink(r, 1).pictures;
        assertEquals(1, pictures.size(), "the frame's picture is drawn");
        Picture p = pictures.get(0);
        assertEquals(50 + 20, p.x(), 0.6f);
        assertEquals(30 + 10, p.y(), 0.6f);
    }

    @Test
    void aMergedCellWhoseRowsRunOnToTheNextPageEndsWithThePage() throws IOException {
        String all = border("top", "single", 8) + border("bottom", "single", 8) + border("left", "single", 8)
                + border("right", "single", 8) + border("insideH", "single", 8) + border("insideV", "single", 8);
        String grid = "<w:tbl><w:tblPr><w:tblW w:w=\"6000\" w:type=\"dxa\"/><w:tblBorders>" + all + "</w:tblBorders>"
                + "</w:tblPr><w:tblGrid><w:gridCol w:w=\"3000\"/><w:gridCol w:w=\"3000\"/></w:tblGrid>";
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            String merge = i == 0 ? "<w:vMerge w:val=\"restart\"/>" : "<w:vMerge/>";
            rows.append("<w:tr><w:tc><w:tcPr><w:tcW w:w=\"3000\" w:type=\"dxa\"/>").append(merge).append("</w:tcPr>")
                    .append(i == 0 ? DocxDoc.p("Merged") : "<w:p/>").append("</w:tc><w:tc><w:tcPr><w:tcW w:w=\"3000\"")
                    .append(" w:type=\"dxa\"/></w:tcPr>").append(DocxDoc.p("S" + i)).append("</w:tc></w:tr>");
        }
        DocxDoc.Rendered r = render("mergedcut", fillers(54) + grid + rows + "</w:tbl>");
        assertEquals(1, r.word("S1").page());
        assertEquals(2, r.word("S2").page());
        for (Stroke s : ink(r, 1).strokes) {
            assertTrue(Math.max(s.y0(), s.y1()) < 721, "nothing is drawn past the page's body: " + s);
        }
    }

    @Test
    void aFloatingTableRowAtThePageBottomSplitsLikeAnInlineOne() throws IOException {
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            a.append(DocxDoc.p("A" + i));
        }
        for (int i = 0; i < 20; i++) {
            b.append(DocxDoc.p("B" + i));
        }
        String table = "<w:tbl><w:tblPr><w:tblpPr w:leftFromText=\"180\" w:rightFromText=\"180\""
                + " w:vertAnchor=\"text\" w:horzAnchor=\"margin\" w:tblpY=\"1\"/><w:tblW w:w=\"4000\""
                + " w:type=\"dxa\"/></w:tblPr><w:tblGrid><w:gridCol w:w=\"4000\"/></w:tblGrid>" + row("", a.toString())
                + row("", b.toString()) + row("", DocxDoc.p("C")) + "</w:tbl>";
        DocxDoc.Rendered r = render("floatsplit", DocxDoc.p("Intro") + table + DocxDoc.p("After"));
        assertEquals(1, r.word("B0").page(), "the second row starts where the first ends");
        assertEquals(2, r.word("B19").page());
        assertEquals(2, r.word("C").page());
    }

    @Test
    void aRowBesideARotatedMergedCellStillSplitsAtThePageBottom() throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            body.append(DocxDoc.p("Body" + i));
        }
        String merged = "<w:tc><w:tcPr><w:tcW w:w=\"700\" w:type=\"dxa\"/><w:vMerge w:val=\"restart\"/>"
                + "<w:textDirection w:val=\"btLr\"/></w:tcPr>" + DocxDoc.p("Side") + "</w:tc>";
        String below = "<w:tc><w:tcPr><w:tcW w:w=\"700\" w:type=\"dxa\"/><w:vMerge/></w:tcPr><w:p/></w:tc>";
        String wide = "<w:tc><w:tcPr><w:tcW w:w=\"3300\" w:type=\"dxa\"/></w:tcPr>";
        String table = "<w:tbl><w:tblPr><w:tblW w:w=\"4000\" w:type=\"dxa\"/></w:tblPr><w:tblGrid><w:gridCol"
                + " w:w=\"700\"/><w:gridCol w:w=\"3300\"/></w:tblGrid><w:tr><w:trPr><w:trHeight w:val=\"1134\"/>"
                + "</w:trPr>" + merged + wide + DocxDoc.p("Head") + "</w:tc></w:tr><w:tr>" + below + wide
                + body + "</w:tc></w:tr></w:tbl>";
        DocxDoc.Rendered r = render("rotatedsplit", fillers(30) + table + "<w:p/>");
        assertEquals(1, r.word("Body0").page(), "the row starts on the first page");
        assertEquals(2, r.word("Body39").page());
    }

    @Test
    void anOldLayoutSpreadsAPercentageWidthOverTheHangingCellMargins() throws IOException {
        String table = "<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"pct\"/><w:tblBorders>" + border("top", "single", 24)
                + "</w:tblBorders></w:tblPr><w:tblGrid><w:gridCol w:w=\"9360\"/></w:tblGrid><w:tr><w:tc><w:tcPr>"
                + "<w:tcW w:w=\"5000\" w:type=\"pct\"/></w:tcPr>" + DocxDoc.p("Cell") + "</w:tc></w:tr></w:tbl><w:p/>";
        float[] widths = new float[2];
        int i = 0;
        for (int mode : new int[] {15, 14}) {
            String settings = "<w:settings " + DocxDoc.NS + "><w:compat><w:compatSetting w:name=\"compatibilityMode\""
                    + " w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"" + mode + "\"/></w:compat>"
                    + "</w:settings>";
            DocxDoc.Rendered r = DocxDoc.render(dir, "pct" + mode, new DocxDoc().styles(DEFAULTS).body(table)
                    .part("settings.xml", "settings",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml", settings)
                    .bytes());
            List<Stroke> top = heavy(ink(r, 1), 3);
            widths[i++] = (float) top.stream().mapToDouble(st -> Math.abs(st.x1() - st.x0())).sum();
        }
        assertEquals(468, widths[0], 1);
        assertEquals(468 + 10.8f, widths[1], 1);
    }

    @Test
    void aRowWhoseFirstParagraphBreaksThePageStartsTheNextPage() throws IOException {
        String breaking = "<w:p><w:pPr><w:pageBreakBefore/></w:pPr><w:r><w:t>Second</w:t></w:r></w:p>";
        String inner = "<w:p><w:r><w:t>Head</w:t></w:r></w:p><w:p><w:pPr><w:pageBreakBefore/></w:pPr><w:r>"
                + "<w:t>Inside</w:t></w:r></w:p>";
        DocxDoc.Rendered r = render("rowbreak", table("", row("", DocxDoc.p("First")) + row("", breaking)
                + row("", inner)) + "<w:p/>");
        assertEquals(1, r.word("First").page());
        assertEquals(2, r.word("Second").page());
        assertEquals(2, r.word("Inside").page(), "a break further down a cell is not a row break");
    }

    @Test
    void aPageAnchoredTableOverTextAboveItsAnchorMovesToTheNextPage() throws IOException {
        String table = "<w:tbl><w:tblPr><w:tblpPr w:leftFromText=\"180\" w:rightFromText=\"180\""
                + " w:vertAnchor=\"page\" w:horzAnchor=\"margin\" w:tblpY=\"1440\"/><w:tblW w:w=\"9360\""
                + " w:type=\"dxa\"/></w:tblPr><w:tblGrid><w:gridCol w:w=\"9360\"/></w:tblGrid>"
                + row("", DocxDoc.p("Cell")) + "</w:tbl>";
        DocxDoc.Rendered moved = render("floatover", DocxDoc.p("Contents") + table + "<w:p/>");
        assertEquals(1, moved.word("Contents").page());
        assertEquals(2, moved.word("Cell").page());
        DocxDoc.Rendered kept = render("floatempty", "<w:p/><w:p/>" + table + "<w:p/>");
        assertEquals(1, kept.word("Cell").page(), "empty paragraphs above it do not move it");
    }

    @Test
    void textAfterAPageAnchoredTableThatRunsOnGoesOnFromTheTopOfItsLastPage() throws IOException {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            rows.append("<w:tr><w:tc><w:tcPr><w:tcW w:w=\"4000\" w:type=\"dxa\"/></w:tcPr>").append(DocxDoc.p("T" + i))
                    .append("</w:tc></w:tr>");
        }
        String table = "<w:tbl><w:tblPr><w:tblpPr w:leftFromText=\"180\" w:rightFromText=\"180\""
                + " w:vertAnchor=\"page\" w:horzAnchor=\"margin\" w:tblpY=\"1440\"/><w:tblW w:w=\"9360\""
                + " w:type=\"dxa\"/></w:tblPr><w:tblGrid><w:gridCol w:w=\"9360\"/></w:tblGrid>" + rows + "</w:tbl>";
        long size = 20 * 12700L;
        String marker = "<w:p><w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\""
                + " simplePos=\"0\" relativeHeight=\"2\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\""
                + " allowOverlap=\"1\"><wp:simplePos x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"column\">"
                + "<wp:posOffset>0</wp:posOffset></wp:positionH><wp:positionV relativeFrom=\"paragraph\"><wp:posOffset>0"
                + "</wp:posOffset></wp:positionV><wp:extent cx=\"" + size + "\" cy=\"" + size + "\"/><wp:wrapNone/>"
                + "<wp:docPr id=\"2\" name=\"a\"/><a:graphic><a:graphicData"
                + " uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:nvPicPr><pic:cNvPr"
                + " id=\"1\" name=\"p\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"rIdImg\"/>"
                + "<a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/>"
                + "<a:ext cx=\"" + size + "\" cy=\"" + size + "\"/></a:xfrm><a:prstGeom prst=\"rect\"/></pic:spPr>"
                + "</pic:pic></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r></w:p>";
        DocxDoc.Rendered r = render("floatrun", table + "<w:p/>" + DocxDoc.p("After"));
        assertEquals(2, r.word("T69").page());
        assertEquals(2, r.word("After").page());
        assertTrue(r.word("After").y() > r.word("T69").y(), "text still goes below the rows: " + r.word("After"));
        DocxDoc.Rendered m = DocxDoc.render(dir, "floatmark", new DocxDoc().styles(DEFAULTS)
                .media("g.png", Fixtures.png(4, 4, Color.GREEN), "rIdImg").body(table + marker + DocxDoc.p("Tail"))
                .bytes());
        List<Picture> pictures = ink(m, 2).pictures;
        assertEquals(1, pictures.size(), "the paragraph after the table is on the table's last page");
        assertEquals(72, pictures.get(0).y(), 1, "and starts at the top of that page, under the rows");
    }

    @Test
    void theDocumentsPageColourIsPaintedUnderEveryPage() throws IOException {
        DocxDoc doc = new DocxDoc().styles(DEFAULTS).body(DocxDoc.p("Green"));
        doc.part("settings.xml", "settings", "application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml",
                "<w:settings " + DocxDoc.NS + "><w:displayBackgroundShape/></w:settings>");
        doc.bytes();
        doc.zip().put("word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document "
                + DocxDoc.NS + "><w:background w:color=\"E2EFD9\"/><w:body>" + DocxDoc.p("Green") + DocxDoc.LETTER
                + "</w:body></w:document>");
        DocxDoc.Rendered r = DocxDoc.render(dir, "pagecolour", doc.zip().bytes());
        Color corner = new Color(DocxPaginationTest.page(r, 0).getRGB(5, 5));
        assertEquals(new Color(0xE2, 0xEF, 0xD9), corner);
    }
}
