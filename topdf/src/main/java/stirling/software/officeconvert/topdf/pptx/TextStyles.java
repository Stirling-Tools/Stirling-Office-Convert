package stirling.software.officeconvert.topdf.pptx;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.poi.ooxml.util.POIXMLUnits;
import org.apache.poi.sl.usermodel.PaintStyle;
import org.apache.poi.sl.usermodel.TabStop.TabStopType;
import org.apache.poi.sl.usermodel.TextParagraph.TextAlign;
import org.apache.poi.sl.usermodel.TextRun.FieldType;
import org.apache.poi.sl.usermodel.TextRun.TextCap;
import org.apache.poi.common.usermodel.fonts.FontGroup;
import org.apache.poi.xslf.model.CharacterPropertyFetcher;
import org.apache.poi.xslf.model.ParagraphPropertyFetcher;
import org.apache.poi.xslf.usermodel.XSLFColor;
import org.apache.poi.xslf.usermodel.XSLFSheet;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTabStop;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xslf.usermodel.XSLFTheme;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.drawingml.x2006.main.CTFontCollection;
import org.openxmlformats.schemas.drawingml.x2006.main.CTFontScheme;
import org.openxmlformats.schemas.drawingml.x2006.main.CTHyperlink;
import org.openxmlformats.schemas.drawingml.x2006.main.CTLineProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTOuterShadowEffect;
import org.openxmlformats.schemas.drawingml.x2006.main.CTRegularTextRun;
import org.openxmlformats.schemas.drawingml.x2006.main.CTSolidColorFillProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTableCell;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextCharacterProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextField;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextFont;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextBody;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextBodyProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextLineBreak;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextListStyle;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextNormalAutofit;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextParagraphProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.STSchemeColorVal;

import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.font.FontRun;
import stirling.software.officeconvert.topdf.io.ActiveContent;
import stirling.software.officeconvert.topdf.io.Relationship;
import stirling.software.officeconvert.topdf.pdf.Stroke;
import stirling.software.officeconvert.topdf.pdf.TextStyle;

final class TextStyles {

    static final float DEFAULT_SIZE = 18;

    static final String DEFAULT_FAMILY = "Calibri";

    record Defaults(Color color, Boolean bold, Boolean italic, String latin, Shadows.Shadow shadow) {

        static final Defaults NONE = new Defaults(null, null, null, null, null);

        Defaults(Color color, Boolean bold, Boolean italic, String latin) {
            this(color, bold, italic, latin, null);
        }

        Defaults withShadow(Shadows.Shadow s) {
            return new Defaults(color, bold, italic, latin, s);
        }
    }

    record Scope(String relsPart, int slideNumber, Defaults defaults) {}

    private enum Script {
        LATIN,
        EAST_ASIAN,
        COMPLEX,
        SYMBOL
    }

    private final Deck deck;

    TextStyles(Deck deck) {
        this.deck = deck;
    }

    Para paragraph(XSLFTextParagraph p, Scope scope, Numbering numbering) throws IOException {
        List<Piece> pieces = new ArrayList<>();
        for (XSLFTextRun r : p.getTextRuns()) {
            XmlObject x = r.getXmlObject();
            String text;
            if (x instanceof CTTextLineBreak) {
                text = "\n";
            } else if (x instanceof CTTextField f) {
                text = field(r, f, scope);
            } else {
                text = r.getRawText();
            }
            if (text == null || text.isEmpty()) {
                continue;
            }
            pieces.addAll(pieces(r, text.replace('\u000B', '\n').replace('\r', '\n'), scope));
        }
        Piece empty = probe(p, scope);
        TextAlign align = p.getTextAlign();
        float marL = points(p.getLeftMargin(), 0);
        float marR = points(p.getRightMargin(), 0);
        float indent = points(p.getIndent(), 0);
        Double spacing = p.getLineSpacing();
        Para.Spacing line = Para.Spacing.of(spacing, Para.Spacing.SINGLE);
        float reduction = autofit(p.getParentShape(), false);
        if (reduction > 0 && (spacing == null || spacing > 0)) {
            double percent = spacing == null ? 1 : spacing / 100 / (1 - reduction);
            line = new Para.Spacing((float) Math.max(0.1, percent - reduction), -1);
        }
        Para.Spacing before = Para.Spacing.of(p.getSpaceBefore(), Para.Spacing.NONE);
        Para.Spacing after = Para.Spacing.of(p.getSpaceAfter(), Para.Spacing.NONE);
        float defTab = points(p.getDefaultTabSize(), 72);
        List<Para.Tab> tabs = tabs(p);
        int level = p.getIndentLevel();
        boolean rtl = rtl(p);
        Para para = new Para(pieces, empty, align == null ? TextAlign.LEFT : align, marL, marR, indent, line, before,
                after, defTab > 0 ? defTab : 72, tabs, null, level, rtl);
        Para.Bullet bullet = Bullets.picture(deck, p, para, numbering);
        if (bullet == null) {
            bullet = Bullets.of(deck.fonts(), p, para, numbering);
        }
        return bullet == null ? para
                : new Para(pieces, empty, para.align(), marL, marR, indent, line, before, after, para.defTab(), tabs,
                        bullet, level, rtl);
    }

    // The stored shrink: sizes round to whole points; the reduction takes points off any percentage spacing
    static float autofit(XSLFTextShape shape, boolean fontScale) {
        try {
            CTTextBodyProperties body = shape == null ? null : TextFrame.bodyPr(shape);
            CTTextNormalAutofit fit = body == null || !body.isSetNormAutofit() ? null : body.getNormAutofit();
            if (fit == null || !(fontScale ? fit.isSetFontScale() : fit.isSetLnSpcReduction())) {
                return 0;
            }
            int v = fontScale ? POIXMLUnits.parsePercent(fit.xgetFontScale())
                    : POIXMLUnits.parsePercent(fit.xgetLnSpcReduction());
            float f = v / 100_000f;
            return fontScale ? Math.max(0, Math.min(0.99f, 1 - f)) : Math.max(0, Math.min(0.9f, f));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static boolean rtl(XSLFTextParagraph p) {
        try {
            Boolean v = new ParagraphPropertyFetcher<Boolean>(p, (props, val) -> {
                if (props.isSetRtl()) {
                    val.accept(props.getRtl());
                }
            }).fetchProperty(p.getParentShape());
            return v != null && v;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static float points(Double d, float fallback) {
        return d == null || !Double.isFinite(d) ? fallback : d.floatValue();
    }

    private static List<Para.Tab> tabs(XSLFTextParagraph p) {
        List<XSLFTabStop> stops;
        try {
            stops = p.getTabStops();
        } catch (RuntimeException e) {
            stops = null;
        }
        if (stops == null || stops.isEmpty()) {
            return List.of();
        }
        List<Para.Tab> out = new ArrayList<>();
        for (XSLFTabStop t : stops) {
            double pos = t.getPositionInPoints();
            if (Double.isFinite(pos) && pos >= 0) {
                TabStopType type;
                try {
                    type = t.getType();
                } catch (RuntimeException e) {
                    type = null;
                }
                out.add(new Para.Tab((float) pos, type == null ? TabStopType.LEFT : type));
            }
        }
        out.sort((a, b) -> Float.compare(a.position(), b.position()));
        return out;
    }

    private String field(XSLFTextRun r, CTTextField f, Scope scope) {
        String type = f.getType() == null ? "" : f.getType().toLowerCase(Locale.ROOT);
        if (r.getFieldType() == FieldType.SLIDE_NUMBER || type.startsWith("slidenum")) {
            return Integer.toString(scope.slideNumber());
        }
        return f.getT();
    }

    private Piece probe(XSLFTextParagraph p, Scope scope) {
        XSLFTextRun r = null;
        try {
            r = p.addNewTextRun();
            CTTextCharacterProperties end = p.getXmlObject().getEndParaRPr();
            if (end != null && r.getXmlObject() instanceof CTRegularTextRun run) {
                run.setRPr((CTTextCharacterProperties) end.copy());
            }
            List<Piece> list = pieces(r, " ", scope);
            return list.isEmpty() ? null : list.get(0).withText("");
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (r != null) {
                try {
                    p.removeTextRun(r);
                } catch (RuntimeException e) {
                    deck.job().warn("An empty paragraph could not be measured: " + e.getMessage());
                }
            }
        }
    }

    List<Piece> pieces(XSLFTextRun r, String raw, Scope scope) throws IOException {
        Double sz = r.getParagraph().getParentShape() instanceof XSLFTableCell ? cellSize(r) : r.getFontSize();
        float fontScale = 1 - autofit(r.getParagraph().getParentShape(), true);
        float size = sz == null || !(sz > 0) ? DEFAULT_SIZE * fontScale : (float) Math.min(4000, Math.max(0.5, sz));
        if (fontScale != 1) {
            size = Math.max(1, Math.round(size));
        }
        Defaults d = scope.defaults() == null ? Defaults.NONE : scope.defaults();
        CTTextCharacterProperties own = r.getRPr(false);
        boolean bold = d.bold() != null && !explicit(own, "b") ? d.bold() : r.isBold();
        boolean italic = d.italic() != null && !explicit(own, "i") ? d.italic() : r.isItalic();
        boolean underline = r.isUnderlined();
        boolean strike = r.isStrikethrough();
        Color color = null;
        if (d.color() != null && !hasFill(own, r)) {
            color = d.color();
        } else {
            PaintStyle ps = r.getFontColor();
            color = Paints.solid(ps);
        }
        if (color == null) {
            color = Color.BLACK;
        }
        if (noFill(r)) {
            color = new Color(0, 0, 0, 0);
        }
        Stroke outline = outline(r);
        String link = null;
        CTHyperlink h = own == null ? null : own.getHlinkClick();
        if (h != null) {
            link = link(h, scope);
            if (!hyperlinkUsesTextColor(h)) {
                Color hc = themeColor(r.getParagraph().getParentShape().getSheet(), STSchemeColorVal.HLINK);
                if (hc != null) {
                    color = hc;
                }
            }
            underline = true;
        }
        float spc = (float) r.getCharacterSpacing();
        TextCap cap = r.getTextCap();
        float rise = rise(r);
        Color highlight = Paints.solid(r.getHighlightColor());
        int kern = kern(r);
        boolean cell = r.getParagraph().getParentShape() instanceof XSLFTableCell;
        String latin = cell ? cellFamily(r, FontGroup.LATIN, d.latin()) : family(r, FontGroup.LATIN);
        if (latin == null) {
            latin = d.latin() != null ? d.latin() : themeFont(r);
        }
        String text = raw;
        if (cap == TextCap.ALL) {
            text = text.toUpperCase(Locale.ROOT);
        }
        float drawSize = rise != 0 ? size * 2 / 3f : size;
        Shadows.Shadow shadow = textShadow(r);
        if (shadow == null) {
            shadow = d.shadow();
        }
        List<Piece> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int start = i;
            Script s = script(text.codePointAt(i));
            while (i < text.length()) {
                int cp = text.codePointAt(i);
                Script t = script(cp);
                if (t != s && !(cp == ' ' || cp == '\n' || cp == '\t')) {
                    break;
                }
                i += Character.charCount(cp);
            }
            String chunk = text.substring(start, i);
            String family = switch (s) {
                case EAST_ASIAN -> or(cell ? cellFamily(r, FontGroup.EAST_ASIAN, null)
                        : family(r, FontGroup.EAST_ASIAN), latin);
                case COMPLEX -> or(cell ? cellFamily(r, FontGroup.COMPLEX_SCRIPT, null)
                        : family(r, FontGroup.COMPLEX_SCRIPT), latin);
                case SYMBOL -> or(cell ? cellFamily(r, FontGroup.SYMBOL, null) : family(r, FontGroup.SYMBOL), latin);
                default -> latin;
            };
            Standins.Emulation emulation = deck.standins().emulate(family, bold, italic);
            FontFace face = emulation.face();
            float scale = emulation.scale();
            float[] win = emulation.vertical();
            if (emulation.note() != null) {
                deck.job().warn(emulation.note());
            }
            FontLibrary lib = deck.fonts();
            for (FontRun fr : lib.runs(chunk, face)) {
                boolean primary = fr.face().equals(face);
                float stretch = primary ? scale : 100;
                float[] vertical = primary ? win : null;
                Advances metrics = primary ? emulation.metrics() : null;
                if (cap == TextCap.SMALL) {
                    smallCaps(out, fr, drawSize, color, spc, kern, stretch, vertical, metrics, underline, strike,
                            rise, highlight, link, shadow);
                } else {
                    TextStyle ts = style(fr.face(), drawSize, color, spc, kern, stretch);
                    out.add(new Piece(fr.text(), ts, drawSize, underline, strike, rise, highlight, link, vertical,
                            metrics, shadow));
                }
            }
        }
        if (outline != null) {
            out.replaceAll(p -> p.withOutline(outline));
        }
        return out;
    }

    // Text drawn without a fill shows only its outline, if any
    private static boolean noFill(XSLFTextRun r) {
        try {
            Boolean none = new CharacterPropertyFetcher<Boolean>(r, (props, val) -> {
                if (props.isSetNoFill()) {
                    val.accept(true);
                } else if (props.isSetSolidFill() || props.isSetGradFill() || props.isSetPattFill()
                        || props.isSetBlipFill()) {
                    val.accept(false);
                }
            }).fetchProperty(r.getParagraph().getParentShape());
            return none != null && none;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // The glyph outline of WordArt and outlined text; a line without a fill draws nothing
    static Stroke outline(XSLFTextRun r) {
        try {
            CTLineProperties ln = new CharacterPropertyFetcher<CTLineProperties>(r, (props, val) -> {
                if (props.isSetLn()) {
                    val.accept(props.getLn());
                }
            }).fetchProperty(r.getParagraph().getParentShape());
            if (ln == null || ln.isSetNoFill()) {
                return null;
            }
            XSLFSheet sheet = r.getParagraph().getParentShape().getSheet();
            Color c = null;
            if (ln.isSetSolidFill()) {
                c = TableStyles.color(ln.getSolidFill(), sheet);
            } else if (ln.isSetGradFill() && ln.getGradFill().getGsLst() != null
                    && ln.getGradFill().getGsLst().sizeOfGsArray() > 0) {
                c = TableStyles.color(ln.getGradFill().getGsLst().getGsArray(0), sheet);
            }
            float w = ln.isSetW() ? ln.getW() / 12_700f : Strokes.DEFAULT_WIDTH;
            return c == null || c.getAlpha() == 0 || !(w > 0) ? null : Stroke.solid(Math.min(w, 100), c);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // Table text skips the presentation's default text style: the master's other style, else 18 pt
    static Double cellSize(XSLFTextRun r) {
        try {
            List<CTTextCharacterProperties> chain = cellChain(r);
            CTTextParagraphProperties master = r.getParagraph().getDefaultMasterStyle();
            chain.add(master == null ? null : master.getDefRPr());
            for (CTTextCharacterProperties c : chain) {
                if (c != null && c.isSetSz()) {
                    return c.getSz() / 100.0;
                }
            }
        } catch (RuntimeException e) {
            return (double) DEFAULT_SIZE;
        }
        return (double) DEFAULT_SIZE;
    }

    private static List<CTTextCharacterProperties> cellChain(XSLFTextRun r) {
        List<CTTextCharacterProperties> chain = new ArrayList<>();
        chain.add(r.getRPr(false));
        XSLFTextParagraph p = r.getParagraph();
        CTTextParagraphProperties own = p.getXmlObject().getPPr();
        chain.add(own == null ? null : own.getDefRPr());
        CTTextBody body = p.getParentShape().getXmlObject() instanceof CTTableCell cell ? cell.getTxBody() : null;
        CTTextListStyle list = body == null ? null : body.getLstStyle();
        CTTextParagraphProperties level = list == null ? null : level(list, p.getIndentLevel());
        chain.add(level == null ? null : level.getDefRPr());
        return chain;
    }

    // Table text takes its font from the cell, then the table style, then the master, never the presentation default
    static String cellFamily(XSLFTextRun r, FontGroup g, String tableFont) {
        try {
            for (CTTextCharacterProperties c : cellChain(r)) {
                String f = typeface(c, g, r);
                if (f != null) {
                    return f;
                }
            }
            if (tableFont != null && !tableFont.isBlank()) {
                return tableFont;
            }
            CTTextParagraphProperties master = r.getParagraph().getDefaultMasterStyle();
            return master == null ? null : typeface(master.getDefRPr(), g, r);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String typeface(CTTextCharacterProperties c, FontGroup g, XSLFTextRun r) {
        if (c == null) {
            return null;
        }
        CTTextFont font = switch (g) {
            case EAST_ASIAN -> c.getEa();
            case COMPLEX_SCRIPT -> c.getCs();
            case SYMBOL -> c.getSym();
            default -> c.getLatin();
        };
        String t = font == null ? null : font.getTypeface();
        if (t == null || t.isBlank()) {
            return null;
        }
        if (t.startsWith("+mj-") || t.startsWith("+mn-")) {
            CTFontScheme scheme = r.getParagraph().getParentShape().getSheet().getTheme().getXmlObject()
                    .getThemeElements().getFontScheme();
            CTFontCollection coll = t.startsWith("+mj-") ? scheme.getMajorFont() : scheme.getMinorFont();
            String part = t.substring(4);
            CTTextFont f = "ea".equals(part) ? coll.getEa() : "cs".equals(part) ? coll.getCs() : coll.getLatin();
            t = f == null ? null : f.getTypeface();
        }
        return t == null || t.isBlank() ? null : t;
    }

    private static CTTextParagraphProperties level(CTTextListStyle list, int level) {
        return switch (Math.max(0, Math.min(8, level))) {
            case 0 -> list.getLvl1PPr();
            case 1 -> list.getLvl2PPr();
            case 2 -> list.getLvl3PPr();
            case 3 -> list.getLvl4PPr();
            case 4 -> list.getLvl5PPr();
            case 5 -> list.getLvl6PPr();
            case 6 -> list.getLvl7PPr();
            case 7 -> list.getLvl8PPr();
            default -> list.getLvl9PPr();
        };
    }

    // A text shadow set on the run or inherited from its paragraph, list or master styles
    private static Shadows.Shadow textShadow(XSLFTextRun r) {
        try {
            CTOuterShadowEffect none = CTOuterShadowEffect.Factory.newInstance();
            CTOuterShadowEffect ct = new CharacterPropertyFetcher<CTOuterShadowEffect>(r, (props, val) -> {
                if (props.isSetEffectLst()) {
                    CTOuterShadowEffect o = props.getEffectLst().getOuterShdw();
                    val.accept(o == null ? none : o);
                }
            }).fetchProperty(r.getParagraph().getParentShape());
            return ct == null || ct == none ? null : Shadows.of(ct, r.getParagraph().getParentShape().getSheet());
        } catch (RuntimeException e) {
            return null;
        }
    }

    // A run no style gives a font uses the theme's minor font, not a fixed family
    private static String themeFont(XSLFTextRun r) {
        try {
            XSLFTheme theme = r.getParagraph().getParentShape().getSheet().getTheme();
            String t = theme == null ? null : theme.getMinorFont();
            return t == null || t.isBlank() ? DEFAULT_FAMILY : t;
        } catch (RuntimeException e) {
            return DEFAULT_FAMILY;
        }
    }

    private static String or(String a, String b) {
        return a == null || a.isBlank() ? b : a;
    }

    private void smallCaps(List<Piece> out, FontRun fr, float size, Color color, float spc, int kern, float scale,
            float[] win, Advances metrics, boolean underline, boolean strike, float rise, Color highlight,
            String link, Shadows.Shadow shadow) {
        String t = fr.text();
        int i = 0;
        while (i < t.length()) {
            int start = i;
            boolean lower = Character.isLowerCase(t.codePointAt(i));
            while (i < t.length() && Character.isLowerCase(t.codePointAt(i)) == lower) {
                i += Character.charCount(t.codePointAt(i));
            }
            String part = t.substring(start, i);
            float sz = lower ? size * 0.8f : size;
            TextStyle ts = style(fr.face(), sz, color, spc, kern, scale);
            out.add(new Piece(lower ? part.toUpperCase(Locale.ROOT) : part, ts, sz, underline, strike, rise, highlight,
                    link, win, metrics, shadow));
        }
    }

    private static TextStyle style(FontFace face, float size, Color color, float spc, int kern, float scale) {
        boolean kerning = kern > 0 && size * 100 >= kern;
        return new TextStyle(face, size, color, Float.isFinite(spc) ? spc : 0, scale, 0, kerning);
    }

    private static String family(XSLFTextRun r, FontGroup g) {
        try {
            String f = r.getFontFamily(g);
            return f == null || f.isBlank() ? null : f;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean explicit(CTTextCharacterProperties rPr, String attribute) {
        if (rPr == null) {
            return false;
        }
        return switch (attribute) {
            case "b" -> rPr.isSetB();
            case "i" -> rPr.isSetI();
            default -> false;
        };
    }

    private static boolean hasFill(CTTextCharacterProperties rPr, XSLFTextRun r) {
        if (rPr != null && (rPr.isSetSolidFill() || rPr.isSetGradFill() || rPr.isSetNoFill() || rPr.isSetPattFill())) {
            return true;
        }
        var pPr = r.getParagraph().getXmlObject().getPPr();
        if (pPr != null && pPr.isSetDefRPr()) {
            CTTextCharacterProperties def = pPr.getDefRPr();
            if (def.isSetSolidFill() || def.isSetGradFill() || def.isSetNoFill()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hyperlinkUsesTextColor(CTHyperlink h) {
        String xml = h.xmlText();
        return xml.contains("hlinkClr") && xml.contains("val=\"tx\"");
    }

    private String link(CTHyperlink h, Scope scope) throws IOException {
        String id = h.getId();
        if (id == null || id.isEmpty()) {
            return null;
        }
        Relationship rel = deck.pictures().relationship(scope.relsPart(), id);
        return rel == null ? null : ActiveContent.hyperlink(rel);
    }

    static Color themeColor(XSLFSheet sheet, STSchemeColorVal.Enum value) {
        try {
            XSLFTheme theme = sheet.getTheme();
            CTSolidColorFillProperties f = CTSolidColorFillProperties.Factory.newInstance();
            f.addNewSchemeClr().setVal(value);
            return Paints.color(new XSLFColor(f, theme, null, sheet).getColorStyle());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static float rise(XSLFTextRun r) {
        try {
            Integer v = new CharacterPropertyFetcher<Integer>(r, (props, val) -> {
                if (props.isSetBaseline()) {
                    val.accept(POIXMLUnits.parsePercent(props.xgetBaseline()));
                }
            }).fetchProperty(r.getParagraph().getParentShape());
            return v == null ? 0 : Math.max(-1, Math.min(1, v / 100_000f));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static int kern(XSLFTextRun r) {
        try {
            Integer v = new CharacterPropertyFetcher<Integer>(r, (props, val) -> {
                if (props.isSetKern()) {
                    val.accept(props.getKern());
                }
            }).fetchProperty(r.getParagraph().getParentShape());
            return v == null ? 0 : v;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static Script script(int cp) {
        if (cp >= 0xF000 && cp <= 0xF0FF) {
            return Script.SYMBOL;
        }
        if (cp >= 0x0590 && cp <= 0x07BF || cp >= 0x0900 && cp <= 0x109F || cp >= 0x1780 && cp <= 0x18AF
                || cp >= 0xFB1D && cp <= 0xFDFF || cp >= 0xFE70 && cp <= 0xFEFF) {
            return Script.COMPLEX;
        }
        if (cp >= 0x1100 && cp <= 0x11FF || cp >= 0x2E80 && cp <= 0x9FFF || cp >= 0xA960 && cp <= 0xA97F
                || cp >= 0xAC00 && cp <= 0xD7FF || cp >= 0xF900 && cp <= 0xFAFF || cp >= 0xFE30 && cp <= 0xFE4F
                || cp >= 0xFF00 && cp <= 0xFFEF || cp >= 0x20000 && cp <= 0x3FFFF) {
            return Script.EAST_ASIAN;
        }
        return Script.LATIN;
    }
}
