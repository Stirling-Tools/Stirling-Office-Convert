package stirling.software.officeconvert.topdf.xlsx;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import stirling.software.officeconvert.topdf.dml.DmlColors;

final class Dml {

    private Dml() {}

    static Element child(Element e, String local) {
        if (e == null) {
            return null;
        }
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c && local.equals(c.getLocalName())) {
                return c;
            }
        }
        return null;
    }

    static List<Element> children(Element e) {
        List<Element> out = new ArrayList<>();
        if (e == null) {
            return out;
        }
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c) {
                out.add(c);
            }
        }
        return out;
    }

    static List<Element> children(Element e, String local) {
        List<Element> out = new ArrayList<>();
        for (Element c : children(e)) {
            if (local.equals(c.getLocalName())) {
                out.add(c);
            }
        }
        return out;
    }

    static Element path(Element e, String... locals) {
        Element at = e;
        for (String l : locals) {
            at = child(at, l);
            if (at == null) {
                return null;
            }
        }
        return at;
    }

    static String attr(Element e, String name) {
        if (e == null) {
            return null;
        }
        String v = e.getAttribute(name);
        return v == null || v.isEmpty() ? null : v;
    }

    static String attrNs(Element e, String local) {
        if (e == null) {
            return null;
        }
        var attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            String l = a.getLocalName() == null ? a.getNodeName() : a.getLocalName();
            if (local.equals(l)) {
                return a.getNodeValue();
            }
        }
        return null;
    }

    static long number(Element e, String name, long fallback) {
        String v = attr(e, name);
        if (v == null) {
            return fallback;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException ex) {
            try {
                return (long) Double.parseDouble(v.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
    }

    static boolean flag(Element e, String name) {
        String v = attr(e, name);
        return v != null && (v.equals("1") || v.equalsIgnoreCase("true"));
    }

    static long text(Element e, long fallback) {
        if (e == null) {
            return fallback;
        }
        try {
            return Long.parseLong(e.getTextContent().trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    static Color color(Element holder, ExcelColors colors, Color placeholder) {
        if (holder == null) {
            return null;
        }
        for (Element c : children(holder)) {
            Color base = base(c, colors, placeholder);
            if (base != null) {
                return modified(base, c);
            }
        }
        return null;
    }

    private static Color base(Element c, ExcelColors colors, Color placeholder) {
        String name = c.getLocalName();
        try {
            switch (name) {
                case "srgbClr" -> {
                    String v = attr(c, "val");
                    return v == null ? null : new Color(Integer.parseInt(v, 16));
                }
                case "sysClr" -> {
                    String v = attr(c, "lastClr");
                    if (v != null) {
                        return new Color(Integer.parseInt(v, 16));
                    }
                    return "window".equals(attr(c, "val")) ? Color.WHITE : Color.BLACK;
                }
                case "schemeClr" -> {
                    String v = attr(c, "val");
                    if ("phClr".equals(v)) {
                        return placeholder;
                    }
                    int idx = scheme(v);
                    return idx < 0 ? null : colors.themeColor(idx);
                }
                case "prstClr" -> {
                    return DmlColors.preset(attr(c, "val"));
                }
                case "hslClr" -> {
                    return DmlColors.hsl(number(c, "hue", 0), number(c, "sat", 0), number(c, "lum", 0));
                }
                case "scrgbClr" -> {
                    return new Color(pct(c, "r"), pct(c, "g"), pct(c, "b"));
                }
                default -> {
                    return null;
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static float pct(Element c, String a) {
        return (float) Math.max(0, Math.min(1, number(c, a, 0) / 100000.0));
    }

    private static int scheme(String v) {
        if (v == null) {
            return -1;
        }
        return switch (v) {
            case "bg1", "lt1" -> 0;
            case "tx1", "dk1" -> 1;
            case "bg2", "lt2" -> 2;
            case "tx2", "dk2" -> 3;
            case "accent1" -> 4;
            case "accent2" -> 5;
            case "accent3" -> 6;
            case "accent4" -> 7;
            case "accent5" -> 8;
            case "accent6" -> 9;
            case "hlink" -> 10;
            case "folHlink" -> 11;
            default -> -1;
        };
    }

    private static Color modified(Color base, Element c) {
        Color out = base;
        for (Element m : children(c)) {
            out = DmlColors.modify(out, m.getLocalName(), number(m, "val", 100000));
        }
        return out;
    }
}
