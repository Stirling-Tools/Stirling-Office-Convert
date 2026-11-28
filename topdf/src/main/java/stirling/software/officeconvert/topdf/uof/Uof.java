package stirling.software.officeconvert.topdf.uof;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

final class Uof {

    static final String NS = "http://schemas.uof.org/cn/2003/uof";

    private Uof() {}

    static List<Element> kids(Node e) {
        List<Element> out = new ArrayList<>();
        if (e == null) {
            return out;
        }
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element k) {
                out.add(k);
            }
        }
        return out;
    }

    static List<Element> kids(Node e, String local) {
        List<Element> out = new ArrayList<>();
        for (Element k : kids(e)) {
            if (local.equals(name(k))) {
                out.add(k);
            }
        }
        return out;
    }

    static Element kid(Node e, String local) {
        if (e == null) {
            return null;
        }
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element k && local.equals(name(k))) {
                return k;
            }
        }
        return null;
    }

    static Element path(Node e, String... locals) {
        Node at = e;
        for (String l : locals) {
            at = kid(at, l);
            if (at == null) {
                return null;
            }
        }
        return (Element) at;
    }

    static String name(Node n) {
        String l = n.getLocalName();
        if (l == null) {
            l = n.getNodeName();
            int colon = l.indexOf(':');
            return colon < 0 ? l : l.substring(colon + 1);
        }
        return l;
    }

    static String attr(Element e, String local) {
        if (e == null) {
            return null;
        }
        NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            if (local.equals(name(a))) {
                return a.getNodeValue();
            }
        }
        return null;
    }

    static String text(Element e) {
        return e == null ? "" : e.getTextContent();
    }

    static boolean truth(Element e) {
        if (e == null) {
            return false;
        }
        String v = attr(e, "值");
        if (v == null) {
            v = text(e).strip();
        }
        return v.equalsIgnoreCase("true") || v.equals("1");
    }

    static double number(String s) {
        if (s == null) {
            return Double.NaN;
        }
        String t = s.strip().toLowerCase(Locale.ROOT);
        int end = 0;
        while (end < t.length() && (Character.isDigit(t.charAt(end)) || "+-.eE".indexOf(t.charAt(end)) >= 0)) {
            end++;
        }
        if (end == 0) {
            return Double.NaN;
        }
        try {
            double v = Double.parseDouble(t.substring(0, end));
            return Double.isFinite(v) ? v : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    static String colour(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        if (t.startsWith("#") && t.length() == 7) {
            for (int i = 1; i < 7; i++) {
                if (Character.digit(t.charAt(i), 16) < 0) {
                    return null;
                }
            }
            return t.substring(1).toUpperCase(Locale.ROOT);
        }
        return null;
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                default -> {
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        if (c != 0xFFFE && c != 0xFFFF) {
                            b.append(c);
                        }
                    }
                }
            }
        }
        return b.toString();
    }

    static String font(Map<String, String> fonts, String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String f = fonts.get(ref);
        return f != null ? f : fontName(ref);
    }

    static String fontName(String declared) {
        if (declared == null) {
            return null;
        }
        String n = declared.strip();
        if (n.length() >= 2 && (n.startsWith("'") && n.endsWith("'") || n.startsWith("\"") && n.endsWith("\""))) {
            n = n.substring(1, n.length() - 1).strip();
        }
        return n.isEmpty() ? null : n;
    }
}
