package stirling.software.officeconvert.extract;

import java.awt.Font;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.util.Locale;

import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;

final class SubstituteGlyphs {

    private static final float EM = 1000f;

    private static final float MIN_SQUEEZE = 0.5f;

    private static final float MAX_STRETCH = 2f;

    private static final FontRenderContext CONTEXT = new FontRenderContext(null, true, true);

    private static final Font[] FACES = new Font[4];

    private SubstituteGlyphs() {}

    static Shape outline(PDFont font, String text, float advance) {
        Font face = face(style(font));
        if (text.isEmpty() || face.canDisplayUpTo(text) != -1) {
            return null;
        }
        GlyphVector glyphs = face.createGlyphVector(CONTEXT, text);
        float drawn = (float) glyphs.getLogicalBounds().getWidth() / EM;
        float scale = drawn > 0 && advance > 0 ? Math.clamp(advance / drawn, MIN_SQUEEZE, MAX_STRETCH) : 1f;
        AffineTransform toText = AffineTransform.getScaleInstance(scale / EM, -1 / EM);
        return toText.createTransformedShape(glyphs.getOutline());
    }

    private static int style(PDFont font) {
        String name = font.getName() == null ? "" : FontNames.stripSubset(font.getName()).toLowerCase(Locale.ROOT);
        PDFontDescriptor fd = font.getFontDescriptor();
        boolean italic = name.matches("cm(mi|ti|sl|bxti|bxsl|itt|ssi|u).*") || fd != null && fd.isItalic();
        boolean bold = name.matches("cm(bx|b\\d|mib|bsy|ssbx).*") || fd != null && fd.isForceBold();
        return (bold ? Font.BOLD : 0) | (italic ? Font.ITALIC : 0);
    }

    private static Font face(int style) {
        synchronized (FACES) {
            if (FACES[style] == null) {
                FACES[style] = new Font(Font.SERIF, style, (int) EM);
            }
            return FACES[style];
        }
    }
}
