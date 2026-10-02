package stirling.software.officeconvert.topdf.testing;

import java.awt.Font;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class NoNetwork implements AutoCloseable {

    public static final String CANARY_HOST = "nonet-canary.invalid";

    static final Queue<String> LOOKUPS = new ConcurrentLinkedQueue<>();

    static volatile boolean resolverInstalled;

    private static final long LOCAL_HOST_CACHE_NANOS = 5_500_000_000L;

    private static final long FONT_MANAGER_LOADED = loadJdkFontManagerWhoseCacheIsNamedAfterTheLocalHost();

    private final ServerSocket server;

    private final Thread acceptor;

    private final List<String> events = Collections.synchronizedList(new ArrayList<>());

    private final ProxySelector previous;

    private final int lookupsBefore;

    private NoNetwork() throws IOException {
        InetAddress.getByName("localhost");
        lookupsBefore = LOOKUPS.size();
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = Thread.ofPlatform().daemon().name("no-network-canary").start(this::accept);
        previous = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                events.add("proxy lookup for " + uri);
                return List.of(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                events.add("connection failed to " + uri);
            }
        });
    }

    public static NoNetwork start() throws IOException {
        outwaitTheLocalHostCache();
        return new NoNetwork();
    }

    private static long loadJdkFontManagerWhoseCacheIsNamedAfterTheLocalHost() {
        new Font(Font.DIALOG, Font.PLAIN, 12).getFontName();
        return System.nanoTime();
    }

    private static void outwaitTheLocalHostCache() throws IOException {
        long left = LOCAL_HOST_CACHE_NANOS - (System.nanoTime() - FONT_MANAGER_LOADED);
        if (left > 0) {
            try {
                Thread.sleep(Duration.ofNanos(left));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while the local host cache expired");
            }
        }
    }

    public int port() {
        return server.getLocalPort();
    }

    public String url(String path) {
        return "http://127.0.0.1:" + port() + "/" + path;
    }

    public String httpsUrl(String path) {
        return "https://127.0.0.1:" + port() + "/" + path;
    }

    public String ftpUrl(String path) {
        return "ftp://127.0.0.1:" + port() + "/" + path;
    }

    public String canaryUrl(String path) {
        return "http://" + CANARY_HOST + "/" + path;
    }

    public String uncPath(String path) {
        return "\\\\127.0.0.1@" + port() + "\\share\\" + path.replace('/', '\\');
    }

    public String fileUrl(String path) {
        return "file://127.0.0.1:" + port() + "/" + path;
    }

    public List<String> hostileTargets(String name) {
        return List.of(url(name), httpsUrl(name), ftpUrl(name), canaryUrl(name), uncPath(name), fileUrl(name));
    }

    public static boolean resolverHookActive() {
        return resolverInstalled;
    }

    public List<String> attempts() {
        List<String> all = new ArrayList<>(events);
        List<String> lookups = new ArrayList<>(LOOKUPS);
        for (int i = lookupsBefore; i < lookups.size(); i++) {
            all.add(lookups.get(i));
        }
        return all;
    }

    public void assertNothingConnected() {
        List<String> seen = attempts();
        if (!seen.isEmpty()) {
            throw new AssertionError("Network activity during conversion: " + seen);
        }
    }

    @Override
    public void close() throws IOException {
        ProxySelector.setDefault(previous);
        server.close();
        try {
            acceptor.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void accept() {
        while (!server.isClosed()) {
            try (Socket s = server.accept()) {
                s.setSoTimeout(500);
                byte[] head = new byte[256];
                int n = 0;
                try (InputStream in = s.getInputStream()) {
                    n = Math.max(0, in.read(head));
                } catch (IOException ignored) {
                    n = 0;
                }
                String first = new String(head, 0, n, StandardCharsets.ISO_8859_1).lines().findFirst()
                        .orElse("");
                events.add("connection from " + s.getRemoteSocketAddress() + ": " + first.toLowerCase(Locale.ROOT));
            } catch (SocketException closed) {
                return;
            } catch (IOException e) {
                events.add("connection error " + e);
            }
        }
    }
}
