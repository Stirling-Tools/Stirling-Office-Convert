package stirling.software.officeconvert.pdfa;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

final class XmpSupplementSchemas {

    private static final String EXTENSION = "http://www.aiim.org/pdfa/ns/extension/";

    private XmpSupplementSchemas() {}

    static List<String> add(List<String> descriptions, PdfALevel level, String trapped, XmpProperties properties) {
        StringBuilder schemas = new StringBuilder();
        if (trapped != null) {
            schemas.append(schema(XmpProperties.PDF, "pdf", "Adobe PDF trapping status",
                    List.of(new Property("Trapped", "Text", "internal", "Trapping status"))));
        }
        if (level.part() == 1) {
            List<Property> extra = new ArrayList<>();
            if (properties.first(XmpProperties.XMP, "Label", "Text") != null) {
                extra.add(new Property("Label", "Text", "external", "User label"));
            }
            String rating = properties.first(XmpProperties.XMP, "Rating", "Integer");
            if (rating != null && rating.matches("-1|[0-5]")) {
                extra.add(new Property("Rating", "Integer", "external", "User rating"));
            }
            if (!extra.isEmpty()) {
                schemas.append(schema(XmpProperties.XMP, "xmp", "Additional XMP basic properties", extra));
            }
        }
        if (schemas.isEmpty()) {
            return descriptions;
        }
        String declaration = "<rdf:Description xmlns:rdf='" + XmpCarryOver.RDF + "' rdf:about=''"
                + " xmlns:pdfaExtension='" + EXTENSION + "' xmlns:pdfaSchema='http://www.aiim.org/pdfa/ns/schema#'"
                + " xmlns:pdfaProperty='http://www.aiim.org/pdfa/ns/property#'><pdfaExtension:schemas><rdf:Bag>"
                + schemas + "</rdf:Bag></pdfaExtension:schemas></rdf:Description>";
        Element extra = XmpCarryOver.parse(declaration.getBytes(StandardCharsets.UTF_8)).getDocumentElement();
        Element extraBag = (Element) extra.getElementsByTagNameNS(XmpCarryOver.RDF, "Bag").item(0);
        List<String> result = new ArrayList<>(descriptions);
        for (int i = 0; i < result.size(); i++) {
            var document = XmpCarryOver.parse(result.get(i).getBytes(StandardCharsets.UTF_8));
            var existing = document.getElementsByTagNameNS(EXTENSION, "schemas");
            if (existing.getLength() != 1) {
                continue;
            }
            Element root = (Element) existing.item(0);
            Element bag = (Element) XmpCarryOver.children(root).getFirst();
            for (Node item : XmpCarryOver.children(extraBag)) {
                bag.appendChild(document.importNode(item, true));
            }
            result.set(i, XmpCarryOver.serialise(List.of(root), Map.of(), Map.of()));
            return result;
        }
        result.add(declaration);
        return result;
    }

    private static String schema(String namespace, String prefix, String title, List<Property> properties) {
        StringBuilder xml = new StringBuilder("<rdf:li rdf:parseType='Resource'><pdfaSchema:schema>");
        xml.append(title).append("</pdfaSchema:schema><pdfaSchema:namespaceURI>").append(namespace)
                .append("</pdfaSchema:namespaceURI><pdfaSchema:prefix>").append(prefix)
                .append("</pdfaSchema:prefix><pdfaSchema:property><rdf:Seq>");
        for (Property property : properties) {
            xml.append("<rdf:li rdf:parseType='Resource'><pdfaProperty:name>").append(property.name)
                    .append("</pdfaProperty:name><pdfaProperty:valueType>").append(property.type)
                    .append("</pdfaProperty:valueType><pdfaProperty:category>").append(property.category)
                    .append("</pdfaProperty:category><pdfaProperty:description>").append(property.description)
                    .append("</pdfaProperty:description></rdf:li>");
        }
        return xml.append("</rdf:Seq></pdfaSchema:property></rdf:li>").toString();
    }

    private record Property(String name, String type, String category, String description) {}
}
