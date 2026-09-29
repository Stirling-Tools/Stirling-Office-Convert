package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class Theme {

    static final Theme DEFAULT = new Theme(null);

    private final Map<String, Color> scheme = new HashMap<>();

    private final Map<String, String> fonts = new HashMap<>();

    private final Map<String, String> mapping = new HashMap<>();

    private final List<Float> lineWidths = new ArrayList<>();

    private final List<XEl> fillStyles = new ArrayList<>();

    Theme(XEl theme) {
        scheme.put("dk1", Color.BLACK);
        scheme.put("lt1", Color.WHITE);
        scheme.put("dk2", new Color(0x0E2841));
        scheme.put("lt2", new Color(0xE8E8E8));
        scheme.put("accent1", new Color(0x4472C4));
        scheme.put("accent2", new Color(0xED7D31));
        scheme.put("accent3", new Color(0xA5A5A5));
        scheme.put("accent4", new Color(0xFFC000));
        scheme.put("accent5", new Color(0x5B9BD5));
        scheme.put("accent6", new Color(0x70AD47));
        scheme.put("hlink", new Color(0x0563C1));
        scheme.put("folHlink", new Color(0x954F72));
        fonts.put("major.latin", "Calibri Light");
        fonts.put("minor.latin", "Calibri");
        mapping.put("bg1", "lt1");
        mapping.put("tx1", "dk1");
        mapping.put("bg2", "lt2");
        mapping.put("tx2", "dk2");
        if (theme == null) {
            return;
        }
        XEl elements = theme.child("a:themeElements");
        if (elements == null) {
            return;
        }
        XEl clr = elements.child("a:clrScheme");
        if (clr != null) {
            for (XEl c : clr.kids) {
                String key = c.name.startsWith("a:") ? c.name.substring(2) : c.name;
                Color color = Colors.drawing(c, null, null);
                if (color != null) {
                    scheme.put(key, color);
                }
            }
        }
        XEl fontScheme = elements.child("a:fontScheme");
        if (fontScheme != null) {
            font(fontScheme.child("a:majorFont"), "major");
            font(fontScheme.child("a:minorFont"), "minor");
        }
        XEl fmt = elements.child("a:fmtScheme");
        if (fmt != null) {
            XEl lines = fmt.child("a:lnStyleLst");
            if (lines != null) {
                for (XEl ln : lines.children("a:ln")) {
                    lineWidths.add(Ooxml.emu(ln.attr("w"), 0.75f));
                }
            }
            XEl fills = fmt.child("a:fillStyleLst");
            if (fills != null) {
                fillStyles.addAll(fills.kids);
            }
        }
    }

    private void font(XEl f, String which) {
        if (f == null) {
            return;
        }
        for (XEl k : f.kids) {
            String face = k.attr("typeface");
            if (face == null || face.isEmpty()) {
                continue;
            }
            switch (k.name) {
                case "a:latin" -> fonts.put(which + ".latin", face);
                case "a:ea" -> fonts.put(which + ".ea", face);
                case "a:cs" -> fonts.put(which + ".cs", face);
                case "a:font" -> fonts.put(which + ".script." + k.attr("script", ""), face);
                default -> {
                }
            }
        }
    }

    void mapping(XEl clrSchemeMapping) {
        if (clrSchemeMapping == null) {
            return;
        }
        String[][] keys = {{"bg1", "bg1"}, {"t1", "tx1"}, {"bg2", "bg2"}, {"t2", "tx2"}, {"accent1", "accent1"},
            {"accent2", "accent2"}, {"accent3", "accent3"}, {"accent4", "accent4"}, {"accent5", "accent5"},
            {"accent6", "accent6"}, {"hyperlink", "hlink"}, {"followedHyperlink", "folHlink"}};
        for (String[] k : keys) {
            String v = clrSchemeMapping.attr(k[0]);
            if (v != null) {
                mapping.put(k[1], abbreviate(v));
            }
        }
    }

    private static String abbreviate(String v) {
        return switch (v) {
            case "dark1" -> "dk1";
            case "light1" -> "lt1";
            case "dark2" -> "dk2";
            case "light2" -> "lt2";
            case "hyperlink" -> "hlink";
            case "followedHyperlink" -> "folHlink";
            default -> v;
        };
    }

    Color schemeColor(String name) {
        if (name == null) {
            return null;
        }
        String key = mapping.getOrDefault(name, name);
        Color c = scheme.get(key);
        return c != null ? c : scheme.get(name);
    }

    Color wordColor(String themeColor) {
        String key = switch (themeColor) {
            case "dark1" -> "dk1";
            case "light1" -> "lt1";
            case "dark2" -> "dk2";
            case "light2" -> "lt2";
            case "text1" -> "tx1";
            case "text2" -> "tx2";
            case "background1" -> "bg1";
            case "background2" -> "bg2";
            case "hyperlink" -> "hlink";
            case "followedHyperlink" -> "folHlink";
            default -> themeColor;
        };
        return schemeColor(key);
    }

    String font(String themeFont) {
        return font(themeFont, null);
    }

    static String script(String lang) {
        if (lang == null) {
            return null;
        }
        String l = lang.toLowerCase(Locale.ROOT);
        if (l.startsWith("zh")) {
            return l.contains("tw") || l.contains("hk") || l.contains("mo") || l.contains("hant") ? "Hant" : "Hans";
        }
        if (l.startsWith("ja")) {
            return "Jpan";
        }
        if (l.startsWith("ko")) {
            return "Hang";
        }
        return null;
    }

    String font(String themeFont, String eastAsiaLang) {
        if (themeFont == null) {
            return null;
        }
        String t = themeFont.toLowerCase(Locale.ROOT);
        String which = t.startsWith("major") ? "major" : "minor";
        String slot;
        if (t.endsWith("eastasia")) {
            slot = "ea";
        } else if (t.endsWith("bidi")) {
            slot = "cs";
        } else {
            slot = "latin";
        }
        String f = fonts.get(which + "." + slot);
        if ((f == null || f.isEmpty()) && slot.equals("ea")) {
            String script = script(eastAsiaLang);
            f = fonts.get(which + ".script." + (script == null ? "Jpan" : script));
        }
        return f == null || f.isEmpty() ? null : f;
    }

    float lineWidth(int idx) {
        if (idx <= 0) {
            return 0;
        }
        return idx <= lineWidths.size() ? lineWidths.get(idx - 1) : 0.75f;
    }

    XEl fillStyle(int idx) {
        if (idx <= 0) {
            return null;
        }
        int i = idx >= 1001 ? idx - 1001 : idx - 1;
        return i < fillStyles.size() ? fillStyles.get(i) : null;
    }
}
