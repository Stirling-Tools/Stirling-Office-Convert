package stirling.software.officeconvert.pdfa;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

final class XmpHistory {

    private static final String EVENT = "http://ns.adobe.com/xap/1.0/sType/ResourceEvent#";

    private static final Map<String, String> TYPES = Map.of("action", "Text", "instanceID", "URI", "parameters",
            "Text", "softwareAgent", "AgentName", "when", "Date");

    private XmpHistory() {}

    static Element repaired(Element property) {
        Element copy = (Element) property.cloneNode(true);
        var containers = XmpCarryOver.children(copy);
        if (containers.size() != 1) {
            return null;
        }
        for (Node item : XmpCarryOver.children(containers.getFirst())) {
            for (Node field : XmpCarryOver.children(item)) {
                if (!EVENT.equals(field.getNamespaceURI()) || !TYPES.containsKey(field.getLocalName())) {
                    if (EVENT.equals(field.getNamespaceURI()) && "changed".equals(field.getLocalName())) {
                        keepChanged(item, field);
                    }
                    item.removeChild(field);
                }
            }
            if (XmpCarryOver.children(item).isEmpty()) {
                containers.getFirst().removeChild(item);
            }
        }
        return !XmpCarryOver.children(containers.getFirst()).isEmpty() && valid(copy) ? copy : null;
    }

    private static void keepChanged(Node item, Node changed) {
        String parts = changed.getTextContent() == null ? "" : changed.getTextContent().strip();
        if (parts.isEmpty() || XmpCarryOver.children(changed).size() > 0) {
            return;
        }
        Node parameters = null;
        for (Node field : XmpCarryOver.children(item)) {
            if (EVENT.equals(field.getNamespaceURI()) && "parameters".equals(field.getLocalName())) {
                parameters = field;
            }
        }
        if (parameters == null) {
            String prefix = changed.getPrefix();
            parameters = item.getOwnerDocument().createElementNS(EVENT,
                    (prefix == null ? "" : prefix + ":") + "parameters");
            item.appendChild(parameters);
            parameters.setTextContent("changed: " + parts);
        } else if (XmpCarryOver.children(parameters).isEmpty()) {
            String existing = parameters.getTextContent() == null ? "" : parameters.getTextContent().strip();
            parameters.setTextContent(existing.isEmpty() ? "changed: " + parts : existing + "; changed: " + parts);
        }
    }

    static boolean valid(Element property) {
        var containers = XmpCarryOver.children(property);
        if (containers.size() != 1 || !XmpCarryOver.RDF.equals(containers.getFirst().getNamespaceURI())
                || !"Seq".equals(containers.getFirst().getLocalName()) || !plain(property)
                || !plain((Element) containers.getFirst())) {
            return false;
        }
        for (Node item : XmpCarryOver.children(containers.getFirst())) {
            if (!XmpCarryOver.RDF.equals(item.getNamespaceURI()) || !"li".equals(item.getLocalName())
                    || !(item instanceof Element element)
                    || !"Resource".equals(element.getAttributeNS(XmpCarryOver.RDF, "parseType"))) {
                return false;
            }
            for (int i = 0; i < element.getAttributes().getLength(); i++) {
                Node attribute = element.getAttributes().item(i);
                if (!"http://www.w3.org/2000/xmlns/".equals(attribute.getNamespaceURI())
                        && !(XmpCarryOver.RDF.equals(attribute.getNamespaceURI())
                        && "parseType".equals(attribute.getLocalName()))) {
                    return false;
                }
            }
            Set<String> seen = new HashSet<>();
            for (Node field : XmpCarryOver.children(item)) {
                String type = TYPES.get(field.getLocalName());
                if (!EVENT.equals(field.getNamespaceURI()) || type == null || !seen.add(field.getLocalName())
                        || !XmpExtensions.typed((Element) field, type)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean plain(Element element) {
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            if (!"http://www.w3.org/2000/xmlns/".equals(element.getAttributes().item(i).getNamespaceURI())) {
                return false;
            }
        }
        return true;
    }
}
