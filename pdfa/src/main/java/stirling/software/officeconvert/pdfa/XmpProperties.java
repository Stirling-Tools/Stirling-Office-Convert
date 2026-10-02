package stirling.software.officeconvert.pdfa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

final class XmpProperties {

    static final String DC = "http://purl.org/dc/elements/1.1/";

    static final String XMP = "http://ns.adobe.com/xap/1.0/";

    static final String PDF = "http://ns.adobe.com/pdf/1.3/";

    static final String PHOTOSHOP = "http://ns.adobe.com/photoshop/1.0/";

    static final String MM = "http://ns.adobe.com/xap/1.0/mm/";

    private final Document document;

    private final Map<String, List<Element>> properties = new HashMap<>();

    XmpProperties(byte[] bytes) {
        document = XmpCarryOver.parse(bytes);
        if (document == null) {
            return;
        }
        var descriptions = document.getElementsByTagNameNS(XmpCarryOver.RDF, "Description");
        for (int i = 0; i < descriptions.getLength(); i++) {
            Element description = (Element) descriptions.item(i);
            for (Node child : XmpCarryOver.children(description)) {
                add((Element) child);
            }
            var attributes = description.getAttributes();
            for (int j = 0; j < attributes.getLength(); j++) {
                Node attribute = attributes.item(j);
                if (attribute.getNamespaceURI() != null && attribute.getPrefix() != null
                        && !attribute.getPrefix().equals("xmlns") && !attribute.getPrefix().equals("rdf")) {
                    Element property = document.createElementNS(attribute.getNamespaceURI(), attribute.getNodeName());
                    property.setTextContent(attribute.getNodeValue());
                    add(property);
                }
            }
        }
    }

    private void add(Element property) {
        properties.computeIfAbsent(property.getNamespaceURI() + property.getLocalName(), key -> new ArrayList<>())
                .add(property);
    }

    Element property(String namespace, String name, String type) {
        List<Element> values = properties.get(namespace + name);
        return values != null && values.size() == 1 && XmpExtensions.typed(values.getFirst(), type)
                ? values.getFirst() : null;
    }

    String first(String namespace, String name, String type) {
        Element property = property(namespace, name, type);
        if (property == null) {
            return null;
        }
        if (!type.contains(" ")) {
            return Metadata.clean(property.getTextContent());
        }
        List<Node> entries = XmpCarryOver.children(XmpCarryOver.children(property).getFirst());
        if (entries.isEmpty()) {
            return null;
        }
        for (Node entry : entries) {
            if (entry instanceof Element element && "x-default".equals(element.getAttributeNS(
                    "http://www.w3.org/XML/1998/namespace", "lang"))) {
                return Metadata.clean(element.getTextContent());
            }
        }
        return Metadata.clean(entries.getFirst().getTextContent());
    }

    void authors(org.apache.pdfbox.cos.COSDictionary info) {
        Element creator = property(DC, "creator", "Seq Text");
        if (creator == null) {
            return;
        }
        List<Node> entries = XmpCarryOver.children(XmpCarryOver.children(creator).getFirst());
        if (entries.isEmpty()) {
            return;
        }
        String old = info.getString(org.apache.pdfbox.cos.COSName.AUTHOR);
        if (old != null && !Metadata.clean(old).isEmpty() && !old.equals(joined(creator))) {
            entries.getFirst().setTextContent(Metadata.clean(old));
        }
        info.setString(org.apache.pdfbox.cos.COSName.AUTHOR, joined(creator));
    }

    private static String joined(Element creator) {
        List<String> names = XmpCarryOver.children(XmpCarryOver.children(creator).getFirst()).stream()
                .map(Node::getTextContent).map(Metadata::clean).toList();
        return String.join("; ", names);
    }

    String array(String prefix, String namespace, String name, String kind, String value) {
        if (value == null) {
            return "";
        }
        Element original = property(namespace, name, kind + " Text");
        Document owner = document;
        if (owner == null) {
            owner = XmpCarryOver.parse(("<rdf:RDF xmlns:rdf='" + XmpCarryOver.RDF + "'/>")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Element property = original == null ? owner.createElementNS(namespace, prefix + ":" + name)
                : (Element) original.cloneNode(true);
        Element container;
        if (original == null) {
            container = owner.createElementNS(XmpCarryOver.RDF, "rdf:" + kind);
            property.appendChild(container);
        } else {
            container = (Element) XmpCarryOver.children(property).getFirst();
        }
        if (namespace.equals(DC) && name.equals("creator")) {
            while (container.hasChildNodes()) {
                container.removeChild(container.getFirstChild());
            }
        }
        Element selected = null;
        for (Node child : XmpCarryOver.children(container)) {
            Element element = (Element) child;
            if (!kind.equals("Alt") || "x-default".equals(element.getAttributeNS(
                    "http://www.w3.org/XML/1998/namespace", "lang"))) {
                selected = element;
                break;
            }
        }
        if (selected == null) {
            selected = owner.createElementNS(XmpCarryOver.RDF, "rdf:li");
            if (kind.equals("Alt")) {
                selected.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:lang", "x-default");
            }
            container.insertBefore(selected, container.getFirstChild());
        }
        selected.setTextContent(value);
        return XmpCarryOver.serialise(List.of(property), Map.of(), Map.of());
    }

    List<String> extras() {
        List<Element> kept = new ArrayList<>();
        keep(kept, DC, "rights", "Alt Text");
        for (String name : List.of("subject", "language", "relation", "type")) {
            keep(kept, DC, name, "Bag Text");
        }
        for (String name : List.of("publisher", "contributor")) {
            keep(kept, DC, name, "Seq Text");
        }
        for (String name : List.of("coverage", "identifier", "source")) {
            keep(kept, DC, name, "Text");
        }
        for (String name : List.of("Label", "Nickname", "BaseURL")) {
            keep(kept, XMP, name, "Text");
        }
        Element rating = property(XMP, "Rating", "Integer");
        if (rating != null && rating.getTextContent().strip().matches("-1|[0-5]")) {
            kept.add(rating);
        }
        for (String name : List.of("AuthorsPosition", "CaptionWriter", "City", "Country", "Credit", "Headline",
                "Instructions", "Source", "State", "TransmissionReference", "Category")) {
            keep(kept, PHOTOSHOP, name, "Text");
        }
        keep(kept, PHOTOSHOP, "DateCreated", "Date");
        keep(kept, PHOTOSHOP, "SupplementalCategories", "Bag Text");
        Element urgency = property(PHOTOSHOP, "Urgency", "Integer");
        if (urgency != null && urgency.getTextContent().strip().matches("[1-8]")) {
            kept.add(urgency);
        }
        List<Element> history = properties.get(MM + "History");
        if (history != null && history.size() == 1 && XmpHistory.valid(history.getFirst())) {
            kept.add(history.getFirst());
        }
        return kept.isEmpty() ? List.of() : List.of(XmpCarryOver.serialise(kept, Map.of(), Map.of()));
    }

    private void keep(List<Element> into, String namespace, String name, String type) {
        Element property = property(namespace, name, type);
        if (property != null) {
            into.add(property);
        }
    }
}
