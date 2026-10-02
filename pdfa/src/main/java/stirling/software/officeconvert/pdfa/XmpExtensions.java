package stirling.software.officeconvert.pdfa;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

final class XmpExtensions {

    private static final String SCHEMA = "http://www.aiim.org/pdfa/ns/schema#";

    private static final String PROPERTY = "http://www.aiim.org/pdfa/ns/property#";

    private static final Set<String> RESERVED = Set.of("http://www.aiim.org/pdfa/ns/id/",
            "http://purl.org/dc/elements/1.1/", "http://ns.adobe.com/xap/1.0/", "http://ns.adobe.com/pdf/1.3/",
            "http://ns.adobe.com/xap/1.0/mm/", "http://ns.adobe.com/xap/1.0/rights/",
            "http://ns.adobe.com/photoshop/1.0/", "http://www.aiim.org/pdfa/ns/extension/", SCHEMA, PROPERTY,
            XmpCarryOver.RDF, "http://www.w3.org/XML/1998/namespace");

    private static final Set<String> TEXT = Set.of("Text", "URI", "URL", "MIMEType", "ProperName", "AgentName",
            "GUID", "RenditionClass", "Locale", "Part");

    private XmpExtensions() {}

    static Map<String, String> declared(Element schemas) {
        Map<String, String> out = new HashMap<>();
        Element bag = container(schemas, "Bag");
        if (bag == null || !attributes(schemas, false) || !attributes(bag, false)) {
            return Map.of();
        }
        for (Node child : XmpCarryOver.children(bag)) {
            if (child instanceof Element element && RESERVED.contains(
                    XmpCarryOver.value(element, SCHEMA, "namespaceURI"))) {
                bag.removeChild(child);
            }
        }
        Set<String> namespaces = new HashSet<>();
        Set<String> prefixes = new HashSet<>();
        for (Node child : XmpCarryOver.children(bag)) {
            if (!item(child)) {
                return Map.of();
            }
            Element schema = (Element) child;
            if (!resource(schema)) {
                return Map.of();
            }
            String ns = XmpCarryOver.value(schema, SCHEMA, "namespaceURI");
            String prefix = XmpCarryOver.value(schema, SCHEMA, "prefix");
            String title = XmpCarryOver.value(schema, SCHEMA, "schema");
            if (blank(ns) || RESERVED.contains(ns) || blank(prefix) || !prefix.matches("[A-Za-z_][A-Za-z0-9_.-]*")
                    || Set.of("xml", "xmlns", "rdf", "dc", "xmp", "pdf", "pdfaid", "xmpMM", "xmpRights", "photoshop", "pdfaExtension", "pdfaSchema", "pdfaProperty", "pdfaType", "pdfaField").contains(prefix) || blank(title)
                    || !namespaces.add(ns) || !prefixes.add(prefix)) {
                return Map.of();
            }
            Element properties = null;
            Set<String> fields = new HashSet<>();
            for (Node n : XmpCarryOver.children(schema)) {
                if (!SCHEMA.equals(n.getNamespaceURI())
                        || !Set.of("schema", "namespaceURI", "prefix", "property").contains(n.getLocalName())
                        || !fields.add(n.getLocalName())) {
                    return Map.of();
                }
                if ("property".equals(n.getLocalName())) {
                    if (properties != null) {
                        return Map.of();
                    }
                    if (!attributes((Element) n, false)) {
                        return Map.of();
                    }
                    properties = container((Element) n, "Seq");
                } else if (!XmpCarryOver.children(n).isEmpty() || !attributes((Element) n, false)) {
                    return Map.of();
                }
            }
            if (properties == null || XmpCarryOver.children(properties).isEmpty() || !attributes(properties, false)) {
                return Map.of();
            }
            for (Node n : XmpCarryOver.children(properties)) {
                if (!item(n) || !resource((Element) n)) {
                    return Map.of();
                }
                String name = XmpCarryOver.value(n, PROPERTY, "name");
                String type = XmpCarryOver.value(n, PROPERTY, "valueType");
                String category = XmpCarryOver.value(n, PROPERTY, "category");
                String description = XmpCarryOver.value(n, PROPERTY, "description");
                if (blank(name) || !name.matches("[A-Za-z_][A-Za-z0-9_.-]*") || !known(type)
                        || !Set.of("internal", "external").contains(category == null ? "" : category)
                        || blank(description) || out.putIfAbsent(ns + name, type) != null) {
                    return Map.of();
                }
                Set<String> names = new HashSet<>();
                for (Node field : XmpCarryOver.children(n)) {
                    if (!PROPERTY.equals(field.getNamespaceURI()) || !Set.of("name", "valueType", "category",
                            "description").contains(field.getLocalName()) || !XmpCarryOver.children(field).isEmpty()
                            || !names.add(field.getLocalName()) || !attributes((Element) field, false)) {
                        return Map.of();
                    }
                }
            }
        }
        return out;
    }

    static boolean typed(Element property, String type) {
        if (type == null) {
            return false;
        }
        int split = type.indexOf(' ');
        if (split < 0) {
            return XmpCarryOver.children(property).isEmpty() && attributes(property, false)
                    && scalar(property.getTextContent(), type);
        }
        String kind = type.substring(0, split);
        String itemType = type.substring(split + 1);
        Element array = container(property, kind);
        if (array == null || !attributes(property, false) || !attributes(array, false)) {
            return false;
        }
        Set<String> languages = new HashSet<>();
        for (Node n : XmpCarryOver.children(array)) {
            if (!item(n) || !XmpCarryOver.children(n).isEmpty() || !attributes((Element) n, kind.equals("Alt"))
                    || !scalar(n.getTextContent(), itemType)) {
                return false;
            }
            if (kind.equals("Alt")) {
                String language = ((Element) n).getAttributeNS("http://www.w3.org/XML/1998/namespace", "lang");
                if (!language.matches("[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*") || !languages.add(language)) {
                    return false;
                }
            }
        }
        return !kind.equals("Alt") || !languages.isEmpty();
    }

    static boolean scalar(String value, String type) {
        String s = value.strip();
        if (TEXT.contains(type)) {
            return true;
        }
        return switch (type) {
            case "Boolean" -> Set.of("True", "False", "true", "false").contains(s);
            case "Integer" -> s.matches("[+-]?[0-9]+");
            case "Real" -> s.matches("[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([Ee][+-]?[0-9]+)?");
            case "Date" -> date(s);
            default -> false;
        };
    }

    private static boolean date(String value) {
        try {
            java.time.temporal.TemporalAccessor ignored = java.time.format.DateTimeFormatter.ISO_DATE_TIME.parse(value);
            return ignored != null;
        } catch (java.time.format.DateTimeParseException e) {
            try {
                java.time.LocalDate.parse(value);
                return true;
            } catch (java.time.format.DateTimeParseException invalid) {
                return false;
            }
        }
    }

    private static boolean known(String type) {
        if (type == null) {
            return false;
        }
        int split = type.indexOf(' ');
        if (split >= 0) {
            return Set.of("Bag", "Seq", "Alt").contains(type.substring(0, split))
                    && known(type.substring(split + 1)) && type.indexOf(' ', split + 1) < 0;
        }
        return TEXT.contains(type) || Set.of("Boolean", "Integer", "Real", "Date").contains(type);
    }

    private static boolean attributes(Element element, boolean language) {
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            Node attribute = element.getAttributes().item(i);
            if (!"http://www.w3.org/2000/xmlns/".equals(attribute.getNamespaceURI())
                    && !(language && "http://www.w3.org/XML/1998/namespace".equals(attribute.getNamespaceURI())
                    && "lang".equals(attribute.getLocalName()))) {
                return false;
            }
        }
        return true;
    }

    private static Element container(Element parent, String name) {
        List<Node> children = XmpCarryOver.children(parent);
        return children.size() == 1 && XmpCarryOver.RDF.equals(children.get(0).getNamespaceURI())
                && name.equals(children.get(0).getLocalName()) ? (Element) children.get(0) : null;
    }

    private static boolean item(Node node) {
        return XmpCarryOver.RDF.equals(node.getNamespaceURI()) && "li".equals(node.getLocalName());
    }

    private static boolean resource(Element element) {
        if (!"Resource".equals(element.getAttributeNS(XmpCarryOver.RDF, "parseType"))) {
            return false;
        }
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            Node attribute = element.getAttributes().item(i);
            if (!"http://www.w3.org/2000/xmlns/".equals(attribute.getNamespaceURI())
                    && !(XmpCarryOver.RDF.equals(attribute.getNamespaceURI()) && "parseType".equals(attribute.getLocalName()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
