package stirling.software.officeconvert.pdfa;

import java.awt.geom.PathIterator;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;

import stirling.software.officeconvert.extract.PdfFiles;

final class FlattenRenderer extends PDFRenderer {

    static final int MAX_PATH_SEGMENTS = 100_000;

    private boolean tooComplex;

    FlattenRenderer(PDDocument doc) {
        super(doc);
    }

    BufferedImage render(int page, float scale) throws IOException {
        tooComplex = false;
        BufferedImage image = renderImage(page, scale, ImageType.RGB);
        PdfFiles.stopIfInterrupted();
        if (tooComplex) {
            throw new IOException("a path has more than " + MAX_PATH_SEGMENTS + " segments, too many to draw");
        }
        return image;
    }

    @Override
    protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
        return new Drawer(parameters);
    }

    private final class Drawer extends PageDrawer {

        Drawer(PageDrawerParameters parameters) throws IOException {
            super(parameters);
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            PdfFiles.stopIfInterrupted();
            super.processOperator(operator, operands);
        }

        @Override
        public void strokePath() throws IOException {
            if (drawable()) {
                super.strokePath();
            }
        }

        @Override
        public void fillPath(int windingRule) throws IOException {
            if (drawable()) {
                super.fillPath(windingRule);
            }
        }

        @Override
        public void fillAndStrokePath(int windingRule) throws IOException {
            if (drawable()) {
                super.fillAndStrokePath(windingRule);
            }
        }

        private boolean drawable() {
            int n = 0;
            for (PathIterator it = getLinePath().getPathIterator(null); !it.isDone(); it.next()) {
                if (++n > MAX_PATH_SEGMENTS) {
                    tooComplex = true;
                    getLinePath().reset();
                    return false;
                }
            }
            return true;
        }
    }
}
