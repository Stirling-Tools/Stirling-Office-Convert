package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfToPptxTest {

    @TempDir static Path dir;
    static Path pdf;
    static Map<String, byte[]> parts;

    @BeforeAll
    static void convert() throws Exception {
        pdf = SlideFixtures.deck(dir, 0);
        Path pptx = dir.resolve("deck.pptx");
        PdfToPptx.convert(pdf, pptx, PdfToPptx.Options.defaults());
        parts = SlideFixtures.parts(Files.readAllBytes(pptx));
    }

    private static String part(String name) {
        String xml = SlideFixtures.text(parts, name);
        assertNotNull(xml, name + " missing");
        return xml;
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
    void everyXmlPartParses() throws Exception {
        for (var e : parts.entrySet()) {
            if (e.getKey().endsWith(".xml") || e.getKey().endsWith(".rels")) {
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(e.getValue()));
            }
        }
    }

    @Test
    void oneSlidePerPageAtThePageSize() {
        String pres = part("ppt/presentation.xml");
        assertEquals(3, count(pres, "<p:sldId "));
        assertTrue(pres.contains("<p:sldSz cx=\"12192000\" cy=\"6858000\"/>"), "960 x 540 points");
        for (int i = 1; i <= 3; i++) {
            part("ppt/slides/slide" + i + ".xml");
            assertTrue(part("[Content_Types].xml").contains("/ppt/slides/slide" + i + ".xml"));
        }
    }

    @Test
    void titleSlideKeepsItsBackgroundAndTitle() {
        String slide = part("ppt/slides/slide1.xml");
        assertTrue(slide.contains("<p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"1F3864\"/>"), "coloured background");
        assertTrue(slide.contains("<p:ph type=\"title\"/>"), "the title is the slide's title");
        assertTrue(slide.contains("Quarterly Review 0"));
        assertTrue(slide.contains("<a:srgbClr val=\"FFFFFF\"/>"), "white text");
    }

    @Test
    void bulletsAreOneTextBoxWithRealBullets() {
        String slide = part("ppt/slides/slide2.xml");
        assertEquals(4, count(slide, "<a:buChar char=\"•\"/>"), "four bullet paragraphs");
        assertTrue(count(slide, "<p:sp>") <= 3, "title and list, not a box per line");
        assertFalse(slide.contains(">•<"), "the bullet is not text");
        assertTrue(slide.contains("<a:hlinkClick r:id="), "the link is kept");
        assertTrue(part("ppt/slides/_rels/slide2.xml.rels").contains("https://example.com/report"));
    }

    @Test
    void tablePictureAndSidewaysText() {
        String slide = part("ppt/slides/slide3.xml");
        assertTrue(slide.contains("<a:tbl>"), "a native table");
        assertEquals(3, count(slide, "<a:tr "));
        assertTrue(slide.contains(">Revenue<") && slide.contains(">1,200<"));
        assertTrue(slide.contains("<p:pic>"), "the image is a picture");
        assertTrue(slide.contains("rot=\"16200000\""), "text running up the page is a turned box");
        assertTrue(slide.contains(">Draft 0<"));
    }

    @Test
    void pageRangeAndStreamOverloadLeaveTheStreamOpen() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream() {
            @Override
            public void close() {
                throw new AssertionError("caller's stream closed");
            }
        };
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PdfToPptx.convert(doc, out, new PdfToPptx.Options(2, 3, true, 150f, null));
        }
        Map<String, byte[]> two = SlideFixtures.parts(out.toByteArray());
        assertEquals(2, count(SlideFixtures.text(two, "ppt/presentation.xml"), "<p:sldId "));
        assertTrue(SlideFixtures.text(two, "ppt/slides/slide1.xml").contains("Highlights"));
    }

    @Test
    void failedConversionLeavesNoFile() throws IOException {
        Path sub = Files.createDirectories(dir.resolve("broken"));
        Path bad = sub.resolve("broken.pdf");
        Files.writeString(bad, "not a pdf");
        assertThrows(IOException.class, () -> PdfToPptx.convert(bad, sub.resolve("broken.pptx"), PdfToPptx.Options.defaults()));
        try (var files = Files.list(sub)) {
            assertTrue(files.allMatch(bad::equals), "only the input remains");
        }
    }

    @Test
    void optionsHideThePasswordAndRejectNonsense() {
        assertFalse(new PdfToPptx.Options(0, 0, true, 150f, "secret").toString().contains("secret"));
        assertThrows(IllegalArgumentException.class, () -> new PdfToPptx.Options(-1, 0, true, 150f, null));
        assertThrows(IllegalArgumentException.class, () -> new PdfToPptx.Options(0, 0, true, 9f, null));
    }

    @Test
    void pagesOfAnotherSizeAreScaledOntoTheSlide() throws Exception {
        Path mixed = dir.resolve("mixed.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (PDRectangle size : new PDRectangle[] {SlideFixtures.WIDE, SlideFixtures.WIDE, PDRectangle.A4}) {
                PDPage page = new PDPage(size);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    SlideFixtures.text(cs, SlideFixtures.SANS, 20, 50, size.getHeight() - 60, "Page text");
                }
            }
            doc.save(mixed.toFile());
        }
        Path out = dir.resolve("mixed.pptx");
        PdfToPptx.convert(mixed, out, PdfToPptx.Options.defaults());
        Map<String, byte[]> p = SlideFixtures.parts(Files.readAllBytes(out));
        assertTrue(SlideFixtures.text(p, "ppt/presentation.xml").contains("<p:sldSz cx=\"12192000\" cy=\"6858000\"/>"));
        Matcher m = Pattern.compile("sz=\"(\\d+)\"").matcher(SlideFixtures.text(p, "ppt/slides/slide3.xml"));
        assertTrue(m.find());
        assertTrue(Integer.parseInt(m.group(1)) < 2000, "the portrait page's text shrinks with it");
    }

    @Test
    void slantedTextIsOneTurnedTextBox() throws Exception {
        Path slanted = dir.resolve("slanted.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(SlideFixtures.WIDE);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                SlideFixtures.text(cs, SlideFixtures.SANS, 32, 60, 480, "Quarterly review");
                cs.beginText();
                cs.setFont(SlideFixtures.SANS, 44);
                cs.setNonStrokingColor(0.8f, 0.1f, 0.1f);
                cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(25), 250, 70));
                cs.showText("CONFIDENTIAL DRAFT");
                cs.endText();
            }
            doc.save(slanted.toFile());
        }
        Path out = dir.resolve("slanted.pptx");
        PdfToPptx.convert(slanted, out, PdfToPptx.Options.defaults());
        String slide = SlideFixtures.text(SlideFixtures.parts(Files.readAllBytes(out)), "ppt/slides/slide1.xml");
        Matcher m = Pattern.compile("<a:xfrm rot=\"(\\d+)\">(?:(?!</p:sp>).)*<a:t>CONFIDENTIAL DRAFT</a:t>", Pattern.DOTALL)
                .matcher(slide);
        assertTrue(m.find(), "the stamp is one text box");
        assertEquals(335 * 60000, Integer.parseInt(m.group(1)), "turned as drawn, rising to the right");
        assertTrue(slide.contains("<a:t>Quarterly review</a:t>"));
    }

    @Test
    void aPictureTheFileHoldsTwiceIsStoredOnce() throws Exception {
        BufferedImage img = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(30, 120, 200));
        g.fillRect(0, 0, 120, 80);
        g.setColor(Color.ORANGE);
        g.fillOval(20, 10, 60, 60);
        g.dispose();
        Path twice = dir.resolve("twice.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 2; i++) {
                PDPage page = new PDPage(SlideFixtures.WIDE);
                doc.addPage(page);
                PDImageXObject x = LosslessFactory.createFromImage(doc, img);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(x, 100, 100, 240, 160);
                }
            }
            doc.save(twice.toFile());
        }
        Path out = dir.resolve("twice.pptx");
        PdfToPptx.convert(twice, out, PdfToPptx.Options.defaults());
        long media = SlideFixtures.parts(Files.readAllBytes(out)).keySet().stream().filter(n -> n.startsWith("ppt/media/")).count();
        assertEquals(1, media);
    }

    @Test
    void aDarkPageOverWhitePaperKeepsItsBackground() throws Exception {
        Path dark = dir.resolve("dark.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(SlideFixtures.WIDE);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(1f, 1f, 1f);
                cs.addRect(0, 0.5f, 960, 539.5f);
                cs.fill();
                cs.setNonStrokingColor(0f, 0f, 0f);
                cs.addRect(0, 0, 960, 540);
                cs.fill();
                cs.setNonStrokingColor(1f, 1f, 1f);
                cs.beginText();
                cs.setFont(SlideFixtures.SANS, 40);
                cs.newLineAtOffset(60, 440);
                cs.showText("White on black");
                cs.endText();
            }
            doc.save(dark.toFile());
        }
        Path out = dir.resolve("dark.pptx");
        PdfToPptx.convert(dark, out, PdfToPptx.Options.defaults());
        String slide = SlideFixtures.text(SlideFixtures.parts(Files.readAllBytes(out)), "ppt/slides/slide1.xml");
        assertTrue(slide.contains("<p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"000000\"/>"), "the black page is the background");
        assertFalse(slide.contains("<p:pic>"), "the white paper under it is not drawn over the background");
        assertTrue(slide.contains("<a:t>White on black</a:t>"));
    }

    @Test
    void ocrTextOverAScanStaysSearchableButInvisible() throws Exception {
        assertTrue(ocrSlide(true).matches("(?s).*<a:srgbClr val=\"[0-9A-F]{6}\"><a:alpha val=\"0\"/></a:srgbClr>(?:(?!</a:r>).)*"
                + "<a:t>Scanned report text</a:t>.*"), "the scan shows the words, the text layer only carries them");
        String bare = ocrSlide(false);
        assertTrue(bare.contains("<a:t>Scanned report text</a:t>"));
        assertFalse(bare.contains("<a:alpha val=\"0\"/>"), "with no picture under it the text is the only copy, so it shows");
    }

    private static String ocrSlide(boolean scan) throws Exception {
        Path file = dir.resolve(scan ? "scan.pdf" : "ocr-only.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(SlideFixtures.WIDE);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                if (scan) {
                    BufferedImage img = new BufferedImage(480, 270, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = img.createGraphics();
                    g.setColor(new Color(250, 248, 240));
                    g.fillRect(0, 0, 480, 270);
                    g.setColor(Color.DARK_GRAY);
                    g.fillRect(30, 45, 260, 14);
                    g.dispose();
                    cs.drawImage(LosslessFactory.createFromImage(doc, img), 0, 0, 960, 540);
                }
                cs.beginText();
                cs.setRenderingMode(RenderingMode.NEITHER);
                cs.setFont(SlideFixtures.SANS, 24);
                cs.newLineAtOffset(60, 430);
                cs.showText("Scanned report text");
                cs.endText();
            }
            doc.save(file.toFile());
        }
        Path out = dir.resolve(scan ? "scan.pptx" : "ocr-only.pptx");
        PdfToPptx.convert(file, out, PdfToPptx.Options.defaults());
        return SlideFixtures.text(SlideFixtures.parts(Files.readAllBytes(out)), "ppt/slides/slide1.xml");
    }
}
