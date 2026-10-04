package stirling.software.officeconvert.topdf.pptx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.Fixtures;

class PptxShadowTest {

    @TempDir
    Path dir;

    private static String rect(String effects) {
        return "<p:sp " + Decks.NS + "><p:nvSpPr><p:cNvPr id=\"7\" name=\"Rect\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>"
                + "<p:spPr><a:xfrm><a:off x=\"1270000\" y=\"1270000\"/><a:ext cx=\"2540000\" cy=\"1270000\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val=\"4472C4\"/>"
                + "</a:solidFill>" + effects + "</p:spPr><p:style><a:lnRef idx=\"0\"><a:schemeClr val=\"accent1\"/>"
                + "</a:lnRef><a:fillRef idx=\"1\"><a:schemeClr val=\"accent1\"/></a:fillRef><a:effectRef idx=\"0\">"
                + "<a:schemeClr val=\"accent1\"/></a:effectRef><a:fontRef idx=\"minor\"><a:schemeClr val=\"lt1\"/>"
                + "</a:fontRef></p:style></p:sp>";
    }

    private static String shadow(int blur) {
        return "<a:effectLst><a:outerShdw blurRad=\"" + blur + "\" dist=\"114300\" dir=\"2700000\" algn=\"tl\" "
                + "rotWithShape=\"0\"><a:srgbClr val=\"000000\"><a:alpha val=\"50000\"/></a:srgbClr></a:outerShdw>"
                + "</a:effectLst>";
    }

    private int images(Decks.Converted c) throws IOException {
        try (PDDocument d = c.open()) {
            int n = 0;
            for (COSName name : d.getPage(0).getResources().getXObjectNames()) {
                n++;
            }
            return n;
        }
    }

    private static int grey(BufferedImage img, int x, int y) {
        int p = img.getRGB(x, y);
        return ((p >> 16 & 0xFF) + (p >> 8 & 0xFF) + (p & 0xFF)) / 3;
    }

    @Test
    void aHardShadowIsTheOutlineMovedAndTranslucent() throws IOException {
        Decks.Converted c = Decks.convert(dir, "hard.pptx", Decks.slideXml(rect(shadow(0))));
        BufferedImage img = c.render(0, 72);
        int shade = grey(img, 300, 205);
        assertTrue(shade > 100 && shade < 160, "shadow " + shade);
        assertEquals(255, grey(img, 95, 95));
        assertEquals(0, images(c));
    }

    @Test
    void aSoftShadowIsABlurredPicture() throws IOException {
        Decks.Converted c = Decks.convert(dir, "soft.pptx", Decks.slideXml(rect(shadow(50800))));
        BufferedImage img = c.render(0, 72);
        int shade = grey(img, 300, 205);
        assertTrue(shade < 235, "shadow " + shade);
        assertTrue(grey(img, 300, 220) > shade, "fades out");
        assertEquals(1, images(c));
    }

    @Test
    void textShadowsAreDrawnBehindTheText() throws IOException {
        String effects = "<a:effectLst><a:outerShdw dist=\"38100\" dir=\"2700000\" algn=\"tl\"><a:srgbClr "
                + "val=\"FF0000\"/></a:outerShdw></a:effectLst>";
        String box = Decks.textBox(8, 1270000, 1270000, 5080000, 1270000, "<a:bodyPr wrap=\"none\"/>",
                "<a:p>" + "<a:r><a:rPr lang=\"en-US\" sz=\"4000\">" + effects + "</a:rPr><a:t>IIII</a:t></a:r></a:p>");
        Decks.Converted c = Decks.convert(dir, "text.pptx", Decks.slideXml(box));
        BufferedImage img = c.render(0, 72);
        int red = 0;
        for (int y = 100; y < 200; y++) {
            for (int x = 100; x < 250; x++) {
                int p = img.getRGB(x, y);
                if ((p >> 16 & 0xFF) > 200 && (p >> 8 & 0xFF) < 80 && (p & 0xFF) < 80) {
                    red++;
                }
            }
        }
        assertTrue(red > 20, "red shadow pixels " + red);
        assertEquals("IIII", c.text().strip());
    }

    private static byte[] picture(String effects) {
        String pic = "<p:pic " + Decks.NS + "><p:nvPicPr><p:cNvPr id=\"7\" name=\"Picture\"/><p:cNvPicPr/><p:nvPr/>"
                + "</p:nvPicPr><p:blipFill><a:blip r:embed=\"rIdP\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>"
                + "<p:spPr><a:xfrm><a:off x=\"1270000\" y=\"1270000\"/><a:ext cx=\"1270000\" cy=\"1270000\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>" + effects + "</p:spPr></p:pic>";
        Fixtures.Zip z = Fixtures.edit(Decks.slideXml(pic));
        z.put("ppt/media/red.png", Fixtures.png(4, 4, Color.RED));
        z.defaultType("png", "image/png");
        z.relationship("/ppt/slides/slide1.xml", "rIdP", Fixtures.REL + "image", "../media/red.png", false);
        return z.bytes();
    }

    @Test
    void picturesCastShadowsAndReflections() throws IOException {
        String effects = "<a:effectLst><a:outerShdw dist=\"114300\" dir=\"0\" algn=\"l\"><a:srgbClr val=\"000000\">"
                + "<a:alpha val=\"50000\"/></a:srgbClr></a:outerShdw><a:reflection stA=\"50000\" endPos=\"50000\" "
                + "dir=\"5400000\" sy=\"-100000\" algn=\"bl\" rotWithShape=\"0\"/></a:effectLst>";
        BufferedImage img = Decks.convert(dir, "picfx.pptx", picture(effects)).render(0, 72);
        assertEquals(Color.RED.getRGB(), img.getRGB(150, 150));
        int shade = grey(img, 204, 150);
        assertTrue(shade > 100 && shade < 160, "shadow " + shade);
        Color below = new Color(img.getRGB(150, 205));
        assertTrue(below.getRed() > 240 && below.getGreen() > 100 && below.getGreen() < 200, below.toString());
        assertTrue(grey(img, 150, 240) > grey(img, 150, 205));
        assertEquals(255, grey(img, 150, 256));
    }

    @Test
    void anEmptyEffectListCastsNothing() throws IOException {
        Decks.Converted c = Decks.convert(dir, "none.pptx", Decks.slideXml(rect("<a:effectLst/>")));
        BufferedImage img = c.render(0, 72);
        assertEquals(255, grey(img, 300, 205));
    }
}
