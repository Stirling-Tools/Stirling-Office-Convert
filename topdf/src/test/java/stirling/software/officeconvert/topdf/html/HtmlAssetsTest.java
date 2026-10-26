package stirling.software.officeconvert.topdf.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

import stirling.software.officeconvert.topdf.HtmlAssets;
import stirling.software.officeconvert.topdf.OfficeToPdf;

class HtmlAssetsTest {

    @TempDir
    Path dir;

    record Pdf(int pages, String text, int images, OfficeToPdf.Result result) {}

    private static byte[] png(int w, int h, Color c) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static final String SVG = "<svg xmlns='http://www.w3.org/2000/svg' width='80' height='40'>"
            + "<rect width='80' height='40' fill='#2a6'/></svg>";

    private Path zip(String name, Map<String, byte[]> entries) throws IOException {
        Path z = dir.resolve(name);
        try (OutputStream os = Files.newOutputStream(z); ZipOutputStream out = new ZipOutputStream(os)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return z;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private Pdf convert(Path in, OfficeToPdf.Options options) throws IOException {
        Path out = dir.resolve(in.getFileName() + ".pdf");
        OfficeToPdf.Result r = OfficeToPdf.convert(in, out, options);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            int images = 0;
            for (PDPage page : doc.getPages()) {
                for (var n : page.getResources().getXObjectNames()) {
                    if (page.getResources().getXObject(n) instanceof PDImageXObject) {
                        images++;
                    }
                }
            }
            return new Pdf(doc.getNumberOfPages(), new PDFTextStripper().getText(doc), images, r);
        }
    }

    private static OfficeToPdf.Options options() {
        return OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2));
    }

    private static OfficeToPdf.Options packages() {
        return options().allowPackages(true);
    }

    @Test
    void urlsInALinkedStyleSheetResolveAgainstTheSheet() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("docs/index.html", utf8("<html><head><link rel='stylesheet' href='../assets/css/site.css'></head>"
                + "<body><div class='hero'>Hero text</div></body></html>"));
        entries.put("assets/css/site.css", utf8(".hero{height:80px;background:url(../img/hero.png)}"));
        entries.put("assets/img/hero.png", png(60, 30, Color.ORANGE));
        Pdf pdf = convert(zip("sheet.zip", entries), packages());
        assertEquals(1, pdf.images(), pdf.result().warnings().toString());
        assertEquals("/assets/img/a.png", "/" + HtmlRefs.resolve("assets/css/site.css", "../img/a.png"));
        assertTrue(Cascade.rebased("a{background:url(../img/a.png)}", "assets/css/site.css")
                .contains("url(\"/assets/img/a.png\")"));
        assertTrue(Cascade.rebased("a{background:url('x.png')}", "https://h.test/css/s.css")
                .contains("url(\"https://h.test/css/x.png\")"));
        assertTrue(Cascade.rebased("a{background:url(data:image/png;base64,AA)}", "css/s.css")
                .contains("url(data:image/png;base64,AA)"));
    }

    @Test
    void zipPagesAreRefusedUnlessPackagesAreAllowed() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("index.html", utf8("<p>Packaged page</p><img src='logo.png'>"));
        entries.put("logo.png", png(20, 20, Color.BLUE));
        Path z = zip("page.zip", entries);
        assertFalse(OfficeToPdf.Options.defaults().allowPackages());
        IOException e = assertThrows(IOException.class, () -> convert(z, options()));
        assertTrue(e.getMessage().contains("allowPackages") && e.getMessage().contains("--allow-packages"),
                e.getMessage());
        assertFalse(Files.exists(dir.resolve("page.zip.pdf")));
        IOException streamed = assertThrows(IOException.class, () -> {
            try (InputStream in = Files.newInputStream(z)) {
                OfficeToPdf.convert(in, OfficeToPdf.Format.HTML, new ByteArrayOutputStream(), options());
            }
        });
        assertTrue(streamed.getMessage().contains("--allow-packages"), streamed.getMessage());
        Pdf pdf = convert(z, packages());
        assertTrue(pdf.text().contains("Packaged page"), pdf.text());
        assertEquals(1, pdf.images());
    }

    @Test
    void zipPageDrawsItsPicturesAndStyleSheets() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("site/index.html", utf8("<!doctype html><html><head><link rel='stylesheet' href='css/site.css'>"
                + "</head><body><h1>Quarterly report</h1><p class='gone'>Hidden by the linked sheet</p>"
                + "<p class='hidden-by-import'>Hidden by the imported sheet</p>"
                + "<img src='img/logo.png' alt='logo'><img src='../shared/chart.svg' alt='chart'>"
                + "<img src='img/missing.png' alt='absent'></body></html>"));
        entries.put("site/css/site.css", utf8("@charset \"utf-8\";\n@import url(\"base.css\");\n"
                + "@import 'print-only.css' screen;\n.gone { display: none }"));
        entries.put("site/css/base.css", utf8(".hidden-by-import { display: none }"));
        entries.put("site/css/print-only.css", utf8("h1 { display: none }"));
        entries.put("site/img/logo.png", png(120, 60, Color.BLUE));
        entries.put("shared/chart.svg", utf8(SVG));
        Pdf pdf = convert(zip("report.zip", entries), packages());
        assertTrue(pdf.text().contains("Quarterly report"), pdf.text());
        assertFalse(pdf.text().contains("Hidden by the linked sheet"), pdf.text());
        assertFalse(pdf.text().contains("Hidden by the imported sheet"), pdf.text());
        assertEquals(2, pdf.images());
        assertTrue(pdf.text().contains("[absent]"), pdf.text());
        assertTrue(pdf.result().warnings().contains(HtmlImages.NOT_IN_PACKAGE), pdf.result().warnings().toString());
    }

    @Test
    void zipPageCannotReachOutsideThePackage() throws IOException {
        Files.write(dir.resolve("outside.png"), png(10, 10, Color.RED));
        Files.write(dir.resolve("outside.css"), utf8("h1 { display: none }"));
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("index.html", utf8("<html><head><link rel='stylesheet' href='../outside.css'>"
                + "<link rel='stylesheet' href='file://" + dir.resolve("outside.css") + "'>"
                + "<style>@import '/" + dir.resolve("outside.css") + "';</style></head><body><h1>Title stays</h1>"
                + "<img src='../outside.png'><img src='%2e%2e/outside.png'><img src='file://" + dir.resolve("outside.png")
                + "'><img src='" + dir.resolve("outside.png") + "'><img src='http://127.0.0.1:9/x.png'>"
                + "<img src='..\\outside.png'></body></html>"));
        Pdf pdf = convert(zip("escape.zip", entries), packages());
        assertTrue(pdf.text().contains("Title stays"), pdf.text());
        assertEquals(0, pdf.images());
    }

    @Test
    void pageChosenFromTheShallowestIndex() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("__MACOSX/index.html", utf8("<p>resource fork</p>"));
        entries.put("deep/inner/index.html", utf8("<p>deep page</p>"));
        entries.put("about.html", utf8("<p>about page</p>"));
        entries.put("index.htm", utf8("<p>front page</p>"));
        Pdf pdf = convert(zip("site.zip", entries), packages());
        assertTrue(pdf.text().contains("front page"), pdf.text());
        assertFalse(pdf.text().contains("about page"), pdf.text());
    }

    @Test
    void zipWithoutAPageIsRefused() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("notes.txt", utf8("plain"));
        Path z = zip("nothing.zip", entries);
        IOException e = assertThrows(IOException.class, () -> convert(z, packages()));
        assertTrue(e.getMessage().contains("no HTML page"), e.getMessage());
    }

    @Test
    void folderAssetsStayInsideTheirRoot() throws IOException {
        Path root = Files.createDirectories(dir.resolve("root"));
        Files.createDirectories(root.resolve("img"));
        Files.write(root.resolve("img/in.png"), png(40, 40, Color.GREEN));
        Files.write(dir.resolve("secret.png"), png(40, 40, Color.RED));
        List<String> refs = new ArrayList<>(List.of("img/in.png", "../secret.png", "img/../../secret.png"));
        try {
            Files.createSymbolicLink(root.resolve("img/link.png"), dir.resolve("secret.png"));
            refs.add("img/link.png");
        } catch (UnsupportedOperationException | IOException | SecurityException ignored) {
        }
        StringBuilder html = new StringBuilder("<html><body><p>Folder page</p>");
        for (String r : refs) {
            html.append("<img src='").append(r).append("'>");
        }
        Path page = Files.write(root.resolve("page.html"), utf8(html + "</body></html>"));
        Pdf pdf = convert(page, options().htmlAssets(HtmlAssets.folder(root, "page.html")));
        assertEquals(1, pdf.images());
    }

    @Test
    void withoutAssetsLinkedPicturesAreLeftOut() throws IOException {
        Files.write(dir.resolve("near.png"), png(30, 30, Color.BLACK));
        Path page = Files.write(dir.resolve("plain.html"), utf8("<p>Plain</p><img src='near.png'>"));
        Pdf pdf = convert(page, options());
        assertEquals(0, pdf.images());
        assertTrue(pdf.result().warnings().contains(HtmlImages.NOT_FETCHED), pdf.result().warnings().toString());
    }

    @Test
    void callerAssetsResolveAgainstAUrlBase() throws IOException {
        byte[] logo = png(50, 20, Color.ORANGE);
        List<String> asked = new ArrayList<>();
        HtmlAssets web = new HtmlAssets() {
            @Override
            public String base() {
                return "https://example.test/docs/guide/page.html";
            }

            @Override
            public byte[] read(String reference) {
                asked.add(reference);
                return reference.equals("https://example.test/docs/img/logo.png") ? logo : null;
            }
        };
        Path page = Files.write(dir.resolve("web.html"), utf8("<p>Web page</p><img src='../img/logo.png'>"
                + "<img src='javascript:alert(1)'><img src='#frag'>"));
        Pdf pdf = convert(page, options().htmlAssets(web));
        assertEquals(1, pdf.images());
        assertEquals(List.of("https://example.test/docs/img/logo.png"), asked);
    }

    @Test
    void selfImportingSheetsAreReadOnce() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("index.html", utf8("<html><head><link rel='stylesheet' href='a.css'></head><body><p>Loop</p>"
                + "</body></html>"));
        entries.put("a.css", utf8("@import \"a.css\";".repeat(8) + "@import 'b.css'; p { color: red }"));
        entries.put("b.css", utf8("@import 'a.css'; @import 'b.css';"));
        long start = System.nanoTime();
        Pdf pdf = convert(zip("loop.zip", entries), packages());
        assertTrue(pdf.text().contains("Loop"));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 20_000);
    }

    @Test
    void pagesLaidOutTwiceReadEachAssetOnce() throws IOException {
        byte[] logo = png(20, 20, Color.GRAY);
        List<String> asked = new ArrayList<>();
        HtmlAssets web = new HtmlAssets() {
            @Override
            public String base() {
                return "https://example.test/page.html";
            }

            @Override
            public byte[] read(String reference) {
                asked.add(reference);
                return reference.endsWith(".css") ? utf8("p { color: red }") : logo;
            }
        };
        Path page = Files.write(dir.resolve("wide.html"), utf8("<html><head><link rel='stylesheet' href='s.css'>"
                + "</head><body><pre>" + "wide ".repeat(60) + "</pre><img src='a.png'><img src='b.png' width='800'>"
                + "</body></html>"));
        Pdf pdf = convert(page, options().htmlAssets(web));
        assertEquals(2, pdf.images());
        assertEquals(List.of("https://example.test/a.png", "https://example.test/b.png", "https://example.test/s.css"),
                asked.stream().sorted().toList());
    }

    @Test
    void oneLinkedSvgDrawnManyTimesIsParsedOnce() throws IOException {
        StringBuilder svg = new StringBuilder("<svg xmlns='http://www.w3.org/2000/svg' width='10' height='10'>");
        while (svg.length() < (2 << 20) - 200) {
            svg.append("<g><desc>padding padding padding padding padding</desc></g>");
        }
        svg.append("<rect width='10' height='10' fill='#2a6'/></svg>");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("index.html", utf8("<p>Icons</p>" + "<img src='icon.svg'>".repeat(1_000)));
        entries.put("icon.svg", utf8(svg.toString()));
        long start = System.nanoTime();
        Pdf pdf = convert(zip("icons.zip", entries), packages());
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(pdf.text().contains("Icons"));
        assertTrue(millis < 8_000, millis + " ms");
    }

    @Test
    void referencesResolveInsideThePackage() {
        assertEquals("img/a.png", HtmlRefs.resolve("site/index.html", "../img/a.png"));
        assertEquals("site/img/a.png", HtmlRefs.resolve("site/index.html", "img/a.png?v=2#top"));
        assertEquals("img/a b.png", HtmlRefs.resolve("index.html", "img/a%20b.png"));
        assertEquals("img/a.png", HtmlRefs.resolve("site/index.html", "/img/a.png"));
        assertNull(HtmlRefs.resolve("index.html", "../a.png"));
        assertNull(HtmlRefs.resolve("index.html", "%2e%2e/a.png"));
        assertNull(HtmlRefs.resolve("index.html", "#top"));
        assertNull(HtmlRefs.resolve("index.html", "data:image/png;base64,AAAA"));
        assertNull(HtmlRefs.resolve("index.html", "JavaScript:alert(1)"));
        assertNull(HtmlRefs.resolve("index.html", "http://h/x.png"));
        assertEquals("https://h/x.png", HtmlRefs.resolve("https://e.test/a.html", "https://h/x.png"));
        assertNull(HtmlRefs.resolve("https://e.test/a.html", "file:///etc/passwd"));
        assertNull(HtmlRefs.resolve("file:///srv/page.html", "img.png"));
        assertEquals("https://e.test/a/c.css", HtmlRefs.resolve("https://e.test/a/b.html", "c.css"));
        assertFalse(HtmlAssets.packagePath("http://h/x.png"));
        assertFalse(HtmlAssets.packagePath("C:/x.png"));
        assertFalse(HtmlAssets.packagePath("a/../b"));
        assertTrue(HtmlAssets.packagePath("a/b.png"));
    }
}
