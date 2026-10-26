package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import javax.imageio.ImageIO;
import javax.net.ssl.SSLSocketFactory;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class WebAssetsTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private final List<String> hits = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> cookiesSeen = new ConcurrentHashMap<>();
    private final Map<String, String> hostsSeen = new ConcurrentHashMap<>();
    private final CountDownLatch release = new CountDownLatch(1);

    private static final WebAssets.Access LOCAL = WebAssets.Access.none().network(true).privateAddresses(true);

    private static final Predicate<InetAddress> LOOPBACK_ONLY = a -> !a.isLoopbackAddress() && WebAssets.refused(a);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        hits.add(path);
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie != null) {
            cookiesSeen.put(path, cookie);
        }
        hostsSeen.put(path, ex.getRequestHeaders().getFirst("Host"));
        switch (path) {
            case "/page.html" -> {
                ex.getResponseHeaders().add("Set-Cookie", "session=abc123; Path=/; HttpOnly");
                send(ex, 200, "text/html; charset=utf-8", page().getBytes(StandardCharsets.UTF_8), false);
            }
            case "/latin1.html" -> send(ex, 200, "text/html; charset=ISO-8859-1",
                    "<html><body><p>Café crème</p></body></html>".getBytes(StandardCharsets.ISO_8859_1), false);
            case "/style.css" -> send(ex, 200, "text/css", "h1{color:#c00}".getBytes(StandardCharsets.UTF_8), true);
            case "/img/red.png" -> send(ex, 200, "image/png", png(), false);
            case "/big" -> send(ex, 200, "application/octet-stream", new byte[200_000], false);
            case "/to-page" -> redirect(ex, "/page.html");
            case "/to-metadata" -> redirect(ex, "http://169.254.169.254/latest/meta-data/");
            case "/to-private" -> redirect(ex, "http://intranet.test:" + server.getAddress().getPort() + "/page.html");
            case "/loop" -> redirect(ex, "/loop");
            case "/to-file" -> redirect(ex, "file:///etc/passwd");
            case "/slow" -> {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                send(ex, 200, "text/html", "<p>late</p>".getBytes(StandardCharsets.UTF_8), false);
            }
            case "/missing" -> send(ex, 404, "text/plain", new byte[0], false);
            default -> send(ex, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8), false);
        }
    }

    private static String page() {
        return "<!DOCTYPE html><html><head><link rel=\"stylesheet\" href=\"style.css\"></head><body>"
                + "<h1>Fetched heading</h1><p>Body text from the server.</p><img src=\"img/red.png\" width=\"80\""
                + " height=\"40\"></body></html>";
    }

    private static void redirect(HttpExchange ex, String to) throws IOException {
        ex.getResponseHeaders().add("Location", to);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body, boolean chunked)
            throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, chunked ? 0 : body.length == 0 ? -1 : body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static byte[] png() throws IOException {
        BufferedImage img = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 40, 20);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static WebAssets fetch(String url, WebAssets.Access access, WebAssets.Resolver resolver,
            Predicate<InetAddress> refused) throws IOException {
        return WebAssets.fetch(url, access, resolver, refused, (SSLSocketFactory) SSLSocketFactory.getDefault());
    }

    @Test
    void everySwitchIsOffByDefault() {
        WebAssets.Access none = WebAssets.Access.none();
        assertFalse(none.network());
        assertFalse(none.redirects());
        assertFalse(none.cookies());
        assertFalse(none.privateAddresses());
    }

    @Test
    void nothingIsFetchedWhileNetworkAccessIsOff() {
        IOException e = assertThrows(IOException.class,
                () -> WebAssets.fetch(base() + "/page.html", WebAssets.Access.none().privateAddresses(true)));
        assertTrue(e.getMessage().contains("Network access is off"), e.getMessage());
        assertTrue(hits.isEmpty());
    }

    @Test
    void loopbackIsRefusedUnlessPrivateAddressesAreAllowed() throws IOException {
        IOException e = assertThrows(IOException.class,
                () -> WebAssets.fetch(base() + "/page.html", WebAssets.Access.none().network(true)));
        assertTrue(e.getMessage().contains("local, private or reserved"), e.getMessage());
        assertTrue(hits.isEmpty());
        WebAssets web = WebAssets.fetch(base() + "/page.html", LOCAL);
        assertEquals(List.of("/page.html"), hits);
        assertEquals(base() + "/page.html", web.url());
        assertEquals(page(), new String(web.page(), StandardCharsets.UTF_8));
    }

    @Test
    void onlyHttpAndHttpsAreFetched() {
        for (String url : List.of("file:///etc/passwd", "ftp://127.0.0.1/x", "jar:file:/x.jar!/a", "gopher://h/",
                "http://user:secret@127.0.0.1/", "data:text/html,hi")) {
            assertThrows(IOException.class, () -> WebAssets.fetch(url, LOCAL), url);
        }
        assertTrue(hits.isEmpty());
    }

    @Test
    void linkedResourcesResolveAgainstThePageAndKeepToTheirBudget() throws IOException {
        WebAssets web = WebAssets.fetch(base() + "/page.html", LOCAL);
        assertArrayEquals(png(), web.read(base() + "/img/red.png"));
        assertEquals("h1{color:#c00}", new String(web.read(base() + "/style.css"), StandardCharsets.UTF_8));
        assertNull(web.read("img/red.png"));
        assertNull(web.read("file:///etc/passwd"));
        assertNull(web.read(base() + "/missing"));
        WebAssets small = WebAssets.fetch(base() + "/page.html", LOCAL.maxBytes(100_000));
        assertNull(small.read(base() + "/big"));
        WebAssets few = WebAssets.fetch(base() + "/page.html", LOCAL.maxRequests(2));
        assertTrue(few.read(base() + "/style.css") != null);
        assertNull(few.read(base() + "/img/red.png"));
    }

    @Test
    void nonAsciiPathsArePercentEncodedAndBadHostsAreRefused() throws IOException {
        WebAssets.fetch(base() + "/caf\u00e9 menu.html".replace(" ", "%20"), LOCAL);
        assertEquals(List.of("/caf\u00e9 menu.html"), hits);
        assertThrows(IOException.class, () -> WebAssets.fetch("http://a..b/", LOCAL));
    }

    @Test
    void aDeclaredCharsetBecomesUtf8WithAByteOrderMark() throws IOException {
        byte[] page = WebAssets.fetch(base() + "/latin1.html", LOCAL).page();
        assertEquals(0xEF, page[0] & 0xFF);
        assertTrue(new String(page, StandardCharsets.UTF_8).contains("Café crème"));
    }

    @Test
    void redirectsAreFollowedOnlyWhenAllowedAndEveryHopIsChecked() throws IOException {
        IOException off = assertThrows(IOException.class, () -> WebAssets.fetch(base() + "/to-page", LOCAL));
        assertTrue(off.getMessage().contains("redirects are allowed"), off.getMessage());
        assertEquals(List.of("/to-page"), hits);

        WebAssets web = WebAssets.fetch(base() + "/to-page", LOCAL.redirects(true));
        assertEquals(base() + "/page.html", web.url());

        IOException metadata = assertThrows(IOException.class,
                () -> WebAssets.fetch(base() + "/to-metadata", LOCAL.redirects(true)));
        assertTrue(metadata.getMessage().contains("local, private or reserved"), metadata.getMessage());
        assertThrows(IOException.class, () -> WebAssets.fetch(base() + "/to-file", LOCAL.redirects(true)));

        hits.clear();
        assertThrows(IOException.class, () -> WebAssets.fetch(base() + "/loop", LOCAL.redirects(true)));
        assertEquals(WebAssets.Access.DEFAULT_MAX_REDIRECTS + 1, hits.size());
    }

    @Test
    void aRedirectToAPrivateHostIsRefused() {
        WebAssets.Resolver resolver = host -> host.equals("intranet.test")
                ? new InetAddress[] {InetAddress.getByAddress(host, new byte[] {10, 0, 0, 7})}
                : InetAddress.getAllByName(host);
        IOException e = assertThrows(IOException.class, () -> fetch(base() + "/to-private",
                WebAssets.Access.none().network(true).redirects(true), resolver, LOOPBACK_ONLY));
        assertTrue(e.getMessage().contains("intranet.test"), e.getMessage());
        assertEquals(List.of("/to-private"), hits);
    }

    @Test
    void theConnectionGoesToTheAddressThatWasChecked() throws IOException {
        AtomicInteger lookups = new AtomicInteger();
        WebAssets.Resolver rebinding = host -> new InetAddress[] {lookups.getAndIncrement() == 0
                ? InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1})
                : InetAddress.getByAddress(host, new byte[] {(byte) 169, (byte) 254, (byte) 169, (byte) 254})};
        int port = server.getAddress().getPort();
        WebAssets web = fetch("http://rebind.test:" + port + "/page.html", WebAssets.Access.none().network(true),
                rebinding, LOOPBACK_ONLY);
        assertEquals(1, lookups.get());
        assertEquals("rebind.test:" + port, hostsSeen.get("/page.html"));
        assertNull(web.read("http://rebind.test:" + port + "/style.css"));
        assertEquals(List.of("/page.html"), hits);
    }

    @Test
    void anyRefusedAddressInTheAnswerRefusesTheHost() {
        WebAssets.Resolver mixed = host -> new InetAddress[] {
                InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1}),
                InetAddress.getByAddress(host, new byte[] {10, 1, 2, 3})};
        assertThrows(IOException.class, () -> fetch("http://mixed.test:" + server.getAddress().getPort() + "/page.html",
                WebAssets.Access.none().network(true), mixed, LOOPBACK_ONLY));
        assertTrue(hits.isEmpty());
    }

    @Test
    void cookiesAreKeptOnlyWhenAllowed() throws IOException {
        WebAssets plain = WebAssets.fetch(base() + "/page.html", LOCAL);
        plain.read(base() + "/style.css");
        assertNull(cookiesSeen.get("/style.css"));
        WebAssets kept = WebAssets.fetch(base() + "/page.html", LOCAL.cookies(true));
        kept.read(base() + "/style.css");
        assertEquals("session=abc123", cookiesSeen.get("/style.css"));
    }

    @Test
    void aServerThatDoesNotAnswerIsAbandonedAtTheTimeout() {
        long start = System.nanoTime();
        IOException e = assertThrows(IOException.class,
                () -> WebAssets.fetch(base() + "/slow", LOCAL.timeout(Duration.ofMillis(500))));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 10_000, e.getMessage());
    }

    @Test
    void aServerThatDripsBytesIsAbandonedAtTheTimeout() throws Exception {
        try (ServerSocket drip = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread t = Thread.ofVirtual().start(() -> {
                try (Socket s = drip.accept()) {
                    OutputStream out = s.getOutputStream();
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    while (!release.await(100, TimeUnit.MILLISECONDS)) {
                        out.write('x');
                        out.flush();
                    }
                } catch (IOException | InterruptedException e) {
                    return;
                }
            });
            long start = System.nanoTime();
            assertThrows(IOException.class, () -> WebAssets.fetch("http://127.0.0.1:" + drip.getLocalPort() + "/",
                    LOCAL.timeout(Duration.ofMillis(800))));
            assertTrue((System.nanoTime() - start) / 1_000_000 < 10_000);
            release.countDown();
            t.join(5_000);
        }
    }

    @Test
    void publicFetchesRefuseEveryLocalPrivateAndReservedForm() throws IOException {
        List<InetAddress> refused = List.of(v4(127, 0, 0, 1), v4(127, 9, 9, 9), v4(10, 1, 2, 3), v4(172, 16, 0, 1),
                v4(172, 31, 255, 255), v4(192, 168, 1, 1), v4(100, 64, 0, 1), v4(169, 254, 169, 254), v4(0, 0, 0, 0),
                v4(224, 0, 0, 1), v4(255, 255, 255, 255), v4(192, 0, 0, 170), v4(198, 18, 0, 1),
                v6("::1"), v6("::"), v6("fe80::1"), v6("fc00::1"), v6("fd00:ec2::254"), v6("ff02::1"),
                mapped(127, 0, 0, 1), mapped(169, 254, 169, 254), mapped(10, 0, 0, 1), compatible(127, 0, 0, 1),
                v6("64:ff9b::a9fe:a9fe"), v6("64:ff9b::7f00:1"), v6("2002:7f00:1::"), v6("2002:a9fe:a9fe::"),
                v6("2001::1"), v6("2001:db8::1"), v6("fec0::1"));
        for (InetAddress a : refused) {
            assertTrue(WebAssets.refused(a), a.toString());
        }
        List<InetAddress> allowed = List.of(v4(8, 8, 8, 8), v4(1, 1, 1, 1), v4(172, 32, 0, 1), v4(100, 128, 0, 1),
                v6("2606:4700::1111"), mapped(8, 8, 8, 8), v6("2002:808:808::"));
        for (InetAddress a : allowed) {
            assertFalse(WebAssets.refused(a), a.toString());
        }
    }

    @Test
    void intranetFetchesStillRefuseLinkLocalMetadataAndReservedAddresses() throws IOException {
        for (InetAddress a : List.of(v4(127, 0, 0, 1), v4(10, 1, 2, 3), v4(192, 168, 0, 1), v6("::1"),
                v6("fd12::1"))) {
            assertFalse(WebAssets.reserved(a), a.toString());
        }
        for (InetAddress a : List.of(v4(169, 254, 169, 254), mapped(169, 254, 169, 254), v6("fe80::1"),
                v4(0, 0, 0, 0), v6("::"), v4(239, 1, 1, 1), v6("64:ff9b::a9fe:a9fe"))) {
            assertTrue(WebAssets.reserved(a), a.toString());
        }
    }

    @Test
    void aFetchedPageConvertsWithItsLinkedStyleSheetAndPicture() throws IOException {
        Path out = dir.resolve("page.pdf");
        OfficeToPdf.convertUrl(base() + "/page.html", out, OfficeToPdf.Options.defaults(), LOCAL);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Fetched heading"), text);
            int images = 0;
            for (var name : doc.getPage(0).getResources().getXObjectNames()) {
                if (doc.getPage(0).getResources().getXObject(name) instanceof PDImageXObject) {
                    images++;
                }
            }
            assertEquals(1, images);
        }
        assertTrue(hits.containsAll(List.of("/page.html", "/style.css", "/img/red.png")), hits.toString());
    }

    @Test
    void anHtmlFileFetchesWhatItLinksOnlyWithItsOwnSwitchAndTheSameGuards() throws IOException {
        java.nio.file.Path page = dir.resolve("upload.html");
        java.nio.file.Files.writeString(page, "<html><head><link rel=stylesheet href=\"" + base() + "/style.css\">"
                + "</head><body><h1>Uploaded</h1><img src=\"" + base() + "/img/red.png\" width=40 height=20>"
                + "<img src=\"//127.0.0.1:" + server.getAddress().getPort() + "/img/red.png\" width=40 height=20>"
                + "</body></html>");
        Path plain = dir.resolve("plain.pdf");
        OfficeToPdf.convert(page, plain, OfficeToPdf.Options.defaults());
        assertTrue(hits.isEmpty(), hits.toString());
        assertThrows(IOException.class, () -> WebAssets.linkedFrom(HtmlAssets.NONE, LOCAL));
        assertThrows(IOException.class, () -> WebAssets.linkedFrom(HtmlAssets.NONE,
                WebAssets.Access.none().linkedFromFiles(true)));
        Path guarded = dir.resolve("guarded.pdf");
        OfficeToPdf.convert(page, guarded, OfficeToPdf.Options.defaults().htmlAssets(WebAssets.linkedFrom(
                HtmlAssets.NONE, WebAssets.Access.none().network(true).linkedFromFiles(true))));
        assertTrue(hits.isEmpty(), hits.toString());
        Path fetched = dir.resolve("fetched.pdf");
        OfficeToPdf.convert(page, fetched, OfficeToPdf.Options.defaults().htmlAssets(WebAssets.linkedFrom(
                HtmlAssets.NONE, LOCAL.linkedFromFiles(true))));
        assertTrue(hits.containsAll(List.of("/style.css", "/img/red.png")), hits.toString());
        try (PDDocument doc = Loader.loadPDF(fetched.toFile())) {
            int images = 0;
            for (var name : doc.getPage(0).getResources().getXObjectNames()) {
                if (doc.getPage(0).getResources().getXObject(name) instanceof PDImageXObject) {
                    images++;
                }
            }
            assertTrue(images >= 1, "images " + images);
        }
    }

    @Test
    void aBaseElementMovesWhereLinksResolve() throws IOException {
        server.createContext("/site/", ex -> send(ex, 200, "text/html", ("<html><head><base href=\"/assets/\">"
                + "</head><body><p>Based</p><img src=\"red.png\" width=\"40\" height=\"20\"></body></html>")
                .getBytes(StandardCharsets.UTF_8), false));
        server.createContext("/assets/", ex -> {
            hits.add(ex.getRequestURI().getPath());
            send(ex, 200, "image/png", png(), false);
        });
        Path out = dir.resolve("based.pdf");
        OfficeToPdf.convertUrl(base() + "/site/index.html", out, OfficeToPdf.Options.defaults(), LOCAL);
        assertTrue(hits.contains("/assets/red.png"), hits.toString());
    }

    private static InetAddress v4(int a, int b, int c, int d) throws IOException {
        return InetAddress.getByAddress(new byte[] {(byte) a, (byte) b, (byte) c, (byte) d});
    }

    private static InetAddress v6(String literal) throws IOException {
        return InetAddress.getByName(literal);
    }

    private static InetAddress mapped(int a, int b, int c, int d) throws IOException {
        byte[] x = new byte[16];
        x[10] = (byte) 0xFF;
        x[11] = (byte) 0xFF;
        x[12] = (byte) a;
        x[13] = (byte) b;
        x[14] = (byte) c;
        x[15] = (byte) d;
        return Inet6Address.getByAddress(null, x, -1);
    }

    private static InetAddress compatible(int a, int b, int c, int d) throws IOException {
        byte[] x = new byte[16];
        x[12] = (byte) a;
        x[13] = (byte) b;
        x[14] = (byte) c;
        x[15] = (byte) d;
        return Inet6Address.getByAddress(null, x, -1);
    }
}
