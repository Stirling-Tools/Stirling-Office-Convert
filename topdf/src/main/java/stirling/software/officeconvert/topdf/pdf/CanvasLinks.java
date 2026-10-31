package stirling.software.officeconvert.topdf.pdf;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;

import stirling.software.officeconvert.model.FormField;

final class CanvasLinks {

    private final PdfOutput output;

    private final PDPage page;

    private final int index;

    private final float height;

    private final AffineTransform ctm;

    CanvasLinks(PdfOutput output, PDPage page, int index, float height, AffineTransform ctm) {
        this.output = output;
        this.page = page;
        this.index = index;
        this.height = height;
        this.ctm = ctm;
    }

    boolean link(float x, float y, float w, float h, String url) throws IOException {
        String safe = SafeLinks.safeUrl(url);
        if (safe == null || !(w > 0 && h > 0) || page == null) {
            return false;
        }
        PDAnnotationLink link = new PDAnnotationLink();
        link.setRectangle(pdfRect(x, y, w, h));
        link.setBorderStyle(PdfOutput.noBorder());
        link.setPrinted(true);
        PDActionURI action = new PDActionURI();
        action.setURI(safe);
        link.setAction(action);
        addAnnotation(page, link);
        return true;
    }

    void field(float x, float y, float w, float h, FormField field, float size, Color color, PDFormXObject look) {
        if (w > 0 && h > 0 && page != null) {
            output.field(new AcroForms.Widget(page, pdfRect(x, y, w, h), field, Math.max(0, size),
                    color == null ? Color.BLACK : color, look));
        }
    }

    void linkTo(float x, float y, float w, float h, String destination) {
        if (w > 0 && h > 0 && page != null) {
            output.link(page, pdfRect(x, y, w, h), destination);
        }
    }

    void linkToPage(float x, float y, float w, float h, int pageIndex, float yTop) {
        if (w > 0 && h > 0 && page != null) {
            output.link(page, pdfRect(x, y, w, h), pageIndex, 0, yTop);
        }
    }

    void destination(String name, float x, float yTop) {
        if (page == null) {
            return;
        }
        Point2D p = ctm.transform(new Point2D.Float(x, yTop), null);
        output.destination(name, index, (float) p.getX(), (float) p.getY());
    }

    static void addAnnotation(PDPage page, PDAnnotation annotation) throws IOException {
        List<PDAnnotation> annotations = new ArrayList<>(page.getAnnotations());
        annotations.add(annotation);
        page.setAnnotations(annotations);
    }

    private PDRectangle pdfRect(float x, float y, float w, float h) {
        double[] pts = {x, y, x + w, y, x, y + h, x + w, y + h};
        ctm.transform(pts, 0, pts, 0, 4);
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (int i = 0; i < 8; i += 2) {
            minX = Math.min(minX, pts[i]);
            maxX = Math.max(maxX, pts[i]);
            minY = Math.min(minY, pts[i + 1]);
            maxY = Math.max(maxY, pts[i + 1]);
        }
        return new PDRectangle((float) minX, (float) (height - maxY), (float) (maxX - minX), (float) (maxY - minY));
    }
}
