package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EpubToPdfTest {

    @TempDir
    Path dir;

    private static byte[] png(Color c) throws IOException {
        BufferedImage img = new BufferedImage(60, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, 60, 80);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private Path epub(String name, Map<String, byte[]> entries) throws IOException {
        Path file = dir.resolve(name);
        try (OutputStream os = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(os)) {
            byte[] mimetype = utf8("application/epub+zip");
            ZipEntry first = new ZipEntry("mimetype");
            first.setMethod(ZipEntry.STORED);
            first.setSize(mimetype.length);
            CRC32 crc = new CRC32();
            crc.update(mimetype);
            first.setCrc(crc.getValue());
            zip.putNextEntry(first);
            zip.write(mimetype);
            zip.closeEntry();
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return file;
    }

    private static String chapter(String head, String body) {
        return "<?xml version='1.0' encoding='utf-8'?>\n<!DOCTYPE html>\n<html xmlns=\"http://www.w3.org/1999/xhtml\">"
                + "<head><title>t</title>" + head + "</head><body>" + body + "</body></html>";
    }

    private Map<String, byte[]> book() throws IOException {
        Map<String, byte[]> e = new LinkedHashMap<>();
        e.put("META-INF/container.xml", utf8("<?xml version=\"1.0\"?><container version=\"1.0\" "
                + "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles>"
                + "<rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>"
                + "</rootfiles></container>"));
        e.put("OEBPS/content.opf", utf8("<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" "
                + "version=\"3.0\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>The Test Book"
                + "</dc:title><dc:creator>A. Writer</dc:creator></metadata><manifest>"
                + "<item id=\"cover\" href=\"text/cover.xhtml\" media-type=\"application/xhtml+xml\"/>"
                + "<item id=\"c1\" href=\"text/one.xhtml\" media-type=\"application/xhtml+xml\"/>"
                + "<item id=\"c2\" href=\"text/two.xhtml\" media-type=\"application/xhtml+xml\"/>"
                + "<item id=\"img\" href=\"images/cover.png\" media-type=\"image/png\" properties=\"cover-image\"/>"
                + "<item id=\"fig\" href=\"images/figure.png\" media-type=\"image/png\"/>"
                + "<item id=\"css\" href=\"styles/book.css\" media-type=\"text/css\"/>"
                + "</manifest><spine><itemref idref=\"cover\"/><itemref idref=\"c1\"/><itemref idref=\"c2\"/></spine>"
                + "</package>"));
        e.put("OEBPS/text/cover.xhtml", utf8(chapter("<meta name=\"calibre:cover\" content=\"true\"/>"
                + "<style>body { text-align: center }</style>",
                "<div><svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" "
                        + "viewBox=\"0 0 600 800\" width=\"100%\" height=\"100%\"><image width=\"600\" height=\"800\" "
                        + "xlink:href=\"../images/cover.png\"/></svg></div>")));
        e.put("OEBPS/text/one.xhtml", utf8(chapter("<link rel=\"stylesheet\" href=\"../styles/book.css\"/>",
                "<h1 id=\"start\">Chapter One</h1><p>First chapter opening line.<a id=\"page2\"/> Same paragraph "
                        + "continues.</p><p class=\"secret\">Hidden by the book style sheet</p>"
                        + "<p><img src=\"../images/figure.png\" alt=\"figure\"/></p>"
                        + "<p><a href=\"two.xhtml#later\">Jump to chapter two</a></p>")));
        e.put("OEBPS/text/two.xhtml", utf8(chapter("<link rel=\"stylesheet\" href=\"../styles/book.css\"/>",
                "<h1 id=\"later\">Chapter Two</h1><p>Second chapter closing line.</p>")));
        e.put("OEBPS/styles/book.css", utf8(".secret { display: none }"));
        e.put("OEBPS/images/cover.png", png(Color.RED));
        e.put("OEBPS/images/figure.png", png(Color.BLUE));
        return e;
    }

    @Test
    void bookReadsInSpineOrderWithItsPicturesAndStyles() throws IOException {
        Path in = epub("book.epub", book());
        Path out = dir.resolve("book.pdf");
        OfficeToPdf.Result r = OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            int one = text.indexOf("Chapter One");
            int two = text.indexOf("Chapter Two");
            assertTrue(one >= 0 && two > one, text);
            assertTrue(text.contains("Same paragraph continues"), text);
            assertFalse(text.contains("Hidden by the book style sheet"), text);
            assertEquals(3, doc.getNumberOfPages(), r.toString());
            assertEquals(1, images(doc.getPage(0)));
            assertEquals(1, images(doc.getPage(1)));
            assertEquals("The Test Book", doc.getDocumentInformation().getTitle());
        }
    }

    @Test
    void tableOfContentsFollowsTheBookWithPageNumbersAndLinks() throws IOException {
        Path in = epub("contents.epub", book());
        Path out = dir.resolve("contents.pdf");
        OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)).tableOfContents(true));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(4, doc.getNumberOfPages());
            String contents = pageText(doc, 4);
            assertTrue(contents.contains("Contents"), contents);
            assertTrue(contents.matches("(?s).*Chapter One\\s*2\\s.*Chapter Two\\s*3\\s.*"), contents);
            assertEquals(List.of(1, 2), linkTargets(doc, 3));
            List<String> bookmarks = new ArrayList<>();
            doc.getDocumentCatalog().getDocumentOutline().children().forEach(i -> bookmarks.add(i.getTitle()));
            assertEquals(List.of("Chapter One", "Chapter Two", "Contents"), bookmarks);
        }
    }

    @Test
    void pageNumbersRunAlongTheFoot() throws IOException {
        Path in = epub("numbered.epub", book());
        Path out = dir.resolve("numbered.pdf");
        OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)).pageNumbers(true));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(3, doc.getNumberOfPages());
            for (int p = 1; p <= 3; p++) {
                String text = pageText(doc, p);
                assertTrue(List.of(text.split("\\R")).contains(Integer.toString(p)), text);
            }
        }
    }

    @Test
    void everyFontIsEmbedded() throws IOException {
        Path in = epub("fonts.epub", book());
        Path out = dir.resolve("fonts.pdf");
        OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)).tableOfContents(true)
                .pageNumbers(true));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            int fonts = 0;
            for (PDPage page : doc.getPages()) {
                for (COSName name : page.getResources().getFontNames()) {
                    PDFont font = page.getResources().getFont(name);
                    assertTrue(font.isEmbedded(), font.getName());
                    fonts++;
                }
            }
            assertTrue(fonts > 0);
        }
    }

    private static List<Integer> linkTargets(PDDocument doc, int page) throws IOException {
        List<Integer> targets = new ArrayList<>();
        for (PDAnnotation a : doc.getPage(page).getAnnotations()) {
            if (a instanceof PDAnnotationLink link) {
                PDDestination d = link.getDestination();
                if (d == null && link.getAction() instanceof PDActionGoTo go) {
                    d = go.getDestination();
                }
                PDPageDestination target = d instanceof PDNamedDestination named
                        ? doc.getDocumentCatalog().findNamedDestinationPage(named) : (PDPageDestination) d;
                targets.add(target == null ? -1 : doc.getPages().indexOf(target.getPage()));
            }
        }
        return targets;
    }

    @Test
    void linksBetweenChaptersLeadToTheirTarget() throws IOException {
        Path in = epub("linked.epub", book());
        Path out = dir.resolve("linked.pdf");
        OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(List.of(2), linkTargets(doc, 1));
        }
    }

    @Test
    void percentEncodedFragmentsLeadToTheirTarget() throws IOException {
        Map<String, byte[]> book = book();
        book.put("OEBPS/text/one.xhtml", utf8(chapter("", "<h1>Chapter One</h1><p>Opening line.</p>"
                + "<p><a href=\"two.xhtml#caf%C3%A9\">Jump to chapter two</a></p>")));
        book.put("OEBPS/text/two.xhtml", utf8(chapter("", "<h1>Chapter Two</h1><p>Second chapter line.</p>"
                + "<p><a href=\"#fin%20de\">Down to the end</a></p><p style=\"page-break-before:always\" "
                + "id=\"caf\u00e9\">Landing paragraph.</p><p style=\"page-break-before:always\" id=\"fin de\">"
                + "The end.</p>")));
        Path out = dir.resolve("encoded.pdf");
        OfficeToPdf.convert(epub("encoded.epub", book), out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertTrue(pageText(doc, 4).contains("Landing paragraph"), pageText(doc, 4));
            assertTrue(pageText(doc, 5).contains("The end"), pageText(doc, 5));
            assertEquals(List.of(3), linkTargets(doc, 1));
            assertEquals(List.of(4), linkTargets(doc, 2));
        }
    }

    private Map<String, byte[]> single(byte[] chapter, String uid, Map<String, byte[]> extra, String manifest) {
        Map<String, byte[]> e = new LinkedHashMap<>();
        e.put("META-INF/container.xml", utf8("<?xml version=\"1.0\"?><container version=\"1.0\" "
                + "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles>"
                + "<rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>"
                + "</rootfiles></container>"));
        e.put("OEBPS/content.opf", utf8("<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" "
                + "version=\"3.0\" unique-identifier=\"uid\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                + "<dc:identifier id=\"uid\">" + uid + "</dc:identifier><dc:title>T</dc:title></metadata><manifest>"
                + "<item id=\"c\" href=\"c.xhtml\" media-type=\"application/xhtml+xml\"/>" + manifest
                + "</manifest><spine><itemref idref=\"c\"/></spine></package>"));
        e.put("OEBPS/c.xhtml", chapter);
        e.putAll(extra);
        return e;
    }

    private String converted(String name, Map<String, byte[]> book) throws IOException {
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.convert(epub(name + ".epub", book), out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            StringBuilder fonts = new StringBuilder();
            for (COSName n : doc.getPage(0).getResources().getFontNames()) {
                fonts.append(doc.getPage(0).getResources().getFont(n).getName()).append(' ');
            }
            return new PDFTextStripper().getText(doc) + "\nFONTS " + fonts;
        }
    }

    @Test
    void chaptersReadInTheEncodingTheyDeclare() throws IOException {
        String latin = converted("latin1", single(("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><html "
                + "xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>caf\u00e9 na\u00efve \u00fcber</p></body></html>")
                .getBytes(StandardCharsets.ISO_8859_1), "x", Map.of(), ""));
        assertTrue(latin.contains("caf\u00e9 na\u00efve \u00fcber"), latin);
        String windows = converted("cp1252", single(("<?xml version=\"1.0\" encoding=\"windows-1252\"?><html "
                + "xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>price \u20ac5 \u201cquoted\u201d</p></body></html>")
                .getBytes(java.nio.charset.Charset.forName("windows-1252")), "x", Map.of(), ""));
        assertTrue(windows.contains("price \u20ac5 \u201cquoted\u201d"), windows);
        ByteArrayOutputStream utf16 = new ByteArrayOutputStream();
        utf16.write(0xFF);
        utf16.write(0xFE);
        utf16.writeBytes(("<?xml version=\"1.0\" encoding=\"UTF-16\"?><html xmlns=\"http://www.w3.org/1999/xhtml\">"
                + "<body><p>\u00e9l\u00e8ve wide</p></body></html>").getBytes(StandardCharsets.UTF_16LE));
        String wide = converted("utf16", single(utf16.toByteArray(), "x", Map.of(), ""));
        assertTrue(wide.contains("\u00e9l\u00e8ve wide"), wide);
    }

    @Test
    void htmlzBooksConvertWithTheirPictures() throws IOException {
        Path in = dir.resolve("book.htmlz");
        try (OutputStream os = Files.newOutputStream(in); ZipOutputStream zip = new ZipOutputStream(os)) {
            zip.putNextEntry(new ZipEntry("index.html"));
            zip.write(utf8("<html><head><link rel=\"stylesheet\" href=\"style.css\"></head><body><h1>Htmlz Book</h1>"
                    + "<p class=\"x\">Zipped page text</p><img src=\"images/p.png\" alt=\"\"></body></html>"));
            zip.putNextEntry(new ZipEntry("style.css"));
            zip.write(utf8(".x{font-weight:bold}"));
            zip.putNextEntry(new ZipEntry("images/p.png"));
            zip.write(png(Color.GREEN));
        }
        Path out = dir.resolve("htmlz.pdf");
        OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Htmlz Book") && text.contains("Zipped page text"), text);
            assertEquals(1, images(doc.getPage(0)));
        }
    }

    @Test
    void obfuscatedFontsAreReadBack() throws IOException {
        byte[] font;
        try (var in = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            font = in.readAllBytes();
        }
        String uid = "urn:uuid:0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";
        byte[] chapter = utf8("<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><style>"
                + "@font-face{font-family:'ZedObf';src:url(f.ttf)} p{font-family:'ZedObf',serif}</style></head>"
                + "<body><p>Obfuscated quick brown fox</p></body></html>");
        byte[][] keys = {sha1(uid), java.util.HexFormat.of().parseHex("0f1e2d3c4b5a69788796a5b4c3d2e1f0")};
        String[] methods = {"http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC"};
        for (int k = 0; k < 2; k++) {
            byte[] hidden = font.clone();
            for (int i = 0; i < (k == 0 ? 1040 : 1024); i++) {
                hidden[i] ^= keys[k][i % keys[k].length];
            }
            String encryption = "<?xml version=\"1.0\"?><encryption xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" "
                    + "xmlns:enc=\"http://www.w3.org/2001/04/xmlenc#\"><enc:EncryptedData><enc:EncryptionMethod Algorithm=\""
                    + methods[k] + "\"/><enc:CipherData><enc:CipherReference URI=\"OEBPS/f.ttf\"/></enc:CipherData>"
                    + "</enc:EncryptedData></encryption>";
            String text = converted("font" + k, single(chapter, uid, Map.of("OEBPS/f.ttf", hidden,
                    "META-INF/encryption.xml", utf8(encryption)),
                    "<item id=\"f\" href=\"f.ttf\" media-type=\"application/vnd.ms-opentype\"/>"));
            assertTrue(text.contains("Obfuscated quick brown fox"), text);
            assertTrue(text.substring(text.indexOf("FONTS")).contains("LiberationSans"), text);
        }
    }

    private static byte[] sha1(String s) {
        try {
            return java.security.MessageDigest.getInstance("SHA-1").digest(utf8(s));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pageText(PDDocument doc, int page) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(doc);
    }

    @Test
    void booksInsideOtherZipsNeedPackagesAllowed() throws IOException {
        OfficeToPdf.Options defaults = OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2));
        Path zipped = epub("book.zip", book());
        IOException named = assertThrows(IOException.class,
                () -> OfficeToPdf.convert(zipped, dir.resolve("zipped.pdf"), defaults));
        assertTrue(named.getMessage().contains("--allow-packages"), named.getMessage());
        assertFalse(Files.exists(dir.resolve("zipped.pdf")));
        IOException streamed = assertThrows(IOException.class, () -> {
            try (var in = Files.newInputStream(zipped)) {
                OfficeToPdf.convert(in, OfficeToPdf.Format.HTML, new ByteArrayOutputStream(), defaults);
            }
        });
        assertTrue(streamed.getMessage().contains("--allow-packages"), streamed.getMessage());
        ByteArrayOutputStream declared = new ByteArrayOutputStream();
        try (var in = Files.newInputStream(zipped)) {
            OfficeToPdf.convert(in, OfficeToPdf.Format.EPUB, declared, defaults);
        }
        try (PDDocument doc = Loader.loadPDF(declared.toByteArray())) {
            assertTrue(new PDFTextStripper().getText(doc).contains("Chapter Two"));
        }
        assertEquals(OfficeToPdf.Format.EPUB, OfficeToPdf.Format.of(dir.resolve("book.epub")));
        OfficeToPdf.convert(zipped, dir.resolve("allowed.pdf"), defaults.allowPackages(true));
        try (PDDocument doc = Loader.loadPDF(dir.resolve("allowed.pdf").toFile())) {
            assertTrue(new PDFTextStripper().getText(doc).contains("Chapter Two"));
        }
    }

    @Test
    void chapterStylesStayInTheirChapter() throws IOException {
        Map<String, byte[]> e = book();
        e.put("OEBPS/text/one.xhtml", utf8(chapter("<style>body { text-align: right } p { color: red }</style>",
                "<p>Short line in chapter one</p>")));
        Path in = epub("styled.epub", e);
        Path out = dir.resolve("styled.pdf");
        OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            float right = firstX(doc, 1);
            float left = firstX(doc, 2);
            assertTrue(right > left + 100, right + " vs " + left);
        }
    }

    @Test
    void sheetLinkedByOneChapterStaysInThatChapter() throws IOException {
        Map<String, byte[]> e = book();
        String shared = "<link rel=\"stylesheet\" href=\"../styles/book.css\"/>";
        e.put("OEBPS/text/one.xhtml", utf8(chapter(shared, "<p class=\"hide\">Shown in chapter one</p>")));
        e.put("OEBPS/text/two.xhtml", utf8(chapter(shared + "<link rel=\"stylesheet\" href=\"../styles/two.css\"/>",
                "<p class=\"hide\">Hidden in chapter two</p><p>Rest of two</p>")));
        e.put("OEBPS/styles/two.css", utf8(".hide { display: none }"));
        Path in = epub("scoped.epub", e);
        Path out = dir.resolve("scoped.pdf");
        OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Shown in chapter one") && text.contains("Rest of two"), text);
            assertFalse(text.contains("Hidden in chapter two"), text);
        }
    }

    @Test
    void inlinedSheetUrlsStayRelativeToTheSheet() {
        assertEquals("a{background:url(\"/OEBPS/images/a.png\")} b{src:url(data:x)} c{x:url(\"/OEBPS/f.otf\")}",
                EpubBook.packageUrls("a{background:url('../images/a.png')} b{src:url(data:x)} c{x:url(../f.otf)}",
                        "OEBPS/styles/s.css"));
    }

    @Test
    void unclosedSheetUrlsStayLinear() {
        String css = "a{b:url(x" + " ".repeat(400_000) + "}" + "url(".repeat(100_000) + "c{d:url( 'e(1).png' )}";
        String out = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> EpubBook.packageUrls(css, "OEBPS/s.css"));
        assertTrue(out.endsWith("c{d:url(\"/OEBPS/e(1).png\")}"), out.substring(out.length() - 40));
    }

    @Test
    void bookWithoutAReadingOrderIsRefused() throws IOException {
        Map<String, byte[]> e = book();
        e.put("OEBPS/content.opf", utf8("<package xmlns=\"http://www.idpf.org/2007/opf\"><manifest/><spine/></package>"));
        Path in = epub("empty.epub", e);
        IOException ex = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("empty.pdf")));
        assertTrue(ex.getMessage().contains("spine"), ex.getMessage());
    }

    private static byte[] encryption(String algorithm, String uri) {
        return utf8("<?xml version=\"1.0\"?><encryption xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" "
                + "xmlns:enc=\"http://www.w3.org/2001/04/xmlenc#\"><enc:EncryptedData><enc:EncryptionMethod Algorithm=\""
                + algorithm + "\"/><enc:CipherData><enc:CipherReference URI=\"" + uri + "\"/></enc:CipherData>"
                + "</enc:EncryptedData></encryption>");
    }

    @Test
    void encryptedChaptersAreRefusedAsDrm() throws IOException {
        Map<String, byte[]> e = book();
        byte[] cipher = new byte[512];
        new java.util.Random(7).nextBytes(cipher);
        e.put("OEBPS/text/one.xhtml", cipher);
        e.put("META-INF/encryption.xml", encryption("http://www.w3.org/2001/04/xmlenc#aes128-cbc",
                "OEBPS/text/one.xhtml"));
        Path in = epub("adept.epub", e);
        Path out = dir.resolve("adept.pdf");
        IOException ex = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, out,
                OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2))));
        assertEquals(MobiBook.DRM, ex.getMessage());
        assertFalse(Files.exists(out));
    }

    @Test
    void rightsManagedBooksAreRefusedAsDrm() throws IOException {
        Map<String, byte[]> e = book();
        e.put("META-INF/rights.xml", utf8("<?xml version=\"1.0\"?><adept:rights xmlns:adept=\"http://ns.adobe.com/adept\"/>"));
        Path in = epub("rights.epub", e);
        IOException ex = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("rights.pdf"),
                OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2))));
        assertEquals(MobiBook.DRM, ex.getMessage());
    }

    @Test
    void encryptedPicturesAloneDoNotRefuseTheBook() throws IOException {
        Map<String, byte[]> e = book();
        e.put("META-INF/encryption.xml", encryption("http://www.w3.org/2001/04/xmlenc#aes128-cbc",
                "OEBPS/images/figure.png"));
        Path out = dir.resolve("picture.pdf");
        OfficeToPdf.convert(epub("picture.epub", e), out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertTrue(new PDFTextStripper().getText(doc).contains("Chapter Two"));
        }
    }

    @Test
    void contentsPagesCountAgainstThePageLimit() throws IOException {
        Path in = epub("limited.epub", book());
        Path out = dir.resolve("limited.pdf");
        OfficeToPdf.Options options = OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)).tableOfContents(true);
        OfficeToPdf.Result r = OfficeToPdf.convert(in, out, options.maxPages(3));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(3, doc.getNumberOfPages());
            assertEquals(3, r.pages());
            assertTrue(r.pageLimitReached(), r.toString());
            assertTrue(r.truncated(), r.toString());
        }
        OfficeToPdf.Result room = OfficeToPdf.convert(in, out, options.maxPages(4));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(4, doc.getNumberOfPages());
            assertEquals(4, room.pages());
            assertFalse(room.pageLimitReached(), room.toString());
        }
    }

    @Test
    void selfClosedElementsDoNotSwallowWhatFollows() {
        String html = EpubBook.xhtmlAsHtml("<?xml version='1.0'?><p>a<a id=\"x\"/>b<br/>c<div class=\"d\"/>e</p>");
        assertEquals("<p>a<a id=\"x\"></a>b<br>c<div class=\"d\"></div>e</p>", html);
    }

    @Test
    void chapterSelectorsAreScoped() throws IOException {
        assertEquals("#epub-c1{color:red}\n", EpubBook.scoped("body{color:red}", 1, 0));
        assertEquals("#epub-c1 p, #epub-c1.x{a:b}\n", EpubBook.scoped("p, body.x{a:b}", 1, 0));
        assertEquals("@media print{#epub-c1 h1{a:b}\n}\n", EpubBook.scoped("@media print{h1{a:b}}", 1, 0));
        assertEquals("", EpubBook.scoped("@page{margin:0}", 1, 0));
        assertEquals("#epub-c2 #epub-c2-intro p{a:b}\n", EpubBook.scoped("#intro p{a:b}", 2, 0));
        assertEquals("#epub-c1 p{a:b}\n", EpubBook.scoped("/* note */p{a:b}/* open", 1, 0));
    }

    @Test
    void unclosedCommentsStayLinear() throws IOException {
        String css = "/*a".repeat(400_000) + "p{color:red}";
        long start = System.nanoTime();
        EpubBook.scoped(css, 1, 0);
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000);
    }

    @Test
    void chaptersNamedTwiceByCaseAreReadOnce() throws IOException {
        Map<String, byte[]> e = book();
        String opf = new String(e.get("OEBPS/content.opf"), StandardCharsets.UTF_8)
                .replace("<itemref idref=\"c2\"/>", "<itemref idref=\"c2\"/><itemref idref=\"c2b\"/>")
                .replace("</manifest>", "<item id=\"c2b\" href=\"text/TWO.xhtml\" media-type=\"application/xhtml+xml\"/>"
                        + "</manifest>");
        e.put("OEBPS/content.opf", utf8(opf));
        Path in = epub("twice.epub", e);
        Path out = dir.resolve("twice.pdf");
        OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            assertEquals(text.indexOf("Chapter Two"), text.lastIndexOf("Chapter Two"), text);
        }
    }

    @Test
    void aBrokenSpineReferenceCannotReturnAPartialBook() throws IOException {
        for (String reference : List.of("missing", "invalid")) {
            Map<String, byte[]> entries = book();
            String opf = new String(entries.get("OEBPS/content.opf"), StandardCharsets.UTF_8);
            if (reference.equals("missing")) {
                opf = opf.replace("</spine>", "<itemref idref='absent'/></spine>");
            } else {
                opf = opf.replace("text/two.xhtml", "https://example.invalid/chapter.xhtml");
            }
            entries.put("OEBPS/content.opf", utf8(opf));
            try (HtmlPackage pkg = HtmlPackage.open(epub(reference + ".epub", entries))) {
                assertThrows(IOException.class, () -> EpubBook.read(pkg));
            }
        }
    }

    @Test
    void anOversizedSpineCannotSilentlyDropItsLastChapters() throws IOException {
        Map<String, byte[]> entries = book();
        StringBuilder opf = new StringBuilder("<package><manifest>");
        for (int i = 0; i <= EpubBook.MAX_CHAPTERS; i++) {
            opf.append("<item id='c").append(i).append("' href='c").append(i).append(".xhtml'/>");
        }
        opf.append("</manifest><spine>");
        for (int i = 0; i <= EpubBook.MAX_CHAPTERS; i++) {
            opf.append("<itemref idref='c").append(i).append("'/>");
        }
        opf.append("</spine></package>");
        entries.put("OEBPS/content.opf", utf8(opf.toString()));
        Path input = epub("too-many-chapters.epub", entries);
        try (HtmlPackage pkg = HtmlPackage.open(input)) {
            IOException error = assertThrows(IOException.class, () -> EpubBook.read(pkg));
            assertTrue(error.getMessage().contains("5000 chapters"), error.getMessage());
        }
    }

    private static int images(PDPage page) throws IOException {
        int n = 0;
        for (var name : page.getResources().getXObjectNames()) {
            if (page.getResources().getXObject(name) instanceof PDImageXObject) {
                n++;
            }
        }
        return n;
    }

    private static float firstX(PDDocument doc, int pageIndex) throws IOException {
        float[] x = {-1};
        PDFTextStripper s = new PDFTextStripper() {
            @Override
            protected void processTextPosition(org.apache.pdfbox.text.TextPosition t) {
                if (x[0] < 0 && !t.getUnicode().isBlank()) {
                    x[0] = t.getXDirAdj();
                }
            }
        };
        s.setStartPage(pageIndex + 1);
        s.setEndPage(pageIndex + 1);
        s.getText(doc);
        return x[0];
    }

    private Map<String, byte[]> notedBook() throws IOException {
        Map<String, byte[]> book = book();
        book.put("OEBPS/content.opf", utf8(new String(book.get("OEBPS/content.opf"), StandardCharsets.UTF_8)
                .replace("<item id=\"c2\"", "<item id=\"n1\" href=\"text/n1.xhtml\" media-type=\"application/xhtml+xml\"/>"
                        + "<item id=\"n2\" href=\"text/n2.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"c2\"")
                .replace("<itemref idref=\"c2\"/>", "<itemref idref=\"n1\"/><itemref idref=\"n2\"/><itemref idref=\"c2\"/>")));
        book.put("OEBPS/text/n1.xhtml", utf8(chapter("", "<div><p>[1] The first short note.</p></div>")));
        book.put("OEBPS/text/n2.xhtml", utf8(chapter("", "<aside epub:type=\"footnote\"><p>[2] The second note, "
                + "marked as a footnote.</p></aside>")));
        return book;
    }

    @Test
    void shortNotesInTheirOwnSpineFilesDoNotEachTakeAPage() throws IOException {
        Path out = dir.resolve("notes.pdf");
        OfficeToPdf.convert(epub("notes.epub", notedBook()), out,
                OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(3, doc.getNumberOfPages());
            String second = pageText(doc, 2);
            assertTrue(second.contains("Chapter One") && second.contains("The first short note")
                    && second.contains("The second note"), second);
            assertTrue(pageText(doc, 3).contains("Chapter Two"));
        }
    }

    @Test
    void aLongChapterWithAFootnoteStillStartsANewPage() throws IOException {
        Map<String, byte[]> book = book();
        book.put("OEBPS/text/two.xhtml", utf8(chapter("", "<h1>Chapter Two</h1><p>"
                + "Second chapter running text that fills more than a short note would. ".repeat(12)
                + "</p><aside epub:type=\"footnote\"><p>[1] A single footnote.</p></aside>")));
        Path out = dir.resolve("footnoted.pdf");
        OfficeToPdf.convert(epub("footnoted.epub", book), out,
                OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertFalse(pageText(doc, 2).contains("Chapter Two"));
            assertTrue(pageText(doc, 3).contains("Chapter Two"));
        }
    }

    @Test
    void aBooksNarrowPageMarginsWidenToAnInch() throws IOException {
        Map<String, byte[]> book = book();
        book.put("OEBPS/styles/book.css", utf8(".secret { display: none } @page { margin: 5pt 5pt 5pt 90pt }"));
        Path out = dir.resolve("margins.pdf");
        OfficeToPdf.convert(epub("margins.epub", book), out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            List<float[]> at = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<org.apache.pdfbox.text.TextPosition> positions) {
                    for (org.apache.pdfbox.text.TextPosition t : positions) {
                        at.add(new float[] {t.getXDirAdj(), t.getYDirAdj() - t.getHeightDir(), t.getXDirAdj() + t.getWidthDirAdj()});
                    }
                }
            };
            stripper.setStartPage(2);
            stripper.setEndPage(2);
            stripper.getText(doc);
            float width = doc.getPage(1).getMediaBox().getWidth();
            assertFalse(at.isEmpty());
            for (float[] p : at) {
                assertTrue(p[1] >= 70, "top " + p[1]);
                assertTrue(p[0] >= 89, "left " + p[0]);
                assertTrue(p[2] <= width - 70, "right " + p[2]);
            }
        }
    }

    private static int[] redBox(PDDocument doc, int page) throws IOException {
        BufferedImage img = new org.apache.pdfbox.rendering.PDFRenderer(doc).renderImage(page, 1f);
        int[] box = {-1, img.getWidth(), -1, img.getHeight()};
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                if ((rgb >> 16 & 0xFF) > 200 && (rgb >> 8 & 0xFF) < 60 && (rgb & 0xFF) < 60) {
                    box[0] = box[0] < 0 ? y : box[0];
                    box[1] = Math.min(box[1], x);
                    box[2] = Math.max(box[2], x);
                }
            }
        }
        return box;
    }

    @Test
    void theCoverKeepsTheBooksOwnNarrowMargins() throws IOException {
        Map<String, byte[]> book = book();
        book.put("OEBPS/styles/book.css", utf8(".secret { display: none } @page { margin: 5pt }"));
        book.put("OEBPS/text/cover.xhtml", utf8(chapter("<meta name=\"calibre:cover\" content=\"true\"/>"
                + "<link rel=\"stylesheet\" href=\"../styles/book.css\"/>",
                "<div><img src=\"../images/cover.png\" alt=\"\"/></div>")));
        Path out = dir.resolve("covered.pdf");
        OfficeToPdf.convert(epub("covered.epub", book), out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            int[] box = redBox(doc, 0);
            float width = doc.getPage(0).getMediaBox().getWidth();
            assertTrue(box[0] >= 0 && box[0] < 20, "cover starts at " + box[0]);
            assertTrue(box[1] < 20 && box[2] > width - 20, "cover spans " + box[1] + " to " + box[2]);
            assertTrue(pageText(doc, 2).contains("Chapter One"));
            List<Float> tops = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<org.apache.pdfbox.text.TextPosition> positions) {
                    tops.add(positions.getFirst().getYDirAdj() - positions.getFirst().getHeightDir());
                }
            };
            stripper.setStartPage(2);
            stripper.setEndPage(2);
            stripper.getText(doc);
            assertTrue(!tops.isEmpty() && tops.getFirst() >= 70, tops.toString());
            assertEquals(3, doc.getNumberOfPages());
        }
    }

    @Test
    void aCoverOnlyNamedInTheManifestKeepsTheBooksOwnNarrowMargins() throws IOException {
        Map<String, byte[]> book = book();
        book.put("OEBPS/styles/book.css", utf8(".secret { display: none } @page { margin: 5pt }"));
        book.put("OEBPS/content.opf", utf8(new String(book.get("OEBPS/content.opf"), StandardCharsets.UTF_8)
                .replace("<itemref idref=\"cover\"/>", "")));
        Path out = dir.resolve("listed.pdf");
        OfficeToPdf.convert(epub("listed.epub", book), out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            int[] box = redBox(doc, 0);
            assertTrue(box[0] >= 0 && box[0] < 20, "cover starts at " + box[0]);
            assertTrue(pageText(doc, 2).contains("Chapter One"), pageText(doc, 2));
        }
    }

    @Test
    void aBookThatSizesItsOwnPaperKeepsItsMargins() throws IOException {
        Map<String, byte[]> book = book();
        book.put("OEBPS/styles/book.css", utf8(".secret { display: none } @page { size: 80mm 160mm; margin: 5pt 5mm }"));
        Path out = dir.resolve("receipt.pdf");
        OfficeToPdf.convert(epub("receipt.epub", book), out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            List<Float> left = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<org.apache.pdfbox.text.TextPosition> positions) {
                    left.add(positions.getFirst().getXDirAdj());
                }
            };
            stripper.setStartPage(2);
            stripper.setEndPage(2);
            stripper.getText(doc);
            assertEquals(227, doc.getPage(1).getMediaBox().getWidth(), 1);
            assertFalse(left.isEmpty());
            assertTrue(left.stream().allMatch(x -> x < 30), left.toString());
        }
    }

    private Path linedBook(String name, int lines, String closing) throws IOException {
        Map<String, byte[]> book = book();
        book.put("OEBPS/styles/book.css", utf8(".secret { display: none } @page { margin: 5pt }"));
        StringBuilder body = new StringBuilder("<h1>Chapter One</h1>");
        for (int i = 1; i <= lines; i++) {
            body.append("<p>Line ").append(i).append(" of the first chapter.</p>");
        }
        body.append("<p>").append(closing).append("</p>");
        book.put("OEBPS/text/one.xhtml", utf8(chapter("<link rel=\"stylesheet\" href=\"../styles/book.css\"/>", body.toString())));
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.convert(epub(name + ".epub", book), out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        return out;
    }

    @Test
    void aChaptersLastLineStaysOnItsPageInsteadOfFillingOneAlone() throws IOException {
        int full;
        try (PDDocument doc = Loader.loadPDF(linedBook("probe", 80, "Probe closing").toFile())) {
            String second = pageText(doc, 2);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Line (\\d+) of").matcher(second);
            full = 0;
            while (m.find()) {
                full = Integer.parseInt(m.group(1));
            }
        }
        assertTrue(full > 10, "lines on the first page " + full);
        try (PDDocument doc = Loader.loadPDF(linedBook("tail", full, "Yours sincerely,").toFile())) {
            String second = pageText(doc, 2);
            assertTrue(second.contains("Yours sincerely,"), second);
            assertTrue(pageText(doc, 3).contains("Chapter Two"));
            assertEquals(3, doc.getNumberOfPages());
            float height = doc.getPage(1).getMediaBox().getHeight();
            float[] bottom = {0};
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<org.apache.pdfbox.text.TextPosition> positions) {
                    for (org.apache.pdfbox.text.TextPosition t : positions) {
                        bottom[0] = Math.max(bottom[0], t.getYDirAdj());
                    }
                }
            };
            stripper.setStartPage(2);
            stripper.setEndPage(2);
            stripper.getText(doc);
            assertTrue(bottom[0] > height - 72 && bottom[0] <= height - 24, "last baseline " + bottom[0]);
        }
    }

    @Test
    void aChapterTailTooLongToAbsorbStillBreaks() throws IOException {
        int full;
        try (PDDocument doc = Loader.loadPDF(linedBook("probe", 80, "Probe closing").toFile())) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Line (\\d+) of").matcher(pageText(doc, 2));
            full = 0;
            while (m.find()) {
                full = Integer.parseInt(m.group(1));
            }
        }
        try (PDDocument doc = Loader.loadPDF(linedBook("long", full + 3, "Yours sincerely,").toFile())) {
            assertTrue(pageText(doc, 3).contains("Yours sincerely,"));
            assertTrue(pageText(doc, 4).contains("Chapter Two"));
        }
    }
}
