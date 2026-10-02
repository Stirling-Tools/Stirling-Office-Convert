package stirling.software.officeconvert.topdf.ooo1;

import java.util.LinkedHashMap;
import java.util.Map;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/** A namespace-aware DOM written out with every namespace it uses declared once on its root. */
final class XmlOut {

    private static final String XMLNS = "http://www.w3.org/2000/xmlns/";

    private static final int MAX_DEPTH = 1000;

    private final Map<String, String> prefixes = new LinkedHashMap<>();

    private XmlOut() {}

    static String write(Document doc) {
        XmlOut w = new XmlOut();
        w.collect(doc.getDocumentElement(), 0);
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        w.element(doc.getDocumentElement(), b, true, 0);
        return b.toString();
    }

    private void collect(Element e, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        declare(e.getNamespaceURI(), e.getPrefix());
        NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            if (!XMLNS.equals(a.getNamespaceURI())) {
                declare(a.getNamespaceURI(), a.getPrefix());
            }
        }
        for (Node k = e.getFirstChild(); k != null; k = k.getNextSibling()) {
            if (k instanceof Element c) {
                collect(c, depth + 1);
            }
        }
    }

    private void declare(String ns, String prefix) {
        if (ns == null || prefixes.containsKey(ns) || "http://www.w3.org/XML/1998/namespace".equals(ns)) {
            return;
        }
        String p = Names.PREFIXES.getOrDefault(ns, prefix == null ? "ns" + prefixes.size() : prefix);
        while (prefixes.containsValue(p)) {
            p = p + prefixes.size();
        }
        prefixes.put(ns, p);
    }

    private String name(Node n) {
        String ns = n.getNamespaceURI();
        if (ns == null) {
            return n.getLocalName() == null ? n.getNodeName() : n.getLocalName();
        }
        if ("http://www.w3.org/XML/1998/namespace".equals(ns)) {
            return "xml:" + n.getLocalName();
        }
        return prefixes.get(ns) + ":" + n.getLocalName();
    }

    private void element(Element e, StringBuilder b, boolean root, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        b.append('<').append(name(e));
        if (root) {
            prefixes.forEach((ns, p) -> b.append(" xmlns:").append(p).append("=\"").append(escape(ns)).append('"'));
        }
        NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            if (XMLNS.equals(a.getNamespaceURI()) || a.getName().startsWith("xmlns")) {
                continue;
            }
            b.append(' ').append(name(a)).append("=\"").append(escape(a.getValue())).append('"');
        }
        if (!e.hasChildNodes()) {
            b.append("/>");
            return;
        }
        b.append('>');
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element ce) {
                element(ce, b, false, depth + 1);
            } else if (c.getNodeType() == Node.TEXT_NODE || c.getNodeType() == Node.CDATA_SECTION_NODE) {
                b.append(escape(c.getNodeValue()));
            }
        }
        b.append("</").append(name(e)).append('>');
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
