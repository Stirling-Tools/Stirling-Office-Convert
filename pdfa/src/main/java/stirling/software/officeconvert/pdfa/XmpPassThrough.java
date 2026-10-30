package stirling.software.officeconvert.pdfa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

final class XmpPassThrough {

    private static final String XMLNS = "http://www.w3.org/2000/xmlns/";

    private static final String XML = "http://www.w3.org/XML/1998/namespace";

    private static final Set<String> DROPPED_NAMESPACES = Set.of("http://www.aiim.org/pdfa/ns/id/",
            "http://www.aiim.org/pdfa/ns/extension/", "http://www.aiim.org/pdfa/ns/schema#",
            "http://www.aiim.org/pdfa/ns/property#", "http://www.aiim.org/pdfa/ns/type#",
            "http://www.aiim.org/pdfa/ns/field#", XmpCarryOver.RDF, XML, XMLNS);

    private static final Set<String> GENERATED = Set.of(XmpProperties.DC + "format", XmpProperties.DC + "title",
            XmpProperties.DC + "creator", XmpProperties.DC + "description", XmpProperties.PDF + "Trapped",
            XmpProperties.PDF + "Keywords", XmpProperties.PDF + "Producer", XmpProperties.XMP + "CreatorTool",
            XmpProperties.XMP + "CreateDate", XmpProperties.XMP + "ModifyDate", XmpProperties.XMP + "MetadataDate");

    private XmpPassThrough() {}

    static List<String> descriptions(byte[] xmp) {
        Document doc = XmpCarryOver.parse(xmp);
        if (doc == null) {
            return List.of();
        }
        var roots = doc.getElementsByTagNameNS(XmpCarryOver.RDF, "RDF");
        if (roots.getLength() == 0) {
            return List.of();
        }
        Map<String, Element> kept = new LinkedHashMap<>();
        for (Node description : XmpCarryOver.children(roots.item(0))) {
            if (!XmpCarryOver.RDF.equals(description.getNamespaceURI())
                    || !"Description".equals(description.getLocalName())) {
                continue;
            }
            for (Node child : XmpCarryOver.children(description)) {
                offer(kept, (Element) child);
            }
            NamedNodeMap attributes = description.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++) {
                Node attribute = attributes.item(i);
                if (attribute.getNamespaceURI() == null || attribute.getPrefix() == null
                        || DROPPED_NAMESPACES.contains(attribute.getNamespaceURI())) {
                    continue;
                }
                Element property = doc.createElementNS(attribute.getNamespaceURI(), attribute.getNodeName());
                property.setTextContent(attribute.getNodeValue());
                offer(kept, property);
            }
        }
        try {
            return grouped(new ArrayList<>(kept.values()));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static void offer(Map<String, Element> kept, Element property) {
        String namespace = property.getNamespaceURI();
        if (namespace == null || DROPPED_NAMESPACES.contains(namespace)) {
            return;
        }
        String key = namespace + property.getLocalName();
        if (GENERATED.contains(key) || kept.containsKey(key)) {
            return;
        }
        if (node(property, false) && prefixes(property, new HashMap<>(Map.of("rdf", XmpCarryOver.RDF,
                "xml", XML)))) {
            kept.put(key, property);
        }
    }

    private static List<String> grouped(List<Element> properties) {
        List<String> out = new ArrayList<>();
        List<Element> group = new ArrayList<>();
        Map<String, String> bound = new HashMap<>(Map.of("rdf", XmpCarryOver.RDF, "xml", XML));
        for (Element property : properties) {
            Map<String, String> merged = new HashMap<>(bound);
            if (!prefixes(property, merged)) {
                out.add(XmpCarryOver.serialise(group, Map.of(), Map.of()));
                group = new ArrayList<>();
                merged = new HashMap<>(Map.of("rdf", XmpCarryOver.RDF, "xml", XML));
                prefixes(property, merged);
            }
            group.add(property);
            bound = merged;
        }
        if (!group.isEmpty()) {
            out.add(XmpCarryOver.serialise(group, Map.of(), Map.of()));
        }
        return out;
    }

    private static boolean prefixes(Node node, Map<String, String> bound) {
        if (!bind(node, bound)) {
            return false;
        }
        NamedNodeMap attributes = node.getAttributes();
        for (int i = 0; attributes != null && i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            if (!XMLNS.equals(attribute.getNamespaceURI()) && !bind(attribute, bound)) {
                return false;
            }
        }
        for (Node child : XmpCarryOver.children(node)) {
            if (!prefixes(child, bound)) {
                return false;
            }
        }
        return true;
    }

    private static boolean bind(Node node, Map<String, String> bound) {
        if (node.getPrefix() == null || node.getNamespaceURI() == null) {
            return node.getNamespaceURI() == null && node.getNodeType() == Node.ATTRIBUTE_NODE;
        }
        String existing = bound.putIfAbsent(node.getPrefix(), node.getNamespaceURI());
        return existing == null || existing.equals(node.getNamespaceURI());
    }

    private static boolean node(Element element, boolean item) {
        if (item ? !XmpCarryOver.RDF.equals(element.getNamespaceURI()) || !"li".equals(element.getLocalName())
                : element.getNamespaceURI() == null || element.getPrefix() == null
                || XmpCarryOver.RDF.equals(element.getNamespaceURI()) || XML.equals(element.getNamespaceURI())) {
            return false;
        }
        boolean resource = false;
        boolean reference = false;
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            String namespace = attribute.getNamespaceURI();
            String name = attribute.getLocalName();
            if (XMLNS.equals(namespace) || XML.equals(namespace) && "lang".equals(name)) {
                continue;
            }
            if (XmpCarryOver.RDF.equals(namespace) && "parseType".equals(name)
                    && "Resource".equals(attribute.getNodeValue())) {
                resource = true;
            } else if (XmpCarryOver.RDF.equals(namespace) && "resource".equals(name)) {
                reference = true;
            } else {
                return false;
            }
        }
        List<Node> children = XmpCarryOver.children(element);
        if (!text(element, children.isEmpty() && !resource)) {
            return false;
        }
        if (reference) {
            return !resource && children.isEmpty() && element.getTextContent().isBlank();
        }
        if (resource) {
            return children.stream().allMatch(child -> node((Element) child, false));
        }
        if (children.isEmpty()) {
            return true;
        }
        if (children.size() != 1) {
            return false;
        }
        Element value = (Element) children.getFirst();
        if (!XmpCarryOver.RDF.equals(value.getNamespaceURI())) {
            return false;
        }
        if (Set.of("Bag", "Seq", "Alt").contains(value.getLocalName())) {
            return plain(value, false) && text(value, false)
                    && XmpCarryOver.children(value).stream().allMatch(child -> node((Element) child, true));
        }
        if ("Description".equals(value.getLocalName())) {
            return plain(value, true) && text(value, false)
                    && XmpCarryOver.children(value).stream().allMatch(child -> node((Element) child, false));
        }
        return false;
    }

    private static boolean plain(Element element, boolean about) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            if (!XMLNS.equals(attribute.getNamespaceURI()) && !(about
                    && XmpCarryOver.RDF.equals(attribute.getNamespaceURI())
                    && "about".equals(attribute.getLocalName()) && attribute.getNodeValue().isEmpty())) {
                return false;
            }
        }
        return true;
    }

    private static boolean text(Element element, boolean allowed) {
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                String value = child.getNodeValue();
                if (!allowed && !value.isBlank() || !characters(value)) {
                    return false;
                }
            }
        }
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            if (!characters(attributes.item(i).getNodeValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean characters(String value) {
        return value.codePoints().allMatch(cp -> cp == 0x9 || cp == 0xA || cp == 0xD
                || cp >= 0x20 && cp <= 0xD7FF || cp >= 0xE000 && cp <= 0xFFFD || cp >= 0x10000 && cp <= 0x10FFFF);
    }
}
