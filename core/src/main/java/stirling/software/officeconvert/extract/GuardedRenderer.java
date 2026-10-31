package stirling.software.officeconvert.extract;

import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import stirling.software.officeconvert.memory.Admission;

public class GuardedRenderer extends PDFRenderer {

    public static final int MAX_ANNOTATIONS = 10_000;

    public static final int MAX_TYPE3_DEPTH = 8;

    private final String pass;

    public GuardedRenderer(PDDocument document, String pass) {
        super(document);
        this.pass = pass;
    }

    public BufferedImage draw(int page, float scale, ImageType type) throws IOException {
        try {
            BufferedImage image = renderImage(page, scale, type);
            PdfFiles.stopIfInterrupted();
            return image;
        } catch (StackOverflowError e) {
            throw new IOException("The page is nested too deeply to draw", e);
        } catch (OutOfMemoryError e) {
            throw new IOException(Admission.NEEDS_MEMORY, e);
        }
    }

    @Override
    protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
        return new Drawer(parameters, pass);
    }

    public static class Drawer extends PageDrawer {

        private static final String[] NONE = new String[0];

        private static final int MAX_FONTS = 256;

        private final OperatorBudget budget;
        private int annotations;
        private int type3;
        private boolean left;
        private final Map<COSDictionary, String[]> builtIn = new HashMap<>();

        public Drawer(PageDrawerParameters parameters, String pass) throws IOException {
            super(parameters);
            budget = new OperatorBudget(pass);
        }

        public boolean leftOut() {
            return left;
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            if (budget.run(operator)) {
                super.processOperator(operator, operands);
            } else {
                left = true;
            }
        }

        @Override
        public void showForm(PDFormXObject form) throws IOException {
            if (budget.form()) {
                super.showForm(form);
            } else {
                left = true;
            }
        }

        @Override
        public void showTransparencyGroup(PDTransparencyGroup form) throws IOException {
            if (budget.form()) {
                super.showTransparencyGroup(form);
            } else {
                left = true;
            }
        }

        @Override
        public void drawImage(PDImage pdImage) throws IOException {
            PDImage fitted = ImageBudget.fitted(pdImage);
            if (fitted != null) {
                super.drawImage(fitted);
            } else {
                left = true;
            }
        }

        @Override
        public void showAnnotation(PDAnnotation annotation) throws IOException {
            if (++annotations <= MAX_ANNOTATIONS) {
                super.showAnnotation(annotation);
            } else {
                left = true;
            }
        }

        @Override
        protected void showFontGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement)
                throws IOException {
            Shape substitute = substitute(font, code, displacement);
            if (substitute == null) {
                super.showFontGlyph(textRenderingMatrix, font, code, displacement);
                return;
            }
            Graphics2D graphics = getGraphics();
            setClip();
            graphics.setComposite(getGraphicsState().getNonStrokingJavaComposite());
            graphics.setPaint(getNonStrokingPaint());
            graphics.fill(textRenderingMatrix.createAffineTransform().createTransformedShape(substitute));
        }

        private Shape substitute(PDFont font, int code, Vector displacement) {
            if (!(font instanceof PDType1Font) || font.isEmbedded()
                    || !getGraphicsState().getTextState().getRenderingMode().isFill()
                    || getGraphicsState().getTextState().getRenderingMode().isClip()) {
                return null;
            }
            if (builtIn.size() >= MAX_FONTS && !builtIn.containsKey(font.getCOSObject())) {
                return null;
            }
            String[] encoding = builtIn.computeIfAbsent(font.getCOSObject(), k -> {
                String[] found = BuiltInEncodings.of(font);
                return found == null ? NONE : found;
            });
            String text = code >= 0 && code < encoding.length ? encoding[code] : null;
            return text == null ? null : SubstituteGlyphs.outline(font, text, displacement.getX());
        }

        @Override
        protected void showType3Glyph(Matrix textRenderingMatrix, PDType3Font font, int code, Vector displacement)
                throws IOException {
            if (type3 >= MAX_TYPE3_DEPTH) {
                left = true;
                return;
            }
            type3++;
            try {
                super.showType3Glyph(textRenderingMatrix, font, code, displacement);
            } finally {
                type3--;
            }
        }
    }
}
