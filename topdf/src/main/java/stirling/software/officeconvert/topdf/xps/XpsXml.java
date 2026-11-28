package stirling.software.officeconvert.topdf.xps;

import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class XpsXml {

    static final String XPS = "http://schemas.microsoft.com/xps/2005/06";

    static final String OXPS = "http://schemas.openxps.org/oxps/v1.0";

    static final String MC = "http://schemas.openxmlformats.org/markup-compatibility/2006";

    private static final Set<String> UNDERSTOOD = Set.of(XPS, OXPS,
            "http://schemas.microsoft.com/xps/2005/06/resourcedictionary-key",
            "http://schemas.openxps.org/oxps/v1.0/resourcedictionary-key",
            "http://schemas.microsoft.com/xps/2005/06/documentstructure",
            "http://schemas.openxps.org/oxps/v1.0/documentstructure",
            "http://www.w3.org/XML/1998/namespace");

    private XpsXml() {}

    static boolean ours(XpsElement n) {
        String ns = n.getNamespaceURI();
        return ns == null || ns.equals(XPS) || ns.equals(OXPS) || ns.endsWith("/documentstructure");
    }

    interface Visitor {
        void visit(XpsElement e) throws java.io.IOException;
    }

    static void each(XpsElement parent, Visitor visitor) throws java.io.IOException {
        for (XpsElement e = parent.firstChild; e != null; e = e.nextSibling) {
            if (MC.equals(e.getNamespaceURI())) {
                if ("AlternateContent".equals(e.getLocalName())) {
                    XpsElement chosen = choose(e);
                    if (chosen != null) {
                        each(chosen, visitor);
                    }
                }
                continue;
            }
            if (ours(e)) {
                visitor.visit(e);
            }
        }
    }

    static List<XpsElement> elements(XpsElement parent) {
        List<XpsElement> out = new ArrayList<>();
        collect(parent, out);
        return out;
    }

    private static void collect(XpsElement parent, List<XpsElement> out) {
        for (XpsElement e = parent.firstChild; e != null; e = e.nextSibling) {
            if (MC.equals(e.getNamespaceURI())) {
                if ("AlternateContent".equals(e.getLocalName())) {
                    XpsElement chosen = choose(e);
                    if (chosen != null) {
                        collect(chosen, out);
                    }
                }
                continue;
            }
            if (ours(e)) {
                out.add(e);
            }
        }
    }

    private static XpsElement choose(XpsElement alternate) {
        XpsElement fallback = null;
        for (XpsElement e = alternate.firstChild; e != null; e = e.nextSibling) {
            if (!MC.equals(e.getNamespaceURI())) {
                continue;
            }
            if ("Choice".equals(e.getLocalName())) {
                if (understood(e, e.getAttribute("Requires"))) {
                    return e;
                }
            } else if ("Fallback".equals(e.getLocalName()) && fallback == null) {
                fallback = e;
            }
        }
        return fallback;
    }

    private static boolean understood(XpsElement at, String prefixes) {
        if (prefixes == null || prefixes.isBlank()) {
            return false;
        }
        for (String prefix : prefixes.strip().split("\\s+")) {
            String ns = at.lookupNamespaceURI(prefix);
            if (ns == null || !UNDERSTOOD.contains(ns)) {
                return false;
            }
        }
        return true;
    }

    static List<XpsElement> children(XpsElement parent, String localName) {
        List<XpsElement> out = new ArrayList<>();
        for (XpsElement e : elements(parent)) {
            if (localName.equals(e.getLocalName())) {
                out.add(e);
            }
        }
        return out;
    }

    static Map<String, XpsElement> properties(XpsElement parent) {
        Map<String, XpsElement> out = new HashMap<>();
        for (XpsElement e = parent.firstChild; e != null; e = e.nextSibling) {
            if (MC.equals(e.getNamespaceURI())) {
                for (XpsElement x : elements(parent)) {
                    String name = x.getLocalName();
                    if (name != null && name.indexOf('.') >= 0) {
                        out.putIfAbsent(name, x);
                    }
                }
                return out;
            }
            String name = e.getLocalName();
            if (name != null && name.indexOf('.') >= 0 && ours(e)) {
                out.putIfAbsent(name, e);
            }
        }
        return out;
    }

    static XpsElement child(XpsElement parent, String localName) {
        for (XpsElement e = parent.firstChild; e != null; e = e.nextSibling) {
            if (localName.equals(e.getLocalName()) && ours(e)) {
                return e;
            }
            if (MC.equals(e.getNamespaceURI())) {
                for (XpsElement x : elements(parent)) {
                    if (localName.equals(x.getLocalName())) {
                        return x;
                    }
                }
                return null;
            }
        }
        return null;
    }

    static XpsElement firstElement(XpsElement parent) {
        List<XpsElement> all = elements(parent);
        return all.isEmpty() ? null : all.get(0);
    }

    static String attr(XpsElement e, String name) {
        String a = e.value(name);
        if (a == null) {
            return null;
        }
        String v = a.strip();
        return v.isEmpty() ? null : v;
    }

    static String key(XpsElement e) {
        return e.key();
    }

    static String lang(XpsElement e) {
        for (XpsElement el = e; el != null; el = el.getParent()) {
            String v = el.getAttributeNS("http://www.w3.org/XML/1998/namespace", "lang");
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return null;
    }

    static double number(String s, double otherwise) {
        if (s == null) {
            return otherwise;
        }
        try {
            double v = Double.parseDouble(s.strip());
            return Double.isFinite(v) ? v : otherwise;
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }

    static final int MAX_NUMBERS = 6_000_000;

    static double[] numbers(String s) {
        if (s == null || s.isBlank()) {
            return new double[0];
        }
        double[] out = new double[Math.min(16, s.length())];
        int n = 0;
        int i = 0;
        int len = s.length();
        while (i < len && n < MAX_NUMBERS) {
            char c = s.charAt(i);
            if (c == ' ' || c == ',' || c == '\t' || c == '\n' || c == '\r') {
                i++;
                continue;
            }
            int start = i;
            while (i < len) {
                char d = s.charAt(i);
                if (d == ' ' || d == ',' || d == '\t' || d == '\n' || d == '\r') {
                    break;
                }
                i++;
            }
            double v;
            try {
                v = Double.parseDouble(s.substring(start, i));
            } catch (NumberFormatException e) {
                break;
            }
            if (n == out.length) {
                out = java.util.Arrays.copyOf(out, Math.min(MAX_NUMBERS, out.length * 2));
            }
            out[n++] = Double.isFinite(v) ? v : 0;
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    static double[] point(String s) {
        double[] v = numbers(s);
        return v.length >= 2 ? new double[] {v[0], v[1]} : null;
    }

    static double[] rect(String s) {
        double[] v = numbers(s);
        return v.length >= 4 ? new double[] {v[0], v[1], v[2], v[3]} : null;
    }

    static AffineTransform matrix(String s) {
        double[] v = numbers(s);
        if (v.length < 6) {
            return null;
        }
        return new AffineTransform((float) v[0], (float) v[1], (float) v[2], (float) v[3], (float) v[4],
                (float) v[5]);
    }

    static String staticResource(String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip();
        if (v.startsWith("{") && v.endsWith("}")) {
            String inner = v.substring(1, v.length() - 1).strip();
            if (inner.startsWith("StaticResource")) {
                return inner.substring("StaticResource".length()).strip();
            }
        }
        return null;
    }
}
