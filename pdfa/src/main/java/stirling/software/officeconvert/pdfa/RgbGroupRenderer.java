package stirling.software.officeconvert.pdfa;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;

import stirling.software.officeconvert.extract.RgbGroup;

final class RgbGroupRenderer extends PDFRenderer {

    RgbGroupRenderer(PDDocument document) {
        super(document);
    }

    @Override
    protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
        return new PageDrawer(parameters) {
            @Override
            public void showTransparencyGroup(PDTransparencyGroup form) throws IOException {
                super.showTransparencyGroup(RgbGroup.of(form));
            }
        };
    }
}
