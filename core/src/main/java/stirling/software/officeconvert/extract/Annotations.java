package stirling.software.officeconvert.extract;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup;

final class Annotations {

    private Annotations() {}

    static void show(PDFStreamEngine engine, PDPage page, OptionalContent layers) throws IOException {
        for (PDAnnotation a : shown(page, layers)) {
            try {
                engine.showAnnotation(a);
            } catch (IOException | RuntimeException e) {
                BrokenOperators.brokenStream(e instanceof IOException io ? io : new IOException(e));
            }
        }
    }

    static boolean any(PDPage page) {
        return page.getCOSObject().getDictionaryObject(COSName.ANNOTS) instanceof COSArray annots && annots.size() > 0;
    }

    private static List<PDAnnotation> shown(PDPage page, OptionalContent layers) {
        List<PDAnnotation> out = new ArrayList<>();
        try {
            for (PDAnnotation a : page.getAnnotations()) {
                if (!(a instanceof PDAnnotationLink) && !(a instanceof PDAnnotationPopup) && !a.isHidden() && !a.isNoView()
                        && !layers.hidden(a.getCOSObject().getDictionaryObject(COSName.OC))) {
                    out.add(a);
                }
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        return out;
    }
}
