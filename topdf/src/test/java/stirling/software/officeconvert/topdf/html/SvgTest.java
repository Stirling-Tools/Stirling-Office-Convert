package stirling.software.officeconvert.topdf.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

class SvgTest {

    private static Svg.Painted paint(String html, float w, float h) {
        Document doc = Jsoup.parse(html);
        Map<String, Element> ids = new HashMap<>();
        doc.select("[id]").forEach(e -> ids.putIfAbsent(e.id(), e));
        return Svg.paint(doc.selectFirst("svg"), ids, w, h, 0x000000);
    }

    private static int pixel(Svg.Painted p, float fx, float fy) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(p.png()));
        return img.getRGB(Math.round(fx * (img.getWidth() - 1)), Math.round(fy * (img.getHeight() - 1))) & 0xFFFFFF;
    }

    @Test
    void shapesAreDrawnInTheirViewBox() throws IOException {
        Svg.Painted p = paint("<svg viewBox=\"0 0 100 100\"><rect width=\"50\" height=\"100\" fill=\"#ff0000\"/>"
                + "<circle cx=\"75\" cy=\"50\" r=\"20\" style=\"fill:blue\"/></svg>", 30, 30);
        assertNotNull(p);
        assertEquals(0xFF0000, pixel(p, 0.25f, 0.5f));
        assertEquals(0x0000FF, pixel(p, 0.75f, 0.5f));
        assertEquals(0, pixel(p, 0.75f, 0.05f), "transparent where nothing is drawn");
    }

    @Test
    void pathsWithEveryCommandParse() {
        Rectangle2D b = SvgPath.parse("M10 10 h20 v20 H10 Z m40 0 l10 10 c5 5 10 5 15 0 s10-5 15 0 q5 5 10 0 t10 0"
                + " a10 10 0 0 1 20 0 A5 5 0 1 0 100 40 z").getBounds2D();
        assertEquals(10, b.getMinX(), 0.5);
        assertTrue(b.getMaxX() >= 100);
        assertTrue(SvgPath.parse("M0,0L1e2,1e-1 1.5.5").getBounds2D().getMaxX() >= 100, "packed numbers");
        assertEquals(0, SvgPath.parse("garbage").getBounds2D().getWidth(), 0.0);
    }

    @Test
    void spritesAreDrawnThroughUse() throws IOException {
        String html = "<svg style=\"display:none\"><symbol id=\"i\" viewBox=\"0 0 10 10\"><rect width=\"10\""
                + " height=\"10\" fill=\"currentColor\"/></symbol></svg>"
                + "<svg width=\"20\" height=\"20\" style=\"color:#00ff00\"><use href=\"#i\"/></svg>";
        Document doc = Jsoup.parse(html);
        Map<String, Element> ids = new HashMap<>();
        doc.select("[id]").forEach(e -> ids.putIfAbsent(e.id(), e));
        Svg.Painted p = Svg.paint(doc.select("svg").get(1), ids, 15, 15, 0x00FF00);
        assertNotNull(p);
        assertEquals(0x00FF00, pixel(p, 0.5f, 0.5f));
    }

    @Test
    void reusedPointsAndTextCostTheirLength() {
        String points = "0,0 1,1 0,1 ".repeat(4_000);
        String text = "W".repeat(48_000);
        for (String shape : new String[] {"<polygon id=\"s\" points=\"" + points + "\"/>",
                "<polyline id=\"s\" points=\"" + points + "\" stroke=\"black\"/>",
                "<text id=\"s\" y=\"5\">" + text + "</text>"}) {
            Document doc = Jsoup.parse("<svg viewBox=\"0 0 10 10\"><defs>" + shape + "</defs>"
                    + "<use href=\"#s\"/>".repeat(100) + "</svg>");
            Map<String, Element> ids = new HashMap<>();
            doc.select("[id]").forEach(e -> ids.putIfAbsent(e.id(), e));
            long[] budget = {Long.MAX_VALUE};
            Svg.paint(doc.selectFirst("svg"), ids, 20, 20, 0, budget);
            assertTrue(Long.MAX_VALUE - budget[0] >= 100L * 48_000, shape.substring(0, 12));
        }
    }

    @Test
    void nothingDrawnIsNoPicture() {
        assertNull(paint("<svg width=\"10\" height=\"10\"><defs><rect width=\"5\" height=\"5\"/></defs></svg>", 10, 10));
        assertNull(paint("<svg><image href=\"https://example.invalid/x.png\" width=\"10\" height=\"10\"/>"
                + "<use href=\"https://example.invalid/s.svg#a\"/><foreignObject><p>x</p></foreignObject></svg>", 10, 10));
    }

    @Test
    void hostileDrawingsStayBounded() {
        assertNotNull(paint("<svg viewBox=\"0 0 10 10\"><g id=\"a\"><use href=\"#a\"/><rect width=\"1\" height=\"1\"/>"
                + "</g></svg>", 10, 10), "a use that points at itself stops");
        StringBuilder deep = new StringBuilder("<svg viewBox=\"0 0 10 10\">");
        deep.append("<g>".repeat(5_000)).append("<rect width=\"5\" height=\"5\"/>").append("</g>".repeat(5_000));
        assertNull(paint(deep.append("</svg>").toString(), 10, 10), "nesting past the limit draws nothing");
        StringBuilder big = new StringBuilder("<svg viewBox=\"0 0 10 10\"><path d=\"M0 0");
        big.append(" L1 1".repeat(200_000));
        assertNull(paint(big.append("\"/></svg>").toString(), 10, 10), "an oversized path is left out");
        Svg.Painted huge = paint("<svg viewBox=\"0 0 10 10\"><rect width=\"10\" height=\"10\"/></svg>", 14_000, 14_000);
        assertNotNull(huge);
        assertTrue((long) huge.pixelWidth() * huge.pixelHeight() <= Svg.MAX_PIXELS + 2 * 14_000);
    }
}
