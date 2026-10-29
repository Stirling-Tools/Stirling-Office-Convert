package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfToEpubTest {

    private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    private static final String[] CHAPTERS = {"Getting Started", "Daily Use", "Troubleshooting"};

    @TempDir static Path dir;
    static Map<String, byte[]> parts;

    @BeforeAll
    static void convert() throws IOException {
        Path pdf = dir.resolve("manual.pdf");
        try (PDDocument doc = new PDDocument()) {
            BufferedImage img = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(Color.ORANGE);
            g.fillRect(0, 0, 120, 80);
            g.setColor(Color.BLUE);
            g.fillOval(20, 10, 60, 60);
            g.dispose();
            var picture = LosslessFactory.createFromImage(doc, img);
            for (int c = 0; c < CHAPTERS.length; c++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    text(cs, BOLD, 24, 72, 700, CHAPTERS[c]);
                    float y = 660;
                    for (int line = 0; line < 12; line++, y -= 16) {
                        text(cs, REGULAR, 12, 72, y, "Chapter " + (c + 1) + " body line " + (line + 1)
                                + " explains the steps & checks <carefully> in order.");
                    }
                    if (c == 1) {
                        cs.drawImage(picture, 72, 300, 180, 120);
                    }
                }
            }
            doc.getDocumentInformation().setTitle("Field Manual");
            doc.getDocumentInformation().setAuthor("Ops Team");
            doc.save(pdf.toFile());
        }
        Path epub = dir.resolve("manual.epub");
        OfficeConvert.convert(pdf, epub);
        parts = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(Files.readAllBytes(epub)))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                parts.put(e.getName(), zip.readAllBytes());
                if (parts.size() == 1) {
                    assertEquals(ZipEntry.STORED, e.getMethod());
                }
            }
        }
    }

    private static void text(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String s)
            throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    private static String part(String name) {
        byte[] b = parts.get(name);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void packageStartsWithItsMimetypeAndNamesItsRoot() {
        assertEquals("mimetype", parts.keySet().iterator().next());
        assertEquals("application/epub+zip", part("mimetype"));
        assertTrue(part("META-INF/container.xml").contains("full-path=\"OEBPS/content.opf\""));
    }

    @Test
    void everyMarkupPartIsWellFormedXml() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        int checked = 0;
        for (Map.Entry<String, byte[]> e : parts.entrySet()) {
            if (e.getKey().matches(".*\\.(xhtml|opf|ncx|xml)")) {
                f.newDocumentBuilder().parse(new ByteArrayInputStream(e.getValue()));
                checked++;
            }
        }
        assertTrue(checked >= 7, "checked " + checked);
    }

    @Test
    void chaptersFollowTheHeadingsInReadingOrder() {
        String opf = part("OEBPS/content.opf");
        assertTrue(opf.contains("<dc:title>Field Manual</dc:title>"), opf);
        assertTrue(opf.contains("<dc:creator>Ops Team</dc:creator>"), opf);
        List<String> spine = new ArrayList<>();
        Matcher m = Pattern.compile("<itemref idref=\"([^\"]+)\"").matcher(opf);
        while (m.find()) {
            spine.add(m.group(1));
        }
        assertEquals(List.of("ch1", "ch2", "ch3"), spine);
        for (int c = 0; c < CHAPTERS.length; c++) {
            String chapter = part("OEBPS/text/ch" + (c + 1) + ".xhtml");
            assertTrue(chapter.contains(CHAPTERS[c]), chapter);
            assertTrue(chapter.contains("Chapter " + (c + 1) + " body line 12"), chapter);
            assertFalse(chapter.contains("Chapter " + (c + 2) + " body line"), chapter);
            assertTrue(chapter.contains("steps &amp; checks &lt;carefully&gt;"), chapter);
        }
    }

    @Test
    void navigationListsEachChapter() {
        String nav = part("OEBPS/nav.xhtml");
        String ncx = part("OEBPS/toc.ncx");
        for (int c = 0; c < CHAPTERS.length; c++) {
            assertTrue(nav.contains("href=\"text/ch" + (c + 1) + ".xhtml#"), nav);
            assertTrue(nav.contains(CHAPTERS[c]), nav);
            assertTrue(ncx.contains(CHAPTERS[c]), ncx);
        }
        assertTrue(part("OEBPS/content.opf").contains("properties=\"nav\""));
    }

    @Test
    void longHeadingsAreCutWithoutBreakingEntities() throws Exception {
        Path pdf = dir.resolve("long.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, BOLD, 9, 72, 700, "A".repeat(197) + " & B < C");
                text(cs, REGULAR, 6, 72, 660, "Body text under the long heading.");
            }
            doc.save(pdf.toFile());
        }
        Path epub = dir.resolve("long.epub");
        OfficeConvert.convert(pdf, epub);
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(Files.readAllBytes(epub)))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                byte[] part = zip.readAllBytes();
                if (e.getName().matches(".*\\.(xhtml|opf|ncx|xml)")) {
                    f.newDocumentBuilder().parse(new ByteArrayInputStream(part));
                }
            }
        }
    }

    @Test
    void picturesAreFilesInTheManifest() {
        List<String> images = parts.keySet().stream().filter(n -> n.startsWith("OEBPS/images/p")).toList();
        assertEquals(1, images.size(), parts.keySet().toString());
        String file = images.get(0).substring("OEBPS/".length());
        assertTrue(part("OEBPS/content.opf").contains("href=\"" + file + "\""));
        assertTrue(part("OEBPS/text/ch2.xhtml").contains("src=\"../" + file + "\""));
        for (String name : parts.keySet()) {
            if (name.endsWith(".xhtml")) {
                assertFalse(part(name).contains("data:image"), name);
            }
        }
    }

    @Test
    void firstPageIsTheCoverPicture() throws IOException {
        byte[] cover = parts.get("OEBPS/images/cover.jpg");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(cover));
        assertEquals(PdfBook.COVER_SIDE, image.getHeight(), 1);
        assertEquals(Math.round(PdfBook.COVER_SIDE * 612f / 792f), image.getWidth(), 1);
        String opf = part("OEBPS/content.opf");
        assertTrue(opf.contains("href=\"images/cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\""), opf);
        assertTrue(opf.contains("<meta name=\"cover\" content=\"cover\"/>"), opf);
        assertFalse(opf.contains("<itemref idref=\"cover\""), opf);
    }

    @Test
    void landmarksStartAtTheFirstChapter() {
        assertTrue(part("OEBPS/nav.xhtml").contains("<a epub:type=\"bodymatter\" href=\"text/ch1.xhtml\">"));
    }

    private static Map<String, String> convertWith(EbookOptions book) throws IOException {
        Path epub = dir.resolve(book.device() + "-" + book.chapters() + ".epub");
        OfficeConvert.convert(dir.resolve("manual.pdf"), epub, OfficeConvert.Settings.defaults().ebook(book));
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(Files.readAllBytes(epub)))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                out.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    @Test
    void withoutChapterDetectionTheBookSplitsOnlyForLength() throws IOException {
        Map<String, String> book = convertWith(EbookOptions.defaults().chapters(false));
        assertEquals(List.of("OEBPS/text/ch1.xhtml"),
                book.keySet().stream().filter(n -> n.startsWith("OEBPS/text/")).toList());
        String nav = book.get("OEBPS/nav.xhtml");
        for (String chapter : CHAPTERS) {
            assertTrue(nav.contains(chapter), nav);
            assertTrue(book.get("OEBPS/text/ch1.xhtml").contains(chapter));
        }
    }

    @Test
    void kindleBooksSizeTextRelativeToTheReadersFont() throws IOException {
        Map<String, String> kindle = convertWith(EbookOptions.defaults().device(EbookOptions.Device.KINDLE));
        String css = kindle.get("OEBPS/styles/book.css");
        assertTrue(css.contains("serif;font-size:1em;"), css);
        assertTrue(css.contains(".s-Heading1{font-family:'Arial', 'Liberation Sans', 'Arimo', sans-serif;font-size:2em;"), css);
        for (Map.Entry<String, String> part : kindle.entrySet()) {
            if (part.getKey().endsWith(".xhtml") || part.getKey().endsWith(".css")) {
                assertFalse(Pattern.compile("(font-size|line-height):[0-9.]+pt").matcher(part.getValue()).find(),
                        part.getKey());
            }
        }
        String tablet = part("OEBPS/styles/book.css");
        assertTrue(tablet.contains(".s-Heading1{font-family:'Arial', 'Liberation Sans', 'Arimo', sans-serif;font-size:24pt;"), tablet);
    }
}
