package stirling.software.officeconvert.topdf.odf;

import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

final class Dom {

    private Dom() {}

    static boolean is(Node n, String ns, String local) {
        return n instanceof Element e && ns.equals(e.getNamespaceURI()) && local.equals(e.getLocalName());
    }

    static List<Element> kids(Element e) {
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

    static List<Element> kids(Element e, String ns, String local) {
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

    static Element kid(Element e, String ns, String local) {
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

    static String attr(Element e, String ns, String local) {
        if (e == null || !e.hasAttributeNS(ns, local)) {
            return null;
        }
        return e.getAttributeNS(ns, local);
    }

    static String attr(Element e, String ns, String local, String fallback) {
        String v = attr(e, ns, local);
        return v == null ? fallback : v;
    }

    static int integer(Element e, String ns, String local, int fallback) {
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

    static String text(Element e) {
        return e == null ? "" : e.getTextContent();
    }

    static String local(Node n) {
        String l = n.getLocalName();
        return l == null ? n.getNodeName() : l;
    }
}
