package stirling.software.officeconvert.topdf.odf;

import java.util.Locale;

import org.w3c.dom.Element;

/** DrawingML fills and outlines for ODF graphic properties, shared by text documents and presentations. */
final class Dml {

    private Dml() {}

    static String color(String hex, double opacityPct) {
        StringBuilder b = new StringBuilder("<a:srgbClr val=\"").append(hex).append('"');
        if (opacityPct < 100) {
            b.append("><a:alpha val=\"").append(Math.max(0, Math.round(opacityPct * 1000))).append("\"/></a:srgbClr>");
        } else {
            b.append("/>");
        }
        return b.toString();
    }

    static double opacity(Props g) {
        double o = Length.percent(g.get("draw:opacity"), 100);
        String t = g.get("style:transparency");
        if (t == null) {
            t = g.get("draw:transparency");
        }
        if (t != null) {
            o = 100 - Length.percent(t, 0);
        }
        return o;
    }

    /** The fill, or null when the properties do not say (the caller's default then applies). */
    static String fill(Props g, Styles styles, BlipSource blips) {
        String fill = g.get("draw:fill");
        if (fill == null) {
            String bg = g.get("fo:background-color");
            if (bg != null) {
                String hex = Colors.fill(bg);
                return hex == null ? "<a:noFill/>" : "<a:solidFill>" + color(hex, opacity(g)) + "</a:solidFill>";
            }
            return null;
        }
        switch (fill) {
            case "none" -> {
                return "<a:noFill/>";
            }
            case "solid" -> {
                String hex = Colors.hex(g.get("draw:fill-color"), "729FCF");
                Element fade = styles.named("opacity", g.get("draw:opacity-name"));
                double op = opacity(g);
                if (fade != null) {
                    op = (Length.percent(Dom.attr(fade, Ns.DRAW, "start"), 100)
                            + Length.percent(Dom.attr(fade, Ns.DRAW, "end"), 100)) / 2;
                }
                return "<a:solidFill>" + color(hex, op) + "</a:solidFill>";
            }
            case "gradient" -> {
                Element grad = styles.named("gradient", g.get("draw:fill-gradient-name"));
                if (grad == null) {
                    return "<a:solidFill>" + color(Colors.hex(g.get("draw:fill-color"), "729FCF"), opacity(g))
                            + "</a:solidFill>";
                }
                Element fade = styles.named("opacity", g.get("draw:opacity-name"));
                double startOpacity = opacity(g);
                double endOpacity = startOpacity;
                if (fade != null) {
                    startOpacity = Length.percent(Dom.attr(fade, Ns.DRAW, "start"), 100);
                    endOpacity = Length.percent(Dom.attr(fade, Ns.DRAW, "end"), 100);
                }
                return gradient(grad, startOpacity, endOpacity);
            }
            case "bitmap" -> {
                Element img = styles.named("fill-image", g.get("draw:fill-image-name"));
                String rid = img == null || blips == null ? null : blips.blip(img);
                if (rid == null) {
                    return "<a:solidFill>" + color(Colors.hex(g.get("draw:fill-color"), "729FCF"), opacity(g))
                            + "</a:solidFill>";
                }
                boolean tile = !"stretch".equals(g.get("style:repeat"));
                return "<a:blipFill rotWithShape=\"1\"><a:blip r:embed=\"" + rid + "\"/>"
                        + (tile ? "<a:tile tx=\"0\" ty=\"0\" sx=\"100000\" sy=\"100000\" flip=\"none\" algn=\"tl\"/>"
                                : "<a:stretch><a:fillRect/></a:stretch>") + "</a:blipFill>";
            }
            case "hatch" -> {
                if ("true".equals(g.get("draw:fill-hatch-solid"))) {
                    return "<a:solidFill>" + color(Colors.hex(g.get("draw:fill-color"), "FFFFFF"), opacity(g))
                            + "</a:solidFill>";
                }
                Element hatch = styles.named("hatch", g.get("draw:fill-hatch-name"));
                String hex = Colors.hex(Dom.attr(hatch, Ns.DRAW, "color"), "000000");
                return "<a:pattFill prst=\"ltUpDiag\"><a:fgClr>" + color(hex, 100) + "</a:fgClr><a:bgClr>"
                        + "<a:srgbClr val=\"FFFFFF\"><a:alpha val=\"0\"/></a:srgbClr></a:bgClr></a:pattFill>";
            }
            default -> {
                return null;
            }
        }
    }

    private static String gradient(Element grad, double opacity, double endOpacity) {
        String start = Colors.hex(Dom.attr(grad, Ns.DRAW, "start-color"), "000000");
        String end = Colors.hex(Dom.attr(grad, Ns.DRAW, "end-color"), "FFFFFF");
        double startI = Length.percent(Dom.attr(grad, Ns.DRAW, "start-intensity"), 100) / 100;
        double endI = Length.percent(Dom.attr(grad, Ns.DRAW, "end-intensity"), 100) / 100;
        start = scale(start, startI);
        end = scale(end, endI);
        String style = Dom.attr(grad, Ns.DRAW, "style", "linear");
        double angle = angle(Dom.attr(grad, Ns.DRAW, "angle"));
        double border = Length.percent(Dom.attr(grad, Ns.DRAW, "border"), 0);
        StringBuilder b = new StringBuilder("<a:gradFill rotWithShape=\"1\"><a:gsLst>");
        switch (style) {
            case "axial" -> b.append(gs(0, end, endOpacity)).append(gs(50_000, start, opacity)).append(gs(100_000, end,
                    endOpacity));
            case "radial", "ellipsoid", "square", "rectangular" -> b.append(gs(0, end, endOpacity))
                    .append(gs(Math.round(100_000 - border * 1000), start, opacity))
                    .append(gs(100_000, start, opacity));
            default -> b.append(gs(0, start, opacity)).append(gs(Math.round(border * 1000), start, opacity))
                    .append(gs(100_000, end, endOpacity));
        }
        b.append("</a:gsLst>");
        if (style.equals("radial") || style.equals("ellipsoid")) {
            b.append("<a:path path=\"circle\"><a:fillToRect l=\"50000\" t=\"50000\" r=\"50000\" b=\"50000\"/></a:path>");
        } else if (style.equals("square") || style.equals("rectangular")) {
            b.append("<a:path path=\"rect\"><a:fillToRect l=\"50000\" t=\"50000\" r=\"50000\" b=\"50000\"/></a:path>");
        } else {
            long ang = Math.round((((90 - angle) % 360) + 360) % 360 * 60000);
            b.append("<a:lin ang=\"").append(ang).append("\" scaled=\"0\"/>");
        }
        return b.append("</a:gradFill>").toString();
    }

    private static String gs(long pos, String hex, double opacity) {
        return "<a:gs pos=\"" + Math.max(0, Math.min(100_000, pos)) + "\">" + color(hex, opacity) + "</a:gs>";
    }

    static double angle(String v) {
        if (v == null) {
            return 0;
        }
        String s = v.trim().toLowerCase(Locale.ROOT);
        try {
            if (s.endsWith("deg")) {
                return Double.parseDouble(s.substring(0, s.length() - 3));
            }
            if (s.endsWith("rad")) {
                return Math.toDegrees(Double.parseDouble(s.substring(0, s.length() - 3)));
            }
            if (s.endsWith("grad")) {
                return Double.parseDouble(s.substring(0, s.length() - 4)) * 0.9;
            }
            return Double.parseDouble(s) / 10;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String scale(String hex, double f) {
        if (f >= 1) {
            return hex;
        }
        int rgb = Integer.parseInt(hex, 16);
        int r = (int) Math.round(((rgb >> 16) & 255) * f);
        int gg = (int) Math.round(((rgb >> 8) & 255) * f);
        int bb = (int) Math.round((rgb & 255) * f);
        return String.format(Locale.ROOT, "%02X%02X%02X", r, gg, bb);
    }

    /** The outline, or null when the properties do not say. */
    static String line(Props g) {
        String stroke = g.get("draw:stroke");
        if (stroke == null && !g.has("svg:stroke-color") && !g.has("svg:stroke-width")) {
            return null;
        }
        if ("none".equals(stroke)) {
            return "<a:ln><a:noFill/></a:ln>";
        }
        double width = g.pt("svg:stroke-width", 0);
        StringBuilder b = new StringBuilder("<a:ln w=\"").append(Length.emu(Math.max(width, 0))).append("\">");
        String hex = Colors.hex(g.get("svg:stroke-color"), "3465A4");
        double op = Length.percent(g.get("svg:stroke-opacity"), 100);
        b.append("<a:solidFill>").append(color(hex, op)).append("</a:solidFill>");
        if ("dash".equals(stroke)) {
            String name = g.get("draw:stroke-dash", "").toLowerCase(Locale.ROOT);
            String dash = name.contains("dot") && name.contains("dash") ? "dashDot"
                    : name.contains("dot") ? "sysDot" : name.contains("long") ? "lgDash" : "dash";
            b.append("<a:prstDash val=\"").append(dash).append("\"/>");
        }
        String join = g.get("draw:stroke-linejoin");
        if ("round".equals(join)) {
            b.append("<a:round/>");
        } else if ("bevel".equals(join)) {
            b.append("<a:bevel/>");
        } else if ("miter".equals(join)) {
            b.append("<a:miter lim=\"800000\"/>");
        }
        b.append(arrow("headEnd", g.get("draw:marker-start"), g.pt("draw:marker-start-width", 0), width));
        b.append(arrow("tailEnd", g.get("draw:marker-end"), g.pt("draw:marker-end-width", 0), width));
        return b.append("</a:ln>").toString();
    }

    private static String arrow(String tag, String marker, double markerWidth, double lineWidth) {
        if (marker == null || marker.isBlank()) {
            return "";
        }
        String m = marker.toLowerCase(Locale.ROOT);
        String type = m.contains("circle") || m.contains("oval") ? "oval"
                : m.contains("square") || m.contains("diamond") ? "diamond"
                : m.contains("line") && m.contains("arrow") || m.contains("open") ? "arrow"
                : m.contains("stealth") ? "stealth" : "triangle";
        double ratio = lineWidth > 0 ? markerWidth / lineWidth : 3;
        String size = ratio < 2.5 ? "sm" : ratio > 4.5 ? "lg" : "med";
        return "<a:" + tag + " type=\"" + type + "\" w=\"" + size + "\" len=\"" + size + "\"/>";
    }

    interface BlipSource {
        String blip(Element fillImage);
    }
}
