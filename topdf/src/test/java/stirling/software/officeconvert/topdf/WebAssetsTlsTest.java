package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;

class WebAssetsTlsTest {

    private static TestCerts.Issued authority;
    private static SSLSocketFactory trusting;

    private final List<HttpsServer> servers = new ArrayList<>();
    private final List<String> sni = Collections.synchronizedList(new ArrayList<>());

    private static final Predicate<InetAddress> LOOPBACK_ONLY = a -> !a.isLoopbackAddress() && WebAssets.refused(a);

    private static final WebAssets.Resolver TEST_HOSTS = host -> host.endsWith(".test")
            ? new InetAddress[] {InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1})}
            : InetAddress.getAllByName(host);

    private static final WebAssets.Access ON = WebAssets.Access.none().network(true).timeout(Duration.ofSeconds(20));

    @BeforeAll
    static void authority() throws Exception {
        authority = TestCerts.authority("Office Convert Test CA");
        trusting = TestCerts.trusting(authority).getSocketFactory();
    }

    @AfterEach
    void stop() {
        servers.forEach(s -> s.stop(0));
    }

    private int serve(TestCerts.Issued leaf, TestCerts.Issued issuer) throws Exception {
        SSLContext ctx = TestCerts.server(leaf, issuer);
        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ctx));
        server.createContext("/", ex -> {
            if (((HttpsExchange) ex).getSSLSession() instanceof ExtendedSSLSession s) {
                for (SNIServerName n : s.getRequestedServerNames()) {
                    if (n instanceof SNIHostName h) {
                        sni.add(h.getAsciiName());
                    }
                }
            }
            byte[] body = "<p>secure</p>".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        servers.add(server);
        return server.getAddress().getPort();
    }

    private static TestCerts.Issued valid(String... hosts) throws Exception {
        return TestCerts.leaf(authority, hosts[0], List.of(hosts), Instant.now().minusSeconds(600),
                Instant.now().plusSeconds(86_400));
    }

    private static WebAssets fetch(String url, SSLSocketFactory tls) throws IOException {
        return WebAssets.fetch(url, ON, TEST_HOSTS, LOOPBACK_ONLY, tls);
    }

    @Test
    void aCertificateFromATrustedAuthorityForTheHostIsAcceptedAndTheHostIsSentAsSni() throws Exception {
        int port = serve(valid("site.test"), authority);
        WebAssets web = fetch("https://site.test:" + port + "/page.html", trusting);
        assertTrue(new String(web.page(), StandardCharsets.UTF_8).contains("secure"));
        assertEquals(List.of("site.test"), sni);
    }

    @Test
    void aCertificateForAnotherHostIsRefused() throws Exception {
        int port = serve(valid("other.test"), authority);
        IOException e = assertThrows(IOException.class, () -> fetch("https://site.test:" + port + "/", trusting));
        assertTrue(e.toString().toLowerCase().contains("certificate") || e.toString().contains("SSL")
                || e.toString().contains("match"), e.toString());
    }

    @Test
    void anExpiredCertificateIsRefused() throws Exception {
        TestCerts.Issued old = TestCerts.leaf(authority, "site.test", List.of("site.test"),
                Instant.now().minusSeconds(86_400 * 30), Instant.now().minusSeconds(86_400));
        int port = serve(old, authority);
        assertThrows(IOException.class, () -> fetch("https://site.test:" + port + "/", trusting));
    }

    @Test
    void aSelfSignedCertificateIsRefused() throws Exception {
        TestCerts.Issued self = TestCerts.leaf(null, "site.test", List.of("site.test"),
                Instant.now().minusSeconds(600), Instant.now().plusSeconds(86_400));
        int port = serve(self, null);
        assertThrows(IOException.class, () -> fetch("https://site.test:" + port + "/", trusting));
    }

    @Test
    void theDefaultTrustStoreDoesNotTrustTheTestAuthority() throws Exception {
        int port = serve(valid("site.test"), authority);
        assertThrows(IOException.class, () -> fetch("https://site.test:" + port + "/",
                (SSLSocketFactory) SSLSocketFactory.getDefault()));
    }

    @Test
    void aSlowLookupIsAbandonedAtTheTimeout() {
        CountDownLatch never = new CountDownLatch(1);
        WebAssets.Resolver stuck = host -> {
            try {
                never.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("released");
        };
        long start = System.nanoTime();
        IOException e = assertThrows(IOException.class, () -> WebAssets.fetch("http://slow.test/",
                ON.timeout(Duration.ofMillis(400)), stuck, LOOPBACK_ONLY, trusting));
        never.countDown();
        assertTrue(e.getMessage().contains("Looking up"), e.getMessage());
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000);
    }
}
