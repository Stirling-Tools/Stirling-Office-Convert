package stirling.software.officeconvert.topdf.pptx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.xslf.usermodel.SlideLayout;
import org.apache.poi.xslf.usermodel.XSLFSlideLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.Fixtures;

class PptxFallbackTest {

    @TempDir
    Path dir;

    @Test
    void aFallbackPlaceholderWithoutAPositionUsesTheLayouts() throws IOException {
        Rectangle2D[] body = new Rectangle2D[1];
        byte[] base = Decks.deck(ppt -> {
            XSLFSlideLayout layout = ppt.getSlideMasters().get(0).getLayout(SlideLayout.TITLE_AND_CONTENT);
            body[0] = layout.getPlaceholder(1).getAnchor();
            ppt.createSlide(layout);
        });
        Fixtures.Zip z = Fixtures.edit(base);
        z.put("ppt/media/fallback1.png", Fixtures.png(4, 4, Color.RED));
        z.defaultType("png", "image/png");
        z.relationship("/ppt/slides/slide1.xml", "rIdFb", Fixtures.REL + "image", "../media/fallback1.png", false);
        String alt = "<mc:AlternateContent xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\" "
                + Decks.NS + "><mc:Choice xmlns:a14=\"http://schemas.microsoft.com/office/drawing/2010/main\""
                + " Requires=\"a14\"><p:sp><p:nvSpPr><p:cNvPr id=\"9\" name=\"Content\"/><p:cNvSpPr/><p:nvPr>"
                + "<p:ph idx=\"1\"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a14:m/></a:p>"
                + "</p:txBody></p:sp></mc:Choice><mc:Fallback><p:sp><p:nvSpPr><p:cNvPr id=\"9\" name=\"Content\"/>"
                + "<p:cNvSpPr/><p:nvPr><p:ph idx=\"1\"/></p:nvPr></p:nvSpPr><p:spPr><a:blipFill><a:blip r:embed=\"rIdFb\"/>"
                + "<a:stretch><a:fillRect/></a:stretch></a:blipFill></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p>"
                + "<a:r><a:rPr lang=\"en-GB\"><a:noFill/></a:rPr><a:t> </a:t></a:r></a:p></p:txBody></p:sp></mc:Fallback>"
                + "</mc:AlternateContent>";
        z.insertBefore("ppt/slides/slide1.xml", "</p:spTree>", alt);
        BufferedImage img = Decks.convert(dir, "fallback.pptx", z.bytes()).render(0, 72);
        Rectangle2D r = body[0];
        assertEquals(Color.RED.getRGB(), img.getRGB((int) r.getCenterX(), (int) r.getCenterY()));
        assertEquals(Color.RED.getRGB(), img.getRGB((int) r.getX() + 3, (int) r.getY() + 3));
        assertEquals(Color.WHITE.getRGB(), img.getRGB((int) r.getX() - 3, (int) r.getY() - 3));
    }

    @Test
    void aPictureWhoseBlipNeedsMacDrawingMlShowsItsFallback() throws IOException {
        String pic = "<p:pic " + Decks.NS + " xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\">"
                + "<p:nvPicPr><p:cNvPr id=\"7\" name=\"Picture 7\"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>"
                + "<mc:AlternateContent xmlns:ma=\"http://schemas.microsoft.com/office/mac/drawingml/2008/main\">"
                + "<mc:Choice Requires=\"ma\"><p:blipFill><a:blip r:embed=\"rIdMac\"/><a:stretch><a:fillRect/>"
                + "</a:stretch></p:blipFill></mc:Choice><mc:Fallback><p:blipFill><a:blip r:embed=\"rIdWin\"/><a:stretch>"
                + "<a:fillRect/></a:stretch></p:blipFill></mc:Fallback></mc:AlternateContent><p:spPr><a:xfrm><a:off"
                + " x=\"914400\" y=\"914400\"/><a:ext cx=\"914400\" cy=\"914400\"/></a:xfrm><a:prstGeom prst=\"rect\">"
                + "<a:avLst/></a:prstGeom></p:spPr></p:pic>";
        Fixtures.Zip z = Fixtures.edit(Decks.slideXml(pic));
        z.put("ppt/media/mac1.png", Fixtures.png(4, 4, Color.RED));
        z.put("ppt/media/win1.png", Fixtures.png(4, 4, Color.BLUE));
        z.defaultType("png", "image/png");
        z.relationship("/ppt/slides/slide1.xml", "rIdMac", Fixtures.REL + "image", "../media/mac1.png", false);
        z.relationship("/ppt/slides/slide1.xml", "rIdWin", Fixtures.REL + "image", "../media/win1.png", false);
        BufferedImage img = Decks.convert(dir, "macpic.pptx", z.bytes()).render(0, 72);
        assertEquals(Color.BLUE.getRGB(), img.getRGB(108, 108));
    }

    @Test
    void pictureBulletsStandOnTheBaselineAndTheTextStartsAtTheMargin() throws IOException {
        String p = "<a:p><a:pPr marL=\"342900\" indent=\"-342900\"><a:buBlip><a:blip r:embed=\"rIdB\"/></a:buBlip>"
                + "</a:pPr>" + Decks.run("Bulleted", "sz=\"3000\"") + "</a:p>";
        byte[] pptx = Decks.slideXml(Decks.textBox(10, 914400, 914400, 6096000, 1828800,
                "<a:bodyPr wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\"/>", p));
        Fixtures.Zip z = Fixtures.edit(pptx);
        z.put("ppt/media/bullet1.png", Fixtures.png(8, 8, Color.RED));
        z.defaultType("png", "image/png");
        z.relationship("/ppt/slides/slide1.xml", "rIdB", Fixtures.REL + "image", "../media/bullet1.png", false);
        Decks.Converted c = Decks.convert(dir, "picbullet.pptx", z.bytes());
        List<TextPosition> pos = c.positions(0);
        TextPosition b = pos.get(0);
        assertEquals('B', b.getUnicode().charAt(0));
        assertEquals(72 + 27, b.getXDirAdj(), 0.05);
        float baseline = b.getYDirAdj();
        BufferedImage img = c.render(0, 144);
        assertEquals(Color.RED.getRGB(), img.getRGB((72 + 10) * 2, (int) ((baseline - 10) * 2)));
        assertEquals(Color.WHITE.getRGB(), img.getRGB((72 + 10) * 2, (int) ((baseline - 23) * 2)));
        assertEquals(Color.WHITE.getRGB(), img.getRGB((72 + 23) * 2, (int) ((baseline - 10) * 2)));
    }

    @Test
    void aTabBeforeAHangingIndentStopsAtTheMargin() throws IOException {
        String p = "<a:p><a:pPr marL=\"342900\" indent=\"-342900\"><a:buNone/></a:pPr>"
                + Decks.run("\tTabbed", "sz=\"2000\"") + "</a:p>";
        byte[] pptx = Decks.slideXml(Decks.textBox(10, 914400, 914400, 6096000, 914400,
                "<a:bodyPr wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\"/>", p));
        List<TextPosition> pos = Decks.convert(dir, "hanging.pptx", pptx).positions(0);
        for (TextPosition t : pos) {
            if (t.getUnicode().equals("T")) {
                assertEquals(72 + 27, t.getXDirAdj(), 0.05);
                return;
            }
        }
        throw new AssertionError("no T");
    }
}
