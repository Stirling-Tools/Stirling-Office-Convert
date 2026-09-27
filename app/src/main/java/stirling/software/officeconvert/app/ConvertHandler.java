package stirling.software.officeconvert.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import stirling.software.officeconvert.PdfToDocx;
import stirling.software.officeconvert.PdfToOdp;
import stirling.software.officeconvert.PdfToOdt;
import stirling.software.officeconvert.PdfToPptx;
import stirling.software.officeconvert.PdfToRtf;
import stirling.software.officeconvert.PdfToText;
import stirling.software.officeconvert.PdfToXlsx;
import stirling.software.officeconvert.Pictures;
import stirling.software.officeconvert.extract.PdfFiles;

final class ConvertHandler implements HttpHandler {

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @FunctionalInterface
    private interface Converter {
        void convert(Path pdf, Path out, PdfToDocx.Options options) throws IOException;
    }

    private record Target(String type, Converter converter) {}

    private static final Map<String, Target> TARGETS = Map.of(
            "docx", new Target(DOCX, PdfToDocx::convert),
            "odt", new Target("application/vnd.oasis.opendocument.text", PdfToOdt::convert),
            "rtf", new Target("application/rtf", PdfToRtf::convert),
            "txt", new Target("text/plain; charset=utf-8", PdfToText::convert),
            "pptx", new Target("application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    (pdf, out, o) -> PdfToPptx.convert(pdf, out, slides(o))),
            "odp", new Target("application/vnd.oasis.opendocument.presentation",
                    (pdf, out, o) -> PdfToOdp.convert(pdf, out, slides(o))),
            "xlsx", new Target("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    (pdf, out, o) -> PdfToXlsx.convert(pdf, out, sheets(o))),
            "ods", new Target("application/vnd.oasis.opendocument.spreadsheet",
                    (pdf, out, o) -> PdfToXlsx.convert(pdf, out, sheets(o))));

    private static PdfToPptx.Options slides(PdfToDocx.Options o) {
        return new PdfToPptx.Options(o.firstPage(), o.lastPage(), o.tables(), o.figureDpi(), o.password(), o.pictureFallback(),
                o.pictures());
    }

    private static PdfToXlsx.Options sheets(PdfToDocx.Options o) {
        return PdfToXlsx.Options.defaults().withPages(o.firstPage(), o.lastPage()).withTables(o.tables())
                .withPassword(o.password()).withTextFallback(o.pictureFallback());
    }

    private static final long STOP_WAIT_MILLIS = 5_000;

    private static final String BUSY = "The converter is busy right now. Please try again in a minute.";

    private final Limits limits;
    private final Semaphore slots;
    private final LibreOffice libreOffice;
    private final Semaphore officeSlots;
    private final AtomicInteger admitted = new AtomicInteger();
    private final AtomicInteger stuck = new AtomicInteger();
    private final AtomicLong stored = new AtomicLong();
    private final Map<String, Integer> clients = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "convert");
        t.setDaemon(true);
        return t;
    });

    ConvertHandler(Limits limits, LibreOffice libreOffice) {
        this.limits = limits;
        this.slots = new Semaphore(limits.concurrent(), true);
        this.libreOffice = libreOffice;
        this.officeSlots = new Semaphore(libreOffice == null ? 0 : libreOffice.slots(), true);
    }

    private int capacity() {
        return limits.concurrent() + limits.queue() + (libreOffice == null ? 0 : libreOffice.slots());
    }

    private static final class Refusal extends Exception {
        final int status;
        final String code;

        Refusal(int status, String code, String message) {
            super(message, null, false, false);
            this.status = status;
            this.code = code;
        }
    }

    private final class Job {
        final Path dir;
        final Path pdf;
        private final String client;
        private long bytes;
        private boolean converting;
        private boolean requestDone;
        private boolean markedStuck;

        Job(Path dir, String client) {
            this.dir = dir;
            this.pdf = dir.resolve("in.pdf");
            this.client = client;
        }

        synchronized void stored(long n) {
            bytes += n;
        }

        synchronized long size() {
            return bytes;
        }

        synchronized void started() {
            converting = true;
        }

        synchronized boolean markStuck() {
            if (!converting) {
                return false;
            }
            markedStuck = true;
            stuck.incrementAndGet();
            return true;
        }

        synchronized void conversionEnded() {
            converting = false;
            if (markedStuck) {
                stuck.decrementAndGet();
                markedStuck = false;
            }
            if (requestDone) {
                clean();
            }
        }

        synchronized void requestEnded() {
            requestDone = true;
            if (!converting) {
                clean();
            }
        }

        private void clean() {
            stored.addAndGet(-bytes);
            bytes = 0;
            ConvertHandler.clean(dir);
            leave(client);
        }
    }

    boolean healthy() {
        return stuck.get() < limits.concurrent();
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"POST".equals(ex.getRequestMethod())) {
                ex.getResponseHeaders().set("Allow", "POST");
                ex.sendResponseHeaders(405, -1);
                return;
            }
            long start = System.nanoTime();
            String client = client(ex);
            if (!enter(client)) {
                fail(ex, 429, "busy", "Several of your conversions are running already. Please wait for one to finish.");
                log(start, 0, "client-limit");
                return;
            }
            Job job = null;
            String outcome = "ok";
            try {
                Map<String, String> q = query(ex);
                String format = q.getOrDefault("format", "docx");
                Target target = TARGETS.get(format);
                if (target == null) {
                    throw new Refusal(422, "format", "This demo converts to docx, odt, rtf, txt, pptx, odp, xlsx or ods.");
                }
                boolean office = office(q.getOrDefault("engine", "ours"), format);
                declaredSize(ex.getRequestHeaders());
                if (admitted.get() >= capacity()) {
                    throw new Refusal(503, "busy", BUSY);
                }
                job = new Job(Files.createTempDirectory("office-convert-app"), client);
                receive(ex.getRequestBody(), job);
                Path out = job.dir.resolve("out." + format);
                String note = convert(job, out, target, format, office, q, ex.getRequestHeaders().getFirst("X-Pdf-Password"));
                ex.getResponseHeaders().set("X-Engine", office ? "libreoffice" : "ours");
                send(ex, out, target.type(), format, note, start);
            } catch (Refusal r) {
                outcome = r.code;
                fail(ex, r.status, r.code, r.getMessage());
            } finally {
                long size = job == null ? 0 : job.size();
                if (job == null) {
                    leave(client);
                } else {
                    job.requestEnded();
                }
                log(start, size, outcome);
            }
        }
    }

    private static void log(long start, long size, String outcome) {
        System.out.printf("%s convert %.1f MB in %d ms: %s%n", LocalTime.now().truncatedTo(ChronoUnit.SECONDS),
                size / 1048576.0, (System.nanoTime() - start) / 1_000_000, outcome);
    }

    private String client(HttpExchange ex) {
        if (limits.clientIpHeader() != null) {
            String v = ex.getRequestHeaders().getFirst(limits.clientIpHeader());
            if (v != null && !v.isBlank()) {
                String last = v.substring(v.lastIndexOf(',') + 1).strip();
                return last.length() > 64 ? last.substring(0, 64) : last;
            }
        }
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    private boolean enter(String client) {
        if (limits.perClient() <= 0) {
            return true;
        }
        boolean[] ok = {false};
        clients.compute(client, (k, n) -> {
            int now = n == null ? 0 : n;
            if (now >= limits.perClient()) {
                return n;
            }
            ok[0] = true;
            return now + 1;
        });
        return ok[0];
    }

    private void leave(String client) {
        if (limits.perClient() > 0) {
            clients.computeIfPresent(client, (k, n) -> n <= 1 ? null : n - 1);
        }
    }

    private void declaredSize(Headers h) throws Refusal {
        String length = h.getFirst("Content-Length");
        if (length == null) {
            return;
        }
        try {
            if (Long.parseLong(length.strip()) > limits.maxUploadBytes()) {
                throw tooLarge();
            }
        } catch (NumberFormatException e) {
            throw new Refusal(400, "length", "The upload's length is not a number.");
        }
    }

    private Refusal tooLarge() {
        return new Refusal(413, "size", "The PDF is larger than the " + limits.maxUploadMb() + " MB this demo accepts.");
    }

    private boolean office(String engine, String format) throws Refusal {
        switch (engine) {
            case "ours" -> {
                return false;
            }
            case "libreoffice", "lo" -> {
                if (libreOffice == null) {
                    throw new Refusal(422, "engine", "LibreOffice is not installed on this server.");
                }
                if (!LibreOffice.converts(format)) {
                    throw new Refusal(422, "engine", "LibreOffice cannot convert a PDF to " + format + ".");
                }
                return true;
            }
            default -> throw new Refusal(422, "engine", "The engine is ours or libreoffice.");
        }
    }

    private String convert(Job job, Path out, Target target, String format, boolean office, Map<String, String> q,
            String password) throws Refusal {
        if (admitted.incrementAndGet() > capacity()) {
            admitted.decrementAndGet();
            throw new Refusal(503, "busy", BUSY);
        }
        Semaphore gate = office ? officeSlots : slots;
        boolean slot = false;
        try {
            slot = gate.tryAcquire(limits.queueWaitSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (!slot) {
                admitted.decrementAndGet();
            }
        }
        if (!slot) {
            throw new Refusal(503, "busy", BUSY);
        }
        String[] note = new String[1];
        Future<?> work;
        job.started();
        try {
            work = workers.submit(() -> {
                try {
                    PdfToDocx.Options options = options(job.pdf, q, password, note);
                    if (office) {
                        convertWithLibreOffice(job, out, format, options);
                    } else {
                        target.converter().convert(job.pdf, out, options);
                    }
                    return null;
                } finally {
                    gate.release();
                    admitted.decrementAndGet();
                    job.conversionEnded();
                }
            });
        } catch (RejectedExecutionException e) {
            gate.release();
            admitted.decrementAndGet();
            job.conversionEnded();
            throw new Refusal(503, "busy", BUSY);
        }
        try {
            if (limits.timeoutSeconds() > 0) {
                work.get(limits.timeoutSeconds(), TimeUnit.SECONDS);
            } else {
                work.get();
            }
            return note[0];
        } catch (TimeoutException e) {
            work.cancel(true);
            if (!awaitStop(work) && !office && job.markStuck()) {
                stuckConversion();
            }
            throw new Refusal(504, "timeout", "This PDF took longer than the " + limits.timeoutSeconds()
                    + " seconds this demo allows. Try a page range under Options.");
        } catch (InterruptedException e) {
            work.cancel(true);
            Thread.currentThread().interrupt();
            throw new Refusal(503, "busy", "The server is shutting down.");
        } catch (ExecutionException e) {
            throw refusal(e.getCause());
        }
    }

    private void stuckConversion() {
        System.out.println("A conversion did not stop after its timeout; " + stuck.get() + " of " + limits.concurrent()
                + " slots are held by stuck conversions");
        if (!healthy() && limits.exitWhenStuck()) {
            System.out.println("Every conversion slot is stuck: exiting so the service can be restarted");
            Thread.ofPlatform().daemon().start(() -> {
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.exit(3);
            });
        }
    }

    private PdfToDocx.Options options(Path pdf, Map<String, String> q, String password, String[] note) throws Refusal {
        int first = 0;
        int last = 0;
        String pages = q.getOrDefault("pages", "").strip();
        if (!pages.isEmpty()) {
            if (!pages.matches("\\d{0,6}\\s*(-\\s*\\d{0,6})?")) {
                throw new Refusal(422, "pages", "Pages should be a range like 2-5, or a single page like 3.");
            }
            String[] ab = pages.split("-", -1);
            first = ab[0].isBlank() ? 0 : Integer.parseInt(ab[0].strip());
            last = ab.length < 2 ? first : ab[1].isBlank() ? 0 : Integer.parseInt(ab[1].strip());
            if (last != 0 && first > last) {
                throw new Refusal(422, "pages", "The page range ends before it starts.");
            }
        }
        if (limits.maxPages() > 0) {
            int total = PdfFiles.pageCount(pdf, password);
            int from = Math.max(1, first);
            int to = last == 0 ? total : last;
            if (to > 0 && to - from + 1 > limits.maxPages()) {
                last = from + limits.maxPages() - 1;
                first = from;
                note[0] = "Converted pages " + first + "-" + last + (total > 0 ? " of " + total : "")
                        + ": this demo converts up to " + limits.maxPages() + " pages at a time.";
            }
        }
        boolean blank = password == null || password.isEmpty();
        return new PdfToDocx.Options(first, last, !"0".equals(q.get("tables")), 150f, blank ? null : password,
                "1".equals(q.get("fallback")), pictures(q.getOrDefault("pictures", "compact")));
    }

    private static Pictures pictures(String value) throws Refusal {
        return switch (value) {
            case "compact" -> Pictures.COMPACT;
            case "lossless" -> Pictures.LOSSLESS;
            default -> throw new Refusal(422, "pictures", "Pictures are compact or lossless.");
        };
    }

    private void convertWithLibreOffice(Job job, Path out, String format, PdfToDocx.Options options) throws IOException {
        try {
            libreOffice.convert(forLibreOffice(job.pdf, options, job.dir), out, format);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            PdfFiles.open(job.pdf, options.password()).close();
            throw e;
        }
    }

    private static Path forLibreOffice(Path pdf, PdfToDocx.Options o, Path dir) throws IOException {
        if (o.firstPage() == 0 && o.lastPage() == 0 && o.password() == null) {
            return pdf;
        }
        Path trimmed = dir.resolve("pages.pdf");
        try (PDDocument doc = PdfFiles.open(pdf, o.password()); PDDocument part = new PDDocument()) {
            int[] range = PdfFiles.pageRange(doc.getNumberOfPages(), o.firstPage(), o.lastPage());
            for (int i = range[0]; i <= range[1]; i++) {
                part.importPage(doc.getPage(i));
            }
            part.save(trimmed.toFile());
        }
        return trimmed;
    }

    private static Refusal refusal(Throwable e) {
        if (e instanceof Refusal r) {
            return r;
        }
        if (e instanceof InvalidPasswordException) {
            return new Refusal(422, "password", "This PDF is password protected. Enter its password under Options and try again.");
        }
        if (e instanceof OutOfMemoryError) {
            return new Refusal(422, "memory", "This PDF needs more memory than this demo has. Try a page range under Options.");
        }
        String message = e instanceof IOException ? e.getMessage() : null;
        if (message != null && message.startsWith("The PDF could not be opened")) {
            return new Refusal(422, "damaged", "This PDF is damaged or incomplete, so it could not be opened.");
        }
        if (message != null && (message.matches("Page \\d+ could not be (converted|read)")
                || message.matches("Page \\d+ is past the end of this \\d+-page PDF") || message.equals("The PDF has no pages")
                || message.startsWith("This PDF is encrypted with a certificate") || message.startsWith("LibreOffice could not"))) {
            return new Refusal(422, "convert", message);
        }
        System.out.println("Conversion failed: " + e);
        return new Refusal(500, "error", "Something went wrong converting this PDF.");
    }

    private static boolean awaitStop(Future<?> job) {
        long until = System.nanoTime() + STOP_WAIT_MILLIS * 1_000_000;
        while (!job.isDone() && System.nanoTime() < until) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return job.isDone();
            }
        }
        return job.isDone();
    }

    private void receive(InputStream body, Job job) throws IOException, Refusal {
        try (InputStream in = body; OutputStream out = Files.newOutputStream(job.pdf)) {
            byte[] head = in.readNBytes(1024);
            if (head.length == 0) {
                throw new Refusal(400, "empty", "The file is empty.");
            }
            if (!new String(head, StandardCharsets.ISO_8859_1).contains("%PDF-")) {
                throw new Refusal(415, "type", "That file is not a PDF.");
            }
            keep(job, head.length);
            out.write(head);
            long total = head.length;
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > limits.maxUploadBytes()) {
                    throw tooLarge();
                }
                keep(job, n);
                out.write(buf, 0, n);
            }
        }
    }

    private void keep(Job job, long n) throws Refusal {
        job.stored(n);
        if (stored.addAndGet(n) > limits.diskBudget()) {
            throw new Refusal(503, "busy", BUSY);
        }
    }

    private static void send(HttpExchange ex, Path doc, String type, String format, String note, long start) throws IOException {
        Headers h = ex.getResponseHeaders();
        guard(h);
        h.set("Content-Type", type);
        h.set("Content-Disposition", "attachment; filename=\"converted." + format + "\"");
        h.set("X-Convert-Ms", Long.toString((System.nanoTime() - start) / 1_000_000));
        if (note != null) {
            h.set("X-Note", note);
        }
        ex.sendResponseHeaders(200, Files.size(doc));
        try (OutputStream out = ex.getResponseBody()) {
            Files.copy(doc, out);
        }
    }

    private static void guard(Headers h) {
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Content-Security-Policy", "default-src 'none'; sandbox; frame-ancestors 'none'");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cross-Origin-Resource-Policy", "same-origin");
    }

    private static Map<String, String> query(HttpExchange ex) throws Refusal {
        Map<String, String> out = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) {
            return out;
        }
        try {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        } catch (IllegalArgumentException e) {
            throw new Refusal(400, "query", "The request's options could not be read.");
        }
        return out;
    }

    private static void fail(HttpExchange ex, int status, String code, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        guard(h);
        h.set("Content-Type", "text/plain; charset=utf-8");
        h.set("X-Error", code);
        if (status == 429 || status == 503) {
            h.set("Retry-After", "30");
        }
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static void clean(Path dir) {
        try (var files = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            System.out.println("Could not delete " + dir + ": " + e.getMessage());
        }
    }
}
