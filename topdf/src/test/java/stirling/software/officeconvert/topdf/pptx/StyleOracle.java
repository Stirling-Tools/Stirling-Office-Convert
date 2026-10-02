package stirling.software.officeconvert.topdf.pptx;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.apache.poi.common.usermodel.fonts.FontGroup;
import org.apache.poi.ooxml.util.POIXMLUnits;
import org.apache.poi.sl.usermodel.PaintStyle;
import org.apache.poi.sl.usermodel.TabStop.TabStopType;
import org.apache.poi.util.Units;
import org.apache.poi.xslf.model.CharacterPropertyFetcher;
import org.apache.poi.xslf.model.CharacterPropertyFetcher.CharPropFetcher;
import org.apache.poi.xslf.model.ParagraphPropertyFetcher;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSheet;
import org.apache.poi.xslf.usermodel.XSLFSlideLayout;
import org.apache.poi.xslf.usermodel.XSLFSlideMaster;
import org.apache.poi.xslf.usermodel.XSLFTabStop;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.openxmlformats.schemas.drawingml.x2006.main.CTRegularTextRun;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextCharacterProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextTabStop;

final class StyleOracle {

    record Report(int paragraphs, int runs, int checks, List<String> mismatches) {}

    private final ResolvedStyles resolved = new ResolvedStyles();

    private final List<String> mismatches = new ArrayList<>();

    private int paragraphs;

    private int runs;

    private int checks;

    static Report check(XMLSlideShow ppt) {
        StyleOracle o = new StyleOracle();
        for (XSLFSheet sheet : sheets(ppt)) {
            for (XSLFShape shape : sheet.getShapes()) {
                o.shape(shape);
            }
        }
        return new Report(o.paragraphs, o.runs, o.checks, List.copyOf(o.mismatches));
    }

    private static List<XSLFSheet> sheets(XMLSlideShow ppt) {
        List<XSLFSheet> out = new ArrayList<>(ppt.getSlides());
        for (XSLFSlideMaster m : ppt.getSlideMasters()) {
            out.add(m);
            for (XSLFSlideLayout l : m.getSlideLayouts()) {
                out.add(l);
            }
        }
        return out;
    }

    private void shape(XSLFShape shape) {
        if (shape instanceof XSLFGroupShape g) {
            for (XSLFShape s : g.getShapes()) {
                shape(s);
            }
        } else if (shape instanceof XSLFTable t) {
            for (XSLFTableRow row : t.getRows()) {
                for (XSLFTableCell cell : row.getCells()) {
                    text(cell);
                }
            }
        } else if (shape instanceof XSLFTextShape t) {
            text(t);
        }
    }

    private void text(XSLFTextShape shape) {
        for (XSLFTextParagraph p : shape.getTextParagraphs()) {
            paragraphs++;
            paragraph(p);
            for (XSLFTextRun r : p.getTextRuns()) {
                runs++;
                run(r);
            }
            probe(p);
        }
    }

    private void probe(XSLFTextParagraph p) {
        XSLFTextRun r = p.addNewTextRun();
        try {
            CTTextCharacterProperties end = p.getXmlObject().getEndParaRPr();
            if (end != null && r.getXmlObject() instanceof CTRegularTextRun run) {
                run.setRPr((CTTextCharacterProperties) end.copy());
            }
            run(r);
        } finally {
            p.removeTextRun(r);
        }
    }

    private void paragraph(XSLFTextParagraph p) {
        String at = where(p) + " paragraph";
        ResolvedStyles.Level level;
        try {
            level = resolved.of(p);
        } catch (RuntimeException e) {
            mismatches.add(at + " model failed: " + e);
            return;
        }
        ParaStyle s = level.paragraph();
        if (p.getParentShape() instanceof XSLFTableCell) {
            same(at, "masterStyle", p::getDefaultMasterStyle, () -> level.chain().masterStyle().get());
        }
        same(at, "align", p::getTextAlign, () -> s.textAlign(p));
        same(at, "marL", p::getLeftMargin, () -> s.leftMargin(p));
        same(at, "marR", p::getRightMargin, () -> s.rightMargin(p));
        same(at, "indent", p::getIndent, () -> s.indent(p));
        same(at, "lnSpc", p::getLineSpacing, () -> s.lineSpacing(p));
        same(at, "spcBef", p::getSpaceBefore, () -> s.spaceBefore(p));
        same(at, "spcAft", p::getSpaceAfter, () -> s.spaceAfter(p));
        same(at, "defTab", p::getDefaultTabSize, () -> s.defaultTabSize(p));
        same(at, "tabs", () -> tabs(p.getTabStops()), () -> ctTabs(s.tabStops(p)));
        same(at, "rtl", () -> paraFetch(p, (props, val) -> {
            if (props.isSetRtl()) {
                val.accept(props.getRtl());
            }
        }), () -> s.rtl(p));
        same(at, "bullet", p::isBullet, () -> s.isBullet(p));
        same(at, "autoNum", p::getAutoNumberingScheme, () -> s.autoNumberingScheme(p));
        same(at, "startAt", p::getAutoNumberingStartAt, () -> s.autoNumberingStartAt(p));
        same(at, "buChar", p::getBulletCharacter, () -> s.bulletCharacter(p));
        same(at, "buFont", p::getBulletFont, () -> s.bulletFont(p));
        same(at, "buSize", p::getBulletFontSize, () -> s.bulletFontSize(p));
        same(at, "buColor", () -> paint(p.getBulletFontColor()), () -> paint(s.bulletFontColor(p)));
        same(at, "buBlip", () -> paraFetch(p, (props, val) -> {
            if (props.isSetBuBlip()) {
                val.accept(props.getBuBlip());
            } else if (props.isSetBuNone() || props.isSetBuChar() || props.isSetBuAutoNum()) {
                val.accept(ParaProps.OTHER_BULLET);
            }
        }), () -> s.bulletPicture(p));
    }

    private void run(XSLFTextRun r) {
        String at = where(r.getParagraph()) + " run '" + r.getRawText() + "'";
        RunStyle s = resolved.of(r.getParagraph()).run();
        boolean plain = RunStyle.plain(r);
        same(at, "size", r::getFontSize, () -> s.fontSize(r));
        if (plain) {
            same(at, "bold", r::isBold, () -> s.bold(r));
            same(at, "italic", r::isItalic, () -> s.italic(r));
            same(at, "color", () -> paint(r.getFontColor()), () -> paint(s.fontColor(r)));
            same(at, "solid", () -> Paints.solid(r.getFontColor()), () -> s.solidColor(r));
        }
        same(at, "underline", r::isUnderlined, () -> s.underlined(r));
        same(at, "strike", r::isStrikethrough, () -> s.strikethrough(r));
        same(at, "spacing", r::getCharacterSpacing, () -> s.characterSpacing(r));
        same(at, "cap", r::getTextCap, () -> s.textCap(r));
        same(at, "highlight", () -> paint(r.getHighlightColor()), () -> paint(s.highlightColor(r)));
        for (FontGroup g : FontGroup.values()) {
            same(at, "font " + g, () -> r.getFontFamily(g), () -> s.fontFamily(r, g));
        }
        same(at, "noFill", () -> runFetch(r, (props, val) -> {
            if (props.isSetNoFill()) {
                val.accept(true);
            } else if (props.isSetSolidFill() || props.isSetGradFill() || props.isSetPattFill()
                    || props.isSetBlipFill()) {
                val.accept(false);
            }
        }), () -> s.noFill(r));
        same(at, "ln", () -> runFetch(r, (props, val) -> {
            if (props.isSetLn()) {
                val.accept(props.getLn());
            }
        }), () -> s.outline(r));
        same(at, "shadow", () -> runFetch(r, (props, val) -> {
            if (props.isSetEffectLst()) {
                val.accept(props.getEffectLst().getOuterShdw());
            }
        }), () -> s.shadow(r));
        same(at, "baseline", () -> runFetch(r, (props, val) -> {
            if (props.isSetBaseline()) {
                val.accept(POIXMLUnits.parsePercent(props.xgetBaseline()));
            }
        }), () -> s.baseline(r));
        same(at, "kern", () -> runFetch(r, (props, val) -> {
            if (props.isSetKern()) {
                val.accept(props.getKern());
            }
        }), () -> s.kern(r));
    }

    private static <T> T runFetch(XSLFTextRun r, CharPropFetcher<T> f) {
        return new CharacterPropertyFetcher<>(r, f).fetchProperty(r.getParagraph().getParentShape());
    }

    private static <T> T paraFetch(XSLFTextParagraph p, ParagraphPropertyFetcher.ParaPropFetcher<T> f) {
        return new ParagraphPropertyFetcher<>(p, f).fetchProperty(p.getParentShape());
    }

    private static List<String> tabs(List<XSLFTabStop> stops) {
        if (stops == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (XSLFTabStop t : stops) {
            out.add(attempt(t::getPositionInPoints) + "/" + attempt(t::getType));
        }
        return out;
    }

    private static List<String> ctTabs(List<CTTextTabStop> stops) {
        if (stops == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (CTTextTabStop t : stops) {
            out.add(attempt(() -> Units.toPoints(POIXMLUnits.parseLength(t.xgetPos()))) + "/"
                    + attempt(() -> TabStopType.fromOoxmlId(t.getAlgn().intValue())));
        }
        return out;
    }

    private static String paint(PaintStyle ps) {
        if (ps == null) {
            return null;
        }
        Color c = Paints.solid(ps);
        return ps.getClass().getName() + " " + (c == null ? null : Integer.toHexString(c.getRGB()));
    }

    private static Object attempt(Supplier<?> s) {
        try {
            return s.get();
        } catch (RuntimeException e) {
            return "throws " + e.getClass().getName() + ": " + e.getMessage();
        }
    }

    private void same(String at, String what, Supplier<?> poi, Supplier<?> model) {
        checks++;
        Object a = attempt(poi);
        Object b = attempt(model);
        if (!Objects.equals(a, b)) {
            mismatches.add(at + " " + what + ": poi=" + a + " model=" + b);
        }
    }

    private static String where(XSLFTextParagraph p) {
        XSLFTextShape s = p.getParentShape();
        String sheet = s.getSheet().getPackagePart().getPartName().getName();
        return sheet + " " + (s instanceof XSLFTableCell ? "cell" : s.getShapeName()) + " lvl" + p.getIndentLevel();
    }
}
