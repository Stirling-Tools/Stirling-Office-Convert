package stirling.software.officeconvert.topdf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.util.Matrix;

/** Shrinks every page of a PDF we drew, content, paper, link areas and jump targets alike, as a browser shrinks a
 * page whose content is wider than the paper. */
final class PageScale {

    private PageScale() {}

    static void shrink(Path pdf, float zoom, OutputStream out) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            for (PDPage page : doc.getPages()) {
                OfficeToPdf.stopIfInterrupted();
                try (PDPageContentStream before = new PDPageContentStream(doc, page, AppendMode.PREPEND, false)) {
                    before.saveGraphicsState();
                    before.transform(Matrix.getScaleInstance(zoom, zoom));
                }
                try (PDPageContentStream after = new PDPageContentStream(doc, page, AppendMode.APPEND, false)) {
                    after.restoreGraphicsState();
                }
                PDRectangle box = scaled(page.getMediaBox(), zoom);
                page.setMediaBox(box);
                page.setCropBox(box);
                for (PDAnnotation a : page.getAnnotations()) {
                    a.setRectangle(scaled(a.getRectangle(), zoom));
                    if (a instanceof PDAnnotationLink link) {
                        target(link.getDestination(), zoom);
                        if (link.getAction() instanceof PDActionGoTo goTo) {
                            target(goTo.getDestination(), zoom);
                        }
                    }
                }
            }
            PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
            if (outline != null) {
                outlines(outline, zoom, 0);
            }
            doc.save(out);
        }
    }

    // Bookmarks point at a spot on a page, which moves with the page's content
    private static void outlines(PDOutlineNode node, float zoom, int depth) throws IOException {
        if (depth > 64) {
            return;
        }
        for (var item : node.children()) {
            target(item.getDestination(), zoom);
            outlines(item, zoom, depth + 1);
        }
    }

    private static void target(PDDestination d, float zoom) {
        if (d instanceof PDPageXYZDestination xyz) {
            if (xyz.getLeft() != -1) {
                xyz.setLeft(Math.round(xyz.getLeft() * zoom));
            }
            if (xyz.getTop() != -1) {
                xyz.setTop(Math.round(xyz.getTop() * zoom));
            }
        }
    }

    private static PDRectangle scaled(PDRectangle r, float zoom) {
        return new PDRectangle(r.getLowerLeftX() * zoom, r.getLowerLeftY() * zoom, r.getWidth() * zoom,
                r.getHeight() * zoom);
    }
}
