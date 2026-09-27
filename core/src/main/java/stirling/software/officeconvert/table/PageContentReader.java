package stirling.software.officeconvert.table;

import java.awt.geom.AffineTransform;
import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

final class PageContentReader {

    private PageContentReader() {}

    static PageContent read(PDDocument document, int pageNumber) throws IOException {
        PDPage page = document.getPage(pageNumber - 1);
        PDRectangle crop = page.getCropBox();
        PageWords.Result words = PageWords.read(document, pageNumber);
        PageShapes.Result shapes = PageShapes.read(page, displayTransform(crop, words.direction()));
        boolean swapped = words.direction() == 90 || words.direction() == 270;
        return new PageContent(
                pageNumber,
                swapped ? crop.getHeight() : crop.getWidth(),
                swapped ? crop.getWidth() : crop.getHeight(),
                words.words(),
                shapes.horizontals(),
                shapes.verticals(),
                shapes.fills());
    }

    private static AffineTransform displayTransform(PDRectangle crop, int direction) {
        double llx = crop.getLowerLeftX();
        double lly = crop.getLowerLeftY();
        double w = crop.getWidth();
        double h = crop.getHeight();
        return switch (direction) {
            case 90 -> new AffineTransform(0, 1, 1, 0, -lly, -llx);
            case 180 -> new AffineTransform(-1, 0, 0, 1, w + llx, -lly);
            case 270 -> new AffineTransform(0, -1, -1, 0, h + lly, w + llx);
            default -> new AffineTransform(1, 0, 0, -1, -llx, h + lly);
        };
    }
}
