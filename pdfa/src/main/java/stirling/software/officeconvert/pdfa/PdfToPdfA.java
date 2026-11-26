package stirling.software.officeconvert.pdfa;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.RuntimeModules;
import stirling.software.officeconvert.extract.PdfFiles;
import stirling.software.officeconvert.jpx.JpxImageIO;
import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.font.FontSet;

public final class PdfToPdfA {

    public static final long MAX_INPUT_BYTES = PdfFiles.MAX_INPUT_BYTES;

    public record Options(PdfALevel level, String password, Duration timeout, List<Path> fontDirs, int maxPages,
            float flattenDpi, FontSet fonts, boolean levelFallback) {

        public static final int DEFAULT_MAX_PAGES = 10_000;

        public static final float DEFAULT_FLATTEN_DPI = 200;

        public Options {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(timeout, "timeout");
            Objects.requireNonNull(fontDirs, "fontDirs");
            Objects.requireNonNull(fonts, "fonts");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("The timeout must be zero (none) or more, was " + timeout);
            }
            if (maxPages < 0) {
                throw new IllegalArgumentException("maxPages must be 0 (no limit) or more, was " + maxPages);
            }
            if (!(flattenDpi >= 36 && flattenDpi <= 600)) {
                throw new IllegalArgumentException("flattenDpi must be from 36 to 600, was " + flattenDpi);
            }
            fontDirs = List.copyOf(fontDirs);
        }

        public Options(PdfALevel level, String password, Duration timeout, List<Path> fontDirs, int maxPages,
                float flattenDpi, FontSet fonts) {
            this(level, password, timeout, fontDirs, maxPages, flattenDpi, fonts, false);
        }

        public Options(PdfALevel level, String password, Duration timeout, List<Path> fontDirs, int maxPages,
                float flattenDpi) {
            this(level, password, timeout, fontDirs, maxPages, flattenDpi, FontSet.system());
        }

        public static Options defaults() {
            return new Options(PdfALevel.A2B, null, Duration.ofMinutes(5), List.of(), DEFAULT_MAX_PAGES,
                    DEFAULT_FLATTEN_DPI);
        }

        public Options level(PdfALevel to) {
            return new Options(to, password, timeout, fontDirs, maxPages, flattenDpi, fonts, levelFallback);
        }

        public Options password(String secret) {
            return new Options(level, secret, timeout, fontDirs, maxPages, flattenDpi, fonts, levelFallback);
        }

        public Options timeout(Duration limit) {
            return new Options(level, password, limit, fontDirs, maxPages, flattenDpi, fonts, levelFallback);
        }

        public Options fontDirs(List<Path> dirs) {
            return new Options(level, password, timeout, dirs, maxPages, flattenDpi, fonts, levelFallback);
        }

        public Options fonts(FontSet set) {
            return new Options(level, password, timeout, fontDirs, maxPages, flattenDpi, set, levelFallback);
        }

        public Options levelFallback(boolean allowed) {
            return new Options(level, password, timeout, fontDirs, maxPages, flattenDpi, fonts, allowed);
        }

        public FontLibrary fontLibrary() {
            return fonts.withDirectories(fontDirs).library();
        }

        public Options maxPages(int pages) {
            return new Options(level, password, timeout, fontDirs, pages, flattenDpi, fonts, levelFallback);
        }

        public Options flattenDpi(float dpi) {
            return new Options(level, password, timeout, fontDirs, maxPages, dpi, fonts, levelFallback);
        }

        @Override
        public String toString() {
            return "Options[level=" + level + ", password=" + (password == null ? "none" : "given") + ", timeout="
                    + timeout + ", fontDirs=" + fontDirs + ", maxPages=" + maxPages + ", flattenDpi=" + flattenDpi
                    + ", fonts=" + fonts + ", levelFallback=" + levelFallback + "]";
        }
    }

    /** {@code unverified} lists PDF/A rules the output may still break, as the converter could not fix them; when
     * it is not empty, validate the file before relying on its PDF/A label. */
    public record Result(PdfALevel level, int pages, List<String> warnings, List<Integer> flattenedPages,
            List<String> substitutedFonts, PdfALevel achievedLevel, List<String> unverified) {

        public Result {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(achievedLevel, "achievedLevel");
            warnings = List.copyOf(warnings);
            flattenedPages = List.copyOf(flattenedPages);
            substitutedFonts = List.copyOf(substitutedFonts);
            unverified = List.copyOf(unverified);
        }

        public Result(PdfALevel level, int pages, List<String> warnings, List<Integer> flattenedPages,
                List<String> substitutedFonts, PdfALevel achievedLevel) {
            this(level, pages, warnings, flattenedPages, substitutedFonts, achievedLevel, List.of());
        }

        public Result(PdfALevel level, int pages, List<String> warnings, List<Integer> flattenedPages,
                List<String> substitutedFonts) {
            this(level, pages, warnings, flattenedPages, substitutedFonts, level);
        }

        public int achievedPart() {
            return achievedLevel.part();
        }

        public String achievedConformance() {
            return achievedLevel.conformance();
        }

        public boolean levelReduced() {
            return achievedLevel != level;
        }
    }

    public static final class TimedOut extends IOException {
        TimedOut(Duration limit) {
            super("The conversion took longer than " + limit.toMillis() + " ms and was stopped");
        }
    }

    private PdfToPdfA() {}

    public static Result convert(Path in, Path out) throws IOException {
        return convert(in, out, Options.defaults());
    }

    public static Result convert(Path in, Path out, Options options) throws IOException {
        return Admission.readmitting(() -> convertOnce(in, out, options));
    }

    private static Result convertOnce(Path in, Path out, Options options) throws IOException {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(options, "options");
        RuntimeModules.check();
        PdfFiles.stopIfInterrupted();
        PdfFiles.checkSize(in);
        return PdfFiles.writeThenMove(out, ".pdfa-", part ->
                Deadline.run(options.timeout(), () -> ContentCache.run(() -> {
                    long size = Files.size(in);
                    Admission.Ticket ticket = Admission.jvm().enter(estimate(size, 0));
                    try (PDDocument doc = PdfFiles.open(in, options.password())) {
                        long content = ContentBudget.peakBytes(doc);
                        ticket.close();
                        ticket = Admission.jvm().enter(estimate(size, content));
                        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(part), 1 << 16)) {
                            return Conversion.run(doc, os, options);
                        }
                    } finally {
                        ticket.close();
                    }
                })));
    }

    /**
     * Converts an open document in place and writes it to {@code out} only once the conversion has succeeded, so a
     * conversion that fails or times out writes nothing; a failure while copying to or flushing {@code out} can leave
     * part or all of the output in it. The caller keeps ownership of the document: it holds the PDF/A changes
     * afterwards, whether the call succeeds or fails. After a {@link TimedOut} a worker that ignores interruption may
     * still read and change the document; do not reuse or close it until {@code Admission.jvm().status().stuck()} is 0.
     */
    public static Result convert(PDDocument document, OutputStream out, Options options) throws IOException {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(options, "options");
        PdfFiles.checkOpen(document);
        RuntimeModules.check();
        JpxImageIO.install();
        Path part = Files.createTempFile("pdf-to-pdfa-", ".pdf");
        AtomicBoolean abandoned = new AtomicBoolean();
        try {
            Result result = Deadline.run(options.timeout(), () -> ContentCache.run(() -> {
                long size = sourceBytes(document);
                Admission.Ticket ticket = Admission.jvm().enter(estimate(size, 0));
                try {
                    long content = ContentBudget.peakBytes(document);
                    ticket.close();
                    ticket = Admission.jvm().enter(estimate(size, content));
                    try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(part), 1 << 16)) {
                        return Conversion.run(document, os, options);
                    }
                } finally {
                    ticket.close();
                    if (abandoned.get()) {
                        deleteQuietly(part);
                    }
                }
            }));
            Files.copy(part, out);
            out.flush();
            return result;
        } finally {
            abandoned.set(true);
            deleteQuietly(part);
        }
    }

    // The file size, from the furthest object offset, for documents opened from a file; 0 for documents built in memory.
    private static long sourceBytes(PDDocument document) {
        long most = 0;
        for (Long offset : document.getDocument().getXrefTable().values()) {
            most = Math.max(most, offset == null ? 0 : offset);
        }
        return most;
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // A worker still writing keeps the file open on Windows; it deletes the file itself when it ends.
        }
    }

    static long estimate(long bytes, long content) {
        return Admission.BASE_BYTES * 8 + Math.min(bytes, 256L << 20) * 3 + content;
    }
}
