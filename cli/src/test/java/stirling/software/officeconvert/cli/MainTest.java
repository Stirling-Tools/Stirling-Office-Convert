package stirling.software.officeconvert.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

class MainTest {

    @TempDir
    Path dir;

    record Result(int code, String out, String err) {}

    private static Result run(String... args) {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code = Main.run(args);
            return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    @Test
    void aUrlIsFetchedOnlyWithNetworkAccessAndNeverFromALocalAddressByDefault() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            byte[] body = "<html><body><p>Served page</p></body></html>".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/index.html";
            Path pdf = dir.resolve("site.pdf");
            Result off = run(url, "-o", pdf.toString());
            assertEquals(2, off.code(), off.err());
            assertTrue(off.err().contains("--allow-network"), off.err());
            Result refused = run(url, "-o", pdf.toString(), "--allow-network");
            assertEquals(1, refused.code(), refused.err());
            assertEquals(0, hits.get());
            assertFalse(Files.exists(pdf));
            Result fetched = run(url, "-o", pdf.toString(), "--allow-network", "--allow-private-addresses", "-q");
            assertEquals(0, fetched.code(), fetched.err());
            assertEquals(1, hits.get());
            assertTrue(Files.size(pdf) > 0);
            Path page = Files.writeString(dir.resolve("linked.html"), "<html><body><p>Local</p><img src=\"" + url
                    + "\"></body></html>");
            Result needsNetwork = run(page.toString(), "-o", dir.resolve("linked.pdf").toString(), "--fetch-linked");
            assertEquals(2, needsNetwork.code(), needsNetwork.err());
            Result linked = run(page.toString(), "-o", dir.resolve("linked.pdf").toString(), "--fetch-linked",
                    "--allow-network", "--allow-private-addresses", "-q");
            assertEquals(0, linked.code(), linked.err());
            assertEquals(2, hits.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void routesOfficeInputToOfficeToPdf() throws Exception {
        Path docx = Files.writeString(dir.resolve("notes.docx"), "not a zip");
        Result r = run(docx.toString());
        assertEquals(1, r.code(), r.err());
        assertTrue(r.err().contains("FAIL " + docx) && r.err().contains("not a zip package"), r.err());
        assertFalse(Files.exists(dir.resolve("notes.pdf")));
    }

    @Test
    void legacyCyrillicTextConvertsWithoutXmlProbeDiagnostics() throws Exception {
        for (String encoding : new String[] {"windows-1251", "KOI8-R"}) {
            for (String prefix : new String[] {"Съешь", "привет", "юность", "ярмарка"}) {
                String text = ("  \n" + prefix + " ещё этих мягких французских булок, да выпей чаю."
                        + " Москва Санкт-Петербург.\n").repeat(10);
                Path source = Files.write(dir.resolve(encoding + "-" + prefix + ".txt"),
                        text.getBytes(java.nio.charset.Charset.forName(encoding)));
                Path pdf = source.resolveSibling(source.getFileName() + ".pdf");
                Result r = run(source.toString(), "-o", pdf.toString(), "-q");
                assertEquals(0, r.code(), r.err());
                assertEquals("", r.err(), encoding + " " + prefix);
                try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
                    assertTrue(new PDFTextStripper().getText(doc).contains("Москва Санкт-Петербург"));
                }
            }
        }
    }

    @Test
    void malformedOfficeXmlStillReportsItsConversionError() throws Exception {
        Path source = Files.writeString(dir.resolve("broken.xml"),
                "<w:wordDocument xmlns:w=\"http://schemas.microsoft.com/office/word/2003/wordml\">"
                + "<w:body><w:p><w:r><w:t>Broken");
        Path pdf = dir.resolve("broken.pdf");
        Result r = run(source.toString(), "-o", pdf.toString(), "-q");
        assertEquals(1, r.code(), r.err());
        assertTrue(r.err().contains("FAIL " + source) && r.err().contains("not well-formed"), r.err());
        assertFalse(Files.exists(pdf));
    }

    @Test
    void convertsSeveralOfficeFilesInOneRun() throws Exception {
        Path a = Files.writeString(dir.resolve("a.docx"), "x");
        Path b = Files.writeString(dir.resolve("b.pptx"), "x");
        Path c = Files.writeString(dir.resolve("c.xlsm"), "x");
        Path d = Files.writeString(dir.resolve("d.doc"), "x");
        Path out = dir.resolve("out");
        Result r = run(a.toString(), b.toString(), c.toString(), d.toString(), "-o", out.toString(), "-q");
        assertEquals(1, r.code());
        assertEquals(4, r.err().lines().filter(l -> l.startsWith("FAIL ")).count(), r.err());
        assertFalse(r.err().contains("Exception"), r.err());
    }

    @Test
    void aValidDocumentConvertsOrFailsCleanly() throws Exception {
        Path docx = minimalDocx(dir.resolve("hello.docx"));
        Path pdf = dir.resolve("hello.pdf");
        Result r = run(docx.toString(), "--max-pages", "3", "--timeout", "120", "--fonts", dir.toString());
        if (r.code() == 0) {
            assertTrue(Files.size(pdf) > 0);
            assertTrue(r.out().startsWith("OK "), r.out());
        } else {
            assertEquals(1, r.code(), r.err());
            assertTrue(r.err().startsWith("FAIL "), r.err());
            assertFalse(Files.exists(pdf));
        }
    }

    @Test
    void officeInputRejectsPdfOnlyOptions() throws Exception {
        Path docx = Files.writeString(dir.resolve("a.docx"), "x");
        assertUsage(run(docx.toString(), "--pages", "1-2"), "--pages");
        assertUsage(run(docx.toString(), "--format", "docx"), "PDF only");
        assertUsage(run(docx.toString(), "-o", dir.resolve("a.odt").toString()), ".pdf");
        assertUsage(run(docx.toString(), "--max-pages", "-1"), "--max-pages");
        assertUsage(run(docx.toString(), "--timeout", "soon"), "--timeout");
        Path pdf = Files.writeString(dir.resolve("b.pdf"), "x");
        assertUsage(run(pdf.toString(), "-o", dir.resolve("b2.pdf").toString()), "Office format");
        assertUsage(run(pdf.toString(), "--format", "pdf"), "not pdf");
    }

    @Test
    void pdfInputStillConvertsToOfficeFormats() throws Exception {
        Path pdf = helloPdf(dir.resolve("in.pdf"));
        Path txt = dir.resolve("in.txt");
        Result r = run(pdf.toString(), "-o", txt.toString(), "--pages", "1");
        assertEquals(0, r.code(), r.err());
        assertTrue(Files.readString(txt).contains("Hello PDF"));
    }

    @Test
    void ebookOptionsShapeTheBook() throws Exception {
        Path pdf = helloPdf(dir.resolve("book.pdf"));
        Path epub = dir.resolve("book.epub");
        Result r = run(pdf.toString(), "-o", epub.toString(), "--device", "kindle", "--no-chapters", "-q");
        assertEquals(0, r.code(), r.err());
        try (ZipFile zip = new ZipFile(epub.toFile())) {
            String css = new String(zip.getInputStream(zip.getEntry("OEBPS/styles/book.css")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(css.contains("font-size:1em"), css);
        }
        assertUsage(run(pdf.toString(), "-o", dir.resolve("book2.epub").toString(), "--device", "nook"), "--device");
    }

    @Test
    void ebookToPdfTakesContentsAndPageNumbers() throws Exception {
        Path pdf = dir.resolve("story.pdf");
        try (PDDocument doc = new PDDocument();
                InputStream font = PDDocument.class.getResourceAsStream(
                        "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            PDType0Font f = PDType0Font.load(doc, font);
            for (String chapter : new String[] {"First Chapter", "Second Chapter"}) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(f, 24);
                    cs.newLineAtOffset(72, 700);
                    cs.showText(chapter);
                    cs.setFont(f, 11);
                    for (int line = 0; line < 8; line++) {
                        cs.newLineAtOffset(0, -18);
                        cs.showText("A plain line of the story that runs along the page, number " + line + ".");
                    }
                    cs.endText();
                }
            }
            doc.save(pdf.toFile());
        }
        Path epub = dir.resolve("story.epub");
        assertEquals(0, run(pdf.toString(), "-o", epub.toString(), "-q").code());
        Path back = dir.resolve("story-back.pdf");
        Result r = run(epub.toString(), "-o", back.toString(), "--toc", "--page-numbers", "-q");
        assertEquals(0, r.code(), r.err());
        try (PDDocument doc = Loader.loadPDF(back.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Contents") && text.contains("Second Chapter"), text);
        }
    }

    @Test
    void legacyPowerPointConvertsBackToPdf() throws Exception {
        Path pdf = helloPdf(dir.resolve("round.pdf"));
        Path ppt = dir.resolve("round.ppt");
        assertEquals(0, run(pdf.toString(), "-o", ppt.toString(), "-q").code());
        Path back = dir.resolve("back.pdf");
        Result r = run(ppt.toString(), "-o", back.toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().startsWith("OK "), r.out());
        assertTrue(Files.size(back) > 0);
    }

    @Test
    void pdfConvertsToFlatOpenDocumentXml() throws Exception {
        Path pdf = helloPdf(dir.resolve("in.pdf"));
        Path xml = dir.resolve("in.xml");
        Result r = run(pdf.toString(), "-o", xml.toString());
        assertEquals(0, r.code(), r.err());
        String out = Files.readString(xml);
        assertTrue(out.contains("<office:document ") && out.contains("Hello PDF"), out);
    }

    @Test
    void formatPdfPicksOfficeFilesOutOfAFolder() throws Exception {
        Path in = Files.createDirectories(dir.resolve("in"));
        Files.writeString(in.resolve("x.docx"), "x");
        Files.writeString(in.resolve("y.pdf"), "x");
        Files.writeString(in.resolve("z.md"), "x");
        Files.writeString(in.resolve("notes.txt"), "plain text");
        Result r = run(in.toString(), "--format", "pdf", "-o", dir.resolve("out").toString());
        assertEquals(1, r.code());
        assertTrue(r.err().contains("x.docx"), r.err());
        assertFalse(r.err().contains("y.pdf") || r.err().contains("z.md"), r.err());
        assertTrue(Files.isRegularFile(dir.resolve("out").resolve("notes.pdf")), r.out());
    }

    @Test
    void aFolderLeavesItsTextFilesAloneUnlessAskedForPdf() throws Exception {
        Path in = Files.createDirectories(dir.resolve("mixed"));
        helloPdf(in.resolve("doc.pdf"));
        Files.writeString(in.resolve("readme.txt"), "notes");
        Path out = dir.resolve("mixed-out");
        Result r = run(in.toString(), "-o", out.toString(), "--pages", "1");
        assertEquals(0, r.code(), r.err());
        assertTrue(Files.isRegularFile(out.resolve("doc.docx")), r.out());
        assertFalse(Files.exists(out.resolve("readme.pdf")), r.out());
    }

    @Test
    void aFolderLeavesItsHtmlAndJsonFilesAloneUnlessAskedForPdf() throws Exception {
        Path in = Files.createDirectories(dir.resolve("web"));
        helloPdf(in.resolve("doc.pdf"));
        Files.writeString(in.resolve("page.html"), "<p>hello</p>");
        Files.writeString(in.resolve("old.htm"), "<p>hello</p>");
        Files.writeString(in.resolve("note.xhtml"), "<p>hello</p>");
        Files.writeString(in.resolve("config.json"), "{\"name\":\"config\"}");
        Path out = dir.resolve("web-out");
        Result r = run(in.toString(), "-o", out.toString(), "--pages", "1");
        assertEquals(0, r.code(), r.err());
        assertTrue(Files.isRegularFile(out.resolve("doc.docx")), r.out());
        assertEquals(1, r.out().lines().filter(l -> l.startsWith("OK ")).count(), r.out());
        Result pdfs = run(in.toString(), "--format", "pdf", "-o", dir.resolve("web-pdf").toString(), "-q");
        assertTrue(Files.isRegularFile(dir.resolve("web-pdf").resolve("page.pdf")), pdfs.err());
        assertTrue(Files.isRegularFile(dir.resolve("web-pdf").resolve("old.pdf")), pdfs.err());
        assertTrue(Files.isRegularFile(dir.resolve("web-pdf").resolve("note.pdf")), pdfs.err());
    }

    @Test
    void anExplicitHtmlFileStillConverts() throws Exception {
        Path html = Files.writeString(dir.resolve("one.html"), "<h1>Hello</h1>");
        Result r = run(html.toString(), "-q");
        assertEquals(0, r.code(), r.err());
        assertTrue(Files.size(dir.resolve("one.pdf")) > 0);
    }

    @Test
    void helpMentionsOfficeInput() {
        Result r = run("--help");
        assertEquals(0, r.code());
        assertTrue(r.out().contains("in.docx") && r.out().contains("--max-pages"), r.out());
        for (String ext : new String[] {".docm", ".dotm", ".ppsm", ".potx", ".potm", ".xlsm", ".xltx", ".xltm",
                ".xls", ".xlt", ".ppt", ".pps", ".pot", ".rtf", ".odt", ".fodt", ".ods", ".odp", ".fodp"}) {
            assertTrue(r.out().contains(ext + " ") || r.out().contains(ext + ")"), ext + " missing from " + r.out());
        }
    }

    @Test
    void inputsWithTheSameNameDoNotOverwriteEachOtherAndLockFilesAreSkipped() throws Exception {
        Path in = Files.createDirectories(dir.resolve("same"));
        minimalDocx(in.resolve("report.docx"));
        minimalXlsx(in.resolve("report.xlsx"));
        Files.writeString(in.resolve("~$report.docx"), "owner file");
        Path out = dir.resolve("same-out");
        Result r = run(in.toString(), "-o", out.toString(), "-q");
        assertEquals(0, r.code(), r.err());
        assertTrue(Files.size(out.resolve("report.docx.pdf")) > 0);
        assertTrue(Files.size(out.resolve("report.xlsx.pdf")) > 0);
        assertFalse(Files.exists(out.resolve("report.pdf")));
        Result beside = run(in.resolve("report.docx").toString(), in.resolve("report.xlsx").toString(), "-q");
        assertEquals(0, beside.code(), beside.err());
        assertTrue(Files.exists(in.resolve("report.docx.pdf")) && Files.exists(in.resolve("report.xlsx.pdf")));
    }

    @Test
    void aFolderOfOfficeFilesConvertsWithoutAFormat() throws Exception {
        Path in = Files.createDirectories(dir.resolve("office"));
        minimalDocx(in.resolve("letter.docx"));
        Path out = dir.resolve("office-out");
        Result r = run(in.toString(), "-o", out.toString(), "-q");
        assertEquals(0, r.code(), r.err());
        assertTrue(Files.size(out.resolve("letter.pdf")) > 0);
    }

    @Test
    void tinyTimeoutsAndMissingFontFoldersAreNotIgnored() throws Exception {
        Path docx = docx(dir.resolve("t.docx"), "<w:p><w:r><w:t>Long enough to outlast a millisecond</w:t></w:r></w:p>"
                .repeat(20_000));
        Result fast = run(docx.toString(), "--timeout", "0.0000001");
        assertEquals(1, fast.code(), fast.out() + fast.err());
        assertTrue(fast.err().contains("longer than"), fast.err());
        assertUsage(run(docx.toString(), "--fonts", dir.resolve("no-such-folder").toString()), "--fonts");
    }

    @Test
    void fontOptionsBuildTheFontSetAndReportBadFonts() throws Exception {
        Path docx = minimalDocx(dir.resolve("fonts.docx"));
        Path fonts = Files.createDirectories(dir.resolve("user-fonts"));
        String bundled = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";
        try (InputStream in = PDDocument.class.getResourceAsStream(bundled)) {
            Files.write(fonts.resolve("sans.ttf"), in.readAllBytes());
        }
        Files.writeString(fonts.resolve("broken.ttf"), "not a font");
        Result r = run(docx.toString(), "--fonts", fonts.toString(), "--no-system-fonts", "--font-map",
                "Calibri=Liberation Sans", "--font-width", "Aptos=0.95", "-o", dir.resolve("fonts.pdf").toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.err().contains("warning: fonts: ") && r.err().contains("broken.ttf"), r.err());
        assertTrue(Files.size(dir.resolve("fonts.pdf")) > 0);
        assertUsage(run(docx.toString(), "--font-map", "Aptos"), "--font-map");
        assertUsage(run(docx.toString(), "--font-map", "=Inter"), "--font-map");
        assertUsage(run(docx.toString(), "--font-width", "Aptos=9"), "width scale");
        assertUsage(run(docx.toString(), "--font-width", "Aptos=wide"), "--font-width");
        assertTrue(run("--help").out().contains("--no-system-fonts"));
    }

    @Test
    void messagesStayOnOneLine() {
        assertEquals("a b c", Main.oneLine("a\nb\u001bc"));
        assertFalse(Main.oneLine("x\u202Ey\r\nz").chars().anyMatch(c -> c < 32 || c == 0x202E));
    }

    @Test
    void poiLoggingIsSilenced() throws Exception {
        java.util.Properties p = new java.util.Properties();
        try (InputStream in = Main.class.getResourceAsStream("/log4j2.simplelog.properties")) {
            p.load(in);
        }
        assertEquals("OFF", p.getProperty("org.apache.logging.log4j.simplelog.level"));
    }

    @Test
    void pdfaWritesAnArchivalCopyBesideTheInput() throws Exception {
        Path pdf = helloPdf(dir.resolve("in.pdf"));
        Result r = run(pdf.toString(), "--pdfa", "2b");
        assertEquals(0, r.code(), r.err());
        Path out = dir.resolve("in.pdfa.pdf");
        assertTrue(Files.size(out) > 0);
        try (PDDocument d = org.apache.pdfbox.Loader.loadPDF(out.toFile())) {
            String xmp = new String(d.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
            assertTrue(xmp.contains("<pdfaid:part>2</pdfaid:part>"), xmp);
        }
        Result again = run(dir.toString(), "--pdfa", "1b", "-o", dir.resolve("archive").toString(), "-q");
        assertEquals(0, again.code(), again.err());
        assertTrue(Files.exists(dir.resolve("archive/in.pdfa.pdf")));
        assertFalse(Files.exists(dir.resolve("archive/in.pdfa.pdfa.pdf")));
    }

    @Test
    void lineArtMakesEachPictureOneBit() throws Exception {
        Path pdf = dir.resolve("scan.pdf");
        java.awt.image.BufferedImage picture = new java.awt.image.BufferedImage(64, 48, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 48; y++) {
            for (int x = 0; x < 64; x++) {
                picture.setRGB(x, y, (x / 8 + y / 8) % 2 == 0 ? 0x202020 : 0xF0F0E0);
            }
        }
        try (PDDocument d = new PDDocument()) {
            PDPage page = new PDPage();
            d.addPage(page);
            var image = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(d, picture);
            try (PDPageContentStream cs = new PDPageContentStream(d, page)) {
                cs.drawImage(image, 72, 72);
            }
            d.save(pdf.toFile());
        }
        Result r = run(pdf.toString(), "--line-art", "otsu", "--codec", "g4");
        assertEquals(0, r.code(), r.err());
        assertTrue(r.err().contains("1 of 1 pictures made 1-bit"), r.err());
        try (PDDocument d = org.apache.pdfbox.Loader.loadPDF(dir.resolve("scan.lineart.pdf").toFile())) {
            var resources = d.getPage(0).getResources();
            var image = (org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject) resources.getXObject(
                    resources.getXObjectNames().iterator().next());
            assertEquals(1, image.getBitsPerComponent());
            assertEquals("CCITTFaxDecode", image.getCOSObject().getCOSName(org.apache.pdfbox.cos.COSName.FILTER).getName());
        }
        Result folder = run(dir.toString(), "--line-art", "edges", "--edge-level", "2", "-o", dir.resolve("art").toString(), "-q");
        assertEquals(0, folder.code(), folder.err());
        assertTrue(Files.exists(dir.resolve("art/scan.lineart.pdf")));
        assertFalse(Files.exists(dir.resolve("art/scan.lineart.lineart.pdf")));
    }

    @Test
    void lineArtRefusesMixedOrUnknownOptions() throws Exception {
        Path pdf = helloPdf(dir.resolve("in.pdf"));
        assertUsage(run(pdf.toString(), "--line-art", "magic"), "sauvola, otsu, fixed or edges");
        assertUsage(run(pdf.toString(), "--threshold", "40"), "go with --line-art");
        assertUsage(run(pdf.toString(), "--line-art", "fixed", "--pdfa", "2b"), "separate runs");
        assertUsage(run(pdf.toString(), "--line-art", "edges", "--edge-level", "5"), "edgeLevel must be 1, 2 or 3");
        assertUsage(run(pdf.toString(), "--line-art", "fixed", "--codec", "png"), "smallest, jbig2, g4 or flate");
        assertUsage(run(pdf.toString(), "--line-art", "fixed", "-o", dir.resolve("x.docx").toString()), "name the output .pdf");
    }

    @Test
    void pdfaHonoursMaxPages() throws Exception {
        Path pdf = dir.resolve("three.pdf");
        try (PDDocument d = new PDDocument()) {
            for (int i = 0; i < 3; i++) {
                d.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            }
            d.save(pdf.toFile());
        }
        Result r = run(pdf.toString(), "--pdfa", "2b", "--max-pages", "2");
        assertTrue(r.code() != 0, r.err());
        assertTrue(r.err().contains("more than the limit of 2"), r.err());
        assertFalse(Files.exists(dir.resolve("three.pdfa.pdf")));
        assertEquals(0, run(pdf.toString(), "--pdfa", "2b", "--max-pages", "3").code());
    }

    @Test
    void pdfaRefusesOfficeInputAndUnknownLevels() throws Exception {
        Path pdf = helloPdf(dir.resolve("in.pdf"));
        assertUsage(run(pdf.toString(), "--pdfa", "9"), "1a, 1b, 2a, 2b, 2u, 3a, 3b or 3u");
        assertUsage(run(pdf.toString(), "--pdfa", "2b", "-o", dir.resolve("x.docx").toString()), "name the output .pdf");
        Path docx = minimalDocx(dir.resolve("a.docx"));
        assertUsage(run(docx.toString(), "--pdfa", "2b"), "PDF input");
    }

    @Test
    void aFolderNeverOverwritesItsOwnFiles() throws Exception {
        Path in = Files.createDirectories(dir.resolve("pair"));
        byte[] word = Files.readAllBytes(minimalDocx(in.resolve("b.docx")));
        byte[] pdf = Files.readAllBytes(helloPdf(in.resolve("b.pdf")));
        Result r = run(in.toString(), "-q");
        assertEquals(0, r.code(), r.err());
        assertArrayEquals(word, Files.readAllBytes(in.resolve("b.docx")));
        assertArrayEquals(pdf, Files.readAllBytes(in.resolve("b.pdf")));
        assertTrue(Files.size(in.resolve("b.docx.pdf")) > 0);
        assertTrue(Files.readString(in.resolve("b.pdf.docx"), StandardCharsets.ISO_8859_1).startsWith("PK"));
        Result again = run(in.toString(), "-q");
        assertEquals(1, again.code(), again.err());
        assertTrue(again.err().contains("b.docx.pdf is an input of this run"), again.err());
        assertArrayEquals(word, Files.readAllBytes(in.resolve("b.docx")));
        assertArrayEquals(pdf, Files.readAllBytes(in.resolve("b.pdf")));
    }

    @Test
    void anExistingOutputIsReplacedOnlyWithOverwrite() throws Exception {
        Path pdf = helloPdf(dir.resolve("in.pdf"));
        Path txt = Files.writeString(dir.resolve("in.txt"), "keep me");
        Result refused = run(pdf.toString(), "-o", txt.toString());
        assertEquals(1, refused.code(), refused.err());
        assertTrue(refused.err().startsWith("SKIP " + pdf) && refused.err().contains("--overwrite"), refused.err());
        assertEquals("keep me", Files.readString(txt));
        Result beside = run(pdf.toString(), "--format", "txt");
        assertEquals(1, beside.code(), beside.err());
        assertEquals("keep me", Files.readString(txt));
        Result replaced = run(pdf.toString(), "-o", txt.toString(), "--overwrite");
        assertEquals(0, replaced.code(), replaced.err());
        assertTrue(Files.readString(txt).contains("Hello PDF"));
        assertTrue(run("--help").out().contains("--overwrite"));
    }

    @Test
    void anInputIsNeverTheOutputEvenWithOverwrite() throws Exception {
        Path pdf = helloPdf(dir.resolve("in.pdf"));
        byte[] before = Files.readAllBytes(pdf);
        Result r = run(pdf.toString(), "--pdfa", "2b", "-o", pdf.toString(), "--overwrite");
        assertEquals(1, r.code(), r.err());
        assertTrue(r.err().contains("is an input of this run"), r.err());
        assertArrayEquals(before, Files.readAllBytes(pdf));
    }

    @Test
    void aFolderWithNothingToConvertFails() throws Exception {
        Path in = Files.createDirectories(dir.resolve("notes"));
        Files.writeString(in.resolve("notes.txt"), "plain text");
        Result r = run(in.toString());
        assertEquals(1, r.code(), r.err());
        assertTrue(r.err().contains("nothing to convert in " + in), r.err());
    }

    @Test
    void zipPagesConvertOnlyWithAllowPackages() throws Exception {
        Path zip = dir.resolve("site.zip");
        try (OutputStream os = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(os)) {
            entry(z, "index.html", "<p>Zipped page</p><img src='dot.png'>");
            z.putNextEntry(new ZipEntry("dot.png"));
            z.write(png());
            z.closeEntry();
        }
        Path pdf = dir.resolve("site.pdf");
        Result refused = run(zip.toString());
        assertEquals(1, refused.code(), refused.err());
        assertTrue(refused.err().contains("--allow-packages"), refused.err());
        assertFalse(Files.exists(pdf));
        Result allowed = run(zip.toString(), "--allow-packages", "-q");
        assertEquals(0, allowed.code(), allowed.err());
        assertEquals(1, images(pdf));
    }

    @Test
    void pagesReadFilesBesideThemOnlyWithAllowLocalFiles() throws Exception {
        Path site = Files.createDirectories(dir.resolve("site"));
        Files.write(site.resolve("dot.png"), png());
        Files.write(dir.resolve("outside.png"), png());
        Path page = Files.writeString(site.resolve("page.html"), "<p>Local page</p><img src='dot.png'>"
                + "<img src='../outside.png'><img src='" + dir.resolve("outside.png").toUri() + "'>");
        Path pdf = site.resolve("page.pdf");
        Result plain = run(page.toString(), "-q");
        assertEquals(0, plain.code(), plain.err());
        assertEquals(0, images(pdf));
        Result local = run(page.toString(), "--allow-local-files", "--overwrite", "-q");
        assertEquals(0, local.code(), local.err());
        assertEquals(1, images(pdf));
    }

    private static byte[] png() throws IOException {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(8, 8,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static int images(Path pdf) throws IOException {
        int images = 0;
        try (PDDocument d = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
            for (PDPage page : d.getPages()) {
                for (var n : page.getResources().getXObjectNames()) {
                    if (page.getResources().getXObject(n)
                            instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject) {
                        images++;
                    }
                }
            }
        }
        return images;
    }

    @Test
    void outputsDodgeInputNamesAndAreNotConvertedAgain() throws Exception {
        Path word = dir.resolve("b.docx");
        Path pdf = dir.resolve("b.pdf");
        assertEquals(java.util.List.of(dir.resolve("b.docx.pdf"), dir.resolve("b.pdf.docx")),
                Targets.of(java.util.List.of(word, pdf), null, "docx", true));
        Files.writeString(pdf, "x");
        Guard guard = new Guard(java.util.List.of(word, pdf), true);
        guard.wrote(pdf);
        assertTrue(guard.refusal(pdf, dir.resolve("c.docx")).contains("written by this run"));
        assertTrue(guard.refusal(word, pdf).contains("is an input"));
    }

    private static Path minimalXlsx(Path file) throws IOException {
        try (OutputStream os = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(os)) {
            entry(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument"
                    + ".spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\""
                    + " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
            entry(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                    + "/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            entry(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                    + "/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
            entry(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook"
                    + " xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                    + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet"
                    + " name=\"S\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            entry(zip, "xl/worksheets/sheet1.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet"
                    + " xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData><row r=\"1\"><c"
                    + " r=\"A1\" t=\"inlineStr\"><is><t>SHEETTEXT</t></is></c></row></sheetData></worksheet>");
        }
        return file;
    }

    private static void assertUsage(Result r, String fragment) {
        assertEquals(2, r.code(), r.err());
        assertTrue(r.err().contains(fragment), r.err());
    }

    private static Path helloPdf(Path file) throws IOException {
        try (PDDocument doc = new PDDocument();
                InputStream font = PDDocument.class.getResourceAsStream(
                        "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType0Font f = PDType0Font.load(doc, font);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(f, 14);
                cs.newLineAtOffset(72, 700);
                cs.showText("Hello PDF");
                cs.endText();
            }
            doc.save(file.toFile());
        }
        return file;
    }

    private static Path minimalDocx(Path file) throws IOException {
        return docx(file, "<w:p><w:r><w:t>Hello Word</w:t></w:r></w:p>");
    }

    private static Path docx(Path file, String body) throws IOException {
        try (OutputStream os = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(os)) {
            entry(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument"
                    + ".wordprocessingml.document.main+xml\"/></Types>");
            entry(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                    + "/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            entry(zip, "word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                    + body + "</w:body></w:document>");
        }
        return file;
    }

    private static void entry(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
