package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

import org.apache.poi.hslf.usermodel.HSLFAutoShape;
import org.apache.poi.hslf.usermodel.HSLFFreeformShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.sl.draw.DrawFactory;
import org.apache.poi.sl.draw.Drawable;
import org.apache.poi.sl.draw.geom.Context;
import org.apache.poi.sl.draw.geom.CustomGeometry;
import org.apache.poi.sl.usermodel.ShapeType;
import org.junit.jupiter.api.Test;

class ShapeGeometryTest {

    @Test
    void literalValuesMatchPoi() {
        CustomGeometry geom = new CustomGeometry();
        Rectangle2D box = new Rectangle2D.Double(0, 0, 21600, 43200);
        Context poi = new Context(geom, box, name -> null);
        Context fast = SafeImageRenderer.geometry(geom, box, name -> null);
        for (String key : List.of("0", "-0", "7", "007", "-45", "21600", "123456789012345", "-123456789012345",
                "1234567890123456", "1.5", "-2.25", "1e3", " 12", "+3", "w", "h", "hc", "vc", "wd2", "ss", "cd4")) {
            assertEquals(Double.doubleToRawLongBits(poi.getValue(key)), Double.doubleToRawLongBits(fast.getValue(key)),
                    key);
        }
    }

    @Test
    void legacyShapesDrawAsPoiDrawsThem() throws Exception {
        try (HSLFSlideShow ppt = new HSLFSlideShow()) {
            ppt.setPageSize(new Dimension(300, 200));
            HSLFSlide slide = ppt.createSlide();
            HSLFFreeformShape free = new HSLFFreeformShape();
            Path2D.Double path = new Path2D.Double();
            path.moveTo(10, 10);
            path.lineTo(120, 30);
            path.curveTo(140, 90, 60, 150, 20, 120);
            path.closePath();
            free.setPath(path);
            free.setFillColor(Color.RED);
            free.setLineColor(Color.BLACK);
            slide.addShape(free);
            HSLFAutoShape arrow = new HSLFAutoShape(ShapeType.RIGHT_ARROW);
            arrow.setAnchor(new Rectangle2D.Double(150, 20, 120, 60));
            arrow.setFillColor(Color.BLUE);
            slide.addShape(arrow);
            HSLFAutoShape ellipse = new HSLFAutoShape(ShapeType.ELLIPSE);
            ellipse.setAnchor(new Rectangle2D.Double(160, 110, 100, 70));
            ellipse.setFillColor(Color.GREEN);
            slide.addShape(ellipse);

            BufferedImage poi = page();
            Graphics2D g = poi.createGraphics();
            try {
                antialias(g);
                slide.draw(g);
            } finally {
                g.dispose();
            }
            BufferedImage ours = page();
            g = ours.createGraphics();
            try {
                antialias(g);
                SafeImageRenderer.draw(g, slide);
                assertNotEquals(DrawFactory.class, g.getRenderingHint(Drawable.DRAW_FACTORY).getClass());
            } finally {
                g.dispose();
            }
            assertNotEquals(Color.WHITE.getRGB(), ours.getRGB(50, 50));
            assertArrayEquals(pixels(poi), pixels(ours));
        }
    }

    private static void antialias(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    }

    private static BufferedImage page() {
        BufferedImage image = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 300, 200);
        g.dispose();
        return image;
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }
}
