package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

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
}
