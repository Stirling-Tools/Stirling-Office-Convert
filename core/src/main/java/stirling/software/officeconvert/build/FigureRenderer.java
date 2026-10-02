package stirling.software.officeconvert.build;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.state.PDTextState;
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

final class FigureRenderer extends PDFRenderer {

    private static final double FLATNESS = 0.1;

    private static final long WHOLE_BILEVEL = 32_000_000L;

    private static final double PAPER_SPAN = 0.9;

    private final boolean text;
    private final boolean paper;
    private Rectangle2D span;

    FigureRenderer(PDDocument document, boolean text) {
        this(document, text, false);
    }

    FigureRenderer(PDDocument document, boolean text, boolean paper) {
        super(document);
        this.text = text && !paper;
        this.paper = paper;
    }

    void paperSpan(Rectangle2D region) {
        span = region;
    }

    @Override
    protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
        Rectangle2D target = span;
        return new PageDrawer(parameters) {
            private boolean painting;
            private final OperatorBudget budget = new OperatorBudget("rendering a figure");

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

            @Override
            protected int getSubsampling(PDImage image, AffineTransform at) {
                boolean bilevel = image.getBitsPerComponent() == 1 && (long) image.getWidth() * image.getHeight() <= WHOLE_BILEVEL;
                return bilevel ? 1 : super.getSubsampling(image, at);
            }

            @Override
            public void clip(int windingRule) {
                flatten(getLinePath());
                super.clip(windingRule);
            }

            @Override
            public void fillPath(int windingRule) throws IOException {
                if (!paper || painting) {
                    super.fillPath(windingRule);
                    return;
                }
                if (!pageSized(getLinePath().getBounds2D())) {
                    getLinePath().reset();
                    return;
                }
                painting = true;
                try {
                    super.fillPath(windingRule);
                } finally {
                    painting = false;
                }
            }

            @Override
            public void fillAndStrokePath(int windingRule) throws IOException {
                if (paper && !painting) {
                    fillPath(windingRule);
                    return;
                }
                super.fillAndStrokePath(windingRule);
            }

            @Override
            public void strokePath() throws IOException {
                if (paper && !painting) {
                    getLinePath().reset();
                    return;
                }
                super.strokePath();
            }

            @Override
            public void shadingFill(COSName shadingName) throws IOException {
                if (!paper || painting) {
                    super.shadingFill(shadingName);
                } else if (pageSized(clipBounds())) {
                    painting = true;
                    try {
                        super.shadingFill(shadingName);
                    } finally {
                        painting = false;
                    }
                }
            }

            @Override
            public void drawImage(PDImage pdImage) throws IOException {
                if ((!paper || painting) && ImageBudget.affordable(pdImage)) {
                    super.drawImage(pdImage);
                }
            }

            @Override
            public void showAnnotation(PDAnnotation annotation) throws IOException {
                if (!paper) {
                    super.showAnnotation(annotation);
                }
            }

            private boolean pageSized(Rectangle2D r) {
                if (r == null) {
                    return true;
                }
                PDRectangle crop = getPage().getCropBox();
                Rectangle2D t = target != null ? target
                        : new Rectangle2D.Double(crop.getLowerLeftX(), crop.getLowerLeftY(), crop.getWidth(), crop.getHeight());
                Rectangle2D on = r.createIntersection(t);
                return on.getWidth() >= PAPER_SPAN * t.getWidth() && on.getHeight() >= PAPER_SPAN * t.getHeight();
            }

            private Rectangle2D clipBounds() {
                Rectangle2D b = null;
                for (Path2D p : getGraphicsState().getCurrentClippingPaths()) {
                    Rectangle2D pb = p.getBounds2D();
                    b = b == null ? pb : b.createIntersection(pb);
                }
                return b;
            }

            @Override
            protected void showFontGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement)
                    throws IOException {
                PDTextState state = getGraphicsState().getTextState();
                RenderingMode mode = state.getRenderingMode();
                if (text) {
                    super.showFontGlyph(textRenderingMatrix, font, code, displacement);
                } else if (mode.isClip()) {
                    state.setRenderingMode(RenderingMode.NEITHER_CLIP);
                    try {
                        super.showFontGlyph(textRenderingMatrix, font, code, displacement);
                    } finally {
                        state.setRenderingMode(mode);
                    }
                }
            }

            @Override
            protected void showType3Glyph(Matrix textRenderingMatrix, PDType3Font font, int code, Vector displacement)
                    throws IOException {
                if (text) {
                    super.showType3Glyph(textRenderingMatrix, font, code, displacement);
                }
            }
        };
    }

    private static void flatten(GeneralPath path) {
        boolean curved = false;
        for (PathIterator it = path.getPathIterator(null); !it.isDone() && !curved; it.next()) {
            int seg = it.currentSegment(new double[6]);
            curved = seg == PathIterator.SEG_CUBICTO || seg == PathIterator.SEG_QUADTO;
        }
        if (curved) {
            GeneralPath flat = new GeneralPath(path.getWindingRule());
            flat.append(path.getPathIterator(null, FLATNESS), false);
            path.reset();
            path.append(flat, false);
        }
    }
}
