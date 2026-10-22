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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
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
    void bookWithoutAReadingOrderIsRefused() throws IOException {
        Map<String, byte[]> e = book();
        e.put("OEBPS/content.opf", utf8("<package xmlns=\"http://www.idpf.org/2007/opf\"><manifest/><spine/></package>"));
        Path in = epub("empty.epub", e);
        IOException ex = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("empty.pdf")));
        assertTrue(ex.getMessage().contains("spine"), ex.getMessage());
    }

    @Test
    void selfClosedElementsDoNotSwallowWhatFollows() {
        String html = EpubBook.xhtmlAsHtml("<?xml version='1.0'?><p>a<a id=\"x\"/>b<br/>c<div class=\"d\"/>e</p>");
        assertEquals("<p>a<a id=\"x\"></a>b<br>c<div class=\"d\"></div>e</p>", html);
    }

    @Test
    void chapterSelectorsAreScoped() {
        assertEquals("#epub-c1{color:red}\n", EpubBook.scoped("body{color:red}", 1, 0));
        assertEquals("#epub-c1 p, #epub-c1.x{a:b}\n", EpubBook.scoped("p, body.x{a:b}", 1, 0));
        assertEquals("@media print{#epub-c1 h1{a:b}\n}\n", EpubBook.scoped("@media print{h1{a:b}}", 1, 0));
        assertEquals("", EpubBook.scoped("@page{margin:0}", 1, 0));
        assertEquals("#epub-c2 #epub-c2-intro p{a:b}\n", EpubBook.scoped("#intro p{a:b}", 2, 0));
        assertEquals("#epub-c1 p{a:b}\n", EpubBook.scoped("/* note */p{a:b}/* open", 1, 0));
    }

    @Test
    void unclosedCommentsStayLinear() {
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
}
