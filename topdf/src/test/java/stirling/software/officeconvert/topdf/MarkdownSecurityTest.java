package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

class MarkdownSecurityTest {

    private static final String CANARY = "CANARY-7d1f3a";

    private static final OfficeToPdf.Options OPTIONS = OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2));

    @TempDir
    Path dir;

    private HttpServer server;

    private final AtomicInteger requests = new AtomicInteger();

    private String origin;

    @BeforeEach
    void listen() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = ("body { color: red } " + CANARY).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private WebAssets fetching(Path page) throws IOException {
        WebAssets.Access access = new WebAssets.Access(true, true, false, true, true, Duration.ofSeconds(5),
                WebAssets.Access.DEFAULT_MAX_BYTES, 50, 3);
        return WebAssets.linkedFrom(HtmlAssets.folder(page.getParent(), page.getFileName().toString()), access);
    }

    private String remoteReferences() {
        return "![remote](" + origin + "/a.png)\n\n<img src=\"" + origin + "/b.png\">\n\n<link rel=\"stylesheet\" href=\""
                + origin + "/c.css\">\n\n<style>@import url(\"" + origin + "/d.css\"); @font-face { font-family: W; src: url("
                + origin + "/e.woff); } p { background: url(" + origin + "/f.png); }</style>\n\n<script src=\"" + origin
                + "/g.js\"></script>\n\n<iframe src=\"" + origin + "/h.html\"></iframe>\n\n[link](" + origin + "/i)\n";
    }

    @Test
    void remoteReferencesAreNeverFetchedEvenWhenFetchingIsAllowed() throws IOException {
        Path site = Files.createDirectories(dir.resolve("site"));
        Path md = Files.writeString(site.resolve("page.md"), remoteReferences(), StandardCharsets.UTF_8);
        OfficeToPdf.convert(md, dir.resolve("md.pdf"), OPTIONS.htmlAssets(fetching(md)));
        assertEquals(0, requests.get());
        Path html = Files.writeString(site.resolve("page.html"), "<!DOCTYPE html><link rel=\"stylesheet\" href=\""
                + origin + "/control.css\"><p>control</p>", StandardCharsets.UTF_8);
        OfficeToPdf.convert(html, dir.resolve("control.pdf"), OPTIONS.htmlAssets(fetching(html)));
        assertTrue(requests.get() > 0, "the control page should reach the test server");
    }

    @Test
    void filesOutsideThePageFolderAreNeverRead() throws IOException {
        Path secret = Files.writeString(dir.resolve("secret.txt"), CANARY, StandardCharsets.UTF_8);
        Path site = Files.createDirectories(dir.resolve("site"));
        String abs = secret.toAbsolutePath().toString();
        String uri = secret.toUri().toString();
        String svg = "<?xml version=\"1.0\"?><!DOCTYPE svg [<!ENTITY x SYSTEM \"" + uri + "\">]>"
                + "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"300\" height=\"40\"><text x=\"5\" y=\"20\">&x;</text>"
                + "<image href=\"" + uri + "\" width=\"10\" height=\"10\"/></svg>";
        String data = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
        String md = "![a](../secret.txt) ![b](" + abs + ") ![c](" + uri + ") ![d](" + data + ")\n\n<img src=\"../secret.txt\">"
                + " <img src=\"" + uri + "\"> <object data=\"" + uri + "\"></object> <embed src=\"../secret.txt\">\n\n"
                + "<style>p { background: url(\"../secret.txt\") } @import \"" + uri + "\";</style>\n\n"
                + "[e](../secret.txt) [f](" + uri + ")\n";
        Path page = Files.writeString(site.resolve("page.md"), md, StandardCharsets.UTF_8);
        List<String> asked = new ArrayList<>();
        HtmlAssets folder = HtmlAssets.folder(site, "page.md");
        HtmlAssets watched = reference -> {
            asked.add(reference);
            return folder.read(reference);
        };
        Path out = dir.resolve("out.pdf");
        OfficeToPdf.convert(page, out, OPTIONS.htmlAssets(watched));
        assertTrue(asked.stream().noneMatch(r -> r.contains("secret")), asked.toString());
        byte[] pdf = Files.readAllBytes(out);
        assertFalse(new String(pdf, StandardCharsets.ISO_8859_1).contains(CANARY));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertFalse(new PDFTextStripper().getText(doc).contains(CANARY));
            assertTrue(uris(doc).isEmpty(), uris(doc).toString());
        }
    }

    @Test
    void activeContentIsInertAndOnlySafeLinksRemain() throws IOException {
        String md = "[a](javascript:alert(1)) [b](data:text/html,<script>alert(1)</script>) [c](file:///etc/passwd)"
                + " [d](vbscript:x) [e](other.md) [f](https://example.com/ok) [g](mailto:a@example.com) [h](#here)\n\n"
                + "<a href=\"javascript:alert(2)\">raw</a> <a href=\"data:text/html,x\">raw</a>"
                + " <a href=\"file:///etc/passwd\">raw</a>\n\n<script>alert(3)</script>\n\n"
                + "<div onclick=\"alert(4)\" onmouseover=\"alert(5)\">handlers</div>\n\n"
                + "<form action=\"https://example.com/post\" onsubmit=\"alert(6)\"><input name=\"q\" value=\"v\">"
                + "<button onclick=\"alert(7)\">Go</button></form>\n\n<iframe srcdoc=\"<script>alert(8)</script>\"></iframe>\n\n"
                + "<object data=\"x.exe\"></object><embed src=\"x.swf\">\n\n## Here\n";
        Path page = Files.writeString(dir.resolve("active.md"), md, StandardCharsets.UTF_8);
        Path out = dir.resolve("active.pdf");
        OfficeToPdf.convert(page, out, OPTIONS);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            var catalog = doc.getDocumentCatalog().getCOSObject();
            assertNull(catalog.getDictionaryObject(COSName.OPEN_ACTION));
            assertNull(catalog.getDictionaryObject(COSName.AA));
            assertTrue(doc.getDocumentCatalog().getNames() == null
                    || doc.getDocumentCatalog().getNames().getJavaScript() == null
                    && doc.getDocumentCatalog().getNames().getEmbeddedFiles() == null);
            for (PDPage p : doc.getPages()) {
                assertNull(p.getCOSObject().getDictionaryObject(COSName.AA));
                for (PDAnnotation a : p.getAnnotations()) {
                    assertNull(a.getCOSObject().getDictionaryObject(COSName.AA));
                    if (a instanceof PDAnnotationLink link) {
                        PDAction action = link.getAction();
                        assertTrue(action == null || action instanceof PDActionURI, String.valueOf(action));
                    }
                }
            }
            assertEquals(List.of("https://example.com/ok", "mailto:a@example.com"), uris(doc));
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("handlers"), text);
            assertFalse(new String(Files.readAllBytes(out), StandardCharsets.ISO_8859_1).contains("/JavaScript"));
        }
    }

    private static List<String> uris(PDDocument doc) throws IOException {
        List<String> uris = new ArrayList<>();
        for (PDPage p : doc.getPages()) {
            for (PDAnnotation a : p.getAnnotations()) {
                if (a instanceof PDAnnotationLink link && link.getAction() instanceof PDActionURI uri) {
                    uris.add(uri.getURI());
                }
            }
        }
        return uris;
    }

    @Test
    void zipSlipAndBombsNamedMarkdownAreRefused() throws IOException {
        ByteArrayOutputStream slip = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(slip)) {
            zip.putNextEntry(new ZipEntry("../../escaped.md"));
            zip.write("# out".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path slipped = Files.write(dir.resolve("slip.md"), slip.toByteArray());
        assertThrows(IOException.class, () -> OfficeToPdf.convert(slipped, dir.resolve("slip.pdf"), OPTIONS));
        assertThrows(IOException.class, () -> OfficeToPdf.convert(slipped, dir.resolve("slip2.pdf"),
                OPTIONS.allowPackages(true)));
        assertFalse(Files.exists(dir.getParent().resolve("escaped.md")));
        ByteArrayOutputStream bomb = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bomb)) {
            zip.putNextEntry(new ZipEntry("index.html"));
            byte[] zeros = new byte[1 << 20];
            for (int i = 0; i < 200; i++) {
                zip.write(zeros);
            }
            zip.closeEntry();
        }
        Path bombed = Files.write(dir.resolve("bomb.md"), bomb.toByteArray());
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            assertThrows(IOException.class, () -> OfficeToPdf.convert(bombed, dir.resolve("bomb.pdf"), OPTIONS));
            assertThrows(IOException.class, () -> OfficeToPdf.convert(bombed, dir.resolve("bomb2.pdf"),
                    OPTIONS.allowPackages(true)));
        });
    }

    @Test
    void aPictureClaimingHugeDimensionsIsLeftOut() throws IOException {
        Path site = Files.createDirectories(dir.resolve("pics"));
        Files.write(site.resolve("huge.png"), png(200_000, 200_000));
        Path page = Files.writeString(site.resolve("p.md"), "Before\n\n![huge](huge.png)\n\nAfter\n", StandardCharsets.UTF_8);
        Path out = dir.resolve("huge.pdf");
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> OfficeToPdf.convert(page, out,
                OPTIONS.htmlAssets(HtmlAssets.folder(site, "p.md"))));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Before") && text.contains("After"), text);
        }
    }

    private static byte[] png(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        chunk(out, "IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) 8).put((byte) 2)
                .put((byte) 0).put((byte) 0).put((byte) 0).array());
        chunk(out, "IDAT", new byte[] {0x78, (byte) 0x9C, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01});
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(t);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    @Test
    void deepNestingConvertsInsteadOfOverflowing() throws IOException {
        String md = ">".repeat(5_000) + " quote\n\n" + "- ".repeat(5_000) + "list\n\n" + "**".repeat(50_000) + "x"
                + "**".repeat(50_000) + "\n\n" + "[".repeat(20_000) + "x" + "]".repeat(20_000) + "\n";
        Path page = Files.writeString(dir.resolve("deep.md"), md, StandardCharsets.UTF_8);
        Path out = dir.resolve("deep.pdf");
        assertTimeoutPreemptively(Duration.ofSeconds(90), () -> OfficeToPdf.convert(page, out, OPTIONS));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("quote") && text.contains("list") && text.contains("x"), text);
        }
    }
}
