package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MailHostileTest {

    private static final String CANARY = "CANARY-7f3a91-SECRET";

    @TempDir
    Path dir;

    private String convertText(String name, byte[] bytes) throws IOException {
        Path out = convert(name, bytes);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private Path convert(String name, byte[] bytes) throws IOException {
        Path inputs = Files.createDirectories(dir.resolve("in"));
        Path in = Files.write(inputs.resolve(name), bytes);
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.convert(in, out);
        return out;
    }

    private static int images(PDDocument doc) throws IOException {
        int n = 0;
        for (PDPage page : doc.getPages()) {
            for (var x : page.getResources().getXObjectNames()) {
                if (page.getResources().getXObject(x) instanceof PDImageXObject) {
                    n++;
                }
            }
        }
        return n;
    }

    private static byte[] ascii(String s) {
        return s.replace("\n", "\r\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String png(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, Color.RED.getRGB());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return Base64.getMimeEncoder().encodeToString(out.toByteArray());
    }

    @Test
    void remotePicturesStyleSheetsImportsAndFramesAreNeverFetched() throws Exception {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        try {
            server.setSoTimeout(200);
            AtomicInteger hits = new AtomicInteger();
            Thread listener = Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket s = server.accept();
                        hits.incrementAndGet();
                        s.close();
                    } catch (SocketTimeoutException e) {
                        continue;
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            String base = "http://127.0.0.1:" + server.getLocalPort();
            String html = "<html><head><link rel=stylesheet href=\"" + base + "/a.css\"><style>@import url(" + base
                    + "/b.css); body{background:url(" + base + "/c.png)} @font-face{font-family:X;src:url(" + base
                    + "/f.woff)}</style></head><body><p>Tracked newsletter</p><img src=\"" + base + "/pixel.gif\""
                    + " width=1 height=1><img src=\"//127.0.0.1:" + server.getLocalPort() + "/p2.gif\"><iframe src=\""
                    + base + "/frame.html\"></iframe><video poster=\"" + base + "/v.png\"></video></body></html>";
            String mail = "From: a@example.org\nTo: b@example.org\nSubject: Pixel\nMIME-Version: 1.0\n"
                    + "Content-Type: text/html; charset=utf-8\n\n" + html + "\n";
            assertTrue(convertText("pixel.eml", ascii(mail)).contains("Tracked newsletter"));
            String archive = "From: <Saved by Blink>\nSnapshot-Content-Location: " + base + "/page\nMIME-Version: 1.0\n"
                    + "Content-Type: multipart/related; type=\"text/html\"; boundary=\"b\"\n\n--b\n"
                    + "Content-Type: text/html\nContent-Location: " + base + "/page\n\n" + html + "\n--b--\n";
            assertTrue(convertText("remote.mhtml", ascii(archive)).contains("Tracked newsletter"));
            server.close();
            listener.join();
            assertEquals(0, hits.get());
        } finally {
            server.close();
        }
    }

    @Test
    void filesBesideOrOutsideTheInputAreNeverRead() throws IOException {
        Path secret = Files.writeString(dir.resolve("canary.txt"), CANARY);
        Files.write(dir.resolve("canary.png"), Base64.getMimeDecoder().decode(png(40, 20)));
        Files.write(Files.createDirectories(dir.resolve("in")).resolve("beside.png"),
                Base64.getMimeDecoder().decode(png(40, 20)));
        String uri = secret.toUri().toString();
        String pngUri = dir.resolve("canary.png").toUri().toString();
        String html = "<html><head><link rel=stylesheet href=\"" + uri + "\"><style>@import url(\"../canary.txt\");"
                + " p{background:url(" + pngUri + ")}</style></head><body><p>Body</p><img src=\"" + pngUri + "\">"
                + "<img src=\"../canary.png\"><img src=\"beside.png\"><img src=\"cid:xxe\"><iframe src=\"" + uri
                + "\"></iframe><object data=\"" + uri + "\"></object></body></html>";
        String svg = "<?xml version=\"1.0\"?><!DOCTYPE svg [<!ENTITY s SYSTEM \"" + uri + "\">]>"
                + "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"200\" height=\"40\"><text y=\"20\">&s;</text></svg>";
        String mail = "From: a@example.org\nTo: b@example.org\nSubject: Paths\nMIME-Version: 1.0\n"
                + "Content-Type: multipart/related; boundary=r\n\n--r\nContent-Type: text/html\n\n" + html + "\n"
                + "--r\nContent-Type: image/svg+xml\nContent-ID: <xxe>\n\n" + svg + "\n--r--\n";
        Path out = convert("paths.eml", ascii(mail));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            assertFalse(text.contains(CANARY), text);
            assertTrue(text.contains("Body"), text);
            assertTrue(images(doc) <= 1);
        }
        String archive = "MIME-Version: 1.0\nContent-Type: multipart/related; boundary=\"n\"\n\n--n\n"
                + "Content-Location: file:///C:/D/saved.htm\nContent-Type: text/html\n\n" + html + "\n--n\n"
                + "Content-Type: image/svg+xml\nContent-ID: <xxe>\n\n" + svg + "\n--n--\n";
        out = convert("paths.mht", ascii(archive));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertFalse(new PDFTextStripper().getText(doc).contains(CANARY));
            assertTrue(images(doc) <= 1);
        }
    }

    @Test
    void scriptsHandlersFormsAndUnsafeLinksStayInert() throws IOException {
        String html = "<html><body onload=\"document.body.innerHTML='ONLOAD-RAN'\"><script>document.write('SCRIPT-RAN')"
                + "</script><noscript>Shown without scripts</noscript><p onclick=\"x()\">Click text</p>"
                + "<a href=\"javascript:alert(1)\">Js link</a> <a href=\"file:///etc/passwd\">File link</a> "
                + "<a href=\"https://example.org/\">Web link</a> <a href=\"mailto:a@example.org\">Mail link</a>"
                + "<form action=\"https://evil.example/\"><input name=q value=\"typed\"><button>Send</button></form>"
                + "</body></html>";
        String mail = "From: a@example.org\nSubject: Scripts\nMIME-Version: 1.0\nContent-Type: text/html\n\n" + html;
        Path out = convert("scripts.eml", ascii(mail));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            assertFalse(text.contains("SCRIPT-RAN"), text);
            assertFalse(text.contains("ONLOAD-RAN"), text);
            assertTrue(text.contains("Click text"), text);
            assertNull(doc.getDocumentCatalog().getNames() == null ? null
                    : doc.getDocumentCatalog().getNames().getJavaScript());
            assertNull(doc.getDocumentCatalog().getOpenAction());
            for (PDPage page : doc.getPages()) {
                for (PDAnnotation a : page.getAnnotations()) {
                    if (a instanceof PDAnnotationLink link) {
                        PDAction action = link.getAction();
                        if (action != null) {
                            assertTrue(action instanceof PDActionURI, action.getClass().getName());
                            String target = ((PDActionURI) action).getURI();
                            assertTrue(target.startsWith("https:") || target.startsWith("http:")
                                    || target.startsWith("mailto:"), target);
                        }
                    }
                }
            }
        }
    }

    @Test
    void headerValuesAreShownAsTextNotMarkup() throws IOException {
        String mail = "From: \"<img src=x onerror=alert(1)>\" <a@example.org>\nTo: b@example.org\n"
                + "Subject: <b>Bold?</b></div><script>x()</script>{\\rtf injected}\nMIME-Version: 1.0\n"
                + "Content-Type: text/plain\n\nBody\n";
        String text = convertText("inject.eml", ascii(mail));
        assertTrue(text.contains("<b>Bold?</b></div><script>x()</script>{\\rtf injected}"), text);
        assertTrue(text.contains("<img src=x onerror=alert(1)>"), text);
        byte[] msg = MailToPdfTest.msg("<b>Bold?</b>}{\\b injected", "x}{\\par", "a@example.org", "c", null,
                "{\\rtf1\\ansi{\\fonttbl{\\f0 Arial;}}\\pard Body\\par}");
        text = convertText("inject.msg", msg);
        assertTrue(text.contains("<b>Bold?</b>}{\\b injected"), text);
    }

    @Test
    void deepNestingIsCutOffWithoutRunningOutOfStack() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            StringBuilder mail = new StringBuilder("From: a@example.org\nSubject: Deep\nMIME-Version: 1.0\n");
            for (int i = 0; i < 5_000; i++) {
                mail.append("Content-Type: multipart/mixed; boundary=b").append(i).append("\n\n--b").append(i)
                        .append('\n');
            }
            mail.append("Content-Type: text/plain\n\nInnermost\n");
            assertTrue(convertText("deep.eml", ascii(mail.toString())).contains("Deep"));
            StringBuilder fwd = new StringBuilder();
            for (int i = 0; i < 2_000; i++) {
                fwd.append("From: x").append(i).append("@example.org\nSubject: Level ").append(i)
                        .append("\nContent-Type: message/rfc822\n\n");
            }
            fwd.append("Content-Type: text/plain\n\nBottom\n");
            assertTrue(convertText("fwd.eml", ascii(fwd.toString())).contains("Level 0"));
            String rtf = "{\\rtf1\\ansi\\fromhtml1 " + "{".repeat(200_000) + "\\htmlrtf0 deep" + "}".repeat(200_000)
                    + "{\\*\\htmltag <p>Shallow</p>}}";
            assertTrue(convertText("deep.msg", MailToPdfTest.msg("Deep RTF", "a", "a@example.org", "b", null, rtf))
                    .contains("Deep RTF"));
        });
    }

    @Test
    void aCompressedRtfBombFallsBackToTheTextBody() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            String text = convertText("bomb.msg", bombMsg());
            assertTrue(text.contains("Bomb"), text);
            assertTrue(text.contains("Plain body that should not be shown"), text);
        });
    }

    @Test
    void aPictureClaimingHugeDimensionsIsNotDecodedInFull() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            String mail = "From: a@example.org\nSubject: Huge picture\nMIME-Version: 1.0\n"
                    + "Content-Type: multipart/related; boundary=r\n\n--r\nContent-Type: text/html\n\n"
                    + "<p>Before</p><img src=\"cid:big\"><p>After</p>\n--r\nContent-Type: image/png\n"
                    + "Content-ID: <big>\nContent-Transfer-Encoding: base64\n\n"
                    + Base64.getMimeEncoder().encodeToString(hugePng(100_000, 100_000)) + "\n--r--\n";
            String text = convertText("huge.eml", ascii(mail));
            assertTrue(text.contains("Before") && text.contains("After"), text);
        });
    }

    @Test
    void aMailHeaderBlockDoesNotMakeATextFileAnEmail() throws IOException {
        String text = convertText("notes.txt", ascii("From: a@example.org\nSubject: kept as text\n\n<b>raw</b>\n"));
        assertTrue(text.contains("From: a@example.org"), text);
        assertTrue(text.contains("<b>raw</b>"), text);
    }

    private record Embedded(String text, Map<String, byte[]> files, Map<String, String> types, boolean names,
            boolean active, int pages) {}

    private Embedded embedded(String name, byte[] mail, OfficeToPdf.MailAttachments limits) throws IOException {
        Path inputs = Files.createDirectories(dir.resolve("in"));
        Path in = Files.write(inputs.resolve(name), mail);
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.Options options = OfficeToPdf.Options.defaults();
        OfficeToPdf.convert(in, out, limits == null ? options : options.mailAttachments(limits));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            Map<String, byte[]> files = new TreeMap<>();
            Map<String, String> types = new TreeMap<>();
            PDDocumentNameDictionary names = doc.getDocumentCatalog().getNames();
            if (names != null && names.getEmbeddedFiles() != null) {
                for (var e : names.getEmbeddedFiles().getNames().entrySet()) {
                    PDEmbeddedFile ef = e.getValue().getEmbeddedFile();
                    files.put(e.getKey(), ef.toByteArray());
                    types.put(e.getKey(), ef.getSubtype());
                }
            }
            boolean active = doc.getDocumentCatalog().getOpenAction() != null
                    || names != null && names.getJavaScript() != null
                    || doc.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.AA) != null;
            return new Embedded(new PDFTextStripper().getText(doc).replace('\u00A0', ' '), files, types, names != null,
                    active, doc.getNumberOfPages());
        }
    }

    private static String flat(String s) {
        return s.replaceAll("\\s+", "");
    }

    private static String attachment(String disposition, String type, byte[] data) {
        return "--b\nContent-Type: " + type + "\nContent-Disposition: " + disposition
                + "\nContent-Transfer-Encoding: base64\n\n" + Base64.getMimeEncoder().encodeToString(data) + "\n";
    }

    @Test
    void attachmentsAreEmbeddedUnopenedOnlyWhenAsked() throws IOException {
        byte[] js = ("%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R/OpenAction<</S/JavaScript/JS(app.alert(1))>>"
                + "/Names<</JavaScript<</Names[(a)<</S/JavaScript/JS(app.launchURL('http://evil.example'))>>]>>>>>>endobj\n"
                + "2 0 obj<</Type/Pages/Kids[]/Count 0>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] exe = new byte[300];
        exe[0] = 'M';
        exe[1] = 'Z';
        String mail = "From: a@example.org\nTo: b@example.org\nSubject: Files\nMIME-Version: 1.0\n"
                + "Content-Type: multipart/mixed; boundary=b\n\n--b\nContent-Type: text/plain\n\nSee attached.\n"
                + attachment("attachment; filename=\"invoice.pdf\"", "application/pdf", js)
                + attachment("attachment; filename=\"../../../../etc/passwd\"; size=999999999999", "text/plain",
                        "root:x:0:0".getBytes(StandardCharsets.US_ASCII))
                + attachment("attachment; filename=\"C:\\\\Windows\\\\..\\\\evil.exe\"", "application/x-msdownload", exe)
                + attachment("attachment; filename*=UTF-8''report%E2%80%AEfdp.exe", "../../weird type", exe)
                + attachment("attachment; filename=\"..\"", "text/plain", new byte[] {1})
                + attachment("attachment; filename=\"CON.txt\"", "text/plain", new byte[] {2})
                + "--b--\n";
        Embedded off = embedded("files-off.eml", ascii(mail), null);
        assertTrue(off.files().isEmpty() && !off.names());
        assertTrue(flat(off.text()).contains(flat("invoice.pdf (application/pdf, " + js.length + " bytes)")), off.text());
        Embedded on = embedded("files-on.eml", ascii(mail), OfficeToPdf.MailAttachments.defaults());
        assertEquals(Set.of("invoice.pdf", "passwd", "evil.exe", "report_fdp.exe", "attachment-5", "_CON.txt"),
                on.files().keySet());
        assertTrue(java.util.Arrays.equals(js, on.files().get("invoice.pdf")));
        assertEquals("application/pdf", on.types().get("invoice.pdf"));
        assertEquals("application/octet-stream", on.types().get("report_fdp.exe"));
        assertEquals(10, on.files().get("passwd").length);
        assertTrue(flat(on.text()).contains(flat("invoice.pdf (application/pdf, " + js.length
                + " bytes, attached to this PDF)")), on.text());
        assertTrue(flat(on.text()).contains(flat("attached to this PDF as passwd")), on.text());
        assertFalse(on.active());
        assertEquals(1, on.pages());
    }

    @Test
    void attachmentLimitsLeaveLargeFilesListedButNotEmbedded() throws IOException {
        String mail = "From: a@example.org\nSubject: Limits\nMIME-Version: 1.0\nContent-Type: multipart/mixed; boundary=b\n\n"
                + "--b\nContent-Type: text/plain\n\nBody\n"
                + attachment("attachment; filename=small.bin", "application/octet-stream", new byte[500])
                + attachment("attachment; filename=big.bin", "application/octet-stream", new byte[5_000])
                + attachment("attachment; filename=second.bin", "application/octet-stream", new byte[800])
                + "--b--\n";
        Embedded e = embedded("limits.eml", ascii(mail), new OfficeToPdf.MailAttachments(1_024, 1_200));
        assertEquals(Set.of("small.bin"), e.files().keySet());
        assertTrue(flat(e.text()).contains(flat("big.bin (application/octet-stream, 4.9 KB, not attached: larger"
                + " than 1.0 KB)")), e.text());
        assertTrue(flat(e.text()).contains(flat("second.bin (application/octet-stream, 800 bytes, not attached: over the 1.2 KB"
                + " total)")), e.text());
    }

    @Test
    void embeddedAttachmentsSurvivePageNumbersAndContents() throws IOException {
        String mail = "From: a@example.org\nSubject: Numbered\nMIME-Version: 1.0\nContent-Type: multipart/mixed; boundary=b\n\n"
                + "--b\nContent-Type: text/html\n\n<h1>Heading</h1><p>Body</p>\n"
                + attachment("attachment; filename=kept.txt", "text/plain", "kept".getBytes(StandardCharsets.US_ASCII))
                + "--b--\n";
        Path in = Files.write(Files.createDirectories(dir.resolve("in")).resolve("numbered.eml"), ascii(mail));
        Path out = dir.resolve("numbered.pdf");
        OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().mailAttachments(OfficeToPdf.MailAttachments.defaults())
                .pageNumbers(true).tableOfContents(true));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            PDDocumentNameDictionary names = doc.getDocumentCatalog().getNames();
            assertTrue(names != null && names.getEmbeddedFiles() != null
                    && names.getEmbeddedFiles().getNames().containsKey("kept.txt"));
        }
    }

    @Test
    void attachmentsOfDeeplyNestedMessagesStayBounded() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            StringBuilder m = new StringBuilder();
            for (int i = 0; i < 300; i++) {
                m.append("From: x").append(i).append("@example.org\nSubject: Level ").append(i)
                        .append("\nMIME-Version: 1.0\nContent-Type: multipart/mixed; boundary=b").append(i).append("\n\n--b")
                        .append(i).append("\nContent-Type: application/octet-stream\nContent-Disposition: attachment;"
                                + " filename=same.bin\n\nx\n--b").append(i).append("\nContent-Type: message/rfc822\n\n");
            }
            m.append("Subject: Bottom\n\nBottom\n");
            Embedded e = embedded("nested-files.eml", ascii(m.toString()), OfficeToPdf.MailAttachments.defaults());
            assertTrue(e.files().size() >= 1 && e.files().size() <= 9, e.files().keySet().toString());
            assertTrue(e.files().containsKey("same.bin") && e.files().containsKey("same (2).bin"));
        });
    }

    private static byte[] hugePng(int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        chunk(out, "IHDR", ByteBuffer.allocate(13).putInt(w).putInt(h).put((byte) 8).put((byte) 2).put((byte) 0)
                .put((byte) 0).put((byte) 0).array());
        Deflater d = new Deflater();
        d.setInput(new byte[1 << 16]);
        d.finish();
        byte[] buf = new byte[1 << 16];
        int n = d.deflate(buf);
        d.end();
        chunk(out, "IDAT", java.util.Arrays.copyOf(buf, n));
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        out.write(ByteBuffer.allocate(4).putInt(data.length).array());
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.write(t);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    private static byte[] bombMsg() throws IOException {
        int runs = 600_000;
        ByteArrayOutputStream body = new ByteArrayOutputStream(runs * 17 + 8);
        int at = 207;
        for (int i = 0; i < runs; i++) {
            body.write(0xFF);
            for (int k = 0; k < 8; k++) {
                int offset = (at + 2_048) % 4_096;
                body.write(offset >> 4);
                body.write((offset & 0xF) << 4 | 0xF);
                at = (at + 17) % 4_096;
            }
        }
        byte[] data = body.toByteArray();
        ByteBuffer b = ByteBuffer.allocate(16 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(data.length + 12).putInt(Integer.MAX_VALUE).putInt(0x75465A4C).putInt(0).put(data);
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            DirectoryEntry root = fs.getRoot();
            string(root, "001A", "IPM.Note");
            string(root, "0037", "Bomb");
            string(root, "1000", "Plain body that should not be shown");
            root.createDocument("__substg1.0_10090102", new ByteArrayInputStream(b.array()));
            root.createDocument("__properties_version1.0", new ByteArrayInputStream(new byte[32]));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            fs.writeFilesystem(out);
            return out.toByteArray();
        }
    }

    private static void string(DirectoryEntry root, String id, String value) throws IOException {
        root.createDocument("__substg1.0_" + id + "001F",
                new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_16LE)));
    }
}
