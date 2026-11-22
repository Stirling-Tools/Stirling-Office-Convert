package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

class XmlElementsTest {

    private static void read(String xml, Charset charset, long max) throws IOException {
        try (InputStream in = XmlElements.limit(new ByteArrayInputStream(xml.getBytes(charset)), "/p.xml", max)) {
            in.readAllBytes();
        }
    }

    @Test
    void utf16PartsCountOnlyElementStarts() throws IOException {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><a><b/><c></c><!-- d --></a>";
        for (Charset cs : new Charset[] {StandardCharsets.UTF_8, StandardCharsets.UTF_16LE,
                StandardCharsets.UTF_16BE}) {
            read(xml, cs, 3);
            assertThrows(OfficeZip.DamagedPart.class, () -> read(xml, cs, 2), cs.name());
        }
    }

    private static final String MANY = "<a><b/><b/><b/><b/><b/><b/><b/><b/></a>";

    private static byte[] ebcdic() {
        return ("<?xml version=\"1.0\" encoding=\"IBM037\"?>" + MANY).getBytes(Charset.forName("IBM037"));
    }

    private static byte[] asciiDeclaredEbcdic() {
        byte[] head = "<?xml version=\"1.0\" encoding=\"IBM037\"?>".getBytes(StandardCharsets.US_ASCII);
        byte[] body = MANY.getBytes(Charset.forName("IBM037"));
        byte[] all = new byte[head.length + body.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(body, 0, all, head.length, body.length);
        return all;
    }

    @Test
    void theJdkParserReadsEbcdicParts() throws IOException {
        assumeTrue(Charset.isSupported("IBM037"));
        for (byte[] xml : new byte[][] {ebcdic(), asciiDeclaredEbcdic()}) {
            Document d = SecureXml.parse(new ByteArrayInputStream(xml));
            assertEquals(8, d.getElementsByTagName("b").getLength());
        }
    }

    @Test
    void refusesEncodingsThatHideElementStarts() {
        assumeTrue(Charset.isSupported("IBM037"));
        for (byte[] xml : new byte[][] {ebcdic(), asciiDeclaredEbcdic()}) {
            assertThrows(OfficeZip.DamagedPart.class, () -> {
                try (InputStream in = XmlElements.limit(new ByteArrayInputStream(xml), "/p.xml", 2)) {
                    SecureXml.parse(in);
                }
            });
        }
    }

    @Test
    void readsAsciiCompatibleDeclarations() throws IOException {
        for (String cs : new String[] {"windows-1252", "ISO-8859-1", "Shift_JIS", "UTF-8"}) {
            String xml = "<?xml version='1.0' encoding='" + cs + "'?>" + MANY;
            read(xml, Charset.forName(cs), 9);
            assertThrows(OfficeZip.DamagedPart.class, () -> read(xml, Charset.forName(cs), 8), cs);
        }
    }
}
