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

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
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
    void aTranslucentPolygonIsADrawPath() throws Exception {
        Path out = dir.resolve("facets.odp");
        PdfToOdp.convert(SlideFixtures.facets(dir), out, PdfToPptx.Options.defaults());
        String content = SlideFixtures.text(SlideFixtures.parts(Files.readAllBytes(out)), "content.xml");
        assertEquals(1, count(content, "<draw:path "), content);
        assertTrue(content.contains("draw:fill-color=\"#4080bf\" draw:opacity=\"60%\" draw:stroke=\"none\""), content);
        assertTrue(content.matches("(?s).*<draw:path [^>]*svg:viewBox=\"0 0 25890 54000\" svg:d=\"M[0-9 ]+L.*Z\"/>.*"), content);
    }

    @Test
    void aStraightRuleAcrossArtworkIsADrawLine() throws Exception {
        Path out = dir.resolve("ruled.odp");
        PdfToOdp.convert(SlideFixtures.ruledFacet(dir), out, PdfToPptx.Options.defaults());
        Map<String, byte[]> files = SlideFixtures.parts(Files.readAllBytes(out));
        String content = SlideFixtures.text(files, "content.xml");
        assertEquals(0, count(content, "<draw:image "), content);
        assertEquals(1, count(content, "<draw:path "), content);
        assertTrue(content.matches("(?s).*<draw:line [^>]*svg:x1=\"26\\.03[0-9]*cm\" svg:y1=\"0\\.0000cm\" svg:x2=\"29\\.41[0-9]*cm\" svg:y2=\"19\\.0500cm\"/>.*"),
                content);
        assertTrue(content.contains("svg:stroke-color=\"#bfbfbf\""), content);
    }

    @Test
    void aCurvedDrawPathIsPlacedByTheCurveNotItsControlPoints() throws Exception {
        Path heart = dir.resolve("heart.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(SlideFixtures.WIDE);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(0.75f, 0f, 0f);
                cs.moveTo(130.25f, 423.81f);
                cs.curveTo(167.85f, 519.82f, 314.51f, 423.81f, 130.25f, 300.38f);
                cs.curveTo(-54.01f, 423.81f, 92.646f, 519.82f, 130.25f, 423.81f);
                cs.closePath();
                cs.fillEvenOdd();
            }
            doc.save(heart.toFile());
        }
        Path out = dir.resolve("heart.odp");
        PdfToOdp.convert(heart, out, PdfToPptx.Options.defaults());
        String content = SlideFixtures.text(SlideFixtures.parts(Files.readAllBytes(out)), "content.xml");
        Matcher m = Pattern.compile("<draw:path [^>]*svg:x=\"(-?[0-9.]+)cm\"[^>]*svg:viewBox=\"0 0 (\\d+) \\d+\" svg:d=\"([^\"]*)\"")
                .matcher(content);
        assertTrue(m.find(), content);
        assertTrue(Integer.parseInt(m.group(2)) < 18400, "the box is the curve's own width: " + m.group(2));
        assertTrue(Double.parseDouble(m.group(1)) > 0, "the box starts where the curve does: " + m.group(1));
        assertTrue(m.group(3).contains(" -") || m.group(3).contains("C-"), "control points may sit outside the box: " + m.group(3));
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
