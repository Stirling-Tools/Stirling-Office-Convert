package stirling.software.officeconvert.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.function.BooleanSupplier;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

final class PageHandler implements HttpHandler {

    private final Limits limits;
    private final LibreOffice libreOffice;
    private final BooleanSupplier healthy;
    private final byte[] page;
    private final String csp;

    PageHandler(Limits limits, LibreOffice libreOffice, BooleanSupplier healthy) throws IOException {
        this.limits = limits;
        this.libreOffice = libreOffice;
        this.healthy = healthy;
        try (InputStream in = PageHandler.class.getResourceAsStream("/app/index.html")) {
            if (in == null) {
                throw new IOException("index.html is missing from the jar");
            }
            this.page = in.readAllBytes();
        }
        String html = new String(page, StandardCharsets.UTF_8);
        this.csp = "default-src 'none'; script-src " + hash(html, "script") + "; style-src " + hash(html, "style")
                + "; img-src data: blob:; frame-src blob:; connect-src 'self'; form-action 'none'; "
                + "frame-ancestors 'none'; base-uri 'none'";
    }

    private static String hash(String html, String tag) throws IOException {
        int open = html.indexOf("<" + tag + ">");
        int close = open < 0 ? -1 : html.indexOf("</" + tag + ">", open);
        if (close < 0 || html.indexOf("<" + tag, close) >= 0) {
            throw new IOException("index.html must hold exactly one plain <" + tag + "> element");
        }
        String body = html.substring(open + tag.length() + 2, close).replace("\r\n", "\n").replace('\r', '\n');
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
            return "'sha256-" + Base64.getEncoder().encodeToString(digest) + "'";
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            boolean get = "GET".equals(ex.getRequestMethod()) || "HEAD".equals(ex.getRequestMethod());
            if (!get) {
                ex.getResponseHeaders().set("Allow", "GET, HEAD");
                ex.sendResponseHeaders(405, -1);
                return;
            }
            switch (path) {
                case "/" -> send(ex, 200, "text/html; charset=utf-8", page);
                case "/limits" -> send(ex, 200, "application/json", limits.json(libreOffice).getBytes(StandardCharsets.UTF_8));
                case "/health" -> {
                    boolean ok = healthy.getAsBoolean();
                    send(ex, ok ? 200 : 503, "text/plain; charset=utf-8", (ok ? "ok" : "stuck").getBytes(StandardCharsets.UTF_8));
                }
                default -> ex.sendResponseHeaders(404, -1);
            }
        }
    }

    private void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        var h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Content-Security-Policy", csp);
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cross-Origin-Opener-Policy", "same-origin");
        h.set("Cache-Control", "no-cache");
        boolean head = "HEAD".equals(ex.getRequestMethod());
        ex.sendResponseHeaders(status, head ? -1 : body.length);
        if (!head) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
