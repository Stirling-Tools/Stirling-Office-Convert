package stirling.software.officeconvert.pdfa;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.extract.PdfFiles;
import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.font.FontSet;

public final class PdfToPdfA {

    public static final long MAX_INPUT_BYTES = 1L << 30;

    public record Options(PdfALevel level, String password, Duration timeout, List<Path> fontDirs, int maxPages,
            float flattenDpi, FontSet fonts) {

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
                float flattenDpi) {
            this(level, password, timeout, fontDirs, maxPages, flattenDpi, FontSet.system());
        }

        public static Options defaults() {
            return new Options(PdfALevel.A2B, null, Duration.ofMinutes(5), List.of(), DEFAULT_MAX_PAGES,
                    DEFAULT_FLATTEN_DPI);
        }

        public Options level(PdfALevel to) {
            return new Options(to, password, timeout, fontDirs, maxPages, flattenDpi, fonts);
        }

        public Options password(String secret) {
            return new Options(level, secret, timeout, fontDirs, maxPages, flattenDpi, fonts);
        }

        public Options timeout(Duration limit) {
            return new Options(level, password, limit, fontDirs, maxPages, flattenDpi, fonts);
        }

        public Options fontDirs(List<Path> dirs) {
            return new Options(level, password, timeout, dirs, maxPages, flattenDpi, fonts);
        }

        public Options fonts(FontSet set) {
            return new Options(level, password, timeout, fontDirs, maxPages, flattenDpi, set);
        }

        public FontLibrary fontLibrary() {
            return fonts.withDirectories(fontDirs).library();
        }

        public Options maxPages(int pages) {
            return new Options(level, password, timeout, fontDirs, pages, flattenDpi, fonts);
        }

        public Options flattenDpi(float dpi) {
            return new Options(level, password, timeout, fontDirs, maxPages, dpi, fonts);
        }

        @Override
        public String toString() {
            return "Options[level=" + level + ", password=" + (password == null ? "none" : "given") + ", timeout="
                    + timeout + ", fontDirs=" + fontDirs + ", maxPages=" + maxPages + ", flattenDpi=" + flattenDpi + ", fonts=" + fonts + "]";
        }
    }

    public record Result(PdfALevel level, int pages, List<String> warnings, List<Integer> flattenedPages,
            List<String> substitutedFonts) {

        public Result {
            Objects.requireNonNull(level, "level");
            warnings = List.copyOf(warnings);
            flattenedPages = List.copyOf(flattenedPages);
            substitutedFonts = List.copyOf(substitutedFonts);
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
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(options, "options");
        PdfFiles.stopIfInterrupted();
        if (Files.size(in) > MAX_INPUT_BYTES) {
            throw new IOException("The PDF is larger than " + (MAX_INPUT_BYTES >> 20) + " MB");
        }
        Path part = Files.createTempFile(PdfFiles.outputFolder(out), ".pdfa-", ".part");
        try {
            Result result = Deadline.run(options.timeout(), () -> {
                try (PDDocument doc = PdfFiles.open(in, options.password())) {
                    long content = ContentBudget.peakBytes(doc);
                    Admission.Ticket ticket = Admission.jvm().enter(estimate(Files.size(in), content));
                    try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(part), 1 << 16)) {
                        return Conversion.run(doc, os, options);
                    } finally {
                        ticket.close();
                    }
                }
            });
            PdfFiles.stopIfInterrupted();
            move(part, out);
            return result;
        } catch (IOException e) {
            throw PdfFiles.interrupted(e);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    public static Result convert(PDDocument document, OutputStream out, Options options) throws IOException {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(options, "options");
        PdfFiles.checkOpen(document);
        return Deadline.run(options.timeout(), () -> Conversion.run(document, out, options));
    }

    static long estimate(long bytes, long content) {
        return Admission.BASE_BYTES * 8 + Math.min(bytes, 256L << 20) * 3 + content;
    }

    private static void move(Path part, Path out) throws IOException {
        try {
            Files.move(part, out, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, out, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
