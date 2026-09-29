package stirling.software.officeconvert.topdf.pptx;

import java.awt.geom.AffineTransform;

import javax.xml.namespace.QName;

import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;

// A flat shape turned in 3-D and seen head on: the revolution turns it in its plane, then it tilts away
final class Cameras {

    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";

    private Cameras() {}

    static AffineTransform view(XmlObject spPr) {
        if (spPr == null) {
            return null;
        }
        try (XmlCursor c = spPr.newCursor()) {
            if (!c.toChild(A, "scene3d") || !c.toChild(A, "camera") || !c.toChild(A, "rot")) {
                return null;
            }
            return view(angle(c, "lat"), angle(c, "lon"), angle(c, "rev"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static double angle(XmlCursor c, String name) {
        String v = c.getAttributeText(new QName("", name));
        if (v == null || v.isBlank()) {
            return 0;
        }
        try {
            double d = Long.parseLong(v.strip()) / 60_000.0;
            return Double.isFinite(d) ? d : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static AffineTransform view(double lat, double lon, double rev) {
        if (lat % 360 == 0 && lon % 360 == 0 && rev % 360 == 0) {
            return null;
        }
        double a = Math.toRadians(lat);
        double l = Math.toRadians(lon);
        AffineTransform t = new AffineTransform(edgeOn(Math.cos(l)), Math.sin(l) * Math.sin(a), 0,
                edgeOn(Math.cos(a)), 0, 0);
        t.rotate(Math.toRadians(-rev));
        return t;
    }

    // Seen exactly edge on a shape would vanish into a line no transform can invert
    private static double edgeOn(double scale) {
        return Math.abs(scale) >= 0.02 ? scale : Math.copySign(0.02, scale);
    }
}
