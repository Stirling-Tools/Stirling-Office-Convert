package stirling.software.officeconvert;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.extract.PdfFiles;
import stirling.software.officeconvert.extract.PdfFootprint;
import stirling.software.officeconvert.memory.Admission;

public final class OfficeConvert {

    public enum Format {
        DOCX,
        ODT,
        FODT,
        XML,
        RTF,
        TXT,
        PPTX,
        ODP,
        XLSX,
        ODS;

        public static Format of(Path file) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            return switch (name.substring(name.lastIndexOf('.') + 1)) {
                case "docx" -> DOCX;
                case "odt" -> ODT;
                case "fodt" -> FODT;
                case "xml" -> XML;
                case "rtf", "doc" -> RTF;
                case "txt" -> TXT;
                case "pptx" -> PPTX;
                case "odp" -> ODP;
                case "xlsx" -> XLSX;
                case "ods" -> ODS;
                default -> throw new IllegalArgumentException("No output format for " + file.getFileName()
                        + ": use .docx, .odt, .fodt, .xml, .rtf, .doc, .txt, .pptx, .odp, .xlsx or .ods");
            };
        }
    }

    public record Settings(int firstPage, int lastPage, String password, boolean tables, boolean pictureFallback,
            float figureDpi, PdfToXlsx.Sheets sheets, Duration timeout, Pictures pictures) {

        public Settings(int firstPage, int lastPage, String password, boolean tables, boolean pictureFallback,
                float figureDpi, PdfToXlsx.Sheets sheets, Duration timeout) {
            this(firstPage, lastPage, password, tables, pictureFallback, figureDpi, sheets, timeout, Pictures.COMPACT);
        }

        public Settings {
            Objects.requireNonNull(sheets, "sheets");
            Objects.requireNonNull(timeout, "timeout");
            Objects.requireNonNull(pictures, "pictures");
            if (firstPage < 0 || lastPage < 0) {
                throw new IllegalArgumentException("Page numbers are 1-based, or 0 for the first or last page");
            }
            if (firstPage > 0 && lastPage > 0 && lastPage < firstPage) {
                throw new IllegalArgumentException("The page range ends before it starts: " + firstPage + "-" + lastPage);
            }
            if (!(figureDpi >= 36 && figureDpi <= 600)) {
                throw new IllegalArgumentException("figureDpi must be between 36 and 600, was " + figureDpi);
            }
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("The timeout must be zero (none) or more, was " + timeout);
            }
        }

        public static Settings defaults() {
            return new Settings(0, 0, null, true, false, 150f, PdfToXlsx.Sheets.PAGE, Duration.ZERO, Pictures.COMPACT);
        }

        public Settings pages(int first, int last) {
            return new Settings(first, last, password, tables, pictureFallback, figureDpi, sheets, timeout, pictures);
        }

        public Settings password(String password) {
            return new Settings(firstPage, lastPage, password, tables, pictureFallback, figureDpi, sheets, timeout, pictures);
        }

        public Settings tables(boolean detect) {
            return new Settings(firstPage, lastPage, password, detect, pictureFallback, figureDpi, sheets, timeout, pictures);
        }

        public Settings pictureFallback(boolean on) {
            return new Settings(firstPage, lastPage, password, tables, on, figureDpi, sheets, timeout, pictures);
        }

        public Settings figureDpi(float dpi) {
            return new Settings(firstPage, lastPage, password, tables, pictureFallback, dpi, sheets, timeout, pictures);
        }

        public Settings sheets(PdfToXlsx.Sheets how) {
            return new Settings(firstPage, lastPage, password, tables, pictureFallback, figureDpi, how, timeout, pictures);
        }

        public Settings timeout(Duration limit) {
            return new Settings(firstPage, lastPage, password, tables, pictureFallback, figureDpi, sheets, limit, pictures);
        }

        public Settings pictures(Pictures how) {
            return new Settings(firstPage, lastPage, password, tables, pictureFallback, figureDpi, sheets, timeout, how);
        }

        @Override
        public String toString() {
            return "Settings[pages=" + firstPage + "-" + lastPage + ", password=" + (password == null ? "none" : "***")
                    + ", tables=" + tables + ", pictureFallback=" + pictureFallback + ", figureDpi=" + figureDpi
                    + ", sheets=" + sheets + ", timeout=" + timeout + ", pictures=" + pictures + "]";
        }

        PdfToDocx.Options document() {
            return new PdfToDocx.Options(firstPage, lastPage, tables, figureDpi, password, pictureFallback, pictures);
        }

        PdfToPptx.Options slides() {
            return new PdfToPptx.Options(firstPage, lastPage, tables, figureDpi, password, pictureFallback, pictures);
        }

        PdfToXlsx.Options workbook(Format format) {
            return PdfToXlsx.Options.defaults().withPages(firstPage, lastPage).withTables(tables).withPassword(password)
                    .withSheets(sheets).withTextFallback(pictureFallback)
                    .withFormat(format == Format.ODS ? PdfToXlsx.Format.ODS : PdfToXlsx.Format.XLSX);
        }
    }

    public static final class TimedOut extends IOException {
        TimedOut(Duration limit) {
            super("The conversion took longer than " + limit.toMillis() + " ms and was stopped");
        }
    }

    private static final long STOP_MILLIS = 5_000;

    private OfficeConvert() {}

    public static void convert(Path pdf, Path out) throws IOException {
        convert(pdf, out, Settings.defaults());
    }

    public static void convert(Path pdf, Path out, Settings settings) throws IOException {
        convert(pdf, out, Format.of(out), settings);
    }

    public static void convert(Path pdf, Path out, Format format, Settings settings) throws IOException {
        Objects.requireNonNull(pdf, "pdf");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(settings, "settings");
        run(settings.timeout(), () -> {
            switch (format) {
                case DOCX -> PdfToDocx.convert(pdf, out, settings.document());
                case ODT -> PdfToOdt.convert(pdf, out, settings.document());
                case FODT, XML -> PdfToOdt.convertFlat(pdf, out, settings.document());
                case RTF -> PdfToRtf.convert(pdf, out, settings.document());
                case TXT -> PdfToText.convert(pdf, out, settings.document());
                case PPTX -> PdfToPptx.convert(pdf, out, settings.slides());
                case ODP -> PdfToOdp.convert(pdf, out, settings.slides());
                case XLSX, ODS -> PdfToXlsx.convert(pdf, out, settings.workbook(format));
            }
            return null;
        });
    }

    public static void convert(PDDocument pdf, OutputStream out, Format format, Settings settings) throws IOException {
        Objects.requireNonNull(pdf, "pdf");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(settings, "settings");
        run(settings.timeout(), () -> {
            switch (format) {
                case DOCX -> PdfToDocx.convert(pdf, out, settings.document());
                case ODT -> PdfToOdt.convert(pdf, out, settings.document());
                case FODT, XML -> PdfToOdt.convertFlat(pdf, out, settings.document());
                case RTF -> PdfToRtf.convert(pdf, out, settings.document());
                case TXT -> PdfToText.convert(pdf, out, settings.document());
                case PPTX -> PdfToPptx.convert(pdf, out, settings.slides());
                case ODP -> PdfToOdp.convert(pdf, out, settings.slides());
                case XLSX, ODS -> PdfToXlsx.convert(pdf, out, settings.workbook(format));
            }
            return null;
        });
    }

    public static String text(Path pdf) throws IOException {
        return text(pdf, Settings.defaults());
    }

    public static String text(Path pdf, Settings settings) throws IOException {
        Objects.requireNonNull(pdf, "pdf");
        Objects.requireNonNull(settings, "settings");
        return run(settings.timeout(), () -> {
            PdfFiles.stopIfInterrupted();
            try (PDDocument doc = PdfFiles.open(pdf, settings.password())) {
                return PdfToText.text(doc, settings.document());
            } catch (IOException e) {
                throw PdfFiles.interrupted(e);
            }
        });
    }

    /** The heap a conversion of this loaded PDF is likely to need, as the converter admits it: a host may queue or refuse
     * work with it. Conversions wait for this much of the shared budget ({@link Admission#BUDGET_PROPERTY}). */
    public static long memoryEstimate(PDDocument pdf, Format format, Settings settings) {
        Objects.requireNonNull(pdf, "pdf");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(settings, "settings");
        float dpi = format == Format.XLSX || format == Format.ODS ? 72f : settings.figureDpi();
        return PdfFootprint.estimate(pdf, settings.firstPage(), settings.lastPage(), dpi);
    }

    @FunctionalInterface
    private interface Work<T> {
        T call() throws IOException;
    }

    private static <T> T run(Duration timeout, Work<T> work) throws IOException {
        if (timeout.isZero()) {
            return guarded(work);
        }
        long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException e) {
            nanos = Long.MAX_VALUE;
        }
        FutureTask<T> task = new FutureTask<>(() -> guarded(work));
        Thread worker = Thread.ofPlatform().name("office-convert").daemon().start(task);
        try {
            return task.get(nanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            join(worker);
            throw new TimedOut(timeout);
        } catch (InterruptedException e) {
            task.cancel(true);
            join(worker);
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while converting");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException r) {
                throw r;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IOException(cause);
        }
    }

    // A conversion stopped for memory, or out of it, fails with one plain reason and leaves the JVM running
    private static <T> T guarded(Work<T> work) throws IOException {
        try {
            return work.call();
        } catch (IOException e) {
            if (stoppedForMemory(e)) {
                throw new IOException(Admission.NEEDS_MEMORY, e);
            }
            throw e;
        } catch (RuntimeException e) {
            if (stoppedForMemory(e)) {
                throw new IOException(Admission.NEEDS_MEMORY, e);
            }
            throw e;
        } catch (OutOfMemoryError e) {
            throw new IOException(Admission.NEEDS_MEMORY, e);
        }
    }

    private static boolean stoppedForMemory(Throwable e) {
        int depth = 0;
        for (Throwable t = e; t != null && depth++ < 16; t = t.getCause()) {
            if (t instanceof Admission.Stopped || t instanceof OutOfMemoryError) {
                return true;
            }
        }
        return false;
    }

    private static void join(Thread worker) {
        try {
            worker.join(STOP_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
