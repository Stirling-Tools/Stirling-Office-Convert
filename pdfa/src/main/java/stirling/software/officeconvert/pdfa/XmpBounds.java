package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

final class XmpBounds {

    private XmpBounds() {}

    static boolean allows(byte[] bytes) {
        if (bytes.length > StreamFixer.MAX_METADATA_BYTES) {
            return false;
        }
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        try {
            var reader = factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
            try {
                int nodes = 0;
                int depth = 0;
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
                        return false;
                    }
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        nodes += 1 + reader.getAttributeCount() + reader.getNamespaceCount();
                        if (++depth > 128 || nodes > 100_000) {
                            return false;
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        depth--;
                    }
                }
                return true;
            } finally {
                reader.close();
            }
        } catch (XMLStreamException | RuntimeException e) {
            return false;
        }
    }
}
