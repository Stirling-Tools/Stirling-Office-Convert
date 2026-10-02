package stirling.software.officeconvert.topdf.sml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import stirling.software.officeconvert.topdf.xls.Xml;

/** The Styles of a SpreadsheetML 2003 workbook, each resolved through its parents, as styles.xml. */
final class Styles {

    private static final int MAX_STYLES = 4000;

    private static final String[] PARTS = {"Alignment", "Font", "Interior", "NumberFormat", "Protection"};

    private final Map<String, Node> nodes = new LinkedHashMap<>();

    private final Map<String, Integer> xfIndex = new HashMap<>();

    private final List<String> xfs = new ArrayList<>();

    private final Map<String, Integer> fonts = new LinkedHashMap<>();

    private final Map<String, Integer> fills = new LinkedHashMap<>();

    private final Map<String, Integer> borders = new LinkedHashMap<>();

    private final Map<String, Integer> formats = new LinkedHashMap<>();

    String defaultFont = "Arial";

    double defaultSize = 10;

    Styles() {
        fills.put("<fill><patternFill patternType=\"none\"/></fill>", 0);
        fills.put("<fill><patternFill patternType=\"gray125\"/></fill>", 1);
    }

    void add(Node style) {
        String id = style.attr("ID");
        if (id != null && nodes.size() < MAX_STYLES) {
            nodes.putIfAbsent(id, style);
        }
    }

    void resolve() {
        Map<String, Map<String, String>> base = resolved("Default", 0);
        Map<String, String> font = base.getOrDefault("Font", Map.of());
        defaultFont = font.getOrDefault("FontName", "Arial");
        defaultSize = parse(font.get("Size"), 10);
        xf("Default");
        for (String id : nodes.keySet()) {
            xf(id);
        }
    }

    int index(String id) {
        if (id == null) {
            return 0;
        }
        Integer i = xfIndex.get(id);
        return i == null ? 0 : i;
    }

    private Map<String, Map<String, String>> resolved(String id, int depth) {
        Map<String, Map<String, String>> out = new HashMap<>();
        Node n = id == null ? null : nodes.get(id);
        if (n == null || depth > 32) {
            return out;
        }
        String parent = n.attr("Parent");
        if (parent != null) {
            out.putAll(copy(resolved(parent, depth + 1)));
        } else if (!"Default".equals(id)) {
            out.putAll(copy(resolved("Default", depth + 1)));
        }
        for (String p : PARTS) {
            Node k = n.kid(p);
            if (k != null) {
                out.computeIfAbsent(p, x -> new HashMap<>()).putAll(k.attrs());
            }
        }
        Node b = n.kid("Borders");
        if (b != null) {
            for (Node border : b.all("Border")) {
                String pos = border.attr("Position");
                if (pos != null) {
                    out.put("Border:" + pos, new HashMap<>(border.attrs()));
                }
            }
        }
        return out;
    }

    private static Map<String, Map<String, String>> copy(Map<String, Map<String, String>> m) {
        Map<String, Map<String, String>> out = new HashMap<>();
        m.forEach((k, v) -> out.put(k, new HashMap<>(v)));
        return out;
    }

    private void xf(String id) {
        if (xfIndex.containsKey(id)) {
            return;
        }
        Map<String, Map<String, String>> s = resolved(id, 0);
        int font = add(fonts, font(s.getOrDefault("Font", Map.of())));
        int fill = add(fills, fill(s.getOrDefault("Interior", Map.of())));
        int border = add(borders, border(s));
        String code = Formats.code(s.getOrDefault("NumberFormat", Map.of()).get("Format"));
        int format = code == null ? 0 : formats.computeIfAbsent(code, c -> 164 + formats.size());
        StringBuilder x = new StringBuilder("<xf numFmtId=\"").append(format).append("\" fontId=\"").append(font)
                .append("\" fillId=\"").append(fill).append("\" borderId=\"").append(border)
                .append("\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\""
                        + " applyAlignment=\"1\">")
                .append(alignment(s.getOrDefault("Alignment", Map.of())));
        Map<String, String> prot = s.getOrDefault("Protection", Map.of());
        if ("0".equals(prot.get("Protected"))) {
            x.append("<protection locked=\"0\"/>");
        }
        xfIndex.put(id, xfs.size());
        xfs.add(x.append("</xf>").toString());
    }

    private static int add(Map<String, Integer> map, String item) {
        Integer known = map.get(item);
        if (known != null) {
            return known;
        }
        map.put(item, map.size());
        return map.size() - 1;
    }

    String fontRun(Map<String, String> f) {
        return font(f).replace("<font>", "<rPr>").replace("</font>", "</rPr>").replace("<name ", "<rFont ");
    }

    private String font(Map<String, String> f) {
        StringBuilder b = new StringBuilder("<font>");
        if ("1".equals(f.get("Bold"))) {
            b.append("<b/>");
        }
        if ("1".equals(f.get("Italic"))) {
            b.append("<i/>");
        }
        if ("1".equals(f.get("StrikeThrough"))) {
            b.append("<strike/>");
        }
        String u = f.get("Underline");
        if (u != null && !u.equalsIgnoreCase("None")) {
            String val = switch (u.toLowerCase(Locale.ROOT)) {
                case "double" -> "double";
                case "singleaccounting" -> "singleAccounting";
                case "doubleaccounting" -> "doubleAccounting";
                default -> "single";
            };
            b.append("<u val=\"").append(val).append("\"/>");
        }
        String v = f.get("VerticalAlign");
        if ("Superscript".equalsIgnoreCase(v) || "Subscript".equalsIgnoreCase(v)) {
            b.append("<vertAlign val=\"").append(v.toLowerCase(Locale.ROOT)).append("\"/>");
        }
        b.append("<sz val=\"").append(parse(f.get("Size"), 10)).append("\"/>");
        String color = rgb(f.get("Color"));
        if (color != null) {
            b.append("<color rgb=\"").append(color).append("\"/>");
        }
        b.append("<name val=\"").append(Xml.attr(f.getOrDefault("FontName", "Arial"))).append("\"/>");
        return b.append("</font>").toString();
    }

    private static String fill(Map<String, String> i) {
        String color = rgb(i.get("Color"));
        String pattern = i.get("Pattern");
        if (pattern == null || pattern.equalsIgnoreCase("None")) {
            return "<fill><patternFill patternType=\"none\"/></fill>";
        }
        if (pattern.equalsIgnoreCase("Solid")) {
            return color == null ? "<fill><patternFill patternType=\"none\"/></fill>" : solid(color);
        }
        String type = switch (pattern) {
            case "Gray75" -> "darkGray";
            case "Gray50" -> "mediumGray";
            case "Gray25" -> "lightGray";
            case "Gray125" -> "gray125";
            case "Gray0625" -> "gray0625";
            case "HorzStripe" -> "darkHorizontal";
            case "VertStripe" -> "darkVertical";
            case "ReverseDiagStripe" -> "darkDown";
            case "DiagStripe" -> "darkUp";
            case "DiagCross" -> "darkGrid";
            case "ThickDiagCross" -> "darkTrellis";
            case "ThinHorzStripe" -> "lightHorizontal";
            case "ThinVertStripe" -> "lightVertical";
            case "ThinReverseDiagStripe" -> "lightDown";
            case "ThinDiagStripe" -> "lightUp";
            case "ThinHorzCross" -> "lightGrid";
            case "ThinDiagCross" -> "lightTrellis";
            default -> "solid";
        };
        String fg = rgb(i.get("PatternColor"));
        return "<fill><patternFill patternType=\"" + type + "\">" + (fg == null ? "<fgColor auto=\"1\"/>"
                : "<fgColor rgb=\"" + fg + "\"/>") + (color == null ? "" : "<bgColor rgb=\"" + color + "\"/>")
                + "</patternFill></fill>";
    }

    private static String solid(String color) {
        return "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"" + color + "\"/><bgColor indexed=\"64\"/>"
                + "</patternFill></fill>";
    }

    private static String border(Map<String, Map<String, String>> s) {
        String[][] sides = {{"left", "Left"}, {"right", "Right"}, {"top", "Top"}, {"bottom", "Bottom"},
            {"diagonal", "DiagonalLeft"}};
        StringBuilder b = new StringBuilder();
        boolean down = false;
        boolean up = false;
        for (String[] side : sides) {
            Map<String, String> m = s.get("Border:" + side[1]);
            if (side[0].equals("diagonal")) {
                Map<String, String> right = s.get("Border:DiagonalRight");
                down = style(m) != null;
                up = style(right) != null;
                if (!down && up) {
                    m = right;
                }
            }
            String st = style(m);
            if (st == null) {
                b.append('<').append(side[0]).append("/>");
            } else {
                String color = rgb(m.get("Color"));
                b.append('<').append(side[0]).append(" style=\"").append(st).append("\">")
                        .append(color == null ? "<color auto=\"1\"/>" : "<color rgb=\"" + color + "\"/>")
                        .append("</").append(side[0]).append('>');
            }
        }
        return "<border" + (down ? " diagonalDown=\"1\"" : "") + (up ? " diagonalUp=\"1\"" : "") + ">" + b
                + "</border>";
    }

    private static String style(Map<String, String> m) {
        if (m == null) {
            return null;
        }
        String line = m.get("LineStyle");
        if (line == null || line.equalsIgnoreCase("None")) {
            return null;
        }
        int w = (int) parse(m.get("Weight"), 1);
        return switch (line) {
            case "Continuous" -> w <= 0 ? "hair" : w == 1 ? "thin" : w == 2 ? "medium" : "thick";
            case "Dash" -> w >= 2 ? "mediumDashed" : "dashed";
            case "Dot" -> w >= 2 ? "mediumDashed" : "dotted";
            case "DashDot" -> w >= 2 ? "mediumDashDot" : "dashDot";
            case "DashDotDot" -> w >= 2 ? "mediumDashDotDot" : "dashDotDot";
            case "SlantDashDot" -> "slantDashDot";
            case "Double" -> "double";
            default -> "thin";
        };
    }

    private static String alignment(Map<String, String> a) {
        StringBuilder b = new StringBuilder();
        String h = a.get("Horizontal");
        if (h != null) {
            String v = switch (h) {
                case "Left" -> "left";
                case "Center" -> "center";
                case "Right" -> "right";
                case "Fill" -> "fill";
                case "Justify" -> "justify";
                case "CenterAcrossSelection" -> "centerContinuous";
                case "Distributed", "JustifyDistributed" -> "distributed";
                default -> null;
            };
            if (v != null) {
                b.append(" horizontal=\"").append(v).append('"');
            }
        }
        String v = a.get("Vertical");
        if (v != null) {
            String x = switch (v) {
                case "Top" -> "top";
                case "Center" -> "center";
                case "Justify" -> "justify";
                case "Distributed", "JustifyDistributed" -> "distributed";
                default -> null;
            };
            if (x != null) {
                b.append(" vertical=\"").append(x).append('"');
            }
        }
        if ("1".equals(a.get("WrapText"))) {
            b.append(" wrapText=\"1\"");
        }
        if ("1".equals(a.get("ShrinkToFit"))) {
            b.append(" shrinkToFit=\"1\"");
        }
        int indent = (int) parse(a.get("Indent"), 0);
        if (indent > 0 && indent < 251) {
            b.append(" indent=\"").append(indent).append('"');
        }
        int rotate = (int) parse(a.get("Rotate"), 0);
        if ("1".equals(a.get("VerticalText"))) {
            b.append(" textRotation=\"255\"");
        } else if (rotate > 0 && rotate <= 90) {
            b.append(" textRotation=\"").append(rotate).append('"');
        } else if (rotate < 0 && rotate >= -90) {
            b.append(" textRotation=\"").append(90 - rotate).append('"');
        }
        if ("RightToLeft".equals(a.get("ReadingOrder"))) {
            b.append(" readingOrder=\"2\"");
        }
        return b.isEmpty() ? "" : "<alignment" + b + "/>";
    }

    static String rgb(String color) {
        if (color == null) {
            return null;
        }
        String c = color.trim();
        if (c.startsWith("#") && c.length() == 7) {
            try {
                Integer.parseInt(c.substring(1), 16);
                return "FF" + c.substring(1).toUpperCase(Locale.ROOT);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return switch (c.toLowerCase(Locale.ROOT)) {
            case "black" -> "FF000000";
            case "white" -> "FFFFFFFF";
            case "red" -> "FFFF0000";
            case "green" -> "FF008000";
            case "blue" -> "FF0000FF";
            case "yellow" -> "FFFFFF00";
            default -> null;
        };
    }

    static double parse(String v, double fallback) {
        if (v == null) {
            return fallback;
        }
        try {
            double d = Double.parseDouble(v.trim());
            return Double.isFinite(d) ? d : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    String xml() {
        StringBuilder b = new StringBuilder(Xml.HEAD).append("<styleSheet xmlns=\"").append(Xml.MAIN).append("\">");
        if (!formats.isEmpty()) {
            b.append("<numFmts count=\"").append(formats.size()).append("\">");
            formats.forEach((code, id) -> b.append("<numFmt numFmtId=\"").append(id).append("\" formatCode=\"")
                    .append(Xml.attr(code)).append("\"/>"));
            b.append("</numFmts>");
        }
        list(b, "fonts", new ArrayList<>(fonts.keySet()));
        list(b, "fills", new ArrayList<>(fills.keySet()));
        list(b, "borders", new ArrayList<>(borders.keySet()));
        list(b, "cellStyleXfs", List.of("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>"));
        list(b, "cellXfs", xfs);
        b.append("<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>");
        return b.append("</styleSheet>").toString();
    }

    private static void list(StringBuilder b, String name, List<String> items) {
        b.append('<').append(name).append(" count=\"").append(items.size()).append("\">");
        items.forEach(b::append);
        b.append("</").append(name).append('>');
    }
}
