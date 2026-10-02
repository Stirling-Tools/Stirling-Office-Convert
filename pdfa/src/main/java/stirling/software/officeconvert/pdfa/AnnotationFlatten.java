package stirling.software.officeconvert.pdfa;

import java.awt.geom.Rectangle2D;
import java.io.IOException;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.util.Matrix;

final class AnnotationFlatten {

    private AnnotationFlatten() {}

    static void run(PDDocument doc, PDPage page, COSDictionary dictionary) throws IOException {
        PDAnnotation annotation = PDAnnotation.createAnnotation(dictionary);
        PDAppearanceStream appearance = annotation.getNormalAppearanceStream();
        if (appearance == null) {
            annotation.constructAppearances(doc);
            appearance = annotation.getNormalAppearanceStream();
        }
        PDRectangle rect = annotation.getRectangle();
        if (rect == null || rect.getWidth() <= 0 || rect.getHeight() <= 0) {
            return;
        }
        if (appearance == null || appearance.getBBox() == null) {
            throw new IOException("An annotation could not be drawn into the page for PDF/A-1");
        }
        Rectangle2D bounds = appearance.getMatrix().createAffineTransform()
                .createTransformedShape(appearance.getBBox().toGeneralPath()).getBounds2D();
        if (bounds.getWidth() <= 0 || bounds.getHeight() <= 0) {
            throw new IOException("An annotation appearance has no drawable bounds");
        }
        float sx = (float) (rect.getWidth() / bounds.getWidth());
        float sy = (float) (rect.getHeight() / bounds.getHeight());
        float tx = rect.getLowerLeftX() - (float) bounds.getMinX() * sx;
        float ty = rect.getLowerLeftY() - (float) bounds.getMinY() * sy;
        try (PDPageContentStream content = new PDPageContentStream(doc, page,
                PDPageContentStream.AppendMode.APPEND, true, true)) {
            content.saveGraphicsState();
            content.addRect(rect.getLowerLeftX(), rect.getLowerLeftY(), rect.getWidth(), rect.getHeight());
            content.clip();
            content.transform(new Matrix(sx, 0, 0, sy, tx, ty));
            content.drawForm(appearance);
            content.restoreGraphicsState();
        }
    }
}
