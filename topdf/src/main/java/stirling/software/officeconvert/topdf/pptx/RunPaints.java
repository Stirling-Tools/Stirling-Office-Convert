package stirling.software.officeconvert.topdf.pptx;

import java.awt.Color;
import java.util.function.Consumer;

import org.apache.poi.common.usermodel.fonts.FontGroup;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.sl.draw.DrawPaint;
import org.apache.poi.sl.usermodel.PaintStyle;
import org.apache.poi.xslf.usermodel.XSLFColor;
import org.apache.poi.xslf.usermodel.XSLFGradientPaint;
import org.apache.poi.xslf.usermodel.XSLFSheet;
import org.apache.poi.xslf.usermodel.XSLFTexturePaint;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xslf.usermodel.XSLFTheme;
import org.apache.xmlbeans.XmlCursor;
import org.openxmlformats.schemas.drawingml.x2006.main.CTColor;
import org.openxmlformats.schemas.drawingml.x2006.main.CTFontCollection;
import org.openxmlformats.schemas.drawingml.x2006.main.CTFontScheme;
import org.openxmlformats.schemas.drawingml.x2006.main.CTSchemeColor;
import org.openxmlformats.schemas.drawingml.x2006.main.CTShapeStyle;
import org.openxmlformats.schemas.drawingml.x2006.main.CTSolidColorFillProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextCharacterProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextFont;
import org.openxmlformats.schemas.drawingml.x2006.main.STSchemeColorVal;

final class RunPaints {

    private static final String P = "http://schemas.openxmlformats.org/presentationml/2006/main";

    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";

    private final XSLFTextShape shape;

    private final Inherited<Boolean> placeholder;

    private final Inherited<CTShapeStyle> style;

    RunPaints(XSLFTextShape shape) {
        this.shape = shape;
        this.placeholder = Inherited.of(() -> shape.getPlaceholder() != null);
        this.style = Inherited.of(() -> style(shape));
    }

    void checkPlaceholder() {
        placeholder.get();
    }

    void fontColor(CTTextCharacterProperties props, Consumer<PaintStyle> val) {
        if (props == null) {
            return;
        }
        CTShapeStyle st = style.get();
        CTSchemeColor phClr = null;
        if (st != null && st.getFontRef() != null) {
            phClr = st.getFontRef().getSchemeClr();
        }
        XSLFSheet sheet = shape.getSheet();
        PackagePart pp = sheet.getPackagePart();
        XSLFTheme theme = sheet.getTheme();
        PaintStyle ps = paint(props, phClr, pp, theme, sheet);
        if (ps != null) {
            val.accept(ps);
        }
    }

    private PaintStyle paint(CTTextCharacterProperties fp, CTSchemeColor phClr, PackagePart pp, XSLFTheme theme,
            XSLFSheet sheet) {
        if (fp.isSetNoFill()) {
            return null;
        } else if (fp.isSetSolidFill()) {
            CTSolidColorFillProperties solid = fp.getSolidFill();
            CTSchemeColor nested = solid.getSchemeClr();
            boolean useNested = nested != null && nested.getVal() != null
                    && !STSchemeColorVal.PH_CLR.equals(nested.getVal());
            return DrawPaint.createSolidPaint(new XSLFColor(solid, theme, useNested ? nested : phClr, sheet)
                    .getColorStyle());
        } else if (fp.isSetBlipFill()) {
            return new XSLFTexturePaint(shape, fp.getBlipFill(), pp, phClr, theme, sheet);
        } else if (fp.isSetGradFill()) {
            return new XSLFGradientPaint(fp.getGradFill(), phClr, theme, sheet);
        } else if (phClr != null) {
            return DrawPaint.createSolidPaint(new XSLFColor(null, theme, phClr, sheet).getColorStyle());
        }
        return null;
    }

    private static CTShapeStyle style(XSLFTextShape shape) {
        Object child = null;
        try (XmlCursor cur = shape.getXmlObject().newCursor()) {
            if (cur.toChild(P, "style")) {
                child = cur.getObject();
            }
            if (cur.toChild(A, "style")) {
                child = cur.getObject();
            }
        }
        return (CTShapeStyle) child;
    }

    static void highlight(CTTextCharacterProperties props, Consumer<PaintStyle> val) {
        if (props == null) {
            return;
        }
        CTColor col = props.getHighlight();
        if (col == null) {
            return;
        }
        byte[] cols = col.getSrgbClr().getVal();
        val.accept(DrawPaint.createSolidPaint(new Color(0xFF & cols[0], 0xFF & cols[1], 0xFF & cols[2])));
    }

    static CTTextFont font(XSLFTextShape shape, CTTextCharacterProperties props, FontGroup group) {
        if (props == null) {
            return null;
        }
        CTTextFont font = switch (group) {
            case EAST_ASIAN -> props.getEa();
            case COMPLEX_SCRIPT -> props.getCs();
            case SYMBOL -> props.getSym();
            default -> props.getLatin();
        };
        if (font == null) {
            return null;
        }
        String typeface = font.getTypeface();
        if (typeface == null) {
            typeface = "";
        }
        if (typeface.startsWith("+mj-") || typeface.startsWith("+mn-")) {
            CTFontScheme scheme = shape.getSheet().getTheme().getXmlObject().getThemeElements().getFontScheme();
            CTFontCollection coll = typeface.startsWith("+mj-") ? scheme.getMajorFont() : scheme.getMinorFont();
            String part = typeface.substring(4);
            if ("ea".equals(part)) {
                font = coll.getEa();
            } else if ("cs".equals(part)) {
                font = coll.getCs();
            } else {
                font = coll.getLatin();
            }
            if (font == null || font.getTypeface() == null || "".equals(font.getTypeface())) {
                return null;
            }
        }
        return font;
    }
}
