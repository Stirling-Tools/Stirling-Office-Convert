package stirling.software.officeconvert.extract;

import org.apache.pdfbox.util.Matrix;

// Text set at an angle other than a quarter turn cannot become flowing text; it is drawn as artwork instead.
public final class SkewedText {

    private static final double TOLERANCE = 1.0;

    private SkewedText() {}

    public static boolean skewed(Matrix m) {
        double angle = Math.toDegrees(Math.atan2(m.getShearY(), m.getScaleX()));
        double off = Math.abs(angle - 90.0 * Math.round(angle / 90.0));
        return off > TOLERANCE && Math.hypot(m.getScaleX(), m.getShearY()) > 1e-6;
    }
}
