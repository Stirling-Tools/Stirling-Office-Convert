package stirling.software.officeconvert.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConvertHandlerTest {

    private HttpServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    private String start(int timeoutSeconds) throws IOException {
        return start(timeoutSeconds, 0);
    }

    private String start(int timeoutSeconds, int maxPages) throws IOException {
        Limits limits = new Limits("127.0.0.1", 0, 64L << 20, maxPages, timeoutSeconds, 2, 4, 30, 0, null, 60, 100, false, "off",
                1, "");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/convert", new ConvertHandler(limits, null));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/convert";
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpResponse<byte[]> post(String url, byte[] body) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String header(HttpResponse<?> r, String name) {
        return r.headers().firstValue(name).orElse(null);
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private void assertPdf(HttpResponse<byte[]> r, String input, String expected) throws IOException {
        assertEquals(200, r.statusCode(), () -> new String(r.body()));
        assertEquals("application/pdf", header(r, "Content-Type"));
        assertEquals("ours", header(r, "X-Engine"));
        assertEquals(input, header(r, "X-Input"));
        assertEquals("1", header(r, "X-Pages"));
        assertTrue(Long.parseLong(header(r, "X-Convert-Ms")) >= 0);
        assertTrue(text(r.body()).contains(expected));
    }

    @Test
    void convertsWordPowerPointAndExcelToPdf() throws Exception {
        String url = start(60);
        assertPdf(post(url, Fixtures.docx("Word text", 3)), "docx", "Word text 3");
        assertPdf(post(url + "?format=pdf", Fixtures.pptx("Slide text")), "pptx", "Slide text");
        assertPdf(post(url + "?engine=ours", Fixtures.xlsx("Cell text")), "xlsx", "Cell text 4");
    }

    @Test
    void convertsLegacyExcelAndPowerPointToPdf() throws Exception {
        String url = start(60);
        assertPdf(post(url, Fixtures.xls("Old cell")), "xls", "Old cell 4");
        assertPdf(post(url + "?format=pdf", Fixtures.ppt("Old slide")), "ppt", "Old slide");
    }

    @Test
    void convertsPdfToFlatOpenDocumentXml() throws Exception {
        HttpResponse<byte[]> r = post(start(60) + "?format=xml", Fixtures.pdf("To XML"));
        assertEquals(200, r.statusCode(), () -> new String(r.body()));
        assertEquals("application/xml", header(r, "Content-Type"));
        String xml = new String(r.body(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(xml.contains("<office:document ") && xml.contains("To XML"), xml);
    }

    @Test
    void convertsMacroEnabledWordWithoutRunningAnything() throws Exception {
        byte[] docm = Fixtures.retype(Fixtures.docx("Macro file", 1), Fixtures.DOCX_MAIN,
                "application/vnd.ms-word.document.macroEnabled.main+xml");
        assertPdf(post(start(60), docm), "docm", "Macro file 1");
    }

    @Test
    void stillConvertsPdfsToOffice() throws Exception {
        HttpResponse<byte[]> r = post(start(60) + "?format=docx", Fixtures.pdf("From a PDF"));
        assertEquals(200, r.statusCode(), () -> new String(r.body()));
        assertEquals("ours", header(r, "X-Engine"));
        assertTrue(header(r, "Content-Type").contains("wordprocessingml"));
        assertEquals('P', r.body()[0]);
    }

    @Test
    void stopsAtThePageLimitAndSaysSo() throws Exception {
        HttpResponse<byte[]> r = post(start(60, 2), Fixtures.docx("A line", 400));
        assertEquals(200, r.statusCode(), () -> new String(r.body()));
        assertEquals("2", header(r, "X-Pages"));
        assertEquals("Converted the first 2 pages: this demo converts up to 2 pages at a time.", header(r, "X-Note"));
    }

    @Test
    void refusesTheWrongDirection() throws Exception {
        String url = start(60);
        HttpResponse<byte[]> office = post(url + "?format=docx", Fixtures.docx("x", 1));
        assertEquals(422, office.statusCode());
        assertEquals("format", header(office, "X-Error"));
        HttpResponse<byte[]> pdf = post(url + "?format=pdf", Fixtures.pdf("x"));
        assertEquals(422, pdf.statusCode());
        assertTrue(new String(pdf.body()).startsWith("This file is a PDF already"));
    }

    @Test
    void refusesFilesItCannotRead() throws Exception {
        String url = start(60);
        HttpResponse<byte[]> random = post(url, "just some text, not a document".getBytes());
        assertEquals(415, random.statusCode());
        assertEquals("type", header(random, "X-Error"));
        byte[] ole = new byte[2048];
        ole[0] = (byte) 0xd0;
        ole[1] = (byte) 0xcf;
        ole[2] = 0x11;
        ole[3] = (byte) 0xe0;
        HttpResponse<byte[]> legacy = post(url, ole);
        assertEquals(415, legacy.statusCode());
        assertTrue(new String(legacy.body()).contains("97-2003"));
        byte[] corrupt = new byte[4096];
        System.arraycopy(new byte[] {'P', 'K', 3, 4}, 0, corrupt, 0, 4);
        HttpResponse<byte[]> damaged = post(url, corrupt);
        assertEquals(422, damaged.statusCode());
        assertEquals("damaged", header(damaged, "X-Error"));
    }

    @Test
    void aDocumentTheConverterRefusesIsAPlainError() throws Exception {
        byte[] hostile = Fixtures.zip("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/"
                + "content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"" + Fixtures.DOCX_MAIN
                + "\"/></Types>", "word/document.xml", "<!DOCTYPE d [<!ENTITY e SYSTEM \"http://127.0.0.1:9/x\">]><d>&e;</d>");
        HttpResponse<byte[]> r = post(start(60), hostile);
        assertEquals(422, r.statusCode(), () -> new String(r.body()));
        assertEquals("convert", header(r, "X-Error"));
        assertTrue(new String(r.body()).contains("DOCTYPE"), () -> new String(r.body()));
    }

    @Test
    void libreOfficeIsRefusedWhenItIsNotInstalled() throws Exception {
        HttpResponse<byte[]> r = post(start(60) + "?engine=libreoffice", Fixtures.docx("x", 1));
        assertEquals(422, r.statusCode());
        assertEquals("engine", header(r, "X-Error"));
    }

    @Test
    void errorMessagesLeaveOutClassNamesAndStackLines() {
        assertEquals("Malformed XML: The document is damaged: the part /word/document.xml cannot be read",
                ConvertHandler.plain("Malformed XML: stirling.software.officeconvert.topdf.io.OfficeZip$DamagedPart: The document"
                        + " is damaged: the part /word/document.xml cannot be read\n\tat somewhere"));
        assertEquals("This document could not be converted.", ConvertHandler.plain(null));
    }

    @Test
    void aLongDocumentTimesOut() throws Exception {
        HttpResponse<byte[]> r = post(start(1), Fixtures.longDocx(300_000));
        assertEquals(504, r.statusCode(), () -> r.statusCode() + " " + header(r, "X-Pages"));
        assertEquals("timeout", header(r, "X-Error"));
        assertTrue(new String(r.body()).startsWith("This document took longer than the 1 seconds"));
    }
}
