package stirling.software.officeconvert.topdf.flat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SniffTest {

    @TempDir
    Path dir;

    @Test
    void rootDetectionKeepsXmlWhitespaceAndUnicodeByteOrderMarks() throws Exception {
        String xml = "  \n\t<document xmlns=\"urn:test\">Текст</document>";
        byte[][] documents = {
            xml.getBytes(StandardCharsets.UTF_8),
            ("\uFEFF" + xml).getBytes(StandardCharsets.UTF_8),
            xml.getBytes(StandardCharsets.UTF_16),
            ("\uFEFF" + xml).getBytes(StandardCharsets.UTF_16LE),
            ("\uFEFF" + xml).getBytes(StandardCharsets.UTF_16BE)
        };
        for (int i = 0; i < documents.length; i++) {
            Path source = Files.write(dir.resolve("document-" + i + ".xml"), documents[i]);
            assertTrue(Sniff.root(source, "urn:test", "document"), source.toString());
        }
    }
}
