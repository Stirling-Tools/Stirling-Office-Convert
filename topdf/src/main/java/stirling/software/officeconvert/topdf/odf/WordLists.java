package stirling.software.officeconvert.topdf.odf;

import java.util.HashMap;
import java.util.Map;

import org.w3c.dom.Element;

final class WordLists {

    static final int MAX_LISTS = 20_000;

    static final class Chain {
        final int abstractId;
        final Element style;
        final Styles.Scope scope;
        int numId;

        Chain(int abstractId, Element style, Styles.Scope scope) {
            this.abstractId = abstractId;
            this.style = style;
            this.scope = scope;
        }
    }

    private final Styles styles;

    private final StringBuilder abstracts = new StringBuilder();

    private final StringBuilder nums = new StringBuilder();

    private final Map<Element, String> levelsCache = new HashMap<>();

    private int nextAbstract;

    private int nextNum = 1;

    WordLists(Styles styles) {
        this.styles = styles;
    }

    boolean full() {
        return nextAbstract >= MAX_LISTS;
    }

    Chain chain(Element listStyle, Styles.Scope scope) {
        Chain c = new Chain(nextAbstract++, listStyle, scope);
        abstracts.append("<w:abstractNum w:abstractNumId=\"").append(c.abstractId).append("\"><w:multiLevelType")
                .append(" w:val=\"hybridMultilevel\"/>").append(levels(listStyle, scope)).append("</w:abstractNum>");
        c.numId = num(c.abstractId, -1, 0);
        return c;
    }

    void restart(Chain c, int level, int start) {
        c.numId = num(c.abstractId, level, start);
    }

    private int num(int abstractId, int level, int start) {
        int id = nextNum++;
        nums.append("<w:num w:numId=\"").append(id).append("\"><w:abstractNumId w:val=\"").append(abstractId)
                .append("\"/>");
        if (level >= 0) {
            nums.append("<w:lvlOverride w:ilvl=\"").append(level).append("\"><w:startOverride w:val=\"")
                    .append(Math.max(0, start)).append("\"/></w:lvlOverride>");
        }
        nums.append("</w:num>");
        return id;
    }

    boolean isEmpty() {
        return nextAbstract == 0;
    }

    String xml() {
        return Xml.HEAD + "<w:numbering xmlns:w=\"" + Xml.W + "\">" + abstracts + nums + "</w:numbering>";
    }

    static Element level(Element listStyle, int level) {
        Element best = null;
        for (Element l : Dom.kids(listStyle)) {
            int n = Dom.integer(l, Ns.TEXT, "level", -1);
            if (n == level + 1) {
                return l;
            }
        }
        return best;
    }

    static double textIndent(Element listStyle, int level) {
        Element l = level(listStyle, level);
        if (l == null) {
            return Double.NaN;
        }
        Element props = Dom.kid(l, Ns.STYLE, "list-level-properties");
        Element align = Dom.kid(props, Ns.STYLE, "list-level-label-alignment");
        if (align != null) {
            return Length.pt(Dom.attr(align, Ns.FO, "margin-left"), 0);
        }
        return Length.pt(Dom.attr(props, Ns.TEXT, "space-before"), 0)
                + Length.pt(Dom.attr(props, Ns.TEXT, "min-label-width"), 0);
    }

    static int start(Element listStyle, int level) {
        Element l = level(listStyle, level);
        return l == null ? 1 : Dom.integer(l, Ns.TEXT, "start-value", 1);
    }

    private String levels(Element listStyle, Styles.Scope scope) {
        String cached = levelsCache.get(listStyle);
        if (cached != null) {
            return cached;
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            b.append(level(listStyle, i, scope));
        }
        levelsCache.put(listStyle, b.toString());
        return b.toString();
    }

    private String level(Element listStyle, int i, Styles.Scope scope) {
        Element l = level(listStyle, i);
        StringBuilder b = new StringBuilder("<w:lvl w:ilvl=\"").append(i).append("\">");
        if (l == null) {
            return b.append("<w:start w:val=\"1\"/><w:numFmt w:val=\"none\"/><w:lvlText w:val=\"\"/></w:lvl>")
                    .toString();
        }
        String kind = Dom.local(l);
        String fmt;
        String text;
        if (kind.equals("list-level-style-bullet") || kind.equals("list-level-style-image")) {
            fmt = "bullet";
            String ch = Dom.attr(l, Ns.TEXT, "bullet-char", "•");
            text = kind.endsWith("image") || ch.isEmpty() ? "•" : ch.substring(0, ch.offsetByCodePoints(0, 1));
        } else {
            fmt = numFmt(Dom.attr(l, Ns.STYLE, "num-format", ""));
            text = lvlText(l, i, fmt);
        }
        b.append("<w:start w:val=\"").append(Math.max(0, Dom.integer(l, Ns.TEXT, "start-value", 1))).append("\"/>");
        b.append("<w:numFmt w:val=\"").append(fmt).append("\"/>");
        b.append("<w:lvlText w:val=\"").append(Xml.esc(text)).append("\"/>");
        Element props = Dom.kid(l, Ns.STYLE, "list-level-properties");
        Element align = Dom.kid(props, Ns.STYLE, "list-level-label-alignment");
        String jc = switch (Dom.attr(props, Ns.FO, "text-align", "start")) {
            case "center" -> "center";
            case "end", "right" -> "right";
            default -> "left";
        };
        double left;
        double first;
        String suff = "tab";
        Double tab = null;
        if (align != null) {
            left = Length.pt(Dom.attr(align, Ns.FO, "margin-left"), 0);
            first = Length.pt(Dom.attr(align, Ns.FO, "text-indent"), 0);
            String follow = Dom.attr(align, Ns.TEXT, "label-followed-by", "listtab");
            suff = switch (follow) {
                case "space" -> "space";
                case "nothing" -> "nothing";
                default -> "tab";
            };
            String stop = Dom.attr(align, Ns.TEXT, "list-tab-stop-position");
            if (suff.equals("tab") && stop != null) {
                tab = Length.pt(stop, Double.NaN);
            }
        } else {
            double before = Length.pt(Dom.attr(props, Ns.TEXT, "space-before"), 0);
            double width = Length.pt(Dom.attr(props, Ns.TEXT, "min-label-width"), 0);
            left = before + width;
            first = -width;
        }
        if (!suff.equals("tab")) {
            b.append("<w:suff w:val=\"").append(suff).append("\"/>");
        }
        b.append("<w:lvlJc w:val=\"").append(jc).append("\"/><w:pPr>");
        if (tab != null && !tab.isNaN()) {
            b.append("<w:tabs><w:tab w:val=\"num\" w:pos=\"").append(Length.twips(tab)).append("\"/></w:tabs>");
        }
        b.append("<w:ind w:left=\"").append(Length.twips(left)).append('"');
        if (first < 0) {
            b.append(" w:hanging=\"").append(Length.twips(-first)).append('"');
        } else {
            b.append(" w:firstLine=\"").append(Length.twips(first)).append('"');
        }
        b.append("/></w:pPr>");
        Props rp = new Props(styles.props("text", Dom.attr(l, Ns.TEXT, "style-name"), scope, "text-properties",
                false));
        rp.merge(Dom.kid(l, Ns.STYLE, "text-properties"));
        if (fmt.equals("bullet")) {
            String font = WordRun.font(rp, styles, "");
            if (font != null && font.equalsIgnoreCase("OpenSymbol") && text.charAt(0) < 0xE000) {
                rp.remove("style:font-name");
                rp.remove("fo:font-family");
            }
        }
        rp.remove("fo:font-size");
        String rpr = WordRun.rPr(rp, styles);
        if (!rpr.isEmpty()) {
            b.append("<w:rPr>").append(rpr).append("</w:rPr>");
        }
        return b.append("</w:lvl>").toString();
    }

    static String numFmt(String f) {
        return switch (f.trim()) {
            case "" -> "none";
            case "a" -> "lowerLetter";
            case "A" -> "upperLetter";
            case "i" -> "lowerRoman";
            case "I" -> "upperRoman";
            case "①, ②, ③, ..." -> "decimalEnclosedCircle";
            case "一, 二, 三, ..." -> "chineseCounting";
            default -> "decimal";
        };
    }

    private static String lvlText(Element l, int i, String fmt) {
        String format = Dom.attr(l, Ns.LOEXT, "num-list-format");
        if (format != null) {
            return format.replaceAll("%10%", "").replaceAll("%([1-9])%", "%$1");
        }
        String prefix = Dom.attr(l, Ns.STYLE, "num-prefix", "");
        String suffix = Dom.attr(l, Ns.STYLE, "num-suffix", "");
        if (fmt.equals("none")) {
            return prefix + suffix;
        }
        int display = Math.max(1, Math.min(i + 1, Dom.integer(l, Ns.TEXT, "display-levels", 1)));
        StringBuilder b = new StringBuilder(prefix);
        for (int k = i - display + 1; k <= i; k++) {
            if (k > i - display + 1) {
                b.append('.');
            }
            b.append('%').append(k + 1);
        }
        return b.append(suffix).toString();
    }
}
