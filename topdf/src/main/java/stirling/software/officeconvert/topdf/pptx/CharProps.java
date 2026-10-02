package stirling.software.officeconvert.topdf.pptx;

import java.util.List;

import org.apache.poi.ooxml.util.POIXMLUnits;
import org.apache.poi.sl.usermodel.PaintStyle;
import org.apache.poi.sl.usermodel.TextRun.TextCap;
import org.apache.poi.util.Units;
import org.apache.poi.xslf.model.CharacterPropertyFetcher.CharPropFetcher;
import org.openxmlformats.schemas.drawingml.x2006.main.CTLineProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTOuterShadowEffect;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextCharacterProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.STTextStrikeType;
import org.openxmlformats.schemas.drawingml.x2006.main.STTextUnderlineType;

record CharProps(Inherited<Double> size,
        Inherited<Boolean> bold,
        Inherited<Boolean> italic,
        Inherited<Boolean> underline,
        Inherited<Boolean> strike,
        Inherited<Double> spacing,
        Inherited<TextCap> cap,
        Inherited<PaintStyle> highlight,
        Inherited<Boolean> noFill,
        Inherited<CTLineProperties> outline,
        Inherited<CTOuterShadowEffect> shadow,
        Inherited<Integer> baseline,
        Inherited<Integer> kern) {

    static final CharPropFetcher<Double> SIZE = (props, val) -> {
        if (props.isSetSz()) {
            val.accept(props.getSz() * 0.01);
        }
    };

    static final CharPropFetcher<Boolean> BOLD = (props, val) -> {
        if (props.isSetB()) {
            val.accept(props.getB());
        }
    };

    static final CharPropFetcher<Boolean> ITALIC = (props, val) -> {
        if (props.isSetI()) {
            val.accept(props.getI());
        }
    };

    static final CharPropFetcher<Boolean> UNDERLINE = (props, val) -> {
        if (props.isSetU()) {
            val.accept(props.getU() != STTextUnderlineType.NONE);
        }
    };

    static final CharPropFetcher<Boolean> STRIKE = (props, val) -> {
        if (props.isSetStrike()) {
            val.accept(props.getStrike() != STTextStrikeType.NO_STRIKE);
        }
    };

    static final CharPropFetcher<Double> SPACING = (props, val) -> {
        if (props.isSetSpc()) {
            val.accept(Units.toPoints(POIXMLUnits.parseLength(props.xgetSpc())));
        }
    };

    static final CharPropFetcher<TextCap> CAP = (props, val) -> {
        if (props.isSetCap()) {
            val.accept(TextCap.values()[props.getCap().intValue() - 1]);
        }
    };

    static final CharPropFetcher<PaintStyle> HIGHLIGHT = RunPaints::highlight;

    static final CharPropFetcher<Boolean> NO_FILL = (props, val) -> {
        if (props.isSetNoFill()) {
            val.accept(true);
        } else if (props.isSetSolidFill() || props.isSetGradFill() || props.isSetPattFill() || props.isSetBlipFill()) {
            val.accept(false);
        }
    };

    static final CharPropFetcher<CTLineProperties> OUTLINE = (props, val) -> {
        if (props.isSetLn()) {
            val.accept(props.getLn());
        }
    };

    static final CharPropFetcher<CTOuterShadowEffect> SHADOW = (props, val) -> {
        if (props.isSetEffectLst()) {
            val.accept(props.getEffectLst().getOuterShdw());
        }
    };

    static final CharPropFetcher<Integer> BASELINE = (props, val) -> {
        if (props.isSetBaseline()) {
            val.accept(POIXMLUnits.parsePercent(props.xgetBaseline()));
        }
    };

    static final CharPropFetcher<Integer> KERN = (props, val) -> {
        if (props.isSetKern()) {
            val.accept(props.getKern());
        }
    };

    static CharProps resolve(List<CTTextCharacterProperties> levels, RuntimeException end) {
        return new CharProps(Inherited.resolve(levels, end, SIZE::fetch),
                Inherited.resolve(levels, end, BOLD::fetch),
                Inherited.resolve(levels, end, ITALIC::fetch),
                Inherited.resolve(levels, end, UNDERLINE::fetch),
                Inherited.resolve(levels, end, STRIKE::fetch),
                Inherited.resolve(levels, end, SPACING::fetch),
                Inherited.resolve(levels, end, CAP::fetch),
                Inherited.resolve(levels, end, HIGHLIGHT::fetch),
                Inherited.resolve(levels, end, NO_FILL::fetch),
                Inherited.resolve(levels, end, OUTLINE::fetch),
                Inherited.resolve(levels, end, SHADOW::fetch),
                Inherited.resolve(levels, end, BASELINE::fetch),
                Inherited.resolve(levels, end, KERN::fetch));
    }
}
