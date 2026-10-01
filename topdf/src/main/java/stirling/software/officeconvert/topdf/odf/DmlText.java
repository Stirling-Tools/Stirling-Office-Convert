package stirling.software.officeconvert.topdf.odf;

import java.util.Locale;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** ODF paragraphs and lists inside a shape written as DrawingML paragraphs (a:p), with every property resolved. */
final class DmlText {

    /** The paragraph and character properties a shape gives its text at each outline level, and its list style. */
    interface Levels {
        Props paragraph(int level);

        Props text(int level);

        Element listStyle();
    }

    /** Text the converter fills in for fields: slide numbers, footers and dates. */
    interface Fields {
        String field(Element field);
    }

    static final int MAX_PARAGRAPHS = 20_000;

    private final Styles styles;

    private final Styles.Scope scope;

    private final Levels levels;

    private final Fields fields;

    private final StringBuilder out = new StringBuilder();

    private int paragraphs;

    DmlText(Styles styles, Styles.Scope scope, Levels levels, Fields fields) {
        this.styles = styles;
        this.scope = scope;
        this.levels = levels;
        this.fields = fields;
    }

    /** The a:p elements of the container's text; one empty paragraph when it has none. */
    String paragraphs(Element container) {
        blocks(container, -1, null, 0);
        if (out.isEmpty()) {
            Props t = levels.text(0);
            return "<a:p><a:endParaRPr" + runAttrs(t) + ">" + runChildren(t) + "</a:endParaRPr></a:p>";
        }
        return out.toString();
    }

    boolean hasText(Element container) {
        return container != null && !container.getTextContent().isBlank();
    }

    private void blocks(Element parent, int level, Element listStyle, int depth) {
        if (parent == null || depth > 16) {
            return;
        }
        for (Element k : Dom.kids(parent)) {
            if (!Ns.TEXT.equals(k.getNamespaceURI())) {
                continue;
            }
            switch (Dom.local(k)) {
                case "p", "h" -> paragraph(k, Math.max(0, level), level >= 0 ? listStyle : null, level >= 0, false);
                case "list" -> list(k, level + 1, listStyle, depth + 1);
                case "section" -> blocks(k, level, listStyle, depth + 1);
                default -> {
                }
            }
        }
    }

    private void list(Element list, int level, Element inherited, int depth) {
        Element style = inherited;
        if (style == null) {
            style = styles.listStyle(Dom.attr(list, Ns.TEXT, "style-name"), scope);
            if (style == null) {
                style = levels.listStyle();
            }
        }
        int lvl = Math.min(8, level);
        for (Element item : Dom.kids(list)) {
            boolean header = Dom.is(item, Ns.TEXT, "list-header");
            if (!header && !Dom.is(item, Ns.TEXT, "list-item")) {
                continue;
            }
            boolean first = true;
            for (Element g : Dom.kids(item)) {
                if (Dom.is(g, Ns.TEXT, "list")) {
                    list(g, level + 1, style, depth + 1);
                    first = false;
                } else if (Dom.is(g, Ns.TEXT, "p") || Dom.is(g, Ns.TEXT, "h")) {
                    String start = first ? Dom.attr(item, Ns.TEXT, "start-value") : null;
                    paragraph(g, lvl, style, !header && first, header || !first);
                    if (start != null) {
                        int at = out.lastIndexOf("<a:buAutoNum ");
                        if (at >= 0) {
                            int end = out.indexOf("/>", at);
                            out.insert(end, " startAt=\"" + Math.max(1, Dom.integer(item, Ns.TEXT, "start-value", 1))
                                    + "\"");
                        }
                    }
                    first = false;
                }
            }
        }
    }

    private void paragraph(Element p, int level, Element listStyle, boolean label, boolean noLabel) {
        if (++paragraphs > MAX_PARAGRAPHS) {
            return;
        }
        String name = Dom.attr(p, Ns.TEXT, "style-name");
        Props pp = new Props(levels.paragraph(level));
        pp.merge(styles.props("paragraph", name, scope, "paragraph-properties", false));
        Props tp = new Props(levels.text(level));
        tp.merge(styles.props("paragraph", name, scope, "text-properties", false));
        Element lvl = listStyle == null ? null : WordLists.level(listStyle, level);
        StringBuilder ppr = new StringBuilder("<a:pPr");
        double margin = pp.pt("fo:margin-left", 0);
        double indent = pp.pt("fo:text-indent", 0);
        if (lvl != null) {
            Element lp = Dom.kid(lvl, Ns.STYLE, "list-level-properties");
            Element align = Dom.kid(lp, Ns.STYLE, "list-level-label-alignment");
            if (align != null && "label-alignment".equals(Dom.attr(lp, Ns.TEXT, "list-level-position-and-space-mode"))) {
                if (!pp.has("fo:margin-left") || pp.pt("fo:margin-left", 0) == 0) {
                    margin = Length.pt(Dom.attr(align, Ns.FO, "margin-left"), margin);
                    indent = Length.pt(Dom.attr(align, Ns.FO, "text-indent"), indent);
                }
            } else {
                double before = Length.pt(Dom.attr(lp, Ns.TEXT, "space-before"), 0);
                double width = Length.pt(Dom.attr(lp, Ns.TEXT, "min-label-width"), 0);
                margin += before + width;
                indent += -width;
            }
        }
        ppr.append(" marL=\"").append(Length.emu(Math.max(0, margin))).append("\" indent=\"")
                .append(Length.emu(indent)).append('"');
        if (level > 0) {
            ppr.append(" lvl=\"").append(level).append('"');
        }
        String algn = switch (pp.get("fo:text-align", "start")) {
            case "center" -> "ctr";
            case "end", "right" -> "r";
            case "justify" -> "just";
            default -> "l";
        };
        ppr.append(" algn=\"").append(algn).append('"');
        if (WordPara.rtl(pp)) {
            ppr.append(" rtl=\"1\"");
        }
        ppr.append('>');
        String lh = pp.get("fo:line-height");
        if (lh != null && !lh.equals("normal")) {
            if (Length.isPercent(lh)) {
                ppr.append("<a:lnSpc><a:spcPct val=\"").append(Math.round(Length.percent(lh, 100) * 1000))
                        .append("\"/></a:lnSpc>");
            } else if (Length.pt(lh, 0) > 0) {
                ppr.append("<a:lnSpc><a:spcPts val=\"").append(Math.round(Length.pt(lh, 0) * 100)).append("\"/></a:lnSpc>");
            }
        } else if (pp.has("style:line-height-at-least")) {
            ppr.append("<a:lnSpc><a:spcPts val=\"").append(Math.round(pp.pt("style:line-height-at-least", 0) * 100))
                    .append("\"/></a:lnSpc>");
        }
        if (pp.has("fo:margin-top")) {
            ppr.append("<a:spcBef><a:spcPts val=\"").append(Math.max(0, Math.round(pp.pt("fo:margin-top", 0) * 100)))
                    .append("\"/></a:spcBef>");
        }
        if (pp.has("fo:margin-bottom")) {
            ppr.append("<a:spcAft><a:spcPts val=\"").append(Math.max(0, Math.round(pp.pt("fo:margin-bottom", 0) * 100)))
                    .append("\"/></a:spcAft>");
        }
        ppr.append(bullet(lvl, label && !noLabel, tp));
        ppr.append(tabs(pp, margin));
        ppr.append("</a:pPr>");
        StringBuilder runs = new StringBuilder();
        inline(p, tp, runs, new boolean[] {true}, 0);
        out.append("<a:p>").append(ppr).append(runs).append("<a:endParaRPr").append(runAttrs(tp)).append('>')
                .append(runChildren(tp)).append("</a:endParaRPr></a:p>");
    }

    private String bullet(Element lvl, boolean label, Props text) {
        if (!label || lvl == null) {
            return "<a:buNone/>";
        }
        String kind = Dom.local(lvl);
        Props bp = new Props();
        bp.merge(Dom.kid(lvl, Ns.STYLE, "text-properties"));
        StringBuilder b = new StringBuilder();
        String color = "true".equals(bp.get("style:use-window-font-color")) ? null : Colors.fill(bp.get("fo:color"));
        if (color != null) {
            b.append("<a:buClr><a:srgbClr val=\"").append(color).append("\"/></a:buClr>");
        }
        String size = bp.get("fo:font-size");
        if (Length.isPercent(size)) {
            b.append("<a:buSzPct val=\"").append(Math.round(Math.max(25, Math.min(400, Length.percent(size, 100))) * 1000))
                    .append("\"/>");
        }
        if (kind.equals("list-level-style-number")) {
            String fmt = Dom.attr(lvl, Ns.STYLE, "num-format", "1");
            if (fmt.isEmpty()) {
                return "<a:buNone/>";
            }
            String suffix = Dom.attr(lvl, Ns.STYLE, "num-suffix", "");
            String prefix = Dom.attr(lvl, Ns.STYLE, "num-prefix", "");
            String base = switch (fmt) {
                case "a" -> "alphaLc";
                case "A" -> "alphaUc";
                case "i" -> "romanLc";
                case "I" -> "romanUc";
                default -> "arabic";
            };
            String tail = prefix.equals("(") && suffix.equals(")") ? "ParenBoth"
                    : suffix.equals(")") ? "ParenR" : suffix.equals(".") ? "Period" : base.equals("arabic") ? "Plain"
                    : "Period";
            b.append("<a:buFontTx/><a:buAutoNum type=\"").append(base).append(tail).append("\"/>");
            return b.toString();
        }
        String ch = kind.equals("list-level-style-image") ? "•" : Dom.attr(lvl, Ns.TEXT, "bullet-char", "•");
        if (ch.isEmpty()) {
            return "<a:buNone/>";
        }
        String font = WordRun.font(bp, styles, "");
        if (font == null || font.equalsIgnoreCase("OpenSymbol") || font.equalsIgnoreCase("StarSymbol")) {
            font = ch.charAt(0) >= 0xF000 ? "Symbol" : "Arial";
        }
        b.append("<a:buFont typeface=\"").append(Xml.esc(font)).append("\"/><a:buChar char=\"")
                .append(Xml.esc(ch.substring(0, ch.offsetByCodePoints(0, 1)))).append("\"/>");
        return b.toString();
    }

    private static String tabs(Props pp, double margin) {
        Element stops = pp.kid("tab-stops");
        if (stops == null || Dom.kids(stops).isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder("<a:tabLst>");
        int n = 0;
        for (Element t : Dom.kids(stops, Ns.STYLE, "tab-stop")) {
            if (n++ > 32) {
                break;
            }
            double pos = Length.pt(Dom.attr(t, Ns.STYLE, "position"), Double.NaN);
            if (Double.isNaN(pos)) {
                continue;
            }
            String algn = switch (Dom.attr(t, Ns.STYLE, "type", "left")) {
                case "center" -> "ctr";
                case "right" -> "r";
                case "char" -> "dec";
                default -> "l";
            };
            b.append("<a:tab pos=\"").append(Length.emu(pos + margin)).append("\" algn=\"").append(algn).append("\"/>");
        }
        return b.append("</a:tabLst>").toString();
    }

    private void inline(Element e, Props run, StringBuilder out, boolean[] lineStart, int depth) {
        if (depth > 32) {
            return;
        }
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                String t = collapse(n.getNodeValue(), lineStart);
                if (!t.isEmpty()) {
                    out.append(run(run, t));
                }
            } else if (n instanceof Element k) {
                String ns = k.getNamespaceURI();
                if (Ns.PRESENTATION.equals(ns)) {
                    String f = fields == null ? null : fields.field(k);
                    if (f != null && !f.isEmpty()) {
                        out.append(run(run, f));
                        lineStart[0] = false;
                    }
                    continue;
                }
                if (!Ns.TEXT.equals(ns)) {
                    continue;
                }
                switch (Dom.local(k)) {
                    case "span" -> {
                        Props p = new Props(run);
                        p.merge(styles.props("text", Dom.attr(k, Ns.TEXT, "style-name"), scope, "text-properties",
                                false));
                        inline(k, p, out, lineStart, depth + 1);
                    }
                    case "a" -> inline(k, run, out, lineStart, depth + 1);
                    case "s" -> {
                        out.append(run(run, " ".repeat(Math.max(1, Math.min(1000, Dom.integer(k, Ns.TEXT, "c", 1))))));
                        lineStart[0] = false;
                    }
                    case "tab" -> {
                        out.append(run(run, "\t"));
                        lineStart[0] = false;
                    }
                    case "line-break" -> {
                        out.append("<a:br><a:rPr").append(runAttrs(run)).append('>').append(runChildren(run))
                                .append("</a:rPr></a:br>");
                        lineStart[0] = true;
                    }
                    case "page-number" -> {
                        String f = fields == null ? null : fields.field(k);
                        out.append("<a:fld id=\"{B6F15528-21DE-4FAA-801E-634DDDAF4B2B}\" type=\"slidenum\"><a:rPr")
                                .append(runAttrs(run)).append('>').append(runChildren(run)).append("</a:rPr><a:t>")
                                .append(Xml.esc(f == null ? k.getTextContent() : f)).append("</a:t></a:fld>");
                        lineStart[0] = false;
                    }
                    case "note", "bookmark", "bookmark-start", "bookmark-end", "soft-page-break", "number" -> {
                    }
                    default -> inline(k, run, out, lineStart, depth + 1);
                }
            }
        }
    }

    private static String collapse(String s, boolean[] lineStart) {
        StringBuilder t = new StringBuilder();
        boolean space = lineStart[0];
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                if (!space) {
                    t.append(' ');
                    space = true;
                }
            } else {
                t.append(c);
                space = false;
            }
        }
        if (!t.isEmpty()) {
            lineStart[0] = space;
        }
        return t.toString();
    }

    private String run(Props p, String text) {
        String t = text;
        if ("uppercase".equals(p.get("fo:text-transform"))) {
            t = t.toUpperCase(Locale.ROOT);
        } else if ("lowercase".equals(p.get("fo:text-transform"))) {
            t = t.toLowerCase(Locale.ROOT);
        }
        return "<a:r><a:rPr" + runAttrs(p) + ">" + runChildren(p) + "</a:rPr><a:t>" + Xml.esc(t) + "</a:t></a:r>";
    }

    String runAttrs(Props p) {
        StringBuilder b = new StringBuilder();
        String lang = p.get("fo:language");
        if (lang != null && !lang.equals("none")) {
            String country = p.get("fo:country");
            b.append(" lang=\"").append(Xml.esc(country == null || country.equals("none") ? lang : lang + "-" + country))
                    .append('"');
        }
        double size = p.pt("fo:font-size", Double.NaN);
        if (size > 0) {
            b.append(" sz=\"").append(Math.max(100, Math.min(400_000, Math.round(size * 100)))).append('"');
        }
        if (p.has("fo:font-weight")) {
            b.append(" b=\"").append(WordRun.bold(p.get("fo:font-weight")) ? 1 : 0).append('"');
        }
        if (p.has("fo:font-style")) {
            b.append(" i=\"").append(WordRun.italic(p.get("fo:font-style")) ? 1 : 0).append('"');
        }
        String u = p.get("style:text-underline-style");
        if (u != null) {
            String type = p.get("style:text-underline-type", "single");
            String val = u.equals("none") || type.equals("none") ? "none"
                    : type.equals("double") ? "dbl" : "bold".equals(p.get("style:text-underline-width")) ? "heavy"
                    : switch (u) {
                        case "dotted" -> "dotted";
                        case "dash" -> "dash";
                        case "long-dash" -> "dashLong";
                        case "dot-dash" -> "dotDash";
                        case "dot-dot-dash" -> "dotDotDash";
                        case "wave" -> "wavy";
                        default -> "sng";
                    };
            b.append(" u=\"").append(val).append('"');
        }
        String strike = p.get("style:text-line-through-style");
        if (strike != null) {
            b.append(" strike=\"").append(strike.equals("none") ? "noStrike"
                    : "double".equals(p.get("style:text-line-through-type")) ? "dblStrike" : "sngStrike").append('"');
        }
        String pos = p.get("style:text-position");
        if (pos != null) {
            String[] t = pos.trim().split("\\s+");
            double offset = t[0].equals("super") ? 33 : t[0].equals("sub") ? -33 : Length.percent(t[0], 0);
            if (offset != 0) {
                b.append(" baseline=\"").append(Math.round(offset * 1000)).append('"');
            }
        }
        String spacing = p.get("fo:letter-spacing");
        if (spacing != null && !spacing.equals("normal")) {
            b.append(" spc=\"").append(Math.round(Length.pt(spacing, 0) * 100)).append('"');
        }
        if ("small-caps".equals(p.get("fo:font-variant"))) {
            b.append(" cap=\"small\"");
        }
        return b.toString();
    }

    String runChildren(Props p) {
        StringBuilder b = new StringBuilder();
        String color = Colors.fill(p.get("fo:color"));
        if (color != null) {
            double opacity = Length.percent(p.get("loext:opacity"), 100);
            b.append("<a:solidFill>").append(Dml.color(color, opacity <= 0 ? 100 : opacity)).append("</a:solidFill>");
        }
        String bg = Colors.fill(p.get("fo:background-color"));
        if (bg != null) {
            b.append("<a:highlight><a:srgbClr val=\"").append(bg).append("\"/></a:highlight>");
        }
        String latin = WordRun.font(p, styles, "");
        String asian = WordRun.font(p, styles, "-asian");
        String complex = WordRun.font(p, styles, "-complex");
        if (latin != null) {
            b.append("<a:latin typeface=\"").append(Xml.esc(latin)).append("\"/>");
        }
        if (asian != null) {
            b.append("<a:ea typeface=\"").append(Xml.esc(asian)).append("\"/>");
        }
        if (complex != null) {
            b.append("<a:cs typeface=\"").append(Xml.esc(complex)).append("\"/>");
        }
        return b.toString();
    }
}
