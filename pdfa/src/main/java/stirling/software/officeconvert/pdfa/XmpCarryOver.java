package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

final class XmpCarryOver {

    static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

    private static final String EXTENSION = "http://www.aiim.org/pdfa/ns/extension/";

    private static final String SCHEMA = "http://www.aiim.org/pdfa/ns/schema#";

    private static final String PROPERTY = "http://www.aiim.org/pdfa/ns/property#";

    private static final String DC = "http://purl.org/dc/elements/1.1/";

    private static final String RIGHTS = "http://ns.adobe.com/xap/1.0/rights/";

    private static final String MM = "http://ns.adobe.com/xap/1.0/mm/";

    private static final Map<String, String> KEPT = Map.ofEntries(Map.entry(DC + "rights", "Alt"),
            Map.entry(DC + "subject", "Bag"), Map.entry(DC + "language", "Bag"), Map.entry(DC + "publisher", "Bag"),
            Map.entry(DC + "contributor", "Bag"), Map.entry(DC + "type", "Bag"), Map.entry(DC + "relation", "Bag"),
            Map.entry(DC + "identifier", ""), Map.entry(DC + "source", ""), Map.entry(DC + "coverage", ""),
            Map.entry(RIGHTS + "Marked", ""), Map.entry(RIGHTS + "WebStatement", ""),
            Map.entry(RIGHTS + "UsageTerms", "Alt"), Map.entry(RIGHTS + "Owner", "Bag"),
            Map.entry(RIGHTS + "Certificate", ""), Map.entry(MM + "DocumentID", ""),
            Map.entry(MM + "InstanceID", ""), Map.entry(MM + "VersionID", ""));

    private XmpCarryOver() {}

    static List<String> descriptions(byte[] xmp) {
        Document doc = parse(xmp);
        if (doc == null) {
            return List.of();
        }
        List<Element> descriptions = new ArrayList<>();
        for (Node n : elements(doc.getDocumentElement())) {
            if (RDF.equals(n.getNamespaceURI()) && "Description".equals(n.getLocalName())) {
                descriptions.add((Element) n);
            }
        }
        Element schemas = null;
        Map<String, Element> kept = new LinkedHashMap<>();
        Map<String, String> attributes = new LinkedHashMap<>();
        Map<String, String> prefixes = new HashMap<>();
        for (Element d : descriptions) {
            for (Node p : children(d)) {
                String key = p.getNamespaceURI() + p.getLocalName();
                if (EXTENSION.equals(p.getNamespaceURI()) && "schemas".equals(p.getLocalName())) {
                    schemas = (Element) p;
                } else if (KEPT.containsKey(key) && typed((Element) p, KEPT.get(key))) {
                    kept.putIfAbsent(key, (Element) p);
                }
            }
        }
        Set<String> described = schemas == null ? Set.of() : described(schemas);
        for (Element d : descriptions) {
            for (Node p : children(d)) {
                String key = p.getNamespaceURI() + p.getLocalName();
                if (described.contains(key)) {
                    kept.putIfAbsent(key, (Element) p);
                }
            }
            NamedNodeMap attrs = d.getAttributes();
            for (int i = 0; i < attrs.getLength(); i++) {
                Attr a = (Attr) attrs.item(i);
                String key = a.getNamespaceURI() + a.getLocalName();
                if (a.getNamespaceURI() != null && a.getPrefix() != null && (described.contains(key)
                        || KEPT.containsKey(key) && KEPT.get(key).isEmpty())) {
                    attributes.putIfAbsent(key, a.getValue());
                    prefixes.put(key, a.getPrefix() + ":" + a.getLocalName());
                }
            }
        }
        List<String> out = new ArrayList<>();
        try {
            if (schemas != null && !described.isEmpty()) {
                out.add(serialise(List.of(schemas), Map.of(), Map.of()));
            }
            if (!kept.isEmpty() || !attributes.isEmpty()) {
                out.add(serialise(new ArrayList<>(kept.values()), attributes, prefixes));
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return out;
    }

    private static Set<String> described(Element schemas) {
        Set<String> out = new HashSet<>();
        for (Node n : elements(schemas)) {
            String ns = value(n, SCHEMA, "namespaceURI");
            if (ns == null) {
                continue;
            }
            for (Node p : elements(n)) {
                String name = value(p, PROPERTY, "name");
                if (name != null) {
                    out.add(ns + name);
                }
            }
        }
        return out;
    }

    private static String value(Node n, String ns, String local) {
        if (!(n instanceof Element e)) {
            return null;
        }
        if (e.hasAttributeNS(ns, local)) {
            return e.getAttributeNS(ns, local).strip();
        }
        for (Node c : children(e)) {
            if (ns.equals(c.getNamespaceURI()) && local.equals(c.getLocalName())) {
                return c.getTextContent().strip();
            }
        }
        return null;
    }

    private static boolean typed(Element p, String container) {
        List<Node> kids = children(p);
        if (container.isEmpty()) {
            return kids.isEmpty() && p.getAttributes().getLength() == 0;
        }
        return kids.size() == 1 && RDF.equals(kids.get(0).getNamespaceURI())
                && container.equals(kids.get(0).getLocalName());
    }

    private static String serialise(List<Element> properties, Map<String, String> attributes,
            Map<String, String> qualified) {
        Map<String, String> declared = new LinkedHashMap<>();
        declared.put("rdf", RDF);
        StringBuilder body = new StringBuilder();
        for (Element p : properties) {
            namespaces(p, declared);
            write(p, body);
        }
        for (Map.Entry<String, String> a : attributes.entrySet()) {
            String q = qualified.get(a.getKey());
            String prefix = q.substring(0, q.indexOf(':'));
            declared.putIfAbsent(prefix, a.getKey().substring(0, a.getKey().length() - q.length() + prefix.length() + 1));
            body.append('<').append(q).append('>').append(escape(a.getValue())).append("</").append(q).append('>');
        }
        StringBuilder out = new StringBuilder("<rdf:Description rdf:about=\"\"");
        for (Map.Entry<String, String> e : declared.entrySet()) {
            out.append(" xmlns:").append(e.getKey()).append("=\"").append(escape(e.getValue())).append('"');
        }
        return out.append('>').append(body).append("</rdf:Description>").toString();
    }

    private static void write(Node n, StringBuilder out) {
        if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
            out.append(escape(n.getNodeValue()));
            return;
        }
        if (n.getNodeType() != Node.ELEMENT_NODE) {
            return;
        }
        out.append('<').append(n.getNodeName());
        NamedNodeMap attrs = n.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            out.append(' ').append(a.getNodeName()).append("=\"").append(escape(a.getNodeValue())).append('"');
        }
        out.append('>');
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
            write(c, out);
        }
        out.append("</").append(n.getNodeName()).append('>');
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void namespaces(Node n, Map<String, String> declared) {
        if (n.getPrefix() != null && n.getNamespaceURI() != null) {
            declared.putIfAbsent(n.getPrefix(), n.getNamespaceURI());
        }
        NamedNodeMap attrs = n.getAttributes();
        for (int i = 0; attrs != null && i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            if (a.getPrefix() != null && !"xmlns".equals(a.getPrefix()) && a.getNamespaceURI() != null) {
                declared.putIfAbsent(a.getPrefix(), a.getNamespaceURI());
            }
        }
        for (Node c : children(n)) {
            namespaces(c, declared);
        }
    }

    private static List<Node> children(Node n) {
        List<Node> out = new ArrayList<>();
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.ELEMENT_NODE) {
                out.add(c);
            }
        }
        return out;
    }

    private static List<Node> elements(Node root) {
        List<Node> out = new ArrayList<>();
        List<Node> todo = new ArrayList<>(children(root));
        while (!todo.isEmpty() && out.size() < 100_000) {
            Node n = todo.remove(todo.size() - 1);
            out.add(n);
            todo.addAll(children(n));
        }
        return out;
    }

    private static Document parse(byte[] xmp) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setNamespaceAware(true);
            var builder = f.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            return builder.parse(new ByteArrayInputStream(xmp));
        } catch (IOException | RuntimeException | javax.xml.parsers.ParserConfigurationException
                | org.xml.sax.SAXException e) {
            return null;
        }
    }
}
