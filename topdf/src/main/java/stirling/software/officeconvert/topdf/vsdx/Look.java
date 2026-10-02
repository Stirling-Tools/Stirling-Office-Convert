package stirling.software.officeconvert.topdf.vsdx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class Look {

    private final Cells cells;

    private final Theme theme;

    private final int colors;

    private final int styles;

    Look(Cells cells, Theme theme, int colors, int styles) {
        this.cells = cells;
        this.theme = theme;
        this.colors = colors;
        this.styles = styles;
    }

    private static boolean themed(String v) {
        return v != null && v.trim().equalsIgnoreCase("Themed");
    }

    private int quick(Sheet s, String name, double fallback) {
        return (int) cells.number(s, name, fallback);
    }

    private boolean connector(Sheet s) {
        return cells.get(s, "BeginX") != null;
    }

    String fill(Sheet s) {
        String patternCell = cells.get(s, "FillPattern");
        String fgCell = cells.get(s, "FillForegnd");
        double pattern = themed(patternCell) ? 1 : Cells.parse(patternCell, 1);
        if (pattern == 0) {
            return "<a:noFill/>";
        }
        if (themed(fgCell) && pattern == 1) {
            String t = theme.fill(quick(s, "QuickStyleFillMatrix", 1), quick(s, "QuickStyleFillColor", 2), styles,
                    connector(s));
            if (t != null) {
                return t;
            }
            fgCell = "#" + theme.color(quick(s, "QuickStyleFillColor", 2), colors);
        }
        String fg = cells.color(fgCell);
        String bg = cells.color(cells.get(s, "FillBkgnd"));
        double fgTrans = cells.number(s, "FillForegndTrans", 0);
        double bgTrans = cells.number(s, "FillBkgndTrans", 0);
        if ("1".equals(cells.get(s, "FillGradientEnabled"))) {
            String g = gradient(s);
            if (g != null) {
                return g;
            }
        }
        if (pattern >= 25 && pattern <= 40 && fg != null && bg != null) {
            return "<a:gradFill rotWithShape=\"1\"><a:gsLst>" + stop(0, fg, fgTrans) + stop(100_000, bg, bgTrans)
                    + "</a:gsLst><a:lin ang=\"" + (pattern == 25 || pattern == 26 ? 5_400_000 : 0)
                    + "\" scaled=\"0\"/></a:gradFill>";
        }
        if (fg == null) {
            return "<a:noFill/>";
        }
        if (pattern > 1 && bg != null) {
            return solid(mix(fg, bg), fgTrans);
        }
        return solid(fg, fgTrans);
    }

    private String gradient(Sheet s) {
        Sheet.Section sec = null;
        for (Sheet x = s; x != null && sec == null; x = x.base) {
            sec = x.section("FillGradient");
        }
        if (sec == null || sec.rows.isEmpty()) {
            return null;
        }
        List<String> stops = new ArrayList<>();
        for (Sheet.Row r : sec.rows.values()) {
            Map<String, String> c = r.cells();
            String color = cells.color(c.get("GradientStopColor"));
            if (color == null) {
                continue;
            }
            double pos = Math.max(0, Math.min(1, Cells.parse(c.get("GradientStopPosition"), 0)));
            stops.add(stop((int) Math.round(pos * 100_000), color, Cells.parse(c.get("GradientStopColorTrans"), 0)));
        }
        if (stops.size() < 2) {
            return null;
        }
        double angle = Math.toDegrees(cells.number(s, "FillGradientAngle", Math.PI / 2));
        long ang = Math.round(((angle % 360) + 360) % 360 * 60_000);
        double dir = cells.number(s, "FillGradientDir", 0);
        String shade = dir == 0 ? "<a:lin ang=\"" + ang + "\" scaled=\"0\"/>"
                : "<a:path path=\"circle\"><a:fillToRect l=\"50000\" t=\"50000\" r=\"50000\" b=\"50000\"/></a:path>";
        return "<a:gradFill rotWithShape=\"1\"><a:gsLst>" + String.join("", stops) + "</a:gsLst>" + shade
                + "</a:gradFill>";
    }

    private static String stop(int pos, String color, double trans) {
        return "<a:gs pos=\"" + pos + "\">" + color(color, trans) + "</a:gs>";
    }

    static String solid(String color, double trans) {
        return "<a:solidFill>" + color(color, trans) + "</a:solidFill>";
    }

    static String color(String color, double trans) {
        if (trans > 0 && trans <= 1) {
            return "<a:srgbClr val=\"" + color + "\"><a:alpha val=\"" + Math.round((1 - trans) * 100_000)
                    + "\"/></a:srgbClr>";
        }
        return "<a:srgbClr val=\"" + color + "\"/>";
    }

    private static String mix(String a, String b) {
        int x = Integer.parseInt(a, 16);
        int y = Integer.parseInt(b, 16);
        int r = (((x >> 16) & 0xFF) + ((y >> 16) & 0xFF)) / 2;
        int g = (((x >> 8) & 0xFF) + ((y >> 8) & 0xFF)) / 2;
        int bl = ((x & 0xFF) + (y & 0xFF)) / 2;
        return String.format(Locale.ROOT, "%02X%02X%02X", r, g, bl);
    }

    String line(Sheet s) {
        Theme.Line t = null;
        String patternCell = cells.get(s, "LinePattern");
        String colorCell = cells.get(s, "LineColor");
        String weightCell = cells.get(s, "LineWeight");
        if (themed(patternCell) || themed(colorCell) || themed(weightCell)) {
            t = theme.line(quick(s, "QuickStyleLineMatrix", 1), quick(s, "QuickStyleLineColor", 2), styles,
                    connector(s));
        }
        double pattern = themed(patternCell) ? t == null ? 1 : t.pattern() : Cells.parse(patternCell, 1);
        String paint = null;
        if (themed(colorCell)) {
            paint = t == null ? null : t.fill();
        } else {
            String color = cells.color(colorCell);
            paint = color == null ? null : solid(color, cells.number(s, "LineColorTrans", 0));
        }
        if (pattern == 0 || paint == null) {
            return "<a:ln><a:noFill/></a:ln>";
        }
        long w = themed(weightCell) ? t == null ? 9525 : t.width()
                : Math.round(Cells.parse(weightCell, 0.01) * PageWriter.EMU);
        w = Math.max(1, Math.min(20 * 914_400, w));
        double capCell = themed(cells.get(s, "LineCap")) ? 0 : cells.number(s, "LineCap", 0);
        String cap = capCell == 1 ? "sq" : capCell == 2 ? "flat" : "rnd";
        StringBuilder b = new StringBuilder("<a:ln w=\"").append(w).append("\" cap=\"").append(cap).append("\">")
                .append(paint);
        String dash = dash((int) pattern);
        if (dash != null) {
            b.append("<a:prstDash val=\"").append(dash).append("\"/>");
        }
        b.append(capCell == 0 ? "<a:round/>" : "<a:miter lim=\"800000\"/>");
        arrow(b, "headEnd", arrowCell(s, "BeginArrow", t == null ? 0 : t.begin()),
                arrowCell(s, "BeginArrowSize", t == null ? 2 : t.beginSize()));
        arrow(b, "tailEnd", arrowCell(s, "EndArrow", t == null ? 0 : t.end()),
                arrowCell(s, "EndArrowSize", t == null ? 2 : t.endSize()));
        return b.append("</a:ln>").toString();
    }

    private double arrowCell(Sheet s, String name, int themedValue) {
        String v = cells.get(s, name);
        return themed(v) ? themedValue : Cells.parse(v, name.endsWith("Size") ? 2 : 0);
    }

    String fontColor(Sheet s) {
        Theme.Font f = theme.font(quick(s, "QuickStyleFontMatrix", 1), quick(s, "QuickStyleFontColor", 0), styles,
                connector(s));
        if (f == null || f.rgb() == null) {
            return null;
        }
        String under = underneath(s);
        if (under != null && Math.abs(luminance(f.rgb()) - luminance(under)) < 40) {
            String dark = theme.scheme("dk1");
            String light = theme.scheme("lt1");
            String pick = luminance(under) > 128 ? dark : light;
            return pick == null ? null : "<a:srgbClr val=\"" + pick + "\"/>";
        }
        return f.xml();
    }

    private String underneath(Sheet s) {
        String patternCell = cells.get(s, "FillPattern");
        double pattern = themed(patternCell) ? 1 : Cells.parse(patternCell, 1);
        if (pattern == 0) {
            return "FFFFFF";
        }
        String fg = cells.get(s, "FillForegnd");
        if (themed(fg)) {
            return theme.color(quick(s, "QuickStyleFillColor", 2), colors);
        }
        return cells.color(fg);
    }

    private static double luminance(String rgb) {
        try {
            int v = Integer.parseInt(rgb, 16);
            int r = (v >> 16) & 0xFF;
            int g = (v >> 8) & 0xFF;
            int b = v & 0xFF;
            return (Math.max(r, Math.max(g, b)) + Math.min(r, Math.min(g, b))) / 2.0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String dash(int p) {
        return switch (p) {
            case 1 -> null;
            case 2, 9, 17 -> "dash";
            case 3, 10, 16 -> "sysDot";
            case 4, 11 -> "dashDot";
            case 5, 12 -> "lgDashDotDot";
            case 6, 13 -> "lgDash";
            case 7, 14 -> "lgDashDot";
            case 8, 15 -> "sysDash";
            default -> p > 1 && p < 24 ? "dash" : null;
        };
    }

    private static void arrow(StringBuilder b, String tag, double kind, double size) {
        int k = (int) kind;
        if (k <= 0) {
            return;
        }
        String type = switch (k) {
            case 1, 6, 9, 12, 14, 19 -> "arrow";
            case 7, 8 -> "stealth";
            case 10, 11, 20, 21, 22 -> "oval";
            case 18, 23, 24 -> "diamond";
            default -> "triangle";
        };
        String sz = size <= 1 ? "sm" : size <= 2 ? "med" : "lg";
        b.append("<a:").append(tag).append(" type=\"").append(type).append("\" w=\"").append(sz).append("\" len=\"")
                .append(sz).append("\"/>");
    }
}
