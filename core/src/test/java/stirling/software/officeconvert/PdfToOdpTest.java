package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfToOdpTest {

    @TempDir static Path dir;
    static Path odp;
    static Map<String, byte[]> parts;

    @BeforeAll
    static void convert() throws Exception {
        Path pdf = SlideFixtures.deck(dir, 0);
        odp = dir.resolve("deck.odp");
        PdfToOdp.convert(pdf, odp, PdfToPptx.Options.defaults());
        parts = SlideFixtures.parts(Files.readAllBytes(odp));
    }

    private static int count(String xml, String regex) {
        Matcher m = Pattern.compile(regex).matcher(xml);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    @Test
    void mimetypeComesFirstUncompressed() throws Exception {
        try (ZipFile zip = new ZipFile(odp.toFile())) {
            ZipEntry first = zip.entries().nextElement();
            assertEquals("mimetype", first.getName());
            assertEquals(ZipEntry.STORED, first.getMethod());
            assertEquals("application/vnd.oasis.opendocument.presentation",
                    new String(zip.getInputStream(first).readAllBytes()));
        }
    }

    @Test
    void partsParseAndTheManifestListsThePictures() throws Exception {
        for (var e : parts.entrySet()) {
            if (e.getKey().endsWith(".xml")) {
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(e.getValue()));
            }
        }
        String manifest = SlideFixtures.text(parts, "META-INF/manifest.xml");
        assertNotNull(manifest);
        for (String name : parts.keySet()) {
            if (name.startsWith("Pictures/")) {
                assertTrue(manifest.contains("\"" + name + "\""), name + " in the manifest");
            }
        }
    }

    @Test
    void slidesHoldTextListsTablesAndPictures() {
        String content = SlideFixtures.text(parts, "content.xml");
        assertEquals(3, count(content, "<draw:page "));
        assertTrue(content.contains("draw:fill-color=\"#1f3864\""), "coloured background");
        assertTrue(content.contains("draw:master-page-name=\"Bg1F3864\""));
        assertTrue(SlideFixtures.text(parts, "styles.xml").contains("<style:master-page style:name=\"Bg1F3864\""));
        assertTrue(content.contains("presentation:class=\"title\""));
        assertEquals(4, count(content, "<text:list "), "four bullet items");
        assertTrue(content.contains("text:bullet-char=\"•\""));
        assertTrue(content.contains("<table:table>") && content.contains(">1,200<"));
        assertTrue(content.contains("<draw:image xlink:href=\"Pictures/"));
        assertTrue(content.contains("xlink:href=\"https://example.com/report\""));
        assertTrue(content.contains("draw:transform=\"rotate ("), "sideways text is turned");
        String styles = SlideFixtures.text(parts, "styles.xml");
        assertTrue(styles.contains("fo:page-width=\"33.8667cm\""), "960 points wide");
    }
}
