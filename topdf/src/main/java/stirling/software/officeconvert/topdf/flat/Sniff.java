package stirling.software.officeconvert.topdf.flat;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import stirling.software.officeconvert.topdf.io.SecureXml;
import stirling.software.officeconvert.topdf.io.SourceFile;

/** The root element of an XML file, read without loading it. */
public final class Sniff {

    private Sniff() {}

    public static boolean root(Path file, String namespace, String local) {
        try (InputStream in = new BufferedInputStream(SourceFile.open(file))) {
            int first;
            do {
                if (Thread.currentThread().isInterrupted()) {
                    return false;
                }
                first = in.read();
            } while (first == ' ' || first == '\t' || first == '\r' || first == '\n');
            if (first != '<' && first != 0 && first != 0xEF && first != 0xFE && first != 0xFF) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        try (InputStream in = SourceFile.open(file)) {
            XMLStreamReader r = SecureXml.reader(in);
            try {
                while (r.hasNext()) {
                    if (r.next() == XMLStreamConstants.START_ELEMENT) {
                        return namespace.equals(r.getNamespaceURI()) && local.equals(r.getLocalName());
                    }
                }
            } finally {
                r.close();
            }
        } catch (IOException | XMLStreamException | RuntimeException e) {
            return false;
        }
        return false;
    }
}
