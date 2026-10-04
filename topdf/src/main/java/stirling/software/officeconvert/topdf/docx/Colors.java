package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import stirling.software.officeconvert.topdf.dml.DmlColors;

final class Colors {

    private static final Map<String, Color> HIGHLIGHT = new HashMap<>();

    static {
        highlight("yellow", 0xFFFF00);
        highlight("green", 0x00FF00);
        highlight("cyan", 0x00FFFF);
        highlight("magenta", 0xFF00FF);
        highlight("blue", 0x0000FF);
        highlight("red", 0xFF0000);
        highlight("darkBlue", 0x000080);
        highlight("darkCyan", 0x008080);
        highlight("darkGreen", 0x008000);
        highlight("darkMagenta", 0x800080);
        highlight("darkRed", 0x800000);
        highlight("darkYellow", 0x808000);
        highlight("darkGray", 0x808080);
        // Word draws the light gray highlight as D3D3D3, not the C0C0C0 the schema lists
        highlight("lightGray", 0xD3D3D3);
        highlight("black", 0x000000);
        highlight("white", 0xFFFFFF);
    }

    private Colors() {}

    private static void highlight(String name, int rgb) {
        HIGHLIGHT.put(name.toLowerCase(Locale.ROOT), new Color(rgb));
    }

    static Color hex(String v) {
        if (v == null) {
            return null;
        }
        String s = v.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() == 3) {
            s = "" + s.charAt(0) + s.charAt(0) + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2);
        }
        if (s.length() != 6) {
            return null;
        }
        try {
            return new Color(Integer.parseInt(s, 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Color highlight(String name) {
        return name == null ? null : HIGHLIGHT.get(name.toLowerCase(Locale.ROOT));
    }

    static Color preset(String name) {
        return DmlColors.preset(name);
    }

    static Color vml(String v) {
        if (v == null) {
            return null;
        }
        String s = v.trim();
        int sp = s.indexOf(' ');
        if (sp > 0) {
            s = s.substring(0, sp);
        }
        int bracket = s.indexOf('[');
        if (bracket > 0) {
            s = s.substring(0, bracket);
        }
        Color c = hex(s);
        if (c != null) {
            return c;
        }
        c = preset(s);
        return c != null ? c : highlight(s);
    }

    static Color word(XEl color, Theme theme) {
        if (color == null) {
            return null;
        }
        return word(color, color.val(), theme);
    }

    static Color attribute(XEl e, Theme theme) {
        if (e == null) {
            return null;
        }
        return word(e, e.attr("color"), theme);
    }

    private static Color word(XEl color, String v, Theme theme) {
        String themeColor = color.attr("themeColor");
        Color base = null;
        if (themeColor != null && theme != null) {
            base = theme.wordColor(themeColor);
        }
        if (base == null) {
            if (v == null || v.equalsIgnoreCase("auto")) {
                return null;
            }
            return hex(v);
        }
        String tint = color.attr("themeTint");
        String shade = color.attr("themeShade");
        if (tint != null) {
            Integer t = hexByte(tint);
            if (t != null) {
                base = tintHsl(base, t / 255f);
            }
        }
        if (shade != null) {
            Integer s = hexByte(shade);
            if (s != null) {
                base = shadeHsl(base, s / 255f);
            }
        }
        return base;
    }

    static Integer hexByte(String v) {
        try {
            return Integer.parseInt(v.trim(), 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Color tintHsl(Color c, float tint) {
        float[] hsl = DmlColors.hsl(c);
        hsl[2] = hsl[2] * tint + (1 - tint);
        return DmlColors.rgb(hsl, c.getAlpha());
    }

    static Color shadeHsl(Color c, float shade) {
        float[] hsl = DmlColors.hsl(c);
        hsl[2] = hsl[2] * shade;
        return DmlColors.rgb(hsl, c.getAlpha());
    }

    static Color drawing(XEl holder, Theme theme, Color placeholder) {
        if (holder == null) {
            return null;
        }
        for (XEl k : holder.kids) {
            Color c = drawingColor(k, theme, placeholder);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    static Color drawingColor(XEl c, Theme theme, Color placeholder) {
        Color base;
        switch (c.name) {
            case "a:srgbClr" -> base = hex(c.val());
            case "a:schemeClr" -> {
                String v = c.val();
                base = "phClr".equals(v) ? placeholder : theme == null ? null : theme.schemeColor(v);
            }
            case "a:sysClr" -> {
                base = hex(c.attr("lastClr"));
                if (base == null) {
                    base = "window".equals(c.val()) ? Color.WHITE : Color.BLACK;
                }
            }
            case "a:prstClr" -> base = preset(c.val());
            case "a:scrgbClr" -> base = new Color(unit(c.attr("r")), unit(c.attr("g")), unit(c.attr("b")));
            case "a:hslClr" -> base = DmlColors.hsl(Ooxml.integer(c.attr("hue"), 0), Ooxml.integer(c.attr("sat"), 0),
                    Ooxml.integer(c.attr("lum"), 0));
            default -> {
                return null;
            }
        }
        if (base == null) {
            return null;
        }
        return transform(base, c);
    }

    private static float unit(String v) {
        return Math.max(0, Math.min(1, Ooxml.integer(v, 0) / 100000f));
    }

    static Color transform(Color base, XEl c) {
        Color out = base;
        for (XEl t : c.kids) {
            if (t.name.startsWith("a:")) {
                out = DmlColors.modify(out, t.name.substring(2), Ooxml.integer(t.val(), 100000));
            }
        }
        return out;
    }

    private static int mix(int c, int target, float keep) {
        return Math.max(0, Math.min(255, Math.round(c * keep + target * (1 - keep))));
    }

    static boolean dark(Color c) {
        return c != null && (c.getRed() * 0.299 + c.getGreen() * 0.587 + c.getBlue() * 0.114) < 96;
    }
}
