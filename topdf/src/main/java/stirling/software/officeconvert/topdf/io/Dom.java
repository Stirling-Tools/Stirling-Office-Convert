package stirling.software.officeconvert.topdf.io;

import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

public final class Dom {

    private Dom() {}

    public static boolean is(Node n, String ns, String local) {
        return n instanceof Element e && ns.equals(e.getNamespaceURI()) && local.equals(e.getLocalName());
    }

    public static List<Element> kids(Node e) {
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

    public static List<Element> kids(Node e, String ns, String local) {
        List<Element> out = new ArrayList<>();
        if (e == null) {
            return out;
        }
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (is(n, ns, local)) {
                out.add((Element) n);
            }
        }
        return out;
    }

    public static Element kid(Node e, String ns, String local) {
        if (e == null) {
            return null;
        }
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (is(n, ns, local)) {
                return (Element) n;
            }
        }
        return null;
    }

    public static List<Element> kids(Node e, String local) {
        List<Element> out = new ArrayList<>();
        if (e == null) {
            return out;
        }
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element k && local.equals(k.getLocalName())) {
                out.add(k);
            }
        }
        return out;
    }

    public static Element kid(Node e, String local) {
        if (e == null) {
            return null;
        }
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element k && local.equals(k.getLocalName())) {
                return k;
            }
        }
        return null;
    }

    public static String attr(Element e, String ns, String local) {
        if (e == null || !e.hasAttributeNS(ns, local)) {
            return null;
        }
        return e.getAttributeNS(ns, local);
    }

    public static String attr(Element e, String ns, String local, String fallback) {
        String v = attr(e, ns, local);
        return v == null ? fallback : v;
    }

    public static int integer(Element e, String ns, String local, int fallback) {
        String v = attr(e, ns, local);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public static String text(Element e) {
        return e == null ? "" : e.getTextContent();
    }

    public static String local(Node n) {
        String l = n.getLocalName();
        return l == null ? n.getNodeName() : l;
    }
}
