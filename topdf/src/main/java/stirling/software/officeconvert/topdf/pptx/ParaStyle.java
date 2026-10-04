package stirling.software.officeconvert.topdf.pptx;

import java.awt.Color;
import java.util.List;
import java.util.function.Consumer;

import org.apache.poi.ooxml.util.POIXMLUnits;
import org.apache.poi.sl.draw.DrawPaint;
import org.apache.poi.sl.usermodel.AutoNumberingScheme;
import org.apache.poi.sl.usermodel.PaintStyle;
import org.apache.poi.sl.usermodel.TextParagraph.TextAlign;
import org.apache.poi.xslf.model.ParagraphPropertyFetcher.ParaPropFetcher;
import org.apache.poi.xslf.usermodel.XSLFColor;
import org.apache.poi.xslf.usermodel.XSLFSheet;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xslf.usermodel.XSLFTheme;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextBodyProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextNormalAutofit;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextParagraphProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextTabStop;

final class ParaStyle {

    private final XSLFTextShape shape;

    private final ParaProps inherited;

    private final ParaPropFetcher<Color> bulletColorFetcher;

    private final Inherited<Color> bulletColor;

    ParaStyle(XSLFTextShape shape, StyleChain chain, ParaProps inherited) {
        this.shape = shape;
        this.inherited = inherited;
        this.bulletColorFetcher = this::bulletColor;
        this.bulletColor = Inherited.resolve(chain.paragraphs(), chain.end(), bulletColorFetcher::fetch);
    }

    TextAlign textAlign(XSLFTextParagraph p) {
        return fetch(p, ParaProps.ALIGN, inherited.align());
    }

    Double leftMargin(XSLFTextParagraph p) {
        return fetch(p, ParaProps.MARGIN_LEFT, inherited.marginLeft());
    }

    Double rightMargin(XSLFTextParagraph p) {
        return fetch(p, ParaProps.MARGIN_RIGHT, inherited.marginRight());
    }

    Double indent(XSLFTextParagraph p) {
        return fetch(p, ParaProps.INDENT, inherited.indent());
    }

    Double defaultTabSize(XSLFTextParagraph p) {
        return fetch(p, ParaProps.DEFAULT_TAB, inherited.defaultTab());
    }

    Double lineSpacing(XSLFTextParagraph p) {
        Double lnSpc = fetch(p, ParaProps.LINE, inherited.line());
        if (lnSpc != null && lnSpc > 0) {
            CTTextBodyProperties body = TextFrame.bodyPr(shape);
            CTTextNormalAutofit fit = body.getNormAutofit();
            if (fit != null) {
                double scale = 1 - POIXMLUnits.parsePercent(fit.xgetLnSpcReduction()) / 100_000.;
                return lnSpc * scale;
            }
        }
        return lnSpc;
    }

    Double spaceBefore(XSLFTextParagraph p) {
        return fetch(p, ParaProps.BEFORE, inherited.before());
    }

    Double spaceAfter(XSLFTextParagraph p) {
        return fetch(p, ParaProps.AFTER, inherited.after());
    }

    List<CTTextTabStop> tabStops(XSLFTextParagraph p) {
        return fetch(p, ParaProps.TABS, inherited.tabs());
    }

    Boolean rtl(XSLFTextParagraph p) {
        return fetch(p, ParaProps.RTL, inherited.rtl());
    }

    boolean isBullet(XSLFTextParagraph p) {
        Boolean b = fetch(p, ParaProps.BULLET, inherited.bullet());
        return b != null && b;
    }

    AutoNumberingScheme autoNumberingScheme(XSLFTextParagraph p) {
        return fetch(p, ParaProps.NUMBERING, inherited.numbering());
    }

    Integer autoNumberingStartAt(XSLFTextParagraph p) {
        return fetch(p, ParaProps.START_AT, inherited.startAt());
    }

    String bulletCharacter(XSLFTextParagraph p) {
        return fetch(p, ParaProps.BULLET_CHAR, inherited.bulletChar());
    }

    String bulletFont(XSLFTextParagraph p) {
        return fetch(p, ParaProps.BULLET_FONT, inherited.bulletFont());
    }

    Double bulletFontSize(XSLFTextParagraph p) {
        return fetch(p, ParaProps.BULLET_SIZE, inherited.bulletSize());
    }

    PaintStyle bulletFontColor(XSLFTextParagraph p) {
        Color col = fetch(p, bulletColorFetcher, bulletColor);
        return col == null ? null : DrawPaint.createSolidPaint(col);
    }

    Object bulletPicture(XSLFTextParagraph p) {
        return fetch(p, ParaProps.BULLET_PICTURE, inherited.bulletPicture());
    }

    private void bulletColor(CTTextParagraphProperties props, Consumer<Color> val) {
        XSLFSheet sheet = shape.getSheet();
        XSLFTheme theme = sheet.getTheme();
        if (props.isSetBuClr()) {
            val.accept(new XSLFColor(props.getBuClr(), theme, null, sheet).getColor());
        }
    }

    private static <T> T fetch(XSLFTextParagraph p, ParaPropFetcher<T> fetcher, Inherited<T> tail) {
        CTTextParagraphProperties own = p.getXmlObject().getPPr();
        if (own != null) {
            Inherited.Found<T> found = new Inherited.Found<>();
            fetcher.fetch(own, found);
            if (found.set()) {
                return found.value();
            }
        }
        return tail.get();
    }
}
