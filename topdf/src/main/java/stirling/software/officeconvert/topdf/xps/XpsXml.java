package stirling.software.officeconvert.topdf.xps;

import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

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

    static boolean ours(Node n) {
        String ns = n.getNamespaceURI();
        return ns == null || ns.equals(XPS) || ns.equals(OXPS) || ns.endsWith("/documentstructure");
    }

    interface Visitor {
        void visit(Element e) throws java.io.IOException;
    }

    static void each(Element parent, Visitor visitor) throws java.io.IOException {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element e)) {
                continue;
            }
            if (MC.equals(e.getNamespaceURI())) {
                if ("AlternateContent".equals(e.getLocalName())) {
                    Element chosen = choose(e);
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

    static List<Element> elements(Element parent) {
        List<Element> out = new ArrayList<>();
        collect(parent, out);
        return out;
    }

    private static void collect(Element parent, List<Element> out) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element e)) {
                continue;
            }
            if (MC.equals(e.getNamespaceURI())) {
                if ("AlternateContent".equals(e.getLocalName())) {
                    Element chosen = choose(e);
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

    private static Element choose(Element alternate) {
        Element fallback = null;
        for (Node n = alternate.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element e) || !MC.equals(e.getNamespaceURI())) {
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

    private static boolean understood(Element at, String prefixes) {
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

    static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        for (Element e : elements(parent)) {
            if (localName.equals(e.getLocalName())) {
                out.add(e);
            }
        }
        return out;
    }

    static Element child(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                if (localName.equals(e.getLocalName()) && ours(e)) {
                    return e;
                }
                if (MC.equals(e.getNamespaceURI())) {
                    for (Element x : elements(parent)) {
                        if (localName.equals(x.getLocalName())) {
                            return x;
                        }
                    }
                    return null;
                }
            }
        }
        return null;
    }

    static Element firstElement(Element parent) {
        List<Element> all = elements(parent);
        return all.isEmpty() ? null : all.get(0);
    }

    static String attr(Element e, String name) {
        org.w3c.dom.Attr a = e.getAttributeNode(name);
        if (a == null) {
            return null;
        }
        String v = a.getValue().strip();
        return v.isEmpty() ? null : v;
    }

    static String key(Element e) {
        var attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            String ns = a.getNamespaceURI();
            if ("Key".equals(a.getLocalName()) && ns != null && ns.endsWith("resourcedictionary-key")) {
                return a.getNodeValue().strip();
            }
        }
        return null;
    }

    static String lang(Element e) {
        for (Node n = e; n instanceof Element el; n = n.getParentNode()) {
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
        return new AffineTransform(v[0], v[1], v[2], v[3], v[4], v[5]);
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
