package stirling.software.officeconvert.pdfa;

import java.awt.geom.PathIterator;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;

import stirling.software.officeconvert.extract.PdfFiles;
import stirling.software.officeconvert.extract.RgbGroup;

final class FlattenRenderer extends PDFRenderer {

    static final int MAX_PATH_SEGMENTS = 100_000;

    private boolean tooComplex;

    private boolean recursiveGroup;

    FlattenRenderer(PDDocument doc) {
        super(doc);
    }

    BufferedImage render(int page, float scale) throws IOException {
        tooComplex = false;
        recursiveGroup = false;
        BufferedImage image = renderImage(page, scale, ImageType.RGB);
        PdfFiles.stopIfInterrupted();
        if (recursiveGroup) {
            throw new IOException("A transparency group or soft mask is recursive or nested too deeply");
        }
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

        private final Set<COSStream> groups = Collections.newSetFromMap(new IdentityHashMap<>());

        Drawer(PageDrawerParameters parameters) throws IOException {
            super(parameters);
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            PdfFiles.stopIfInterrupted();
            super.processOperator(operator, operands);
        }

        @Override
        public void showTransparencyGroup(PDTransparencyGroup form) throws IOException {
            enter(form);
            try {
                super.showTransparencyGroup(RgbGroup.of(form));
            } finally {
                groups.remove(form.getCOSObject());
            }
        }

        @Override
        protected void processSoftMask(PDTransparencyGroup form) throws IOException {
            enter(form);
            try {
                super.processSoftMask(form);
            } finally {
                groups.remove(form.getCOSObject());
            }
        }

        private void enter(PDTransparencyGroup form) throws IOException {
            PdfFiles.stopIfInterrupted();
            if (groups.size() >= 64 || !groups.add(form.getCOSObject())) {
                recursiveGroup = true;
                throw new IOException("A transparency group or soft mask is recursive or nested too deeply");
            }
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
