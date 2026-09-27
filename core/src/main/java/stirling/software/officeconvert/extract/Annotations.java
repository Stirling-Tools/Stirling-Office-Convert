package stirling.software.officeconvert.extract;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup;

final class Annotations {

    private Annotations() {}

    static void show(PDFStreamEngine engine, PDPage page) throws IOException {
        for (PDAnnotation a : shown(page)) {
            try {
                engine.showAnnotation(a);
            } catch (IOException | RuntimeException e) {
                BrokenOperators.brokenStream(e instanceof IOException io ? io : new IOException(e));
            }
        }
    }

    private static List<PDAnnotation> shown(PDPage page) {
        List<PDAnnotation> out = new ArrayList<>();
        try {
            for (PDAnnotation a : page.getAnnotations()) {
                if (!(a instanceof PDAnnotationLink) && !(a instanceof PDAnnotationPopup) && !a.isHidden() && !a.isNoView()) {
                    out.add(a);
                }
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        return out;
    }
}
