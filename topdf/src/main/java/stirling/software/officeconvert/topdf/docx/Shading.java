package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;

final class Shading {

    private Shading() {}

    static Color parse(XEl shd, Theme theme) {
        if (shd == null) {
            return null;
        }
        String val = shd.val();
        if ("nil".equals(val)) {
            return null;
        }
        Color fill = themed(shd, "fill", "themeFill", "themeFillTint", "themeFillShade", theme);
        Color pattern = themed(shd, "color", "themeColor", "themeTint", "themeShade", theme);
        if (val == null || val.equals("clear")) {
            return fill;
        }
        if (val.equals("solid")) {
            return pattern == null ? Color.BLACK : pattern;
        }
        if (val.startsWith("pct")) {
            Integer pct = Ooxml.integer(val.substring(3));
            if (pct == null) {
                return fill;
            }
            Color back = fill == null ? Color.WHITE : fill;
            Color fore = pattern == null ? Color.BLACK : pattern;
            float p = Math.max(0, Math.min(100, pct)) / 100f;
            return new Color(mix(back.getRed(), fore.getRed(), p), mix(back.getGreen(), fore.getGreen(), p),
                    mix(back.getBlue(), fore.getBlue(), p));
        }
        return fill;
    }

    private static int mix(int back, int fore, float p) {
        return Math.round(back * (1 - p) + fore * p);
    }

    private static Color themed(XEl e, String attr, String themeAttr, String tintAttr, String shadeAttr, Theme theme) {
        String themeName = e.attr(themeAttr);
        Color c = null;
        if (themeName != null && theme != null) {
            c = theme.wordColor(themeName);
            if (c != null) {
                Integer tint = e.attr(tintAttr) == null ? null : Colors.hexByte(e.attr(tintAttr));
                Integer shade = e.attr(shadeAttr) == null ? null : Colors.hexByte(e.attr(shadeAttr));
                if (tint != null) {
                    c = Colors.tintHsl(c, tint / 255f);
                }
                if (shade != null) {
                    c = Colors.shadeHsl(c, shade / 255f);
                }
            }
        }
        if (c == null) {
            String v = e.attr(attr);
            if (v != null && !v.equalsIgnoreCase("auto")) {
                c = Colors.hex(v);
            }
        }
        return c;
    }
}
