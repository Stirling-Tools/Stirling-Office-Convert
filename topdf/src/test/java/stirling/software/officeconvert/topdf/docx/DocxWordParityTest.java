package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

class DocxWordParityTest {

    private static final String DEFAULTS = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Liberation Sans\""
            + " w:hAnsi=\"Liberation Sans\"/><w:sz w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>"
            + "<w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";

    @TempDir
    Path dir;

    private DocxDoc.Rendered render(String name, String styles, String body) throws IOException {
        return DocxDoc.render(dir, name, new DocxDoc().styles(DEFAULTS + styles).body(body).bytes());
    }

    private static String lines(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(DocxDoc.p("Line " + i));
        }
        return b.toString();
    }

    @Test
    void aKeptHeadingThatOpensWithAPageBreakAddsNoBlankPage() throws IOException {
        String heading = "<w:style w:type=\"paragraph\" w:styleId=\"H\"><w:name w:val=\"heading 1\"/><w:pPr>"
                + "<w:keepNext/></w:pPr></w:style>";
        String plain = "<w:style w:type=\"paragraph\" w:styleId=\"H\"><w:name w:val=\"heading 1\"/></w:style>";
        String broken = "<w:p><w:pPr><w:pStyle w:val=\"H\"/></w:pPr><w:r><w:br w:type=\"page\"/></w:r><w:r><w:t>"
                + "Heading</w:t></w:r></w:p>" + DocxDoc.p("Body after heading");
        for (int n = 52; n <= 58; n++) {
            DocxDoc.Rendered kept = render("kept" + n, heading, lines(n) + broken);
            DocxDoc.Rendered free = render("free" + n, plain, lines(n) + broken);
            assertEquals(free.pages(), kept.pages(), "lines before the heading: " + n);
            assertEquals(kept.pages(), kept.word("Heading").page(), "lines before the heading: " + n);
        }
    }

    // The red part of the fill colour set last before the page's first text, which is the word's on these pages
    private static float textColor(DocxDoc.Rendered r) throws IOException {
        try (PDDocument d = r.open()) {
            String content = new String(d.getPage(0).getContents().readAllBytes(), StandardCharsets.ISO_8859_1);
            int text = content.indexOf(" TJ");
            int rg = content.lastIndexOf(" rg", text);
            String[] parts = content.substring(content.lastIndexOf('\n', rg) + 1, rg).trim().split("\\s+");
            return Float.parseFloat(parts[parts.length - 3]);
        }
    }

    @Test
    void automaticTextOnADarkCellOrTableIsWhite() throws IOException {
        String cell = "<w:tbl><w:tblPr>%s</w:tblPr><w:tblGrid><w:gridCol w:w=\"3000\"/></w:tblGrid><w:tr><w:tc>"
                + "<w:tcPr>%s</w:tcPr><w:p><w:r><w:t>Permit</w:t></w:r></w:p></w:tc></w:tr></w:tbl>" + DocxDoc.p("");
        String black = "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"000000\"/>";
        String grey = "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"1F1F1F\"/>";
        String pale = "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F2F2F2\"/>";
        assertEquals(1f, textColor(render("cellblack", "", cell.formatted("", black))), 0.01);
        assertEquals(1f, textColor(render("cellgrey", "", cell.formatted("", grey))), 0.01);
        assertEquals(1f, textColor(render("tableblack", "", cell.formatted(black, ""))), 0.01);
        assertEquals(0f, textColor(render("cellpale", "", cell.formatted("", pale))), 0.01);
        String paleParagraph = cell.formatted("", black).replace("<w:p><w:r>", "<w:p><w:pPr>" + pale + "</w:pPr><w:r>");
        assertEquals(0f, textColor(render("parapale", "", paleParagraph)), 0.01);
    }

    record Box(float x, float y, float w, float h) {}

    // Pictures, filled areas and stroked paths of one page as boxes, top-down in points; strokes also carry the
    // line width and the first colour component
    private static final class Marks extends PDFGraphicsStreamEngine {

        final List<Box> pictures = new ArrayList<>();
        final List<Box> fills = new ArrayList<>();
        final List<float[]> strokes = new ArrayList<>();
        private final float height;
        private float[] bounds;
        private Point2D.Float current = new Point2D.Float();

        Marks(PDPage page) {
            super(page);
            height = page.getMediaBox().getHeight();
        }

        private void add(double x, double y) {
            float fx = (float) x;
            float fy = height - (float) y;
            bounds = bounds == null ? new float[] {fx, fy, fx, fy}
                    : new float[] {Math.min(bounds[0], fx), Math.min(bounds[1], fy), Math.max(bounds[2], fx),
                            Math.max(bounds[3], fy)};
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            add(p0.getX(), p0.getY());
            add(p2.getX(), p2.getY());
        }

        @Override
        public void drawImage(PDImage image) {
            Matrix m = getGraphicsState().getCurrentTransformationMatrix();
            pictures.add(new Box(m.getTranslateX(), height - m.getTranslateY() - m.getScaleY(), m.getScaleX(),
                    m.getScaleY()));
        }

        @Override
        public void clip(int windingRule) {
        }

        @Override
        public void moveTo(float x, float y) {
            add(x, y);
            current = new Point2D.Float(x, y);
        }

        @Override
        public void lineTo(float x, float y) {
            add(x, y);
            current = new Point2D.Float(x, y);
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            add(x3, y3);
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
            bounds = null;
        }

        @Override
        public void strokePath() throws IOException {
            if (bounds != null) {
                float[] c = getGraphicsState().getStrokingColor().getComponents();
                strokes.add(new float[] {bounds[0], bounds[1], bounds[2], bounds[3],
                        getGraphicsState().getLineWidth(), c.length > 0 ? c[0] : 0});
            }
            endPath();
        }

        @Override
        public void fillPath(int windingRule) {
            if (bounds != null) {
                fills.add(new Box(bounds[0], bounds[1], bounds[2] - bounds[0], bounds[3] - bounds[1]));
            }
            endPath();
        }

        @Override
        public void fillAndStrokePath(int windingRule) throws IOException {
            float[] kept = bounds;
            fillPath(windingRule);
            bounds = kept;
            strokePath();
        }

        @Override
        public void shadingFill(COSName shadingName) {
        }
    }

    private static Marks marks(DocxDoc.Rendered r, int page) throws IOException {
        try (PDDocument d = r.open()) {
            Marks m = new Marks(d.getPage(page - 1));
            m.processPage(d.getPage(page - 1));
            return m;
        }
    }

    private static long emu(float pt) {
        return Math.round(pt * 12_700);
    }

    private static String picture(String layoutInCell, String hRel, float x, String vRel, float y) {
        return "<w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\""
                + " relativeHeight=\"2\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"" + layoutInCell + "\""
                + " allowOverlap=\"1\"><wp:simplePos x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"" + hRel + "\">"
                + "<wp:posOffset>" + emu(x) + "</wp:posOffset></wp:positionH><wp:positionV relativeFrom=\"" + vRel
                + "\"><wp:posOffset>" + emu(y) + "</wp:posOffset></wp:positionV><wp:extent cx=\"" + emu(50) + "\" cy=\""
                + emu(50) + "\"/><wp:wrapNone/><wp:docPr id=\"5\" name=\"p\"/><a:graphic><a:graphicData"
                + " uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:nvPicPr><pic:cNvPr"
                + " id=\"5\" name=\"p\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"rIdPng\"/>"
                + "<a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext"
                + " cx=\"" + emu(50) + "\" cy=\"" + emu(50) + "\"/></a:xfrm><a:prstGeom prst=\"rect\"/></pic:spPr>"
                + "</pic:pic></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r>";
    }

    private DocxDoc.Rendered anchoredInCell(String name, String anchor, int compat) throws IOException {
        String table = "<w:tbl><w:tblGrid><w:gridCol w:w=\"3000\"/><w:gridCol w:w=\"6000\"/></w:tblGrid><w:tr><w:tc>"
                + "<w:tcPr><w:tcW w:w=\"3000\" w:type=\"dxa\"/></w:tcPr>" + DocxDoc.p("CellOne") + "</w:tc><w:tc><w:tcPr>"
                + "<w:tcW w:w=\"6000\" w:type=\"dxa\"/></w:tcPr><w:p>" + anchor + "<w:r><w:t>CellTwo</w:t></w:r></w:p>"
                + "</w:tc></w:tr></w:tbl>" + DocxDoc.p("");
        DocxDoc doc = new DocxDoc().styles(DEFAULTS).body(lines(10) + table)
                .media("red.png", Fixtures.png(8, 8, java.awt.Color.RED), "rIdPng");
        doc.part("settings.xml", "settings", "application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml",
                "<w:settings " + DocxDoc.NS + "><w:compat><w:compatSetting w:name=\"compatibilityMode\""
                        + " w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"" + compat + "\"/></w:compat>"
                        + "</w:settings>");
        return DocxDoc.render(dir, name, doc.bytes());
    }

    @Test
    void objectsLaidOutInACellArePlacedFromTheCellNotThePage() throws IOException {
        for (int compat : new int[] {14, 15}) {
            DocxDoc.Rendered r = anchoredInCell("incell" + compat, picture("1", "margin", 100, "margin", 10), compat);
            Box img = marks(r, 1).pictures.get(0);
            DocxDoc.Word two = r.word("CellTwo");
            assertEquals(two.x() + 100, img.x(), 0.5f, "compat " + compat);
            float rowTop = two.y() - 12;
            assertTrue(img.y() > rowTop && img.y() < rowTop + 20, "compat " + compat + ": " + img + " row " + rowTop);
        }
        DocxDoc.Rendered page = anchoredInCell("onpage", picture("0", "margin", 100, "margin", 10), 14);
        Box img = marks(page, 1).pictures.get(0);
        assertEquals(172, img.x(), 0.5f);
        assertEquals(82, img.y(), 0.5f);
    }

    @Test
    void aPageBreakBeforeInTheFirstCellMovesTheTable() throws IOException {
        String style = "<w:style w:type=\"paragraph\" w:styleId=\"C\"><w:name w:val=\"Certificate\"/><w:pPr>"
                + "<w:pageBreakBefore/></w:pPr></w:style>";
        String table = "<w:tbl><w:tblGrid><w:gridCol w:w=\"4000\"/></w:tblGrid><w:tr><w:tc><w:p><w:pPr><w:pStyle"
                + " w:val=\"C\"/></w:pPr><w:r><w:t>Permit</w:t></w:r></w:p></w:tc></w:tr></w:tbl>";
        DocxDoc.Rendered r = render("tablebreak", style, DocxDoc.p("Before") + table + DocxDoc.p("After"));
        assertEquals(1, r.word("Before").page());
        assertEquals(2, r.word("Permit").page());
        assertEquals(2, r.word("After").page());
        DocxDoc.Rendered top = render("tabletop", style, table + DocxDoc.p("After"));
        assertEquals(1, top.pages());
    }

    private static String autofitBox(String bodyFit) {
        return "<w:p><w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\""
                + " relativeHeight=\"4\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\""
                + " xmlns:wp14=\"http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing\"><wp:simplePos"
                + " x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"page\"><wp:posOffset>" + emu(72) + "</wp:posOffset>"
                + "</wp:positionH><wp:positionV relativeFrom=\"page\"><wp:posOffset>" + emu(72) + "</wp:posOffset>"
                + "</wp:positionV><wp:extent cx=\"" + emu(240) + "\" cy=\"807720\"/><wp:wrapNone/><wp:docPr id=\"11\""
                + " name=\"tb\"/><a:graphic><a:graphicData"
                + " uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\"><wps:wsp><wps:spPr>"
                + "<a:prstGeom prst=\"rect\"/><a:solidFill><a:srgbClr val=\"FF0000\"/></a:solidFill></wps:spPr>"
                + "<wps:txbx><w:txbxContent>" + DocxDoc.p("Boxed") + "</w:txbxContent></wps:txbx><wps:bodyPr lIns=\"0\""
                + " tIns=\"0\" rIns=\"0\" bIns=\"0\">" + bodyFit + "</wps:bodyPr></wps:wsp></a:graphicData></a:graphic>"
                + "<wp14:sizeRelV relativeFrom=\"margin\"><wp14:pctHeight>20000</wp14:pctHeight></wp14:sizeRelV>"
                + "</wp:anchor></w:drawing></w:r></w:p>";
    }

    private static float tallestFill(DocxDoc.Rendered r) throws IOException {
        float h = 0;
        for (Box b : marks(r, 1).fills) {
            h = Math.max(h, b.h());
        }
        return h;
    }

    @Test
    void aTextBoxThatFitsItsTextKeepsItsHeightOverARelativeOne() throws IOException {
        assertEquals(63.6, tallestFill(render("autofit", "", autofitBox("<a:spAutoFit/>"))), 0.2);
        assertEquals(0.2 * 648, tallestFill(render("relative", "", autofitBox("<a:noAutofit/>"))), 0.2);
    }

    private List<float[]> topBorder(String style) throws IOException {
        String p = "<w:p><w:pPr><w:pBdr><w:top w:val=\"" + style + "\" w:sz=\"24\" w:space=\"1\" w:color=\"000000\"/>"
                + "</w:pBdr></w:pPr><w:r><w:t>Bordered</w:t></w:r></w:p>";
        List<float[]> out = new ArrayList<>();
        for (float[] s : marks(render("border-" + style, "", p), 1).strokes) {
            if (s[2] - s[0] > 100) {
                out.add(s);
            }
        }
        out.sort((a, b) -> Float.compare(a[1], b[1]));
        return out;
    }

    @Test
    void compoundBordersDrawEveryLineTheyReserve() throws IOException {
        assertEquals(1, topBorder("single").size());
        assertEquals(2, topBorder("double").size());
        assertEquals(3, topBorder("triple").size());
        List<float[]> thinThick = topBorder("thinThickSmallGap");
        assertEquals(2, thinThick.size());
        float[] outer = thinThick.get(0);
        float[] inner = thinThick.get(1);
        assertEquals(1.5f, outer[4], 0.01f);
        assertEquals(3f, inner[4], 0.01f);
        assertEquals(0.75f + (1.5f + 3f) / 2, inner[1] - outer[1], 0.01f);
        List<float[]> engraved = topBorder("threeDEngrave");
        assertEquals(2, engraved.size());
        assertNotEquals(engraved.get(0)[5], engraved.get(1)[5], "an engraved border has a dark and a light line");
        assertEquals(2, topBorder("doubleWave").size());
    }

    @Test
    void aTripleTableBorderIsDrawnInsideTheRoomItTakes() throws IOException {
        String edge = "w:val=\"triple\" w:sz=\"8\" w:space=\"0\" w:color=\"000000\"";
        String table = "<w:tbl><w:tblPr><w:tblBorders><w:top " + edge + "/><w:bottom " + edge + "/></w:tblBorders>"
                + "</w:tblPr><w:tblGrid><w:gridCol w:w=\"4000\"/></w:tblGrid><w:tr><w:tc>" + DocxDoc.p("Cell")
                + "</w:tc></w:tr></w:tbl>";
        DocxDoc.Rendered r = render("triple", "", DocxDoc.p("Top") + table);
        List<Float> ys = new ArrayList<>();
        for (float[] s : marks(r, 1).strokes) {
            if (s[2] - s[0] > 100 && s[1] < r.word("Cell").y()) {
                ys.add(s[1]);
            }
        }
        assertEquals(3, ys.size(), ys.toString());
        float spread = ys.stream().max(Float::compare).get() - ys.stream().min(Float::compare).get();
        assertEquals(4, spread, 0.01f);
    }
}
