package stirling.software.officeconvert.slides;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.function.IntPredicate;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import stirling.software.officeconvert.extract.ImageBudget;
import stirling.software.officeconvert.extract.OperatorBudget;
import stirling.software.officeconvert.extract.RgbGroup;
import stirling.software.officeconvert.layout.Box;

final class ArtRenderer extends PDFRenderer {

    private IntPredicate keep = order -> false;

    ArtRenderer(PDDocument document) {
        super(document);
        setSubsamplingAllowed(true);
    }

    BufferedImage render(PDPage page, int pageIndex, AffineTransform toDisplay, Box box, float scale, IntPredicate keep)
            throws IOException {
        this.keep = keep;
        int w = Math.max(1, Math.round(box.width() * scale));
        int h = Math.max(1, Math.round(box.height() * scale));
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setBackground(new Color(0, 0, 0, 0));
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setClip(0, 0, w, h);
            AffineTransform t = new AffineTransform();
            t.scale(scale, scale);
            t.translate(-box.x(), -box.top());
            t.concatenate(toDisplay);
            t.concatenate(deviceTransform(page).createInverse());
            g.transform(t);
            renderPageToGraphics(pageIndex, g, 1f);
        } catch (NoninvertibleTransformException e) {
            throw new IOException("The page cannot be drawn", e);
        } finally {
            g.dispose();
        }
        return img;
    }

    private static AffineTransform deviceTransform(PDPage page) {
        PDRectangle crop = page.getCropBox();
        int rotation = Math.floorMod(page.getRotation(), 360);
        AffineTransform t = new AffineTransform();
        if (rotation != 0) {
            float tx = 0;
            float ty = 0;
            switch (rotation) {
                case 90 -> tx = crop.getHeight();
                case 270 -> ty = crop.getWidth();
                case 180 -> {
                    tx = crop.getWidth();
                    ty = crop.getHeight();
                }
                default -> {}
            }
            t.translate(tx, ty);
            t.rotate(Math.toRadians(rotation));
        }
        t.translate(0, crop.getHeight());
        t.scale(1, -1);
        t.translate(-crop.getLowerLeftX(), -crop.getLowerLeftY());
        return t;
    }

    @Override
    protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
        return new Drawer(parameters);
    }

    private final class Drawer extends PageDrawer {

        private int order;
        private int nested;
        private final OperatorBudget budget = new OperatorBudget("drawing slide art");

        Drawer(PageDrawerParameters parameters) throws IOException {
            super(parameters);
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            if (budget.run(operator)) {
                super.processOperator(operator, operands);
            }
        }

        @Override
        public void showForm(PDFormXObject form) throws IOException {
            if (budget.form()) {
                super.showForm(form);
            }
        }

        @Override
        public void showTransparencyGroup(PDTransparencyGroup form) throws IOException {
            if (budget.form()) {
                super.showTransparencyGroup(RgbGroup.of(form));
            }
        }

        private boolean kept() {
            return nested > 0 || keep.test(order++);
        }

        private interface Paint {
            void run() throws IOException;
        }

        private void paint(Paint p) throws IOException {
            nested++;
            try {
                p.run();
            } finally {
                nested--;
            }
        }

        @Override
        public void fillPath(int windingRule) throws IOException {
            if (kept()) {
                paint(() -> super.fillPath(windingRule));
            } else {
                getLinePath().reset();
            }
        }

        @Override
        public void strokePath() throws IOException {
            if (kept()) {
                paint(super::strokePath);
            } else {
                getLinePath().reset();
            }
        }

        @Override
        public void fillAndStrokePath(int windingRule) throws IOException {
            if (kept()) {
                paint(() -> super.fillAndStrokePath(windingRule));
            } else {
                getLinePath().reset();
            }
        }

        @Override
        public void shadingFill(COSName shadingName) throws IOException {
            if (kept()) {
                paint(() -> super.shadingFill(shadingName));
            }
        }

        @Override
        public void drawImage(PDImage pdImage) throws IOException {
            if (kept() && ImageBudget.affordable(pdImage)) {
                paint(() -> super.drawImage(pdImage));
            }
        }

        @Override
        protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement)
                throws IOException {
            if (nested > 0) {
                super.showGlyph(textRenderingMatrix, font, code, displacement);
                return;
            }
            RenderingMode mode = getGraphicsState().getTextState().getRenderingMode();
            if (mode != RenderingMode.NEITHER && mode != RenderingMode.NEITHER_CLIP) {
                order++;
            }
        }

        @Override
        public void showAnnotation(PDAnnotation annotation) {
        }
    }
}
