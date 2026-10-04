package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.Fixtures;

class DocxBaselineRulesTest {

    private static final String FONT = "Liberation Sans";

    private static final String STYLES = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"" + FONT
            + "\" w:hAnsi=\"" + FONT + "\"/><w:sz w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>"
            + "<w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";

    @TempDir
    Path dir;

    private DocxDoc.Rendered plain(String name, String body) throws IOException {
        return DocxDoc.render(dir, name, new DocxDoc().styles(STYLES).body(body).bytes());
    }

    private static String para(String pPr, String text) {
        return "<w:p><w:pPr>" + pPr + "</w:pPr><w:r><w:t>" + text + "</w:t></w:r></w:p>";
    }

    @Test
    void contextualSpacingAboveAlsoTakesTheSpaceBeforeOfTheNextParagraph() throws IOException {
        String upper = "<w:spacing w:after=\"240\"/><w:contextualSpacing/>";
        String lower = "<w:spacing w:before=\"240\"/>";
        DocxDoc.Rendered r = plain("ctxbefore", para(upper, "One") + para(lower, "Two") + para("", "Three"));
        float gap = r.word("Two").y() - r.word("One").y();
        float pitch = r.word("Three").y() - r.word("Two").y();
        assertEquals(pitch, gap, 0.2f, "the 12 pt before collapses against the 12 pt after contextual spacing took");
        DocxDoc.Rendered plainPair = plain("ctxnone", para("<w:spacing w:after=\"240\"/>", "One")
                + para(lower, "Two"));
        float spaced = plainPair.word("Two").y() - plainPair.word("One").y();
        assertEquals(pitch + 12, spaced, 0.2f, "without contextual spacing the gap stays");
    }

    @Test
    void contextualSpacingOnlyCollapsesAsMuchAsTheSpaceAfterItTook() throws IOException {
        String upper = "<w:spacing w:after=\"120\"/><w:contextualSpacing/>";
        String lower = "<w:spacing w:before=\"360\"/>";
        DocxDoc.Rendered r = plain("ctxlarger", para(upper, "One") + para(lower, "Two") + para("", "Three"));
        float gap = r.word("Two").y() - r.word("One").y();
        float pitch = r.word("Three").y() - r.word("Two").y();
        assertEquals(pitch + 12, gap, 0.2f, "18 pt before less the 6 pt after it collapses against");
    }

    @Test
    void smallCapitalsKeepTheLineHeightOfTheFullSize() throws IOException {
        String small = "<w:p><w:r><w:rPr><w:smallCaps/><w:sz w:val=\"48\"/></w:rPr><w:t>lower</w:t></w:r></w:p>";
        String plainRun = "<w:p><w:r><w:rPr><w:sz w:val=\"48\"/></w:rPr><w:t>lower</w:t></w:r></w:p>";
        float capsBase = plain("smallcaps", small + DocxDoc.p("Next")).word("Next").y();
        float plainBase = plain("plaincaps", plainRun + DocxDoc.p("Next")).word("Next").y();
        assertEquals(plainBase, capsBase, 0.2f, "a line of small capitals is as tall as its full size");
    }

    private static long emu(float pt) {
        return Math.round(pt * 12700);
    }

    private static String picture(float size, String rPr) {
        return "<w:r>" + rPr + "<w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\""
                + emu(size) + "\" cy=\"" + emu(size) + "\"/><wp:docPr id=\"1\" name=\"p\"/><a:graphic><a:graphicData"
                + " uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:nvPicPr><pic:cNvPr"
                + " id=\"1\" name=\"p\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"rIdImg\"/>"
                + "<a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/>"
                + "<a:ext cx=\"" + emu(size) + "\" cy=\"" + emu(size) + "\"/></a:xfrm><a:prstGeom prst=\"rect\"/>"
                + "</pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>";
    }

    private float pictureBottomBelowBaseline(String name, String rPr) throws IOException {
        String body = DocxDoc.p("Top") + "<w:p>" + picture(30, rPr)
                + "<w:r><w:t xml:space=\"preserve\"> Beside</w:t></w:r></w:p>" + DocxDoc.p("After");
        DocxDoc.Rendered r = DocxDoc.render(dir, name, new DocxDoc().styles(STYLES)
                .media("blue.png", Fixtures.png(4, 4, Color.BLUE), "rIdImg").body(body).bytes());
        BufferedImage img = DocxPaginationTest.page(r, 0);
        int bottom = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            Color c = new Color(img.getRGB(87, y));
            if (c.getBlue() > 200 && c.getRed() < 80 && c.getGreen() < 80) {
                bottom = y + 1;
            }
        }
        assertTrue(bottom > 0, "the picture is drawn");
        float below = bottom - r.word("Beside").y();
        float drop = r.word("Beside").y() - r.word("Top").y();
        assertEquals(30 - below + 2.1f, drop, 0.6f, "the line holds the picture above its baseline and the text below");
        return below;
    }

    @Test
    void anInlinePictureBesideTextStandsOnTheBaseline() throws IOException {
        float below = pictureBottomBelowBaseline("picbase", "");
        assertEquals(0, below, 1.2f, "the picture's bottom edge sits on the text baseline");
    }

    @Test
    void aLoweredInlinePictureDropsBelowTheBaselineByItsPosition() throws IOException {
        float below = pictureBottomBelowBaseline("piclow", "<w:rPr><w:position w:val=\"-12\"/></w:rPr>");
        assertEquals(6, below, 1.2f, "w:position -12 half points lowers the picture 6 pt");
    }

    private float thirdColumnX(String name, String tblPr) throws IOException {
        String cells = "<w:tc><w:tcPr><w:tcW w:w=\"4500\" w:type=\"dxa\"/></w:tcPr>" + DocxDoc.p("Left") + "</w:tc>"
                + "<w:tc><w:tcPr><w:tcW w:w=\"1000\" w:type=\"dxa\"/></w:tcPr>" + DocxDoc.p("Mid") + "</w:tc>"
                + "<w:tc><w:tcPr><w:tcW w:w=\"4500\" w:type=\"dxa\"/></w:tcPr>" + DocxDoc.p("Right") + "</w:tc>";
        String table = "<w:tbl>" + tblPr + "<w:tblGrid><w:gridCol w:w=\"4500\"/><w:gridCol w:w=\"1000\"/><w:gridCol"
                + " w:w=\"4500\"/></w:tblGrid><w:tr>" + cells + "</w:tr></w:tbl>";
        return plain(name, table + DocxDoc.p("End")).word("Right").x();
    }

    private float borderedCellX(String name, int compatibility) throws IOException {
        String border = "w:val=\"single\" w:sz=\"24\" w:space=\"0\" w:color=\"000000\"";
        String table = "<w:tbl><w:tblPr><w:tblW w:w=\"4000\" w:type=\"dxa\"/><w:tblBorders><w:top " + border
                + "/><w:left " + border + "/><w:bottom " + border + "/><w:right " + border + "/></w:tblBorders>"
                + "</w:tblPr><w:tblGrid><w:gridCol w:w=\"4000\"/></w:tblGrid><w:tr><w:tc>" + DocxDoc.p("Cell")
                + "</w:tc></w:tr></w:tbl>" + DocxDoc.p("End");
        String settings = "<w:settings " + DocxDoc.NS + "><w:compat><w:compatSetting w:name=\"compatibilityMode\""
                + " w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"" + compatibility + "\"/></w:compat>"
                + "</w:settings>";
        DocxDoc doc = new DocxDoc().styles(STYLES).body(table).part("settings.xml", "settings",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml", settings);
        return DocxDoc.render(dir, name, doc.bytes()).word("Cell").x();
    }

    @Test
    void wordTwentyThirteenLayoutMovesCellTextByHalfTheLeftBorder() throws IOException {
        float modern = borderedCellX("cell15", 15);
        float old = borderedCellX("cell14", 14);
        assertEquals(72 + 5.4f + 1.5f, modern, 0.1f, "a 3 pt border moves the text 1.5 pt further right");
        assertEquals(72, old, 0.1f, "older layouts hang the cell margin outside the text column");
    }

    @Test
    void anAutofitTableWiderThanThePageShrinksToFit() throws IOException {
        float auto = thirdColumnX("autowide", "");
        float fixedWidth = thirdColumnX("setwide", "<w:tblPr><w:tblW w:w=\"10000\" w:type=\"dxa\"/></w:tblPr>");
        assertEquals(72 + 275, fixedWidth, 1, "a table with its own width keeps its grid");
        assertEquals(72 - 5.4f + (468 + 10.8f) * 0.55f + 5.4f, auto, 1, "the 500 pt grid shrinks to the text width");
    }
}
