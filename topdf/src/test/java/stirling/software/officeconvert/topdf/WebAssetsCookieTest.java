package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.net.ssl.SSLSocketFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

class WebAssetsCookieTest {

    @Test
    void cookiesStayWithTheirSchemeAcrossLinkedAssetsAndRedirects() throws Exception {
        var authority = TestCerts.authority("Cookie Test CA");
        var leaf = TestCerts.leaf(authority, "site.test", List.of("site.test"), Instant.now().minusSeconds(600),
                Instant.now().plusSeconds(86_400));
        HttpsServer secure = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        secure.setHttpsConfigurator(new HttpsConfigurator(TestCerts.server(leaf, authority)));
        HttpServer plain = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        String http = "http://site.test:" + plain.getAddress().getPort();
        String https = "https://site.test:" + secure.getAddress().getPort();
        Map<String, String> seen = new ConcurrentHashMap<>();
        HttpHandler handler = ex -> {
            String path = ex.getRequestURI().getPath();
            String cookie = ex.getRequestHeaders().getFirst("Cookie");
            seen.put(path, cookie == null ? "" : cookie);
            if (path.equals("/start")) {
                ex.getResponseHeaders().add("Set-Cookie", "session=secret; Secure; Path=/");
            } else if (path.equals("/set-plain")) {
                ex.getResponseHeaders().add("Set-Cookie", "plain=value; Path=/");
            }
            if (path.equals("/downgrade")) {
                ex.getResponseHeaders().add("Location", http + "/redirected");
                ex.sendResponseHeaders(302, -1);
                ex.close();
                return;
            }
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (var out = ex.getResponseBody()) {
                out.write(body);
            }
        };
        secure.createContext("/", handler);
        plain.createContext("/", handler);
        secure.start();
        plain.start();
        try {
            var access = WebAssets.Access.none().network(true).cookies(true).redirects(true)
                    .timeout(Duration.ofSeconds(20));
            var web = WebAssets.fetch(https + "/start", access,
                    host -> new InetAddress[] {InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1})},
                    address -> !address.isLoopbackAddress(), TestCerts.trusting(authority).getSocketFactory());
            assertNotNull(web.read(https + "/secure-asset"));
            assertEquals("session=secret", seen.get("/secure-asset"));
            assertNotNull(web.read(http + "/plain-asset"));
            assertEquals("", seen.get("/plain-asset"));
            assertNotNull(web.read(https + "/downgrade"));
            assertEquals("", seen.get("/redirected"));
            assertNotNull(web.read(http + "/set-plain"));
            assertNotNull(web.read(http + "/plain-after"));
            assertEquals("plain=value", seen.get("/plain-after"));
            assertNotNull(web.read(https + "/secure-after"));
            assertEquals("session=secret", seen.get("/secure-after"));
        } finally {
            secure.stop(0);
            plain.stop(0);
        }
    }
    @Test
    void reusedLinkedAssetsStartAFreshSessionForEachConversion(@TempDir Path dir) throws Exception {
        HttpServer plain = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        String http = "http://site.test:" + plain.getAddress().getPort();
        List<String> cookies = new CopyOnWriteArrayList<>();
        plain.createContext("/", ex -> {
            String cookie = ex.getRequestHeaders().getFirst("Cookie");
            cookies.add(cookie == null ? "" : cookie);
            ex.getResponseHeaders().add("Set-Cookie", "user=first; Path=/");
            ex.getResponseHeaders().add("Content-Type", "text/css");
            byte[] body = "p { color: red }".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (var out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        plain.start();
        try {
            var access = WebAssets.Access.none().network(true).linkedFromFiles(true).cookies(true)
                    .timeout(Duration.ofSeconds(2));
            var web = WebAssets.linkedFrom(HtmlAssets.NONE, access,
                    host -> new InetAddress[] {InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1})},
                    address -> !address.isLoopbackAddress(), (SSLSocketFactory) SSLSocketFactory.getDefault());
            var options = OfficeToPdf.Options.defaults().htmlAssets(web);
            Path page = Files.writeString(dir.resolve("page.html"), "<html><head><link rel=stylesheet href=\""
                    + http + "/a.css\"><link rel=stylesheet href=\"" + http + "/b.css\"></head>"
                    + "<body><p>Styled</p></body></html>");
            OfficeToPdf.convert(page, dir.resolve("one.pdf"), options);
            assertEquals(List.of("", "user=first"), cookies);
            Thread.sleep(2_500);
            OfficeToPdf.convert(page, dir.resolve("two.pdf"), options);
            assertEquals(List.of("", "user=first", "", "user=first"), cookies);
        } finally {
            plain.stop(0);
        }
    }
}
