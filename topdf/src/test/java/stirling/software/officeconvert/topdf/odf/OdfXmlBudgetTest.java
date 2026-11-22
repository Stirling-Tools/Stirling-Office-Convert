package stirling.software.officeconvert.topdf.odf;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.io.XmlElements;

class OdfXmlBudgetTest {

    @TempDir
    Path dir;

    private IOException refused(String name, byte[] data) throws IOException {
        Path in = OdfFixtures.write(dir, name, data);
        return assertTimeoutPreemptively(Duration.ofMinutes(2), () -> assertThrows(IOException.class,
                () -> OfficeToPdf.convert(in, dir.resolve(name + ".pdf"))));
    }

    private static String emptyParagraphs(long n) {
        return OdfFixtures.content("", OdfFixtures.text("<text:p/>".repeat((int) n)));
    }

    @Test
    void aContentPartPastTheElementBudgetIsRefusedNotSalvaged() throws IOException {
        long over = XmlElements.domElements() + 10;
        IOException e = refused("many.odt", OdfFixtures.odf(OdfFixtures.TEXT, emptyParagraphs(over), null));
        assertTrue(e.getMessage().contains("XML elements"), e.getMessage());
    }

    @Test
    void aFlatDocumentPastTheElementBudgetIsRefused() throws IOException {
        long over = XmlElements.domElements() + 10;
        String flat = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document " + OdfFixtures.NS
                + " office:version=\"1.3\" office:mimetype=\"" + OdfFixtures.TEXT + "\"><office:body>"
                + OdfFixtures.text("<text:p/>".repeat((int) over)) + "</office:body></office:document>";
        IOException e = refused("many.fodt", flat.getBytes(StandardCharsets.UTF_8));
        assertTrue(e.getMessage().contains("XML elements"), e.getMessage());
    }

    @Test
    void aPartThatInflatesLikeAZipBombIsRefusedWhileItIsRead() throws IOException {
        String blank = " ".repeat(60 << 20);
        byte[] noise = new byte[2 << 20];
        new Random(7).nextBytes(noise);
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("content.xml", OdfFixtures.content("", OdfFixtures.text("<text:p>x" + blank + "</text:p>"))
                .getBytes(StandardCharsets.UTF_8));
        parts.put("Pictures/noise.bin", noise);
        IOException e = refused("bomb.odt", OdfFixtures.zip(OdfFixtures.TEXT, parts));
        assertTrue(e.getMessage().contains("/content.xml inflates more than 100 times"), e.getMessage());
    }

    @Test
    void aDensePartWithinTheDenseAllowanceStillConverts() throws IOException {
        String body = OdfFixtures.text("<text:p>Dense but fine</text:p>" + "<text:p/>".repeat(200_000));
        Path in = OdfFixtures.write(dir, "dense.odt", OdfFixtures.odf(OdfFixtures.TEXT,
                OdfFixtures.content("", body), null));
        Path out = dir.resolve("dense.pdf");
        assertTimeoutPreemptively(Duration.ofMinutes(2), () -> OfficeToPdf.convert(in, out));
        assertTrue(Files.size(out) > 0);
    }

    @Test
    void theWholePackageRatioIsCheckedFromTheDirectory() {
        java.util.zip.ZipEntry e = new java.util.zip.ZipEntry("content.xml");
        e.setSize(200L << 20);
        e.setCompressedSize(1L << 20);
        assertTrue(assertThrows(IOException.class, () -> OdfDocument.checkInflation(java.util.List.of(e)))
                .getMessage().contains("200 times"));
    }
}
