package stirling.software.officeconvert.extract;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.io.IOException;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDAbstractPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDShadingPattern;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType3;

final class PatternPaint {

    private PatternPaint() {}

    static int at(PDColor colour, PDResources resources, float x, float y) {
        try {
            COSName name = colour.getPatternName();
            PDAbstractPattern pattern = name == null || resources == null ? null : resources.getPattern(name);
            if (pattern instanceof PDShadingPattern shaded) {
                return shadingAt(shaded, x, y);
            }
            return pattern == null ? -1 : PatternTone.of(colour, resources);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    private static int shadingAt(PDShadingPattern pattern, float x, float y) throws IOException {
        PDShading shading = pattern.getShading();
        if (!(shading instanceof PDShadingType2) && !(shading instanceof PDShadingType3)) {
            return -1;
        }
        AffineTransform toPattern;
        try {
            toPattern = pattern.getMatrix().createAffineTransform().createInverse();
        } catch (NoninvertibleTransformException e) {
            return -1;
        }
        Point2D p = toPattern.transform(new Point2D.Float(x, y), null);
        COSArray coords = shading.getCOSObject().getCOSArray(COSName.COORDS);
        if (coords == null || coords.size() < (shading instanceof PDShadingType3 ? 6 : 4)) {
            return -1;
        }
        float[] c = coords.toFloatArray();
        float t;
        if (shading instanceof PDShadingType3) {
            double r1 = Math.max(c[5], 1e-3);
            t = (float) ((p.distance(c[3], c[4]) - c[2]) / Math.max(r1 - c[2], 1e-3));
        } else {
            double dx = c[2] - c[0];
            double dy = c[3] - c[1];
            double len = dx * dx + dy * dy;
            if (len <= 0) {
                return -1;
            }
            t = (float) (((p.getX() - c[0]) * dx + (p.getY() - c[1]) * dy) / len);
        }
        if (t < 0 && !extended(shading, 0) || t > 1 && !extended(shading, 1)) {
            return -1;
        }
        t = Math.clamp(t, 0f, 1f);
        float t0 = 0;
        float t1 = 1;
        COSArray domain = shading.getCOSObject().getCOSArray(COSName.DOMAIN);
        if (domain != null && domain.size() >= 2) {
            float[] d = domain.toFloatArray();
            t0 = d[0];
            t1 = d[1];
        }
        float[] rgb = shading.getColorSpace().toRGB(shading.evalFunction(t0 + t * (t1 - t0)));
        int out = 0;
        for (float v : rgb) {
            out = out << 8 | Math.round(Math.clamp(v, 0f, 1f) * 255);
        }
        return out;
    }

    private static boolean extended(PDShading shading, int end) {
        COSArray ends = shading.getCOSObject().getCOSArray(COSName.EXTEND);
        return ends != null && ends.size() > end && COSBoolean.TRUE.equals(ends.getObject(end));
    }
}
