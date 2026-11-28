package stirling.software.officeconvert.topdf.xps;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.HashMap;
import java.util.Map;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import stirling.software.officeconvert.topdf.io.SecureXml;

final class XpsDom {

    private XpsDom() {}

    static XpsElement parse(InputStream in) throws IOException {
        XMLStreamReader r = SecureXml.reader(in);
        try {
            XpsElement root = null;
            XpsElement at = null;
            Map<String, String> values = new HashMap<>();
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    XpsElement e = new XpsElement(blank(r.getNamespaceURI()), r.getLocalName(), at);
                    int nn = r.getNamespaceCount();
                    if (nn > 0) {
                        String[] pairs = new String[2 * nn];
                        for (int i = 0; i < nn; i++) {
                            pairs[2 * i] = nonNull(r.getNamespacePrefix(i));
                            pairs[2 * i + 1] = nonNull(r.getNamespaceURI(i));
                        }
                        e.namespaces(pairs);
                    }
                    int na = r.getAttributeCount();
                    if (na > 0) {
                        String[] triples = new String[3 * na];
                        for (int i = 0; i < na; i++) {
                            triples[3 * i] = blank(r.getAttributeNamespace(i));
                            triples[3 * i + 1] = r.getAttributeLocalName(i);
                            triples[3 * i + 2] = shared(values, r.getAttributeValue(i));
                        }
                        e.attributes(triples);
                    }
                    if (root == null) {
                        root = e;
                    }
                    at = e;
                } else if (event == XMLStreamConstants.END_ELEMENT && at != null) {
                    at = at.getParent();
                }
            }
            if (root == null) {
                throw new IOException("Malformed XML: the part has no root element");
            }
            return root;
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

    private static String shared(Map<String, String> values, String v) {
        if (v == null || v.length() > 24) {
            return v;
        }
        String known = values.get(v);
        if (known != null) {
            return known;
        }
        if (values.size() < 4096) {
            values.put(v, v);
        }
        return v;
    }

    private static String blank(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }
}
