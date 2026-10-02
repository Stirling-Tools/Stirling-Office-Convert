package stirling.software.officeconvert.app;

import java.util.Map;

record Limits(
        String host,
        int port,
        long maxUploadBytes,
        int maxPages,
        int timeoutSeconds,
        int concurrent,
        int queue,
        int queueWaitSeconds,
        int perClient,
        String clientIpHeader,
        int requestTimeoutSeconds,
        int maxConnections,
        boolean exitWhenStuck,
        String libreOffice,
        int libreOfficeConcurrent,
        String libreOfficeTemplate) {

    private static final int DOWNLOAD_SECONDS = 300;

    static final int DEFAULT_TIMEOUT_SECONDS = 180;

    static Limits fromEnvironment(String[] args) {
        return from(System.getenv(), args);
    }

    static Limits from(Map<String, String> env, String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : number(env, "PORT", 8177);
        String ipHeader = env.getOrDefault("CLIENT_IP_HEADER", "").strip();
        return new Limits(
                env.getOrDefault("HOST", "127.0.0.1"),
                port,
                number(env, "MAX_UPLOAD_MB", 512) * 1024L * 1024L,
                number(env, "MAX_PAGES", 0),
                atLeastOne(env, "CONVERT_TIMEOUT_SECONDS", DEFAULT_TIMEOUT_SECONDS),
                Math.max(1, number(env, "MAX_CONCURRENT", Math.max(2, Runtime.getRuntime().availableProcessors() / 2))),
                Math.max(0, number(env, "MAX_QUEUED", 8)),
                Math.max(1, number(env, "QUEUE_WAIT_SECONDS", 300)),
                Math.max(0, number(env, "MAX_PER_CLIENT", 4)),
                ipHeader.isEmpty() ? null : ipHeader,
                Math.max(1, number(env, "REQUEST_TIMEOUT_SECONDS", 300)),
                Math.max(1, number(env, "MAX_CONNECTIONS", 1000)),
                !"false".equalsIgnoreCase(env.getOrDefault("EXIT_WHEN_STUCK", "true").strip()),
                env.getOrDefault("LIBREOFFICE", "auto").strip(),
                Math.max(1, number(env, "LIBREOFFICE_CONCURRENT", 2)),
                env.getOrDefault("LIBREOFFICE_TEMPLATE", "/opt/lo-template").strip());
    }

    private static int number(Map<String, String> env, String key, int fallback) {
        String v = env.get(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a whole number, not '" + v + "'", e);
        }
    }

    private static int atLeastOne(Map<String, String> env, String key, int fallback) {
        int v = number(env, key, fallback);
        if (v < 1) {
            throw new IllegalArgumentException(key + " must be 1 or more, not " + v);
        }
        return v;
    }

    void applyServerSettings() {
        setDefault("sun.net.httpserver.maxReqTime", requestTimeoutSeconds);
        setDefault("sun.net.httpserver.maxRspTime", queueWaitSeconds + timeoutSeconds + DOWNLOAD_SECONDS);
        setDefault("jdk.httpserver.maxConnections", maxConnections);
        setDefault("sun.net.httpserver.drainAmount", 0);
    }

    private static void setDefault(String key, long value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, Long.toString(value));
        }
    }

    long maxUploadMb() {
        return maxUploadBytes / (1024 * 1024);
    }

    long diskBudget() {
        return maxUploadBytes * (concurrent + libreOfficeConcurrent + queue + 2L);
    }

    String json(LibreOffice lo) {
        String engines = lo == null ? "[\"ours\"]"
                : "[\"ours\",\"libreoffice\"],\"libreofficeFormats\":[\"" + String.join("\",\"", LibreOffice.formats().stream().sorted().toList()) + "\"]";
        return "{\"maxUploadMb\":" + maxUploadMb() + ",\"maxPages\":" + maxPages + ",\"timeoutSeconds\":" + timeoutSeconds
                + ",\"engines\":" + engines + "}";
    }

    @Override
    public String toString() {
        return "Limits[upload=" + maxUploadMb() + "MB, pages=" + maxPages + ", timeout=" + timeoutSeconds + "s, concurrent="
                + concurrent + ", queue=" + queue + ", perClient=" + perClient + ", clientIpHeader=" + clientIpHeader
                + ", requestTimeout=" + requestTimeoutSeconds + "s, maxConnections=" + maxConnections + "]";
    }
}
