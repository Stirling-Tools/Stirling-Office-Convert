package stirling.software.officeconvert.pptx;

import stirling.software.officeconvert.slides.Gradient;

final class Ooxml {

    static final String NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    static final String NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    static final String NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main";
    static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
    static final String HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
    static final String NAMESPACES = " xmlns:a=\"" + NS_A + "\" xmlns:r=\"" + NS_R + "\" xmlns:p=\"" + NS_P + "\"";

    private static final long MAX_COORD = 51206400L;

    private Ooxml() {}

    static void text(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                default -> {
                    if (c >= 0x20 && c != 0xFFFE && c != 0xFFFF || c == '\t') {
                        if (Character.isHighSurrogate(c)) {
                            if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                                sb.append(c).append(s.charAt(++i));
                            }
                        } else if (!Character.isLowSurrogate(c)) {
                            sb.append(c);
                        }
                    }
                }
            }
        }
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        text(sb, s);
        return sb.toString();
    }

    static long emu(float pt) {
        return Math.min(MAX_COORD, Math.max(0L, Math.round(pt * 12700.0)));
    }

    static long offset(float pt) {
        return Math.max(-MAX_COORD, Math.min(MAX_COORD, Math.round(pt * 12700.0)));
    }

    static int centipoints(float pt) {
        return Math.round(pt * 100f);
    }

    static int fontSize(float pt) {
        return Math.clamp(Math.round(pt * 100f), 100, 400000);
    }

    static long angle(int degrees) {
        return Math.floorMod(degrees, 360) * 60000L;
    }

    static String hex(int rgb) {
        return String.format("%06X", rgb & 0xFFFFFF);
    }

    static void solidFill(StringBuilder sb, int rgb) {
        sb.append("<a:solidFill><a:srgbClr val=\"").append(hex(rgb)).append("\"/></a:solidFill>");
    }

    static void solidFill(StringBuilder sb, int rgb, float alpha) {
        if (alpha >= 0.995f) {
            solidFill(sb, rgb);
            return;
        }
        sb.append("<a:solidFill><a:srgbClr val=\"").append(hex(rgb)).append("\"><a:alpha val=\"")
                .append(Math.clamp(Math.round(alpha * 100000), 0, 100000)).append("\"/></a:srgbClr></a:solidFill>");
    }

    static void gradFill(StringBuilder sb, Gradient g, float alpha) {
        sb.append("<a:gradFill rotWithShape=\"1\"><a:gsLst>");
        for (int i = 0; i < g.colours().length; i++) {
            stop(sb, g.positions()[i], g.colours()[i], alpha);
            if (g.colours().length == 2 && i == 0) {
                stop(sb, (g.positions()[0] + g.positions()[1]) / 2, mix(g.colours()[0], g.colours()[1]), alpha);
            }
        }
        sb.append("</a:gsLst>");
        if (g.radial()) {
            sb.append("<a:path path=\"circle\"><a:fillToRect l=\"50000\" t=\"50000\" r=\"50000\" b=\"50000\"/></a:path>");
        } else {
            sb.append("<a:lin ang=\"").append(Math.floorMod(Math.round(g.angle() * 60000), 21_600_000)).append("\" scaled=\"0\"/>");
        }
        sb.append("</a:gradFill>");
    }

    private static void stop(StringBuilder sb, float position, int rgb, float alpha) {
        sb.append("<a:gs pos=\"").append(Math.clamp(Math.round(position * 100000), 0, 100000)).append("\">");
        solidColour(sb, rgb, alpha);
        sb.append("</a:gs>");
    }

    private static int mix(int a, int b) {
        int out = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            out |= ((a >> shift & 0xFF) + (b >> shift & 0xFF) + 1) / 2 << shift;
        }
        return out;
    }

    private static void solidColour(StringBuilder sb, int rgb, float alpha) {
        sb.append("<a:srgbClr val=\"").append(hex(rgb)).append('"');
        if (alpha >= 0.995f) {
            sb.append("/>");
            return;
        }
        sb.append("><a:alpha val=\"").append(Math.clamp(Math.round(alpha * 100000), 0, 100000)).append("\"/></a:srgbClr>");
    }

    static void line(StringBuilder sb, int rgb, float width) {
        if (rgb < 0 || width <= 0) {
            sb.append("<a:ln><a:noFill/></a:ln>");
            return;
        }
        sb.append("<a:ln w=\"").append(emu(width)).append("\">");
        solidFill(sb, rgb);
        sb.append("</a:ln>");
    }
}
