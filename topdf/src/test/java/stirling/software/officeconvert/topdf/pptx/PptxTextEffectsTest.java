package stirling.software.officeconvert.topdf.pptx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PptxTextEffectsTest {

    @TempDir
    Path dir;

    private static byte[] word(String rPrChildren) {
        String run = "<a:r><a:rPr lang=\"en-US\" sz=\"8000\" b=\"1\">" + rPrChildren + "</a:rPr><a:t>HHH</a:t></a:r>";
        return Decks.slideXml(Decks.textBox(9, 635000, 635000, 7620000, 2540000, "<a:bodyPr wrap=\"none\"/>",
                "<a:p>" + run + "</a:p>"));
    }

    private static int count(BufferedImage img, java.util.function.IntPredicate test) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (test.test(img.getRGB(x, y) & 0xFFFFFF)) {
                    n++;
                }
            }
        }
        return n;
    }

    private static boolean red(int rgb) {
        return (rgb >> 16 & 0xFF) > 200 && (rgb >> 8 & 0xFF) < 80 && (rgb & 0xFF) < 80;
    }

    @Test
    void outlinedTextDrawsItsOutline() throws IOException {
        String outlined = "<a:ln w=\"38100\"><a:solidFill><a:srgbClr val=\"FF0000\"/></a:solidFill></a:ln>"
                + "<a:solidFill><a:srgbClr val=\"0000FF\"/></a:solidFill>";
        BufferedImage img = Decks.convert(dir, "outline.pptx", word(outlined)).render(0, 72);
        assertTrue(count(img, PptxTextEffectsTest::red) > 100);
        assertTrue(count(img, rgb -> rgb == 0x0000FF) > 100);
        BufferedImage plain = Decks.convert(dir, "plain.pptx",
                word("<a:ln w=\"38100\"/><a:solidFill><a:srgbClr val=\"0000FF\"/></a:solidFill>")).render(0, 72);
        assertEquals(0, count(plain, PptxTextEffectsTest::red));
    }

    @Test
    void textWithoutAFillShowsOnlyItsOutline() throws IOException {
        BufferedImage none = Decks.convert(dir, "nofill.pptx", word("<a:noFill/>")).render(0, 72);
        assertEquals(0, count(none, rgb -> rgb != 0xFFFFFF));
        String hollow = "<a:ln w=\"12700\"><a:solidFill><a:srgbClr val=\"FF0000\"/></a:solidFill></a:ln><a:noFill/>";
        BufferedImage img = Decks.convert(dir, "hollow.pptx", word(hollow)).render(0, 72);
        assertTrue(count(img, PptxTextEffectsTest::red) > 50);
        assertEquals(0, count(img, rgb -> rgb == 0));
    }

    private static byte[] bar(String scene) {
        String sp = "<p:sp " + Decks.NS + "><p:nvSpPr><p:cNvPr id=\"8\" name=\"Bar\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>"
                + "<p:spPr><a:xfrm><a:off x=\"2032000\" y=\"3302000\"/><a:ext cx=\"5080000\" cy=\"254000\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val=\"FF0000\"/></a:solidFill>"
                + "<a:ln><a:noFill/></a:ln>" + scene + "</p:spPr></p:sp>";
        return Decks.slideXml(sp);
    }

    @Test
    void aCameraTurnsAndTiltsTheShape() throws IOException {
        String turn = "<a:scene3d><a:camera prst=\"orthographicFront\"><a:rot lat=\"0\" lon=\"0\" rev=\"5400000\"/>"
                + "</a:camera><a:lightRig rig=\"threePt\" dir=\"t\"/></a:scene3d>";
        BufferedImage img = Decks.convert(dir, "turn.pptx", bar(turn)).render(0, 72);
        assertTrue(red(img.getRGB(360, 270 + 80) & 0xFFFFFF));
        assertTrue(!red(img.getRGB(360 + 80, 270) & 0xFFFFFF));
        String tilt = "<a:scene3d><a:camera prst=\"orthographicFront\"><a:rot lat=\"0\" lon=\"3600000\" rev=\"0\"/>"
                + "</a:camera><a:lightRig rig=\"threePt\" dir=\"t\"/></a:scene3d>";
        BufferedImage tilted = Decks.convert(dir, "tilt.pptx", bar(tilt)).render(0, 72);
        assertTrue(red(tilted.getRGB(360 + 90, 270) & 0xFFFFFF));
        assertTrue(!red(tilted.getRGB(360 + 110, 270) & 0xFFFFFF));
        BufferedImage flat = Decks.convert(dir, "flat.pptx", bar("")).render(0, 72);
        assertTrue(red(flat.getRGB(360 + 190, 270) & 0xFFFFFF));
    }

    private static int[] inkRows(BufferedImage img, int x0, int x1) {
        int top = -1;
        int bottom = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = x0; x < x1; x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) == 0x0000FF) {
                    top = top < 0 ? y : top;
                    bottom = y;
                    break;
                }
            }
        }
        return new int[] {top, bottom};
    }

    private static byte[] wordArt(String warp) {
        return wordArt(warp, "");
    }

    private static byte[] wordArt(String warp, String effects) {
        String run = "<a:r><a:rPr lang=\"en-US\" sz=\"2000\"><a:solidFill><a:srgbClr val=\"0000FF\"/></a:solidFill>"
                + effects + "</a:rPr><a:t>HHHH</a:t></a:r>";
        return Decks.slideXml(Decks.textBox(9, 1270000, 1270000, 5080000, 2540000, "<a:bodyPr wrap=\"none\" lIns=\"0\""
                + " tIns=\"0\" rIns=\"0\" bIns=\"0\" fromWordArt=\"1\"><a:prstTxWarp prst=\"" + warp + "\"><a:avLst/>"
                + "</a:prstTxWarp></a:bodyPr>", "<a:p><a:pPr algn=\"ctr\"/>" + run + "</a:p>"));
    }

    @Test
    void wordArtWarpsStretchTheTextBetweenTheirGuides() throws IOException {
        BufferedImage plain = Decks.convert(dir, "plain-warp.pptx", wordArt("textPlain")).render(0, 72);
        int[] rows = inkRows(plain, 100, 500);
        assertTrue(rows[0] >= 100 && rows[0] < 106 && rows[1] > 290 && rows[1] <= 300, rows[0] + " " + rows[1]);
        BufferedImage up = Decks.convert(dir, "slant-warp.pptx", wordArt("textSlantUp")).render(0, 72);
        int[] left = inkRows(up, 100, 130);
        int[] right = inkRows(up, 470, 500);
        assertTrue(left[0] > right[0] + 60 && left[1] > right[1] + 60, left[0] + " " + right[0]);
    }

    @Test
    void curvedWarpsBendTheGlyphsAndKeepTheText() throws IOException {
        Decks.Converted deflate = Decks.convert(dir, "deflate-warp.pptx", wordArt("textDeflate"));
        BufferedImage img = deflate.render(0, 72);
        int[] edge = inkRows(img, 110, 130);
        int[] middle = inkRows(img, 260, 340);
        assertTrue(edge[0] >= 100 && edge[0] < 115 && edge[1] > 285, edge[0] + " " + edge[1]);
        assertTrue(middle[0] > edge[0] + 20 && middle[1] < edge[1] - 20, middle[0] + " " + middle[1]);
        assertTrue(deflate.text().contains("HHHH"));
        BufferedImage inflate = Decks.convert(dir, "inflate-warp.pptx", wordArt("textInflate")).render(0, 72);
        int[] in = inkRows(inflate, 110, 130);
        int[] out = inkRows(inflate, 260, 340);
        assertTrue(in[0] > out[0] + 10 && in[1] < out[1] - 10, in[0] + " " + out[0]);
    }

    @Test
    void aWarpedTextShadowFallsOnTheSlideFromTheBox() throws IOException {
        String shadow = "<a:effectLst><a:outerShdw dist=\"254000\" dir=\"5400000\" algn=\"tl\" rotWithShape=\"0\">"
                + "<a:srgbClr val=\"FF0000\"/></a:outerShdw></a:effectLst>";
        BufferedImage img = Decks.convert(dir, "shadow-warp.pptx", wordArt("textPlain", shadow)).render(0, 72);
        int below = 0;
        int far = 0;
        for (int y = 302; y < img.getHeight(); y++) {
            for (int x = 100; x < 500; x++) {
                if (red(img.getRGB(x, y) & 0xFFFFFF)) {
                    below += y < 325 ? 1 : 0;
                    far += y >= 330 ? 1 : 0;
                }
            }
        }
        assertTrue(below > 50 && far == 0, below + " " + far);
    }
}
