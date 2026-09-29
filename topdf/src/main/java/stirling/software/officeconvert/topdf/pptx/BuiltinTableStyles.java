package stirling.software.officeconvert.topdf.pptx;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlOptions;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTableStyle;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import stirling.software.officeconvert.topdf.io.SecureXml;

// PowerPoint's built-in table styles, drawn when a table names one its file does not define
final class BuiltinTableStyles {

    private static final String RESOURCE = "table-styles.xml";

    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";

    private record Entry(Element template, String[] accents) {}

    private static Map<String, Entry> entries;

    private BuiltinTableStyles() {}

    static synchronized CTTableStyle get(String id) {
        if (id == null) {
            return null;
        }
        Entry e = entries().get(id.strip().toUpperCase(Locale.ROOT));
        if (e == null) {
            return null;
        }
        try {
            Document d = SecureXml.documentBuilder().newDocument();
            Element style = d.createElementNS(A, "a:tblStyle");
            style.setAttribute("styleId", id.strip());
            d.appendChild(style);
            for (Node n = e.template().getFirstChild(); n != null; n = n.getNextSibling()) {
                style.appendChild(d.importNode(n, true));
            }
            substitute(style, e.accents());
            return CTTableStyle.Factory.parse(style, new XmlOptions().setLoadReplaceDocumentElement(null));
        } catch (XmlException | RuntimeException ex) {
            return null;
        }
    }

    private static void substitute(Element el, String[] accents) {
        NamedNodeMap attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            if ("{A}".equals(a.getNodeValue())) {
                a.setNodeValue(accents[0]);
            } else if ("{B}".equals(a.getNodeValue())) {
                a.setNodeValue(accents[accents.length - 1]);
            }
        }
        for (Node n = el.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element child) {
                substitute(child, accents);
            }
        }
    }

    private static Map<String, Entry> entries() {
        if (entries != null) {
            return entries;
        }
        Map<String, Entry> m = new HashMap<>();
        try (InputStream in = BuiltinTableStyles.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                Document d = SecureXml.parse(in);
                NodeList families = d.getDocumentElement().getElementsByTagName("family");
                for (int i = 0; i < families.getLength(); i++) {
                    Element f = (Element) families.item(i);
                    Element template = (Element) f.getElementsByTagName("template").item(0);
                    NodeList ids = f.getElementsByTagName("id");
                    for (int j = 0; template != null && j < ids.getLength(); j++) {
                        Element id = (Element) ids.item(j);
                        String accent = id.getAttribute("accent").strip();
                        m.put(id.getAttribute("guid").toUpperCase(Locale.ROOT),
                                new Entry(template, accent.isEmpty() ? new String[] {"dk1"} : accent.split(" ")));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            m.clear();
        }
        entries = m;
        return m;
    }
}
