package stirling.software.officeconvert.topdf.flat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/** An element written back out as XML text, carrying the namespace declarations it inherits from its ancestors. */
final class DomWriter {

    private static final String XMLNS = "http://www.w3.org/2000/xmlns/";

    private static final int MAX_DEPTH = 1000;

    private DomWriter() {}

    static String write(Element e) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        Map<String, String> inherited = new LinkedHashMap<>();
        for (Node p = e.getParentNode(); p instanceof Element pe; p = p.getParentNode()) {
            NamedNodeMap attrs = pe.getAttributes();
            for (int i = 0; i < attrs.getLength(); i++) {
                Attr a = (Attr) attrs.item(i);
                if (XMLNS.equals(a.getNamespaceURI())) {
                    inherited.putIfAbsent(a.getName(), a.getValue());
                }
            }
        }
        element(e, b, inherited, 0);
        return b.toString();
    }

    private static void element(Element e, StringBuilder b, Map<String, String> extra, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        b.append('<').append(e.getNodeName());
        NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            extra.remove(a.getName());
            b.append(' ').append(a.getName()).append("=\"").append(escape(a.getValue(), true)).append('"');
        }
        for (Map.Entry<String, String> x : extra.entrySet()) {
            b.append(' ').append(x.getKey()).append("=\"").append(escape(x.getValue(), true)).append('"');
        }
        if (!e.hasChildNodes()) {
            b.append("/>");
            return;
        }
        b.append('>');
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            switch (c.getNodeType()) {
                case Node.ELEMENT_NODE -> element((Element) c, b, new LinkedHashMap<>(), depth + 1);
                case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> b.append(escape(c.getNodeValue(), false));
                default -> {
                }
            }
        }
        b.append("</").append(e.getNodeName()).append('>');
    }

    private static String escape(String s, boolean attribute) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append(attribute ? "&quot;" : "\"");
                case '\n' -> b.append(attribute ? "&#10;" : "\n");
                case '\r' -> b.append("&#13;");
                case '\t' -> b.append(attribute ? "&#9;" : "\t");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
