package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.Color;
import java.awt.Paint;
import java.awt.PaintContext;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.ColorModel;
import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

class PaintRasterTest {

    private static final class Odd implements Paint {

        @Override
        public PaintContext createContext(ColorModel cm, Rectangle deviceBounds, Rectangle2D userBounds,
                AffineTransform xform, RenderingHints hints) {
            return Color.RED.createContext(cm, deviceBounds, userBounds, xform, hints);
        }

        @Override
        public int getTransparency() {
            return OPAQUE;
        }
    }

    @Test
    void nanTransformDropsTheShapeQuietly() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            Metafiles.Checked g = new Metafiles.Checked(doc, 100, 100);
            g.setPaint(new Odd());
            g.transform(new AffineTransform(Double.NaN, 0, 0, Double.NaN, 0, 0));
            assertDoesNotThrow(() -> g.fill(new Rectangle2D.Double(10, 10, 50, 50)));
            assertDoesNotThrow(() -> g.draw(new Rectangle2D.Double(10, 10, 50, 50)));
            g.dispose();
            assertNotNull(g.getXFormObject());
        }
    }
}
