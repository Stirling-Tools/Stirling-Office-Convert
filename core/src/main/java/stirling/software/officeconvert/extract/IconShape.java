package stirling.software.officeconvert.extract;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.io.IOException;

import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;

public record IconShape(GeneralPath outline, int rgb, String key) {

    GeneralPath scaled(float k) {
        GeneralPath p = new GeneralPath(outline);
        p.transform(AffineTransform.getScaleInstance(k, k));
        return p;
    }

    static IconShape of(PDFont font, int code, float size, int rgb) {
        if (!(font instanceof PDVectorFont vector)) {
            return null;
        }
        try {
            GeneralPath path = vector.getNormalizedPath(code);
            if (path == null || path.getBounds2D().isEmpty()) {
                return null;
            }
            AffineTransform toPoints = AffineTransform.getScaleInstance(size / 1000f, -size / 1000f);
            GeneralPath outline = new GeneralPath(path);
            outline.transform(toPoints);
            return new IconShape(outline, rgb, font.getName() + "|" + code + "|" + Integer.toHexString(rgb) + "|" + Math.round(size * 4));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
