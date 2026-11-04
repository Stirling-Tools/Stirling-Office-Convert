package stirling.software.officeconvert.slides;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.util.Random;

import org.junit.jupiter.api.Test;

class GlyphBoundsTest {

    @Test
    void boundsMatchTransformedRectangleAcrossAffineAndDegenerateCases() {
        double[] scratch = new double[8];
        Random random = new Random(2020);
        for (int i = 0; i < 1000000; i++) {
            AffineTransform transform = switch (i % 6) {
                case 0 -> new AffineTransform();
                case 1 -> AffineTransform.getTranslateInstance(random.nextDouble(-2000, 2000),
                        random.nextDouble(-2000, 2000));
                case 2 -> AffineTransform.getScaleInstance(random.nextDouble(-20, 20), random.nextDouble(-20, 20));
                case 3 -> AffineTransform.getRotateInstance(random.nextDouble(-Math.PI, Math.PI));
                case 4 -> AffineTransform.getShearInstance(random.nextDouble(-20, 20), random.nextDouble(-20, 20));
                default -> new AffineTransform(random.nextDouble(-20, 20), random.nextDouble(-20, 20),
                        random.nextDouble(-20, 20), random.nextDouble(-20, 20),
                        random.nextDouble(-2000, 2000), random.nextDouble(-2000, 2000));
            };
            compare(transform, Math.max(0.1, random.nextDouble(-1, 100)), scratch);
        }
        for (double value : new double[] {-0.0, 0, 1, -1, Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY}) {
            for (double width : new double[] {0.1, 0.5, 1, Double.NaN, Double.POSITIVE_INFINITY}) {
                compare(new AffineTransform(value, 0, 0, value, 0, 0), width, scratch);
                compare(new AffineTransform(0, value, value, 0, -0.0, 0), width, scratch);
            }
        }
    }

    private static void compare(AffineTransform transform, double advance, double[] scratch) {
        Rectangle2D expected = transform.createTransformedShape(new Rectangle2D.Double(0, -0.2, advance, 1))
                .getBounds2D();
        Rectangle2D actual = PaintOrder.glyphBounds(transform, advance, scratch);
        assertEquals(Double.doubleToLongBits(expected.getX()), Double.doubleToLongBits(actual.getX()));
        assertEquals(Double.doubleToLongBits(expected.getY()), Double.doubleToLongBits(actual.getY()));
        assertEquals(Double.doubleToLongBits(expected.getWidth()), Double.doubleToLongBits(actual.getWidth()));
        assertEquals(Double.doubleToLongBits(expected.getHeight()), Double.doubleToLongBits(actual.getHeight()));
    }
}
