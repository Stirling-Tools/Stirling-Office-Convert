package stirling.software.officeconvert.topdf.pptx;

import java.awt.Color;
import java.util.List;

import org.apache.poi.common.usermodel.fonts.FontGroup;
import org.apache.poi.ooxml.util.POIXMLUnits;
import org.apache.poi.sl.usermodel.PaintStyle;
import org.apache.poi.sl.usermodel.TextRun.TextCap;
import org.apache.poi.xslf.model.CharacterPropertyFetcher.CharPropFetcher;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.openxmlformats.schemas.drawingml.x2006.main.CTLineProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTOuterShadowEffect;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextCharacterProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextFont;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextLineBreak;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextNormalAutofit;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextBodyProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextParagraphProperties;

final class RunStyle {

    private final XSLFTextShape shape;

    private final boolean master;

    private final CharProps inherited;

    private final RunPaints paints;

    private final CharPropFetcher<PaintStyle> colorFetcher;

    private final Inherited<PaintStyle> color;

    private final Inherited<Color> solid;

    private final CharPropFetcher<CTTextFont> latinFont;

    private final CharPropFetcher<CTTextFont> eastAsianFont;

    private final CharPropFetcher<CTTextFont> complexFont;

    private final CharPropFetcher<CTTextFont> symbolFont;

    private final Inherited<CTTextFont> latin;

    private final Inherited<CTTextFont> eastAsian;

    private final Inherited<CTTextFont> complex;

    private final Inherited<CTTextFont> symbol;

    RunStyle(XSLFTextShape shape, StyleChain chain, CharProps inherited) {
        this.shape = shape;
        this.inherited = inherited;
        this.master = chain.master();
        this.paints = new RunPaints(shape);
        this.colorFetcher = paints::fontColor;
        this.latinFont = font(FontGroup.LATIN);
        this.eastAsianFont = font(FontGroup.EAST_ASIAN);
        this.complexFont = font(FontGroup.COMPLEX_SCRIPT);
        this.symbolFont = font(FontGroup.SYMBOL);
        List<CTTextCharacterProperties> levels = chain.runs();
        RuntimeException end = chain.end();
        color = Inherited.resolve(levels, end, colorFetcher::fetch);
        solid = color.failure() != null ? new Inherited<>(null, color.failure())
                : Inherited.of(() -> Paints.solid(color.value()));
        latin = Inherited.resolve(levels, end, latinFont::fetch);
        eastAsian = Inherited.resolve(levels, end, eastAsianFont::fetch);
        complex = Inherited.resolve(levels, end, complexFont::fetch);
        symbol = Inherited.resolve(levels, end, symbolFont::fetch);
    }

    Double fontSize(XSLFTextRun r) {
        double scale = 1;
        CTTextBodyProperties body = TextFrame.bodyPr(shape);
        if (body != null) {
            CTTextNormalAutofit fit = body.getNormAutofit();
            if (fit != null && fit.isSetFontScale()) {
                scale = POIXMLUnits.parsePercent(fit.xgetFontScale()) / 100000.;
            }
        }
        Double d = fetch(r, CharProps.SIZE, inherited.size());
        return d == null ? null : d * scale;
    }

    boolean bold(XSLFTextRun r) {
        Boolean b = fetch(r, CharProps.BOLD, inherited.bold());
        return b != null && b;
    }

    boolean italic(XSLFTextRun r) {
        Boolean b = fetch(r, CharProps.ITALIC, inherited.italic());
        return b != null && b;
    }

    boolean underlined(XSLFTextRun r) {
        Boolean b = fetch(r, CharProps.UNDERLINE, inherited.underline());
        return b != null && b;
    }

    boolean strikethrough(XSLFTextRun r) {
        Boolean b = fetch(r, CharProps.STRIKE, inherited.strike());
        return b != null && b;
    }

    double characterSpacing(XSLFTextRun r) {
        Double d = fetch(r, CharProps.SPACING, inherited.spacing());
        return d == null ? 0 : d;
    }

    TextCap textCap(XSLFTextRun r) {
        TextCap c = fetch(r, CharProps.CAP, inherited.cap());
        return c == null ? TextCap.NONE : c;
    }

    PaintStyle fontColor(XSLFTextRun r) {
        paints.checkPlaceholder();
        return fetch(r, colorFetcher, color);
    }

    Color solidColor(XSLFTextRun r) {
        paints.checkPlaceholder();
        Inherited.Found<PaintStyle> found = local(r, colorFetcher);
        return found.set() ? Paints.solid(found.value()) : solid.get();
    }

    static boolean plain(XSLFTextRun r) {
        return r.getClass() == XSLFTextRun.class || r.getXmlObject() instanceof CTTextLineBreak;
    }

    PaintStyle highlightColor(XSLFTextRun r) {
        paints.checkPlaceholder();
        return fetch(r, CharProps.HIGHLIGHT, inherited.highlight());
    }

    String fontFamily(XSLFTextRun r, FontGroup g) {
        CTTextFont f = switch (g) {
            case EAST_ASIAN -> fetch(r, eastAsianFont, eastAsian);
            case COMPLEX_SCRIPT -> fetch(r, complexFont, complex);
            case SYMBOL -> fetch(r, symbolFont, symbol);
            default -> fetch(r, latinFont, latin);
        };
        return f == null ? null : f.getTypeface();
    }

    Boolean noFill(XSLFTextRun r) {
        return fetch(r, CharProps.NO_FILL, inherited.noFill());
    }

    CTLineProperties outline(XSLFTextRun r) {
        return fetch(r, CharProps.OUTLINE, inherited.outline());
    }

    CTOuterShadowEffect shadow(XSLFTextRun r) {
        return fetch(r, CharProps.SHADOW, inherited.shadow());
    }

    Integer baseline(XSLFTextRun r) {
        return fetch(r, CharProps.BASELINE, inherited.baseline());
    }

    Integer kern(XSLFTextRun r) {
        return fetch(r, CharProps.KERN, inherited.kern());
    }

    private CharPropFetcher<CTTextFont> font(FontGroup g) {
        return (props, val) -> {
            CTTextFont f = RunPaints.font(shape, props, g);
            if (f != null) {
                val.accept(f);
            }
        };
    }

    private <T> T fetch(XSLFTextRun r, CharPropFetcher<T> fetcher, Inherited<T> tail) {
        Inherited.Found<T> found = local(r, fetcher);
        return found.set() ? found.value() : tail.get();
    }

    private <T> Inherited.Found<T> local(XSLFTextRun r, CharPropFetcher<T> fetcher) {
        Inherited.Found<T> found = new Inherited.Found<>();
        CTTextCharacterProperties own = r.getRPr(false);
        if (own != null) {
            fetcher.fetch(own, found);
        }
        if (!found.set() && !master) {
            CTTextParagraphProperties pPr = r.getParagraph().getXmlObject().getPPr();
            CTTextCharacterProperties def = pPr == null ? null : pPr.getDefRPr();
            if (def != null) {
                fetcher.fetch(def, found);
            }
        }
        return found;
    }
}
