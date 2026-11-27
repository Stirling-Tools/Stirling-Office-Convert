package stirling.software.officeconvert.topdf.xps;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import stirling.software.officeconvert.topdf.io.SecureXml;

final class XpsDom {

    private XpsDom() {}

    static Document parse(InputStream in) throws IOException {
        Document doc = SecureXml.documentBuilder().newDocument();
        doc.setStrictErrorChecking(false);
        XMLStreamReader r = SecureXml.reader(in);
        try {
            Node at = doc;
            while (r.hasNext()) {
                int event = r.next();
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        Element e = doc.createElementNS(blank(r.getNamespaceURI()), qname(r.getPrefix(),
                                r.getLocalName()));
                        for (int i = 0, n = r.getNamespaceCount(); i < n; i++) {
                            String p = r.getNamespacePrefix(i);
                            e.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI,
                                    p == null || p.isEmpty() ? "xmlns" : "xmlns:" + p, nonNull(r.getNamespaceURI(i)));
                        }
                        for (int i = 0, n = r.getAttributeCount(); i < n; i++) {
                            e.setAttributeNS(blank(r.getAttributeNamespace(i)),
                                    qname(r.getAttributePrefix(i), r.getAttributeLocalName(i)),
                                    r.getAttributeValue(i));
                        }
                        at.appendChild(e);
                        at = e;
                    }
                    case XMLStreamConstants.END_ELEMENT -> at = at.getParentNode();
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        if (at != doc && !r.isWhiteSpace()) {
                            at.appendChild(doc.createTextNode(r.getText()));
                        }
                    }
                    default -> {
                    }
                }
            }
            if (doc.getDocumentElement() == null) {
                throw new IOException("Malformed XML: the part has no root element");
            }
            return doc;
        } catch (XMLStreamException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof InterruptedIOException x) {
                    throw x;
                }
            }
            throw new IOException("Malformed XML: " + e.getMessage(), e);
        } finally {
            try {
                r.close();
            } catch (XMLStreamException ignored) {
            }
        }
    }

    private static String blank(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    private static String qname(String prefix, String local) {
        return prefix == null || prefix.isEmpty() ? local : prefix + ":" + local;
    }
}
