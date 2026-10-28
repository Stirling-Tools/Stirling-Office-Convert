package stirling.software.officeconvert.topdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class WebAssets implements HtmlAssets {

    public record Access(boolean network, boolean redirects, boolean cookies, boolean privateAddresses,
            boolean linkedFromFiles, Duration timeout, long maxBytes, int maxRequests, int maxRedirects) {

        public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

        public static final long DEFAULT_MAX_BYTES = 64L << 20;

        public static final int DEFAULT_MAX_REQUESTS = 300;

        public static final int DEFAULT_MAX_REDIRECTS = 5;

        public Access {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            if (maxBytes <= 0 || maxRequests <= 0 || maxRedirects < 0) {
                throw new IllegalArgumentException("limits must be positive");
            }
        }

        public static Access none() {
            return new Access(false, false, false, false, false, DEFAULT_TIMEOUT, DEFAULT_MAX_BYTES,
                    DEFAULT_MAX_REQUESTS, DEFAULT_MAX_REDIRECTS);
        }

        public Access network(boolean on) {
            return new Access(on, redirects, cookies, privateAddresses, linkedFromFiles, timeout, maxBytes,
                    maxRequests, maxRedirects);
        }

        public Access redirects(boolean on) {
            return new Access(network, on, cookies, privateAddresses, linkedFromFiles, timeout, maxBytes,
                    maxRequests, maxRedirects);
        }

        public Access cookies(boolean on) {
            return new Access(network, redirects, on, privateAddresses, linkedFromFiles, timeout, maxBytes,
                    maxRequests, maxRedirects);
        }

        public Access privateAddresses(boolean on) {
            return new Access(network, redirects, cookies, on, linkedFromFiles, timeout, maxBytes, maxRequests,
                    maxRedirects);
        }

        public Access linkedFromFiles(boolean on) {
            return new Access(network, redirects, cookies, privateAddresses, on, timeout, maxBytes, maxRequests,
                    maxRedirects);
        }

        public Access timeout(Duration limit) {
            return new Access(network, redirects, cookies, privateAddresses, linkedFromFiles, limit, maxBytes,
                    maxRequests, maxRedirects);
        }

        public Access maxBytes(long limit) {
            return new Access(network, redirects, cookies, privateAddresses, linkedFromFiles, timeout, limit,
                    maxRequests, maxRedirects);
        }

        public Access maxRequests(int limit) {
            return new Access(network, redirects, cookies, privateAddresses, linkedFromFiles, timeout, maxBytes,
                    limit, maxRedirects);
        }

        public Access maxRedirects(int limit) {
            return new Access(network, redirects, cookies, privateAddresses, linkedFromFiles, timeout, maxBytes,
                    maxRequests, limit);
        }
    }

    interface Resolver {
        InetAddress[] resolve(String host) throws IOException;
    }

    static final int MAX_HEADER_BYTES = 64 << 10;

    static final int MAX_URL_CHARS = 8_192;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(10);

    private final Access access;
    private final Resolver resolver;
    private final Predicate<InetAddress> refused;
    private final SSLSocketFactory tls;
    private final long deadline;
    private final Map<String, Map<String, String>> cookies = new HashMap<>();
    private Map<String, Map<String, String>> seeded = Map.of();
    private long bytes;
    private int requests;
    private String url;
    private byte[] page;
    private HtmlAssets local = HtmlAssets.NONE;

    private WebAssets(Access access, Resolver resolver, Predicate<InetAddress> refused, SSLSocketFactory tls) {
        this.access = access;
        this.resolver = resolver;
        this.refused = refused;
        this.tls = tls;
        this.deadline = System.nanoTime() + access.timeout().toNanos();
    }

    public static WebAssets fetch(String url, Access access) throws IOException {
        Objects.requireNonNull(access, "access");
        return fetch(url, access, InetAddress::getAllByName, policy(access),
                (SSLSocketFactory) SSLSocketFactory.getDefault());
    }

    public static WebAssets linkedFrom(HtmlAssets local, Access access) throws IOException {
        Objects.requireNonNull(access, "access");
        return linkedFrom(local, access, InetAddress::getAllByName, policy(access),
                (SSLSocketFactory) SSLSocketFactory.getDefault());
    }

    static WebAssets linkedFrom(HtmlAssets local, Access access, Resolver resolver, Predicate<InetAddress> refused,
            SSLSocketFactory tls) throws IOException {
        if (!access.network() || !access.linkedFromFiles()) {
            throw new IOException("Pictures and style sheets on the web are fetched for an HTML file only when"
                    + " network access and linked resources are both turned on (WebAssets.Access.network and"
                    + " linkedFromFiles, --allow-network --fetch-linked)");
        }
        WebAssets web = new WebAssets(access, resolver, refused, tls);
        web.local = local == null ? HtmlAssets.NONE : local;
        String base = web.local.base();
        web.url = base == null ? "" : base;
        return web;
    }

    private static Predicate<InetAddress> policy(Access access) {
        return access.privateAddresses() ? WebAssets::reserved : WebAssets::refused;
    }

    static WebAssets fetch(String url, Access access, Resolver resolver, Predicate<InetAddress> refused,
            SSLSocketFactory tls) throws IOException {
        Objects.requireNonNull(url, "url");
        if (!access.network()) {
            throw new IOException("Network access is off: URL to PDF needs it turned on"
                    + " (WebAssets.Access.network, --allow-network)");
        }
        WebAssets web = new WebAssets(access, resolver, refused, tls);
        Response r = web.get(uri(url), true);
        web.url = r.url().toString();
        web.page = utf8(r.body(), r.contentType());
        web.seeded = jars(web.cookies);
        return web;
    }

    private static Map<String, Map<String, String>> jars(Map<String, Map<String, String>> from) {
        Map<String, Map<String, String>> out = new HashMap<>();
        from.forEach((origin, jar) -> out.put(origin, new LinkedHashMap<>(jar)));
        return out;
    }

    public String url() {
        return url;
    }

    public byte[] page() {
        return page.clone();
    }

    @Override
    public String base() {
        return url;
    }

    @Override
    public boolean absoluteWeb() {
        return true;
    }

    @Override
    public HtmlAssets session() {
        WebAssets web = new WebAssets(access, resolver, refused, tls);
        web.local = local.session();
        web.url = url;
        web.page = page;
        web.seeded = seeded;
        web.cookies.putAll(jars(seeded));
        return web;
    }

    @Override
    public byte[] read(String reference) throws IOException {
        if (reference == null || !(reference.regionMatches(true, 0, "http://", 0, 7)
                || reference.regionMatches(true, 0, "https://", 0, 8))) {
            return page == null && reference != null ? local.read(reference) : null;
        }
        try {
            return get(uri(reference), false).body();
        } catch (IOException | IllegalArgumentException e) {
            OfficeToPdf.stopIfInterrupted();
            return null;
        }
    }

    private static URI uri(String url) throws IOException {
        if (url.length() > MAX_URL_CHARS) {
            throw new IOException("The URL is longer than " + MAX_URL_CHARS + " characters");
        }
        try {
            URI u = new URI(url.strip()).normalize();
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IOException("Only http and https URLs are fetched, not '" + scheme + "'");
            }
            if (u.getHost() == null || u.getRawUserInfo() != null) {
                throw new IOException("The URL has no host, or carries a user name or password");
            }
            return u;
        } catch (URISyntaxException e) {
            throw new IOException("Not a valid URL: " + e.getMessage(), e);
        }
    }

    record Response(URI url, int status, String contentType, String location, byte[] body) {}

    private Response get(URI start, boolean page) throws IOException {
        URI at = start;
        for (int hop = 0;; hop++) {
            Response r = once(at);
            if (r.status() >= 300 && r.status() < 400 && r.status() != 304) {
                String location = r.location();
                if (!access.redirects()) {
                    throw new IOException("The server redirected to another address, which is followed only when"
                            + " redirects are allowed (WebAssets.Access.redirects, --follow-redirects)");
                }
                if (location == null || hop >= access.maxRedirects()) {
                    throw new IOException("Too many redirects, or a redirect without a Location");
                }
                try {
                    at = uri(at.resolve(location.strip()).toString());
                } catch (IllegalArgumentException e) {
                    throw new IOException("A redirect to an invalid URL", e);
                }
                continue;
            }
            if (r.status() < 200 || r.status() >= 300) {
                throw new IOException("The server answered " + r.status() + (page ? " for the page" : ""));
            }
            return r;
        }
    }

    private Response once(URI u) throws IOException {
        if (++requests > access.maxRequests()) {
            throw new IOException("More than " + access.maxRequests() + " requests");
        }
        String host = u.getHost();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        try {
            host = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new IOException("Not a valid host name: " + host, e);
        }
        boolean secure = u.getScheme().equalsIgnoreCase("https");
        int port = u.getPort() < 0 ? secure ? 443 : 80 : u.getPort();
        InetAddress[] addresses = lookup(host);
        if (addresses == null || addresses.length == 0) {
            throw new IOException("The host " + host + " has no address");
        }
        for (InetAddress a : addresses) {
            if (refused.test(a)) {
                throw new IOException("The host " + host + " resolves to a local, private or reserved address,"
                        + " which is not fetched");
            }
        }
        InetAddress target = addresses[0];
        try (SocketChannel channel = SocketChannel.open()) {
            Socket plain = channel.socket();
            Thread expiry = Thread.ofVirtual().start(() -> {
                try {
                    long wait = deadline - System.nanoTime();
                    if (wait > 0) {
                        TimeUnit.NANOSECONDS.sleep(wait);
                    }
                    plain.close();
                } catch (InterruptedException | IOException e) {
                    return;
                }
            });
            try {
                plain.connect(new InetSocketAddress(target, port), (int) Math.max(1, Math.min(remainingMillis(),
                        CONNECT_TIMEOUT.toMillis())));
                Socket socket = plain;
                if (secure) {
                    SSLSocket ssl = (SSLSocket) tls.createSocket(plain, host, port, true);
                    SSLParameters params = ssl.getSSLParameters();
                    params.setEndpointIdentificationAlgorithm("HTTPS");
                    if (!literal(host)) {
                        params.setServerNames(List.of(new SNIHostName(host)));
                    }
                    ssl.setSSLParameters(params);
                    socket = ssl;
                }
                socket.setSoTimeout((int) Math.max(1, Math.min(Integer.MAX_VALUE, remainingMillis())));
                if (socket instanceof SSLSocket ssl) {
                    ssl.startHandshake();
                }
                OutputStream out = socket.getOutputStream();
                out.write(request(u, host, port, secure).getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                return read(u, host, socket);
            } finally {
                expiry.interrupt();
            }
        } catch (SocketTimeoutException e) {
            throw new IOException("The server did not answer within " + access.timeout().toSeconds() + " s", e);
        }
    }

    private InetAddress[] lookup(String host) throws IOException {
        long limit = Math.max(1, Math.min(remainingMillis(), LOOKUP_TIMEOUT.toMillis()));
        CompletableFuture<InetAddress[]> answer = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                answer.complete(resolver.resolve(host));
            } catch (IOException | RuntimeException e) {
                answer.completeExceptionally(e);
            }
        });
        try {
            return answer.get(limit, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("Looking up " + host + " took longer than " + limit + " ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while looking up " + host, e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io
                    : new IOException("The host " + host + " could not be looked up", e.getCause());
        }
    }

    private String request(URI given, String host, int port, boolean secure) throws IOException {
        URI u;
        try {
            u = new URI(given.toASCIIString());
        } catch (URISyntaxException e) {
            throw new IOException("Not a valid URL: " + e.getMessage(), e);
        }
        String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        if (u.getRawQuery() != null) {
            path += "?" + u.getRawQuery();
        }
        String hostHeader = (host.indexOf(':') >= 0 ? "[" + host + "]" : host)
                + (port == (secure ? 443 : 80) ? "" : ":" + port);
        StringBuilder sb = new StringBuilder();
        sb.append("GET ").append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(hostHeader).append("\r\n");
        sb.append("User-Agent: Stirling-Office-Convert\r\n");
        sb.append("Accept: */*\r\n");
        sb.append("Accept-Encoding: identity\r\n");
        sb.append("Connection: close\r\n");
        Map<String, String> jar = access.cookies() ? cookies.get(cookieOrigin(host, secure)) : null;
        if (jar != null && !jar.isEmpty()) {
            List<String> pairs = new ArrayList<>();
            jar.forEach((k, v) -> pairs.add(k + "=" + v));
            sb.append("Cookie: ").append(String.join("; ", pairs)).append("\r\n");
        }
        sb.append("\r\n");
        if (sb.length() > MAX_HEADER_BYTES) {
            throw new IOException("The request headers are too large");
        }
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c > 0x7E || c < 0x20 && c != '\r' && c != '\n') {
                throw new IOException("The URL holds characters that cannot be sent");
            }
        }
        return sb.toString();
    }

    private Response read(URI u, String host, Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        String status = line(in);
        String[] parts = status.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/1.")) {
            throw new IOException("Not an HTTP response");
        }
        int code;
        try {
            code = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Not an HTTP response", e);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        int headerBytes = status.length();
        for (String h = line(in); !h.isEmpty(); h = line(in)) {
            headerBytes += h.length() + 2;
            if (headerBytes > MAX_HEADER_BYTES) {
                throw new IOException("The response headers are too large");
            }
            int colon = h.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = h.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = h.substring(colon + 1).strip();
            if (name.equals("set-cookie")) {
                keep(cookieOrigin(host, u.getScheme().equalsIgnoreCase("https")), value);
            } else {
                headers.merge(name, value, (a, b) -> a + ", " + b);
            }
        }
        if (code >= 300 && code < 400) {
            return new Response(u, code, null, headers.get("location"), new byte[0]);
        }
        if (code < 200 || code >= 300) {
            return new Response(u, code, null, null, new byte[0]);
        }
        byte[] body;
        String encoding = headers.getOrDefault("transfer-encoding", "").toLowerCase(Locale.ROOT);
        if (encoding.contains("chunked")) {
            body = chunked(in, headerBytes);
        } else if (headers.containsKey("content-length")) {
            long length;
            try {
                length = Long.parseLong(headers.get("content-length").split(",")[0].strip());
            } catch (NumberFormatException e) {
                throw new IOException("Invalid Content-Length", e);
            }
            if (length < 0) {
                throw new IOException("Invalid Content-Length");
            }
            charge(length);
            body = exactly(in, (int) length);
        } else {
            body = untilEnd(in);
        }
        String contentEncoding = headers.getOrDefault("content-encoding", "identity").toLowerCase(Locale.ROOT);
        if (!contentEncoding.equals("identity") && !contentEncoding.isEmpty()) {
            throw new IOException("The server sent a " + contentEncoding + " body without being asked to");
        }
        return new Response(u, code, headers.get("content-type"), null, body);
    }

    private static String cookieOrigin(String host, boolean secure) {
        return (secure ? "https://" : "http://") + host;
    }

    private void keep(String origin, String header) {
        if (!access.cookies()) {
            return;
        }
        String pair = header.split(";", 2)[0];
        int eq = pair.indexOf('=');
        if (eq <= 0) {
            return;
        }
        Map<String, String> jar = cookies.computeIfAbsent(origin, h -> new LinkedHashMap<>());
        if (jar.size() < 50 || jar.containsKey(pair.substring(0, eq).strip())) {
            jar.put(pair.substring(0, eq).strip(), pair.substring(eq + 1).strip());
        }
    }

    private String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (;;) {
            checkDeadline();
            int c = in.read();
            if (c < 0) {
                if (b.size() == 0) {
                    throw new IOException("The server closed the connection");
                }
                break;
            }
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                b.write(c);
            }
            if (b.size() > MAX_HEADER_BYTES) {
                throw new IOException("The response headers are too large");
            }
        }
        return b.toString(StandardCharsets.ISO_8859_1);
    }

    private byte[] chunked(InputStream in, int headerBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (;;) {
            String size = line(in).split(";", 2)[0].strip();
            long n;
            try {
                n = Long.parseLong(size, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid chunk size", e);
            }
            if (n < 0) {
                throw new IOException("Invalid chunk size");
            }
            if (n == 0) {
                for (String t = line(in); !t.isEmpty(); t = line(in)) {
                    headerBytes += t.length() + 2;
                    if (headerBytes > MAX_HEADER_BYTES) {
                        throw new IOException("The response trailers are too large");
                    }
                    checkDeadline();
                }
                return out.toByteArray();
            }
            charge(n);
            out.write(exactly(in, (int) n));
            line(in);
        }
    }

    private byte[] exactly(InputStream in, int length) throws IOException {
        byte[] b = new byte[length];
        int off = 0;
        while (off < length) {
            checkDeadline();
            int n = in.read(b, off, Math.min(length - off, 1 << 16));
            if (n < 0) {
                throw new IOException("The server closed the connection early");
            }
            off += n;
        }
        return b;
    }

    private byte[] untilEnd(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        for (;;) {
            checkDeadline();
            int n = in.read(buf);
            if (n < 0) {
                return out.toByteArray();
            }
            charge(n);
            out.write(buf, 0, n);
        }
    }

    private void charge(long n) throws IOException {
        if (n > HtmlAssets.MAX_ASSET_BYTES || bytes + n > access.maxBytes()) {
            throw new IOException("The responses are larger than " + (access.maxBytes() >> 20) + " MB");
        }
        bytes += n;
    }

    private long remainingMillis() throws IOException {
        OfficeToPdf.stopIfInterrupted();
        long left = (deadline - System.nanoTime()) / 1_000_000;
        if (left <= 0) {
            throw new IOException("Fetching took longer than " + access.timeout().toSeconds() + " s");
        }
        return left;
    }

    private void checkDeadline() throws IOException {
        remainingMillis();
    }

    private static boolean literal(String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9.]+");
    }

    private static byte[] utf8(byte[] body, String contentType) {
        Charset declared = null;
        if (contentType != null) {
            for (String p : contentType.split(";")) {
                String s = p.strip();
                if (s.regionMatches(true, 0, "charset=", 0, 8)) {
                    try {
                        declared = Charset.forName(s.substring(8).replace("\"", "").strip());
                    } catch (IllegalArgumentException e) {
                        declared = null;
                    }
                }
            }
        }
        boolean bom = body.length >= 3 && (body[0] & 0xFF) == 0xEF && (body[1] & 0xFF) == 0xBB
                && (body[2] & 0xFF) == 0xBF || body.length >= 2 && ((body[0] & 0xFF) == 0xFE
                && (body[1] & 0xFF) == 0xFF || (body[0] & 0xFF) == 0xFF && (body[1] & 0xFF) == 0xFE);
        if (declared == null || bom || declared.equals(StandardCharsets.UTF_8)) {
            return body;
        }
        byte[] text = new String(body, declared).getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[text.length + 3];
        out[0] = (byte) 0xEF;
        out[1] = (byte) 0xBB;
        out[2] = (byte) 0xBF;
        System.arraycopy(text, 0, out, 3, text.length);
        return out;
    }

    static boolean refused(InetAddress a) {
        if (reserved(a)) {
            return true;
        }
        byte[] b = embeddedIpv4(a);
        if (b != null) {
            int x = b[0] & 0xFF;
            int y = b[1] & 0xFF;
            return x == 10 || x == 127 || x == 172 && (y & 0xF0) == 16 || x == 192 && y == 168
                    || x == 100 && (y & 0xC0) == 64;
        }
        byte[] v6 = a.getAddress();
        return (v6[0] & 0xFE) == 0xFC || a.isLoopbackAddress();
    }

    static boolean reserved(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = embeddedIpv4(a);
        if (b != null) {
            int x = b[0] & 0xFF;
            int y = b[1] & 0xFF;
            int z = b[2] & 0xFF;
            return x == 0 || x == 169 && y == 254 || x >= 224 || x == 192 && y == 0 && (z == 0 || z == 2)
                    || x == 198 && (y & 0xFE) == 18 || x == 198 && y == 51 && z == 100 || x == 203 && y == 0
                    && z == 113 || x == 192 && y == 88 && z == 99
                    || x == 100 && y == 100 && z == 100 && (b[3] & 0xFF) == 200
                    || x == 168 && y == 63 && z == 129 && (b[3] & 0xFF) == 16;
        }
        byte[] v6 = a.getAddress();
        int w0 = (v6[0] & 0xFF) << 8 | v6[1] & 0xFF;
        int w1 = (v6[2] & 0xFF) << 8 | v6[3] & 0xFF;
        if (allZero(v6, 0, 16)) {
            return true;
        }
        if (w0 == 0xFD00 && w1 == 0x0EC2 && allZero(v6, 4, 14) && v6[14] == 0x02
                && (v6[15] & 0xFF) == 0x54) {
            return true;
        }
        return (w0 & 0xFFC0) == 0xFE80 || (w0 & 0xFFC0) == 0xFEC0 || (w0 & 0xFF00) == 0xFF00
                || w0 == 0x0064 && w1 == 0xFF9B && v6[4] == 0 && v6[5] == 1
                || w0 == 0x2001 && w1 == 0 || w0 == 0x2001 && w1 == 0x0DB8 || w0 == 0x3FFF && (w1 & 0xF000) == 0
                || w0 == 0x0100 && allZero(v6, 2, 8) || w0 == 0x2001 && (w1 & 0xFFF0) == 0x0010
                || w0 == 0x2001 && (w1 & 0xFFF0) == 0x0020 || w0 == 0x5F00;
    }

    private static byte[] embeddedIpv4(InetAddress a) {
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            return b;
        }
        if (!(a instanceof Inet6Address) || b.length != 16) {
            return null;
        }
        if (allZero(b, 0, 10) && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
            return new byte[] {b[12], b[13], b[14], b[15]};
        }
        if (allZero(b, 0, 8) && (b[8] & 0xFF) == 0xFF && (b[9] & 0xFF) == 0xFF && allZero(b, 10, 12)) {
            return new byte[] {b[12], b[13], b[14], b[15]};
        }
        if (allZero(b, 0, 12) && !(allZero(b, 12, 15) && (b[15] & 0xFF) <= 1)) {
            return new byte[] {b[12], b[13], b[14], b[15]};
        }
        if ((b[0] & 0xFF) == 0x00 && (b[1] & 0xFF) == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B
                && allZero(b, 4, 12)) {
            return new byte[] {b[12], b[13], b[14], b[15]};
        }
        if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x02) {
            return new byte[] {b[2], b[3], b[4], b[5]};
        }
        return null;
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
