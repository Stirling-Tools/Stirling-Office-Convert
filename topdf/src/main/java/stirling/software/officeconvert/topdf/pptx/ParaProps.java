package stirling.software.officeconvert.topdf.pptx;

import java.util.List;
import java.util.function.Consumer;

import org.apache.poi.ooxml.util.POIXMLUnits;
import org.apache.poi.sl.usermodel.AutoNumberingScheme;
import org.apache.poi.sl.usermodel.TextParagraph.TextAlign;
import org.apache.poi.util.Units;
import org.apache.poi.xslf.model.ParagraphPropertyFetcher.ParaPropFetcher;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextParagraphProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextSpacing;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextTabStop;

record ParaProps(Inherited<TextAlign> align,
        Inherited<Double> marginLeft,
        Inherited<Double> marginRight,
        Inherited<Double> indent,
        Inherited<Double> defaultTab,
        Inherited<Double> line,
        Inherited<Double> before,
        Inherited<Double> after,
        Inherited<List<CTTextTabStop>> tabs,
        Inherited<Boolean> rtl,
        Inherited<Boolean> bullet,
        Inherited<AutoNumberingScheme> numbering,
        Inherited<Integer> startAt,
        Inherited<String> bulletChar,
        Inherited<String> bulletFont,
        Inherited<Double> bulletSize,
        Inherited<Object> bulletPicture) {

    static final Object OTHER_BULLET = new Object();

    static final ParaPropFetcher<TextAlign> ALIGN = (props, val) -> {
        if (props.isSetAlgn()) {
            val.accept(TextAlign.values()[props.getAlgn().intValue() - 1]);
        }
    };

    static final ParaPropFetcher<Double> MARGIN_LEFT = (props, val) -> {
        if (props.isSetMarL()) {
            val.accept(Units.toPoints(props.getMarL()));
        }
    };

    static final ParaPropFetcher<Double> MARGIN_RIGHT = (props, val) -> {
        if (props.isSetMarR()) {
            val.accept(Units.toPoints(props.getMarR()));
        }
    };

    static final ParaPropFetcher<Double> INDENT = (props, val) -> {
        if (props.isSetIndent()) {
            val.accept(Units.toPoints(props.getIndent()));
        }
    };

    static final ParaPropFetcher<Double> DEFAULT_TAB = (props, val) -> {
        if (props.isSetDefTabSz()) {
            val.accept(Units.toPoints(POIXMLUnits.parseLength(props.xgetDefTabSz())));
        }
    };

    static final ParaPropFetcher<Double> LINE = (props, val) -> spacing(props.getLnSpc(), val);

    static final ParaPropFetcher<Double> BEFORE = (props, val) -> spacing(props.getSpcBef(), val);

    static final ParaPropFetcher<Double> AFTER = (props, val) -> spacing(props.getSpcAft(), val);

    static final ParaPropFetcher<List<CTTextTabStop>> TABS = (props, val) -> {
        if (props.isSetTabLst()) {
            val.accept(List.of(props.getTabLst().getTabArray()));
        }
    };

    static final ParaPropFetcher<Boolean> RTL = (props, val) -> {
        if (props.isSetRtl()) {
            val.accept(props.getRtl());
        }
    };

    static final ParaPropFetcher<Boolean> BULLET = (props, val) -> {
        if (props.isSetBuNone()) {
            val.accept(false);
        } else if (props.isSetBuFont() || props.isSetBuChar()) {
            val.accept(true);
        }
    };

    static final ParaPropFetcher<AutoNumberingScheme> NUMBERING = (props, val) -> {
        if (props.isSetBuAutoNum()) {
            AutoNumberingScheme ans = AutoNumberingScheme.forOoxmlID(props.getBuAutoNum().getType().intValue());
            if (ans != null) {
                val.accept(ans);
            }
        }
    };

    static final ParaPropFetcher<Integer> START_AT = (props, val) -> {
        if (props.isSetBuAutoNum() && props.getBuAutoNum().isSetStartAt()) {
            val.accept(props.getBuAutoNum().getStartAt());
        }
    };

    static final ParaPropFetcher<String> BULLET_CHAR = (props, val) -> {
        if (props.isSetBuChar()) {
            val.accept(props.getBuChar().getChar());
        }
    };

    static final ParaPropFetcher<String> BULLET_FONT = (props, val) -> {
        if (props.isSetBuFont()) {
            val.accept(props.getBuFont().getTypeface());
        }
    };

    static final ParaPropFetcher<Double> BULLET_SIZE = (props, val) -> {
        if (props.isSetBuSzPct()) {
            val.accept(POIXMLUnits.parsePercent(props.getBuSzPct().xgetVal()) * 0.001);
        }
        if (props.isSetBuSzPts()) {
            val.accept(-props.getBuSzPts().getVal() * 0.01);
        }
    };

    static final ParaPropFetcher<Object> BULLET_PICTURE = (props, val) -> {
        if (props.isSetBuBlip()) {
            val.accept(props.getBuBlip());
        } else if (props.isSetBuNone() || props.isSetBuChar() || props.isSetBuAutoNum()) {
            val.accept(OTHER_BULLET);
        }
    };

    static ParaProps resolve(List<CTTextParagraphProperties> levels, RuntimeException end) {
        return new ParaProps(Inherited.resolve(levels, end, ALIGN::fetch),
                Inherited.resolve(levels, end, MARGIN_LEFT::fetch),
                Inherited.resolve(levels, end, MARGIN_RIGHT::fetch),
                Inherited.resolve(levels, end, INDENT::fetch),
                Inherited.resolve(levels, end, DEFAULT_TAB::fetch),
                Inherited.resolve(levels, end, LINE::fetch),
                Inherited.resolve(levels, end, BEFORE::fetch),
                Inherited.resolve(levels, end, AFTER::fetch),
                Inherited.resolve(levels, end, TABS::fetch),
                Inherited.resolve(levels, end, RTL::fetch),
                Inherited.resolve(levels, end, BULLET::fetch),
                Inherited.resolve(levels, end, NUMBERING::fetch),
                Inherited.resolve(levels, end, START_AT::fetch),
                Inherited.resolve(levels, end, BULLET_CHAR::fetch),
                Inherited.resolve(levels, end, BULLET_FONT::fetch),
                Inherited.resolve(levels, end, BULLET_SIZE::fetch),
                Inherited.resolve(levels, end, BULLET_PICTURE::fetch));
    }

    private static void spacing(CTTextSpacing spc, Consumer<Double> val) {
        if (spc != null) {
            if (spc.isSetSpcPct()) {
                val.accept(POIXMLUnits.parsePercent(spc.getSpcPct().xgetVal()) * 0.001);
            } else if (spc.isSetSpcPts()) {
                val.accept(-spc.getSpcPts().getVal() * 0.01);
            }
        }
    }
}
