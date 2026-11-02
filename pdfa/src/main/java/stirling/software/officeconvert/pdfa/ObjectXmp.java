package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import javax.xml.XMLConstants;

import org.verapdf.model.tools.xmp.SchemasDefinition;
import org.verapdf.model.tools.xmp.SchemasDefinitionCreator;
import org.verapdf.xmp.impl.VeraPDFMeta;
import org.w3c.dom.Element;

final class ObjectXmp {

    private static final SchemasDefinition PREDEFINED =
            SchemasDefinitionCreator.getPredefinedSchemaDefinitionForPDFA_2_3(false);

    private ObjectXmp() {}

    static byte[] predefined(byte[] bytes, Report report) {
        var document = XmpCarryOver.parse(bytes);
        if (document == null) {
            return null;
        }
        try {
            var metadata = VeraPDFMeta.parse(new ByteArrayInputStream(bytes));
            Set<String> allowed = new HashSet<>();
            for (var property : metadata.getProperties()) {
                if (PREDEFINED.isDefinedProperty(property)
                        && Boolean.TRUE.equals(PREDEFINED.isCorrespondsDefinedType(property))) {
                    String name = property.getName();
                    allowed.add(property.getNamespaceURI() + name.substring(name.indexOf(':') + 1));
                }
            }
            boolean changed = false;
            var descriptions = document.getElementsByTagNameNS(XmpCarryOver.RDF, "Description");
            for (int i = 0; i < descriptions.getLength(); i++) {
                Element description = (Element) descriptions.item(i);
                if (!XmpCarryOver.RDF.equals(description.getParentNode().getNamespaceURI())
                        || !"RDF".equals(description.getParentNode().getLocalName())) {
                    continue;
                }
                var attributes = description.getAttributes();
                for (int j = attributes.getLength() - 1; j >= 0; j--) {
                    var attribute = attributes.item(j);
                    String namespace = attribute.getNamespaceURI();
                    if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(namespace) && !XmpCarryOver.RDF.equals(namespace)
                            && !allowed.contains(namespace + attribute.getLocalName())) {
                        description.removeAttributeNode((org.w3c.dom.Attr) attribute);
                        changed = true;
                    }
                }
                for (var property : XmpCarryOver.children(description)) {
                    if (!allowed.contains(property.getNamespaceURI() + property.getLocalName())) {
                        description.removeChild(property);
                        changed = true;
                    }
                }
            }
            if (!changed) {
                return bytes;
            }
            String output = "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
                    + XmpCarryOver.serialise(document.getDocumentElement()) + "<?xpacket end=\"w\"?>";
            report.warn("Removed non-predefined or invalid properties from object metadata for PDF/A-2 and 3");
            return output.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
