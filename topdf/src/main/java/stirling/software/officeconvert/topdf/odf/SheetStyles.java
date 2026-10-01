package stirling.software.officeconvert.topdf.odf;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** ODF cell styles written as SpreadsheetML cell formats: fonts, fills, borders, number formats and alignment, each
 * shared by every style that resolves to the same thing. */
final class SheetStyles {

    static final int MAX_FORMATS = 60_000;

    record Xf(int index, boolean visible, boolean date) {}

    private final Styles styles;

    private final NumberFormats numbers;

    private final Map<String, Integer> fonts = new LinkedHashMap<>();

    private final Map<String, Integer> fills = new LinkedHashMap<>();

    private final Map<String, Integer> borders = new LinkedHashMap<>();

    private final Map<String, Integer> numFmts = new LinkedHashMap<>();

    private final Map<String, Integer> xfs = new LinkedHashMap<>();

    private final Map<String, Xf> byStyle = new HashMap<>();

    final Props defaultText;

    SheetStyles(Styles styles) {
        this.styles = styles;
        this.numbers = new NumberFormats(styles);
        defaultText = text(null);
        fonts.put(font(defaultText), 0);
        fills.put("<fill><patternFill patternType=\"none\"/></fill>", 0);
        fills.put("<fill><patternFill patternType=\"gray125\"/></fill>", 1);
        borders.put("<border><left/><right/><top/><bottom/><diagonal/></border>", 0);
        xfs.put("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>", 0);
    }

    private Props text(String style) {
        return styles.props("table-cell", style == null ? "Default" : style, Styles.Scope.CONTENT, "text-properties",
                true);
    }

    String defaultFont() {
        String f = WordRun.font(defaultText, styles, "");
        return f == null ? "Liberation Sans" : f;
    }

    double defaultSize() {
        double s = defaultText.pt("fo:font-size", 10);
        return s > 0 ? s : 10;
    }

    Xf xf(String style) {
        String key = style == null ? "" : style;
        Xf cached = byStyle.get(key);
        if (cached != null) {
            return cached;
        }
        if (xfs.size() >= MAX_FORMATS) {
            return new Xf(0, false, false);
        }
        String name = style == null ? "Default" : style;
        Props cell = styles.props("table-cell", name, Styles.Scope.CONTENT, "table-cell-properties", true);
        Props para = styles.props("table-cell", name, Styles.Scope.CONTENT, "paragraph-properties", true);
        Props text = text(name);
        String dataStyle = styles.inherited("table-cell", name, Styles.Scope.CONTENT, Ns.STYLE, "data-style-name");
        String code = numbers.code(dataStyle);
        int numFmt = 0;
        if (code != null && !code.equalsIgnoreCase("General")) {
            numFmt = numFmts.computeIfAbsent(code, c -> 164 + numFmts.size());
        }
        int font = index(fonts, font(text));
        String fillXml = fill(cell);
        int fill = fillXml == null ? 0 : index(fills, fillXml);
        String borderXml = border(cell);
        int border = borderXml == null ? 0 : index(borders, borderXml);
        String align = alignment(cell, para);
        StringBuilder x = new StringBuilder("<xf numFmtId=\"").append(numFmt).append("\" fontId=\"").append(font)
                .append("\" fillId=\"").append(fill).append("\" borderId=\"").append(border).append("\" xfId=\"0\"");
        if (numFmt != 0) {
            x.append(" applyNumberFormat=\"1\"");
        }
        if (align != null) {
            x.append(" applyAlignment=\"1\">").append(align).append("</xf>");
        } else {
            x.append("/>");
        }
        int index = index(xfs, x.toString());
        Xf xf = new Xf(index, fill != 0 || border != 0, NumberFormats.isDate(code));
        byStyle.put(key, xf);
        return xf;
    }

    private static int index(Map<String, Integer> map, String xml) {
        return map.computeIfAbsent(xml, k -> map.size());
    }

    String font(Props t) {
        StringBuilder b = new StringBuilder("<font>");
        if (WordRun.bold(t.get("fo:font-weight"))) {
            b.append("<b/>");
        }
        if (WordRun.italic(t.get("fo:font-style"))) {
            b.append("<i/>");
        }
        String strike = t.get("style:text-line-through-style");
        if (strike != null && !strike.equals("none")) {
            b.append("<strike/>");
        }
        String u = t.get("style:text-underline-style");
        if (u != null && !u.equals("none")) {
            b.append("double".equals(t.get("style:text-underline-type")) ? "<u val=\"double\"/>" : "<u/>");
        }
        String pos = WordRun.position(t.get("style:text-position"), 10);
        if (pos != null && pos.contains("superscript")) {
            b.append("<vertAlign val=\"superscript\"/>");
        } else if (pos != null && pos.contains("subscript")) {
            b.append("<vertAlign val=\"subscript\"/>");
        }
        double size = t.pt("fo:font-size", 10);
        b.append("<sz val=\"").append(trim(size > 0 ? size : 10)).append("\"/>");
        String color = Colors.fill(t.get("fo:color"));
        if (color != null && !"true".equals(t.get("style:use-window-font-color"))) {
            b.append("<color rgb=\"FF").append(color).append("\"/>");
        }
        String name = WordRun.font(t, styles, "");
        b.append("<name val=\"").append(Xml.esc(name == null ? "Liberation Sans" : name)).append("\"/>");
        return b.append("</font>").toString();
    }

    private static String trim(double v) {
        double r = Math.round(v * 100) / 100.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    private static String fill(Props cell) {
        String bg = Colors.fill(cell.get("fo:background-color"));
        if (bg == null) {
            return null;
        }
        return "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF" + bg + "\"/><bgColor indexed=\"64\"/>"
                + "</patternFill></fill>";
    }

    private static String border(Props cell) {
        StringBuilder b = new StringBuilder();
        boolean any = false;
        for (String side : new String[] {"left", "right", "top", "bottom"}) {
            String x = edge(side, Border.parse(cell.get("fo:border-" + side),
                    cell.get("style:border-line-width-" + side)));
            any |= !x.equals("<" + side + "/>");
            b.append(x);
        }
        Border up = Border.parse(cell.get("style:diagonal-bl-tr"), cell.get("style:diagonal-bl-tr-widths"));
        Border down = Border.parse(cell.get("style:diagonal-tl-br"), cell.get("style:diagonal-tl-br-widths"));
        Border diag = up != null ? up : down;
        b.append(edge("diagonal", diag));
        any |= diag != null;
        if (!any) {
            return null;
        }
        String attrs = (up != null ? " diagonalUp=\"1\"" : "") + (down != null ? " diagonalDown=\"1\"" : "");
        return "<border" + attrs + ">" + b + "</border>";
    }

    private static String edge(String side, Border border) {
        if (border == null) {
            return "<" + side + "/>";
        }
        double w = border.width();
        String style = switch (border.style()) {
            case "double", "double-thin" -> "double";
            case "dotted" -> w > 1.2 ? "mediumDashDotDot" : w < 0.6 ? "hair" : "dotted";
            case "dashed", "fine-dashed" -> w > 1.2 ? "mediumDashed" : "dashed";
            case "dot-dash", "dash-dot" -> w > 1.2 ? "mediumDashDot" : "dashDot";
            case "dot-dot-dash", "dash-dot-dot" -> w > 1.2 ? "mediumDashDotDot" : "dashDotDot";
            default -> w >= 2.25 ? "thick" : w > 1.1 ? "medium" : w < 0.3 ? "hair" : "thin";
        };
        return "<" + side + " style=\"" + style + "\"><color rgb=\"FF" + border.color() + "\"/></" + side + ">";
    }

    private static String alignment(Props cell, Props para) {
        StringBuilder b = new StringBuilder();
        String source = cell.get("style:text-align-source", "fix");
        String h = para.get("fo:text-align");
        if (!"value-type".equals(source) && h != null) {
            String v = switch (h) {
                case "center" -> "center";
                case "end", "right" -> "right";
                case "justify" -> "justify";
                case "start", "left" -> "left";
                default -> null;
            };
            if (v != null) {
                b.append(" horizontal=\"").append(v).append('"');
            }
        }
        if ("true".equals(cell.get("style:repeat-content"))) {
            b.append(" horizontal=\"fill\"");
        }
        String va = cell.get("style:vertical-align");
        if ("top".equals(va)) {
            b.append(" vertical=\"top\"");
        } else if ("middle".equals(va)) {
            b.append(" vertical=\"center\"");
        } else if ("justify".equals(cell.get("style:vertical-justify"))) {
            b.append(" vertical=\"justify\"");
        }
        if ("wrap".equals(cell.get("fo:wrap-option"))) {
            b.append(" wrapText=\"1\"");
        }
        if ("true".equals(cell.get("style:shrink-to-fit"))) {
            b.append(" shrinkToFit=\"1\"");
        }
        double indent = para.pt("fo:margin-left", 0);
        if (indent > 0.5 && b.indexOf("horizontal=\"center\"") < 0) {
            long level = Math.max(1, Math.min(250, Math.round(indent / 7.5)));
            b.append(" indent=\"").append(level).append('"');
            if (b.indexOf("horizontal=") < 0) {
                b.append(" horizontal=\"left\"");
            }
        }
        if ("ttb".equals(cell.get("style:direction"))) {
            b.append(" textRotation=\"255\"");
        } else {
            double angle = Length.pt(cell.get("style:rotation-angle"), 0);
            long a = Math.round(((angle % 360) + 360) % 360);
            if (a > 0 && a <= 90) {
                b.append(" textRotation=\"").append(a).append('"');
            } else if (a >= 270 && a < 360) {
                b.append(" textRotation=\"").append(90 + (360 - a)).append('"');
            }
        }
        return b.isEmpty() ? null : "<alignment" + b + "/>";
    }

    String xml() {
        StringBuilder b = new StringBuilder(Xml.HEAD).append("<styleSheet xmlns=\"").append(Xml.S).append("\">");
        if (!numFmts.isEmpty()) {
            b.append("<numFmts count=\"").append(numFmts.size()).append("\">");
            for (Map.Entry<String, Integer> e : numFmts.entrySet()) {
                b.append("<numFmt numFmtId=\"").append(e.getValue()).append("\" formatCode=\"")
                        .append(Xml.esc(e.getKey())).append("\"/>");
            }
            b.append("</numFmts>");
        }
        list(b, "fonts", fonts);
        list(b, "fills", fills);
        list(b, "borders", borders);
        b.append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>");
        list(b, "cellXfs", xfs);
        b.append("<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>");
        return b.append("</styleSheet>").toString();
    }

    private static void list(StringBuilder b, String tag, Map<String, Integer> items) {
        b.append('<').append(tag).append(" count=\"").append(items.size()).append("\">");
        for (String k : items.keySet()) {
            b.append(k);
        }
        b.append("</").append(tag).append('>');
    }
}
