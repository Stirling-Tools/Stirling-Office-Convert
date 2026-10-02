package stirling.software.officeconvert.extract;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.WeakHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.fontbox.util.BoundingBox;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDCIDFont;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

final class TextPositions {

    private static final Log LOG = LogFactory.getLog(TextPositions.class);

    private static final GlyphList GLYPHS = glyphs();

    private final Map<COSDictionary, Float> fontHeights = new WeakHashMap<>();
    private int pageRotation;
    private PDRectangle pageSize;
    private Matrix translateMatrix;

    void page(PDPage page) {
        pageRotation = page.getRotation();
        pageSize = page.getCropBox();
        if (Float.compare(pageSize.getLowerLeftX(), 0) == 0 && Float.compare(pageSize.getLowerLeftY(), 0) == 0) {
            translateMatrix = null;
        } else {
            translateMatrix = Matrix.getTranslateInstance(-pageSize.getLowerLeftX(), -pageSize.getLowerLeftY());
        }
    }

    TextPosition of(PDGraphicsState state, Matrix textMatrix, Matrix trm, PDFont font, int code, Vector displacement)
            throws IOException {
        Matrix ctm = state.getCurrentTransformationMatrix();
        float fontSize = state.getTextState().getFontSize();
        float horizontalScaling = state.getTextState().getHorizontalScaling() / 100f;
        float displacementX = displacement.getX();
        if (font.isVertical()) {
            displacementX = font.getWidth(code) / 1000f;
            TrueTypeFont ttf = null;
            if (font instanceof PDTrueTypeFont tt) {
                ttf = tt.getTrueTypeFont();
            } else if (font instanceof PDType0Font t0) {
                PDCIDFont cid = t0.getDescendantFont();
                if (cid instanceof PDCIDFontType2 cid2) {
                    ttf = cid2.getTrueTypeFont();
                }
            }
            if (ttf != null && ttf.getUnitsPerEm() != 1000) {
                displacementX *= 1000f / ttf.getUnitsPerEm();
            }
        }
        float tx = displacementX * fontSize * horizontalScaling;
        float ty = displacement.getY() * fontSize;
        Matrix next = Matrix.getTranslateInstance(tx, ty).multiply(textMatrix).multiply(ctm);
        float nextX = next.getTranslateX();
        float nextY = next.getTranslateY();
        float dxDisplay = nextX - trm.getTranslateX();
        Float fontHeight = fontHeights.get(font.getCOSObject());
        if (fontHeight == null) {
            fontHeight = fontHeight(font);
            fontHeights.put(font.getCOSObject(), fontHeight);
        }
        float dyDisplay = fontHeight * trm.getScalingFactorY();
        float glyphToText = 0.001f;
        if (font instanceof PDType3Font) {
            glyphToText = font.getFontMatrix().getScaleX();
        }
        float spaceWidthText = 0;
        try {
            spaceWidthText = font.getSpaceWidth() * glyphToText;
        } catch (Exception e) {
            LOG.warn(e, e);
        }
        if (Float.compare(spaceWidthText, 0) == 0) {
            spaceWidthText = font.getAverageFontWidth() * glyphToText;
            spaceWidthText *= 0.8f;
        }
        if (Float.compare(spaceWidthText, 0) == 0) {
            spaceWidthText = 1.0f;
        }
        float spaceWidthDisplay = spaceWidthText * trm.getScalingFactorX();
        String unicode = font.toUnicode(code, GLYPHS);
        if (unicode == null) {
            if (!(font instanceof PDSimpleFont)) {
                return null;
            }
            unicode = new String(new char[] {(char) code});
        }
        Matrix translated = trm;
        if (translateMatrix != null) {
            translated = Matrix.concatenate(translateMatrix, trm);
            nextX -= pageSize.getLowerLeftX();
            nextY -= pageSize.getLowerLeftY();
        }
        return new TextPosition(pageRotation, pageSize.getWidth(), pageSize.getHeight(), translated, nextX, nextY,
                Math.abs(dyDisplay), dxDisplay, Math.abs(spaceWidthDisplay), unicode, new int[] {code}, font, fontSize,
                (int) (fontSize * textMatrix.getScalingFactorX()));
    }

    private static float fontHeight(PDFont font) throws IOException {
        BoundingBox bbox = font.getBoundingBox();
        if (bbox.getLowerLeftY() < Short.MIN_VALUE) {
            bbox.setLowerLeftY(-(bbox.getLowerLeftY() + 65536));
        }
        float glyphHeight = bbox.getHeight() / 2;
        PDFontDescriptor fd = font.getFontDescriptor();
        if (fd != null) {
            float capHeight = fd.getCapHeight();
            if (Float.compare(capHeight, 0) != 0 && (capHeight < glyphHeight || Float.compare(glyphHeight, 0) == 0)) {
                glyphHeight = capHeight;
            }
            float ascent = fd.getAscent();
            float descent = fd.getDescent();
            if (capHeight > ascent && ascent > 0 && descent < 0
                    && ((ascent - descent) / 2 < glyphHeight || Float.compare(glyphHeight, 0) == 0)) {
                glyphHeight = (ascent - descent) / 2;
            }
        }
        if (font instanceof PDType3Font) {
            return font.getFontMatrix().transformPoint(0, glyphHeight).y;
        }
        return glyphHeight / 1000;
    }

    private static GlyphList glyphs() {
        try (InputStream extra = GlyphList.class.getResourceAsStream("/org/apache/pdfbox/resources/glyphlist/additional.txt")) {
            return new GlyphList(GlyphList.getAdobeGlyphList(), extra);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
