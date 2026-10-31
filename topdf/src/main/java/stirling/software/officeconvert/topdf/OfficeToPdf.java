package stirling.software.officeconvert.topdf;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import stirling.software.officeconvert.jpx.JpxImageIO;
import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.crypt.EncryptedPackage;
import stirling.software.officeconvert.topdf.docx.DocxRenderer;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.font.FontSet;
import stirling.software.officeconvert.topdf.io.ActiveContent;
import stirling.software.officeconvert.topdf.io.LegacyOffice;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.SecureXml;
import stirling.software.officeconvert.topdf.odf.OdfDocument;
import stirling.software.officeconvert.topdf.odf.OdfPackage;
import stirling.software.officeconvert.topdf.pdf.DocumentInfo;
import stirling.software.officeconvert.topdf.pdf.PageSize;
import stirling.software.officeconvert.topdf.pdf.PdfOutput;
import stirling.software.officeconvert.topdf.ppt.PptRenderer;
import stirling.software.officeconvert.topdf.pptx.PptxRenderer;
import stirling.software.officeconvert.topdf.rtf.RtfPackage;
import stirling.software.officeconvert.topdf.xls.XlsFile;
import stirling.software.officeconvert.topdf.xls.XlsPackage;
import stirling.software.officeconvert.topdf.xlsb.XlsbPackage;
import stirling.software.officeconvert.topdf.xlsx.XlsxRenderer;

/** Bad input fails with an IOException. On Java 24 and later, very deep or large slides need the JVM-wide XML limits
 * that {@link stirling.software.officeconvert.topdf.io.PoiXml#raiseProcessLimits()} raises once at startup. */
public final class OfficeToPdf {

    public enum Format {
        DOCX,
        PPTX,
        XLSX,
        PPT,
        TEXT,
        CSV,
        TSV,
        HTML,
        EPUB,
        JSON;

        public static Format of(Path file) {
            Objects.requireNonNull(file, "file");
            String ext = extension(file);
            return switch (ext) {
                case "docx", "docm", "dotx", "dotm", "doc", "dot", "rtf", "odt", "ott", "fodt", "odm", "xml", "sxw",
                        "stw", "pages", "pub" -> DOCX;
                case "txt", "text", "log", "asc" -> TEXT;
                case "pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "odp", "otp", "fodp", "odg", "otg", "fodg", "sxi",
                        "sti", "sxd", "std", "key", "vsdx", "vsdm", "vssx", "vssm", "vstx", "vstm", "vsd", "vss", "vst", "vsw" -> PPTX;
                case "xlsx", "xlsm", "xltx", "xltm", "xls", "xlt", "xlsb", "ods", "ots", "fods", "sxc", "stc", "slk",
                        "sylk", "dif", "dbf", "numbers", "wk1", "wks", "wk3", "wk4", "123" -> XLSX;
                case "csv" -> CSV;
                case "tsv", "tab" -> TSV;
                case "html", "htm", "xhtml", "zip", "htmlz", "mobi", "azw3", "azw", "prc", "pdb", "fb2", "fbz" -> HTML;
                case "epub" -> EPUB;
                case "json" -> JSON;
                case "ppt", "pps", "pot" -> PPT;
                default -> {
                    String reason = UnsupportedFormats.byExtension(ext);
                    throw new IllegalArgumentException(reason != null ? reason : "Not an Office document: "
                            + file.getFileName() + "; use .docx, .docm, .dotx, .dotm, .doc, .dot, .rtf, .pptx, .pptm,"
                            + " .ppsx, .ppsm, .potx, .potm, .xlsx, .xlsm, .xltx, .xltm, .xlsb, .xls, .xlt, .ppt, .pps, .pot,"
                            + " .odt, .ott, .fodt, .odm, .ods, .ots, .fods, .odp, .otp, .fodp, .odg, .otg, .fodg, .sxw, .stw,"
                            + " .sxc, .stc, .sxi, .sti, .sxd, .std, .vsdx, .vsdm, .vssx, .vssm, .vstx, .vstm, .xml, .slk,"
                            + " .dif, .dbf, .wk1, .wks, .wk3, .wk4, .123, .pages, .numbers, .key, .pub, .vsd, .vss, .vst, .vsw,"
                            + " .txt,"
                            + " .text, .log, .asc, .csv, .tsv, .tab, .html, .htm, .xhtml, .zip of HTML, .htmlz, .epub, .mobi, .azw3, .azw, .prc, .pdb, .fb2, .fbz or .json");
                }
            };
        }

        public static boolean recognises(Path file) {
            Objects.requireNonNull(file, "file");
            return switch (extension(file)) {
                case "docx", "docm", "dotx", "dotm", "pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "xlsx", "xlsm",
                        "xltx", "xltm", "doc", "dot", "ppt", "pps", "pot", "xls", "xlt", "xlsb", "rtf", "odt", "ott", "fodt",
                        "odm", "ods", "ots", "fods", "odp", "otp", "fodp", "odg", "otg", "fodg", "xml", "sxw", "stw", "sxc",
                        "stc", "sxi", "sti", "sxd", "std", "vsdx", "vsdm", "vssx", "vssm", "vstx", "vstm" -> true;
                case "txt", "text", "log", "asc", "csv", "tsv", "tab", "html", "htm", "xhtml", "json" -> true;
                case "mobi", "azw3", "azw", "prc", "fb2", "fbz", "htmlz" -> true;
                case "slk", "sylk", "dif", "dbf", "pages", "numbers", "key", "wk1", "wks", "wk3", "wk4", "123" -> true;
                case "pub", "vsd", "vss", "vst", "vsw" -> true;
                default -> false;
            };
        }

        private static String extension(Path file) {
            Path name = file.getFileName();
            String n = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
            int dot = n.lastIndexOf('.');
            return dot < 0 ? "" : n.substring(dot + 1);
        }
    }

    /** {@code password} opens a password protected document; it is never written anywhere, nor printed by
     * {@link #toString()}. */
    public record Options(Duration timeout, List<Path> fontDirs, int maxPages, long maxScratchBytes, FontSet fonts,
            String displayName, String password, HtmlAssets htmlAssets, boolean allowPackages, boolean tableOfContents,
            boolean pageNumbers) {

        public static final int DEFAULT_MAX_PAGES = 10_000;

        public static final long DEFAULT_MAX_SCRATCH_BYTES = 2L << 30;

        public Options {
            Objects.requireNonNull(timeout, "timeout");
            Objects.requireNonNull(fontDirs, "fontDirs");
            Objects.requireNonNull(fonts, "fonts");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("The timeout must be zero (none) or more, was " + timeout);
            }
            if (maxPages < 0) {
                throw new IllegalArgumentException("maxPages must be 0 (all) or more, was " + maxPages);
            }
            if (maxScratchBytes < 0) {
                throw new IllegalArgumentException("maxScratchBytes must be 0 (no limit) or more, was " + maxScratchBytes);
            }
            fontDirs = List.copyOf(fontDirs);
            displayName = displayName == null ? null : DocumentInfo.clean(displayName);
            if (displayName != null && displayName.length() > 255) {
                displayName = displayName.substring(0, Character.isHighSurrogate(displayName.charAt(254)) ? 254 : 255);
            }
            if (password != null && password.length() > MAX_PASSWORD_CHARS) {
                throw new IllegalArgumentException("The password is longer than " + MAX_PASSWORD_CHARS + " characters");
            }
            htmlAssets = htmlAssets == null ? HtmlAssets.NONE : htmlAssets;
        }

        public Options(Duration timeout, List<Path> fontDirs, int maxPages, long maxScratchBytes, FontSet fonts,
                String displayName, String password, HtmlAssets htmlAssets, boolean allowPackages) {
            this(timeout, fontDirs, maxPages, maxScratchBytes, fonts, displayName, password, htmlAssets, allowPackages,
                    false, false);
        }

        public Options(Duration timeout, List<Path> fontDirs, int maxPages, long maxScratchBytes, FontSet fonts,
                String displayName, String password, HtmlAssets htmlAssets) {
            this(timeout, fontDirs, maxPages, maxScratchBytes, fonts, displayName, password, htmlAssets, false);
        }

        public Options(Duration timeout, List<Path> fontDirs, int maxPages, long maxScratchBytes, FontSet fonts,
                String displayName, String password) {
            this(timeout, fontDirs, maxPages, maxScratchBytes, fonts, displayName, password, HtmlAssets.NONE);
        }

        public Options(Duration timeout, List<Path> fontDirs, int maxPages, long maxScratchBytes, FontSet fonts,
                String displayName) {
            this(timeout, fontDirs, maxPages, maxScratchBytes, fonts, displayName, null);
        }

        public Options(Duration timeout, List<Path> fontDirs, int maxPages, long maxScratchBytes) {
            this(timeout, fontDirs, maxPages, maxScratchBytes, FontSet.system(), null);
        }

        public Options(Duration timeout, List<Path> fontDirs, int maxPages) {
            this(timeout, fontDirs, maxPages, DEFAULT_MAX_SCRATCH_BYTES);
        }

        public static Options defaults() {
            return new Options(Duration.ofMinutes(5), List.of(), DEFAULT_MAX_PAGES, DEFAULT_MAX_SCRATCH_BYTES);
        }

        public Options timeout(Duration limit) {
            return new Options(limit, fontDirs, maxPages, maxScratchBytes, fonts, displayName, password, htmlAssets,
                    allowPackages, tableOfContents, pageNumbers);
        }

        public Options fontDirs(List<Path> dirs) {
            return new Options(timeout, dirs, maxPages, maxScratchBytes, fonts, displayName, password, htmlAssets,
                    allowPackages, tableOfContents, pageNumbers);
        }

        public Options fonts(FontSet set) {
            return new Options(timeout, fontDirs, maxPages, maxScratchBytes, set, displayName, password, htmlAssets,
                    allowPackages, tableOfContents, pageNumbers);
        }

        public Options maxPages(int pages) {
            return new Options(timeout, fontDirs, pages, maxScratchBytes, fonts, displayName, password, htmlAssets,
                    allowPackages, tableOfContents, pageNumbers);
        }

        public Options maxScratchBytes(long bytes) {
            return new Options(timeout, fontDirs, maxPages, bytes, fonts, displayName, password, htmlAssets,
                    allowPackages, tableOfContents, pageNumbers);
        }

        public Options displayName(String name) {
            return new Options(timeout, fontDirs, maxPages, maxScratchBytes, fonts, name, password, htmlAssets,
                    allowPackages, tableOfContents, pageNumbers);
        }

        public Options password(String secret) {
            return new Options(timeout, fontDirs, maxPages, maxScratchBytes, fonts, displayName, secret, htmlAssets,
                    allowPackages, tableOfContents, pageNumbers);
        }

        public Options htmlAssets(HtmlAssets assets) {
            return new Options(timeout, fontDirs, maxPages, maxScratchBytes, fonts, displayName, password, assets,
                    allowPackages, tableOfContents, pageNumbers);
        }

        public Options allowPackages(boolean allow) {
            return new Options(timeout, fontDirs, maxPages, maxScratchBytes, fonts, displayName, password, htmlAssets,
                    allow, tableOfContents, pageNumbers);
        }

        public Options tableOfContents(boolean add) {
            return new Options(timeout, fontDirs, maxPages, maxScratchBytes, fonts, displayName, password, htmlAssets,
                    allowPackages, add, pageNumbers);
        }

        public Options pageNumbers(boolean add) {
            return new Options(timeout, fontDirs, maxPages, maxScratchBytes, fonts, displayName, password, htmlAssets,
                    allowPackages, tableOfContents, add);
        }

        public FontLibrary fontLibrary() {
            return fonts.withDirectories(fontDirs).library();
        }

        @Override
        public String toString() {
            return "Options[timeout=" + timeout + ", fontDirs=" + fontDirs + ", maxPages=" + maxPages
                    + ", maxScratchBytes=" + maxScratchBytes + ", fonts=" + fonts + ", displayName=" + displayName
                    + ", password=" + (password == null ? "none" : "given") + ", htmlAssets="
                    + (htmlAssets == HtmlAssets.NONE ? "none" : "given") + ", allowPackages=" + allowPackages
                    + ", tableOfContents=" + tableOfContents + ", pageNumbers=" + pageNumbers + "]";
        }
    }

    public static final int MAX_PASSWORD_CHARS = 255;

    /** {@code truncated}: something is missing, the pages past the page limit ({@code pageLimitReached}) or content
     * that could not be read. */
    public record Result(int pages, boolean truncated, List<String> warnings, boolean pageLimitReached) {

        public Result {
            Objects.requireNonNull(warnings, "warnings");
            warnings = List.copyOf(warnings);
            truncated = truncated || pageLimitReached;
        }

        public Result(int pages, boolean truncated, List<String> warnings) {
            this(pages, truncated, warnings, false);
        }
    }

    public static final class TimedOut extends IOException {
        TimedOut(Duration limit) {
            super("The conversion took longer than " + limit.toMillis() + " ms and was stopped");
        }
    }

    /** The PDF would outgrow {@link Options#maxScratchBytes()}. */
    public static final class OutputTooLarge extends IOException {
        public OutputTooLarge(Throwable cause) {
            super("The PDF would be larger than the converter's output limit", cause);
        }
    }

    public static final long MAX_INPUT_BYTES = 512L << 20;

    private static final long STOP_MILLIS = 5_000;

    private static final long STACK_BYTES = 8L << 20;

    private OfficeToPdf() {}

    public static Result convert(Path in, Path out) throws IOException {
        return convert(in, out, Options.defaults());
    }

    public static Result convert(Path in, Path out, Options options) throws IOException {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(options, "options");
        JpxImageIO.install();
        Format format;
        try {
            format = Format.of(in);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
        AtomicReference<Result> result = new AtomicReference<>();
        Path inName = in.getFileName();
        Options named = options.displayName() == null && inName != null ? options.displayName(inName.toString())
                : options;
        writeAtomically(out.toAbsolutePath(), options.timeout(), STOP_MILLIS,
                os -> result.set(render(in, format, os, named)));
        return result.get();
    }

    public static Result convertUrl(String url, Path out, Options options, WebAssets.Access access) throws IOException {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(access, "access");
        JpxImageIO.install();
        AtomicReference<Result> result = new AtomicReference<>();
        writeAtomically(out.toAbsolutePath(), options.timeout(), STOP_MILLIS, os -> {
            WebAssets web = WebAssets.fetch(url, access);
            Path page = Files.createTempFile("office-to-pdf-", ".html");
            try {
                Files.write(page, web.page());
                Options fetched = options.htmlAssets(web);
                Options named = fetched.displayName() == null ? fetched.displayName(web.url()) : fetched;
                result.set(render(page, Format.HTML, os, named));
            } finally {
                deleteQuietly(page);
            }
        });
        return result.get();
    }

    public static Result convert(InputStream in, Format format, OutputStream out, Options options) throws IOException {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(options, "options");
        JpxImageIO.install();
        long started = System.nanoTime();
        AtomicBoolean abandoned = new AtomicBoolean();
        Path source = Files.createTempFile("office-to-pdf-", "." + format.name().toLowerCase(Locale.ROOT));
        Path pdf = null;
        try {
            copy(in, source, options.timeout(), started);
            pdf = Files.createTempFile("office-to-pdf-", ".pdf");
            Path target = pdf;
            Result result = run(options.timeout(), started, abandoned, STOP_MILLIS, () -> {
                try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(target), 1 << 16)) {
                    return render(source, format, os, options);
                } finally {
                    if (abandoned.get()) {
                        deleteQuietly(target);
                        deleteQuietly(source);
                    }
                }
            });
            Files.copy(pdf, out);
            out.flush();
            return result;
        } finally {
            deleteQuietly(source);
            if (pdf != null) {
                deleteQuietly(pdf);
            }
        }
    }

    /** The heap a conversion of this file is likely to need, from its parts alone, as the converter admits it: a host
     * may queue or refuse work with it. An IOException when the file is not an Office document that can be opened. */
    public static long memoryEstimate(Path in) throws IOException {
        Objects.requireNonNull(in, "in");
        Format format;
        try {
            format = Format.of(in);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
        if (LegacyOffice.powerPoint(in)) {
            return Footprint.legacy(Files.size(in));
        }
        if (EncryptedPackage.is(in)) {
            return Footprint.legacy(Files.size(in)) + 2 * Admission.BASE_BYTES;
        }
        Long drawing = LegacyDrawingInput.estimate(in);
        if (drawing != null) {
            return drawing;
        }
        Long iwork = IWorkInput.estimate(in);
        if (iwork != null) {
            return iwork;
        }
        Long visio = VisioInput.estimate(in);
        if (visio != null) {
            return visio;
        }
        if (XlsbPackage.is(in)) {
            return XlsbPackage.estimate(Files.size(in)) + 2 * Admission.BASE_BYTES;
        }
        if (RtfPackage.isRtf(in)) {
            return RtfPackage.estimate(Files.size(in)) + 2 * Admission.BASE_BYTES;
        }
        Long excel95 = LegacyExcel.estimate(in);
        if (excel95 != null) {
            return excel95;
        }
        Long legacy = legacyWorkbookEstimate(in);
        if (legacy != null) {
            return legacy;
        }
        Long word = LegacyWord.estimate(in);
        if (word != null) {
            return word;
        }
        Long ooo1 = Ooo1Input.estimate(in);
        if (ooo1 != null) {
            return ooo1;
        }
        if (OdfPackage.sniff(in) != null) {
            return OdfPackage.estimate(in) + 2 * Admission.BASE_BYTES;
        }
        Long grid = GridInput.estimate(in);
        if (grid != null) {
            return grid;
        }
        Long xml = XmlInput.estimate(in);
        if (xml != null) {
            return xml;
        }
        Long json = JsonInput.estimate(in);
        if (json != null) {
            return json;
        }
        Long html = HtmlInput.estimate(in, format);
        if (html != null) {
            return html;
        }
        Long text = TextInput.estimate(in);
        if (text != null) {
            return text;
        }
        try (OfficeZip zip = OfficeZip.open(in)) {
            return Footprint.estimate(zip, detect(zip, format));
        }
    }

    @FunctionalInterface
    interface Sink {
        void write(OutputStream out) throws IOException;
    }

    static void writeAtomically(Path target, Duration timeout, long stopMillis, Sink sink) throws IOException {
        String random = Long.toHexString(ThreadLocalRandom.current().nextLong());
        Path part = target.getParent().resolve("." + target.getFileName() + "." + random + ".part");
        AtomicBoolean abandoned = new AtomicBoolean();
        boolean moved = false;
        try {
            run(timeout, System.nanoTime(), abandoned, stopMillis, () -> {
                try (OutputStream os = new BufferedOutputStream(
                        Files.newOutputStream(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), 1 << 16)) {
                    sink.write(os);
                } finally {
                    if (abandoned.get() || Thread.currentThread().isInterrupted()) {
                        deleteQuietly(part);
                    }
                }
                return null;
            });
            move(part, target);
            moved = true;
        } finally {
            if (!moved) {
                deleteQuietly(part);
            }
        }
    }

    @FunctionalInterface
    interface Renderer {
        void render(Path source, RenderJob job) throws IOException;
    }

    static Result render(Path source, Format requested, OutputStream sink, Options options) throws IOException {
        return render(source, requested, sink, options, OfficeToPdf::dispatch);
    }

    // Converts a tiny built-in document of each format to nowhere, so a first real conversion finds the fonts scanned
    // and the classes loaded; run it on a spare thread at startup. Never throws.
    public static void warmUp(Format... formats) {
        WarmUp.formats(formats);
    }

    // Loads only what every format shares (fonts, PDF writing, pictures), for a caller converting at the same time
    // whose document needs other classes than a sample would load. Never throws.
    public static void warmUpFoundation() {
        WarmUp.foundation();
    }

    static void dispatch(Path source, RenderJob job) throws IOException {
        switch (job.format()) {
            case DOCX -> DocxRenderer.render(source, job);
            case PPTX -> PptxRenderer.render(source, job);
            case XLSX -> XlsxRenderer.render(source, job);
            case PPT -> PptRenderer.render(source, job);
            case TEXT, HTML, JSON -> DocxRenderer.render(source, job);
            case CSV, TSV -> XlsxRenderer.render(source, job);
        }
    }

    static final int MAX_DAMAGED_PARTS = 8;

    static boolean container(Path source) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(source)) {
            head = in.readNBytes(4);
        }
        boolean zip = head.length == 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
        boolean ole2 = head.length == 4 && (head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF && head[2] == 0x11
                && (head[3] & 0xFF) == 0xE0;
        return zip || ole2;
    }

    static Result render(Path source, Format requested, OutputStream sink, Options options, Renderer renderer)
            throws IOException {
        stopIfInterrupted();
        if (BookPages.wanted(options)) {
            return BookPages.render(source, requested, sink, options, renderer);
        }
        if (Files.size(source) > MAX_INPUT_BYTES) {
            throw tooLarge();
        }
        Result unlocked = encryptedPackage(source, requested, sink, options, renderer);
        if (unlocked != null) {
            return unlocked;
        }
        Result drawing = LegacyDrawingInput.render(source, sink, options);
        if (drawing != null) {
            return drawing;
        }
        Result excel95 = LegacyExcel.render(source, sink, options, renderer);
        if (excel95 != null) {
            return excel95;
        }
        Result legacy = legacyWorkbook(source, sink, options, renderer);
        if (legacy != null) {
            return legacy;
        }
        Result word = LegacyWord.render(source, sink, options, renderer);
        if (word != null) {
            return word;
        }
        Result iwork = IWorkInput.render(source, sink, options);
        if (iwork != null) {
            return iwork;
        }
        Result visio = VisioInput.render(source, sink, options, renderer);
        if (visio != null) {
            return visio;
        }
        Result xlsb = binaryWorkbook(source, sink, options, renderer);
        if (xlsb != null) {
            return xlsb;
        }
        Result rtf = richText(source, sink, options, renderer);
        if (rtf != null) {
            return rtf;
        }
        Result ooo1 = Ooo1Input.render(source, requested, sink, options, renderer);
        if (ooo1 != null) {
            return ooo1;
        }
        Result odf = openDocument(source, sink, options, renderer);
        if (odf != null) {
            return odf;
        }
        Result grid = GridInput.render(source, requested, sink, options, renderer);
        if (grid != null) {
            return grid;
        }
        Result xml = XmlInput.render(source, requested, sink, options, renderer);
        if (xml != null) {
            return xml;
        }
        Result json = JsonInput.render(source, requested, sink, options, renderer);
        if (json != null) {
            return json;
        }
        Result html = HtmlInput.render(source, requested, sink, options, renderer);
        if (html != null) {
            return html;
        }
        Result text = TextInput.render(source, requested, sink, options, renderer);
        if (text != null) {
            return text;
        }
        Path name = source.getFileName();
        if (name != null && name.toString().toLowerCase(Locale.ROOT).endsWith(".rtf") && !container(source)) {
            throw new IOException("The file is not an RTF document: it does not start with {\\rtf");
        }
        String unsupported = UnsupportedFormats.byContent(source);
        if (unsupported != null) {
            throw new IOException(unsupported);
        }
        return render(source, requested, sink, options, renderer, OfficeZip.Limits.DEFAULT);
    }

    // Our own rewrite of a legacy workbook: its parts are trusted, so only their sizes are bounded
    static final OfficeZip.Limits REWRITTEN = new OfficeZip.Limits(10_000, 512L << 20, 1L << 30, 0, 100L << 10,
            48L << 20);

    static Result render(Path source, Format requested, OutputStream sink, Options options, Renderer renderer,
            OfficeZip.Limits limits) throws IOException {
        FontLibrary fonts = options.fontLibrary();
        if (LegacyOffice.powerPoint(source)) {
            return renderLegacy(source, Format.PPT, sink, options, renderer, fonts);
        }
        Attempts attempts = new Attempts();
        while (true) {
            try {
                return render(source, requested, sink, options, renderer, fonts, attempts, limits);
            } catch (IOException | RuntimeException caught) {
                Throwable real = selfSuppressed(caught);
                if (!(real instanceof IOException) && !(real instanceof RuntimeException)) {
                    throw rethrow(real);
                }
                Exception e = (Exception) real;
                if (!attempts.leaveOut(e) && !attempts.salvageMain(source, e)) {
                    Exception reported = attempts.first == null ? e : attempts.first;
                    if (reported != e) {
                        reported.addSuppressed(e);
                    }
                    if (reported instanceof IOException io) {
                        throw io;
                    }
                    throw (RuntimeException) reported;
                }
            }
        }
    }

    // A damaged part other than the main ones is left out and the document drawn again, until output is written
    private static final class Attempts {

        final Map<String, String> damaged = new LinkedHashMap<>();

        final Set<String> refused = new LinkedHashSet<>();

        String main;

        boolean saving;

        Exception first;

        final Set<String> salvaged = new LinkedHashSet<>();

        // A main part or its relationships part that is not well-formed is read up to its damage, once each, before
        // the document is given up
        boolean salvageMain(Path source, Exception e) {
            if (main == null || saving || Thread.currentThread().isInterrupted()
                    || e instanceof InterruptedIOException || e instanceof RenderJob.PageLimitReached
                    || SecureXml.refusedDoctype(e)) {
                return false;
            }
            for (String part : List.of(main, OfficeZip.relsPartFor(main))) {
                if (salvaged.contains(part)) {
                    continue;
                }
                try (OfficeZip zip = OfficeZip.open(source, OfficeZip.Limits.DEFAULT, damaged.keySet())) {
                    if (!zip.salvageable(part)) {
                        continue;
                    }
                } catch (IOException | RuntimeException x) {
                    return false;
                }
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
                salvaged.add(part);
                return true;
            }
            return false;
        }

        boolean leaveOut(Exception e) {
            OfficeZip.DamagedPart d = damagedPart(e);
            if (d == null || saving || main == null || Thread.currentThread().isInterrupted()
                    || damaged.size() >= MAX_DAMAGED_PARTS || damaged.containsKey(d.part())) {
                return false;
            }
            String part = d.part();
            if (part.equalsIgnoreCase(main) || part.equalsIgnoreCase(OfficeZip.relsPartFor(main))
                    || part.equalsIgnoreCase(OfficeZip.CONTENT_TYPES) || part.equalsIgnoreCase(OfficeZip.PACKAGE_RELS)) {
                return false;
            }
            String source = sourceOf(part);
            if (source != null && source.equalsIgnoreCase(main)) {
                return false;
            }
            if (first == null) {
                first = e;
            } else {
                first.addSuppressed(e);
            }
            damaged.put(part, d.getMessage());
            if (source != null) {
                damaged.putIfAbsent(source, d.getMessage());
            }
            if (d.getCause() instanceof OfficeZip.Oversized) {
                refused.add(part);
            }
            return true;
        }
    }

    private static final Pattern NO_CONTENT = Pattern.compile("^/(docprops|customxml|docmetadata|glossary)/"
            + "|/(theme|printersettings|notesslides|notesmasters|handoutmasters|threadedcomments|persons)/"
            + "|/(settings|websettings|fonttable|styleswitheffects|presprops|viewprops|tablestyles|calcchain"
            + "|people|comments[a-z]*[0-9]*)[.]xml$|/(word|ppt)/styles[.]xml$");

    // Whether leaving out a part (or a .rels part, and so its source) loses content rather than only looks
    static boolean losesContent(String part) {
        String p = OfficeZip.canonical(part);
        String source = sourceOf(p);
        return !NO_CONTENT.matcher((source == null ? p : source).toLowerCase(Locale.ROOT)).find();
    }

    // The part whose relationships a .rels part holds: without them it cannot be drawn either
    static String sourceOf(String part) {
        String p = OfficeZip.canonical(part);
        int slash = p.lastIndexOf('/');
        String dir = p.substring(0, slash + 1);
        String name = p.substring(slash + 1);
        if (!dir.endsWith("/_rels/") || !name.toLowerCase(Locale.ROOT).endsWith(".rels") || name.length() <= 5) {
            return null;
        }
        return dir.substring(0, dir.length() - "_rels/".length()) + name.substring(0, name.length() - 5);
    }

    private static OfficeZip.DamagedPart damagedPart(Throwable e) {
        if (e instanceof InterruptedIOException || e instanceof RenderJob.PageLimitReached) {
            return null;
        }
        int depth = 0;
        for (Throwable t = e; t != null && depth++ < 16; t = t.getCause()) {
            if (t instanceof OfficeZip.DamagedPart d) {
                return d;
            }
        }
        return null;
    }

    private static Result render(Path source, Format requested, OutputStream sink, Options options, Renderer renderer,
            FontLibrary fonts, Attempts attempts, OfficeZip.Limits limits) throws IOException {
        try (OfficeZip zip = OfficeZip.open(source, limits, attempts.damaged.keySet(), attempts.salvaged)) {
            attempts.main = zip.mainPart();
            Format format = detect(zip, requested);
            try (Admission.Ticket ticket = Admission.jvm().enter(Footprint.estimate(zip, format));
                    PdfOutput output = new PdfOutput(fonts, options.maxScratchBytes())) {
                output.info(DocumentInfo.read(zip));
                RenderJob job = new RenderJob(zip, format, options, fonts, output);
                job.admitted(ticket);
                Throwable failed = null;
                try {
                    renderer.render(source, job);
                } catch (RenderJob.PageLimitReached e) {
                    stopIfInterrupted();
                } catch (RuntimeException | StackOverflowError e) {
                    if (!keepsPages(e, output.pageCount())) {
                        throw e;
                    }
                    failed = e;
                }
                stopIfInterrupted();
                if (output.pageCount() == 0) {
                    output.newPage(PageSize.LETTER).close();
                }
                attempts.saving = true;
                int pages = output.pageCount();
                try {
                    output.save(sink);
                } catch (IOException | RuntimeException e) {
                    if (failed == null) {
                        throw e;
                    }
                    failed.addSuppressed(e);
                    if (failed instanceof RuntimeException r) {
                        throw r;
                    }
                    throw (Error) failed;
                }
                List<String> warnings = new ArrayList<>();
                if (failed != null) {
                    warnings.add(RenderJob.clean(stoppedEarly(failed, pages)));
                }
                if (job.truncated()) {
                    warnings.add("Stopped at the page limit of " + options.maxPages() + " pages");
                }
                for (String why : attempts.damaged.values()) {
                    String c = RenderJob.clean("Left out a damaged part: "
                            + why.replaceFirst("^The document is damaged: ", ""));
                    if (c != null && !warnings.contains(c)) {
                        warnings.add(c);
                    }
                }
                for (String note : zip.notes()) {
                    String c = RenderJob.clean(note);
                    if (c != null && !warnings.contains(c)) {
                        warnings.add(c);
                    }
                }
                boolean lost = job.partial() || failed != null || !attempts.salvaged.isEmpty();
                for (String part : attempts.damaged.keySet()) {
                    lost |= losesContent(part) && !attempts.refused.contains(part);
                }
                for (String rels : zip.unreadableRelationships()) {
                    if (losesContent(rels) && !attempts.damaged.containsKey(rels)) {
                        lost = true;
                        String c = RenderJob.clean("Left out the relationships of " + sourceOf(rels)
                                + ", which could not be read");
                        if (c != null && !warnings.contains(c)) {
                            warnings.add(c);
                        }
                    }
                }
                for (String w : ActiveContent.describe(ActiveContent.scan(zip))) {
                    String c = RenderJob.clean(w);
                    if (c != null) {
                        warnings.add(c);
                    }
                }
                for (String w : job.warnings()) {
                    if (!warnings.contains(w)) {
                        warnings.add(w);
                    }
                }
                return new Result(output.pageCount(), lost, warnings, job.truncated());
            }
        }
    }

    // A binary file has no parts to leave out: one attempt, the same page, save and warning rules
    private static Result renderLegacy(Path source, Format format, OutputStream sink, Options options,
            Renderer renderer, FontLibrary fonts) throws IOException {
        try (Admission.Ticket ticket = Admission.jvm().enter(Footprint.legacy(Files.size(source)));
                PdfOutput output = new PdfOutput(fonts, options.maxScratchBytes())) {
            RenderJob job = new RenderJob(source, format, options, fonts, output);
            job.admitted(ticket);
            Throwable failed = null;
            try {
                renderer.render(source, job);
            } catch (RenderJob.PageLimitReached e) {
                stopIfInterrupted();
            } catch (RuntimeException | StackOverflowError e) {
                if (!keepsPages(e, output.pageCount())) {
                    throw e;
                }
                failed = e;
            }
            stopIfInterrupted();
            if (output.pageCount() == 0) {
                output.newPage(PageSize.LETTER).close();
            }
            int pages = output.pageCount();
            try {
                output.save(sink);
            } catch (IOException | RuntimeException e) {
                if (failed == null) {
                    throw e;
                }
                failed.addSuppressed(e);
                if (failed instanceof RuntimeException r) {
                    throw r;
                }
                throw (Error) failed;
            }
            List<String> warnings = new ArrayList<>();
            if (failed != null) {
                warnings.add(RenderJob.clean(stoppedEarly(failed, pages)));
            }
            if (job.truncated()) {
                warnings.add("Stopped at the page limit of " + options.maxPages() + " pages");
            }
            for (String w : job.warnings()) {
                if (!warnings.contains(w)) {
                    warnings.add(w);
                }
            }
            return new Result(output.pageCount(), job.partial() || failed != null, warnings, job.truncated());
        }
    }

    private static Long legacyWorkbookEstimate(Path source) throws IOException {
        if (!XlsFile.is(source)) {
            return null;
        }
        return XlsPackage.estimate(Files.size(source)) + 2 * Admission.BASE_BYTES;
    }

    // An Excel 97-2003 workbook is rewritten as an XLSX package first, whatever its extension, and drawn from that
    private static Result legacyWorkbook(Path source, OutputStream sink, Options options, Renderer renderer)
            throws IOException {
        if (!XlsFile.is(source)) {
            return null;
        }
        Path xlsx = null;
        try {
            XlsPackage.Outcome outcome;
            xlsx = Files.createTempFile("office-to-pdf-", ".xlsx");
            Admission.Ticket ticket = Admission.jvm().enter(XlsPackage.estimate(Files.size(source)));
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(xlsx), 1 << 16)) {
                outcome = XlsFile.rewrite(source, options.password(), os);
            } finally {
                ticket.close();
            }
            stopIfInterrupted();
            Result r = render(xlsx, Format.XLSX, sink, options, (s, job) -> renderer.render(source, job), REWRITTEN);
            List<String> warnings = new ArrayList<>();
            for (String w : r.warnings()) {
                if (w.startsWith("Only the first ") || w.startsWith("Stopped at the page limit")) {
                    warnings.add(w);
                }
            }
            for (String w : outcome.warnings()) {
                String c = RenderJob.clean(w);
                if (c != null && !warnings.contains(c)) {
                    warnings.add(c);
                }
            }
            for (String w : r.warnings()) {
                if (!warnings.contains(w)) {
                    warnings.add(w);
                }
            }
            return new Result(r.pages(), r.truncated() || outcome.lost(), warnings, r.pageLimitReached());
        } finally {
            if (xlsx != null) {
                deleteQuietly(xlsx);
            }
        }
    }

    // A password protected OOXML package is decrypted to a scratch file, converted like any other, and deleted
    private static Result encryptedPackage(Path source, Format requested, OutputStream sink, Options options,
            Renderer renderer) throws IOException {
        if (!EncryptedPackage.is(source)) {
            return null;
        }
        Path plain = Files.createTempFile("office-to-pdf-", ".package");
        try {
            EncryptedPackage.decrypt(source, options.password(), plain, MAX_INPUT_BYTES);
            stopIfInterrupted();
            if (EncryptedPackage.is(plain)) {
                throw new IOException("The document is encrypted more than once, which Office never does");
            }
            return render(plain, requested, sink, options, renderer);
        } finally {
            deleteQuietly(plain);
        }
    }

    private static Result binaryWorkbook(Path source, OutputStream sink, Options options, Renderer renderer)
            throws IOException {
        if (!XlsbPackage.is(source)) {
            return null;
        }
        Path xlsx = Files.createTempFile("office-to-pdf-", ".xlsx");
        try {
            XlsbPackage.Outcome outcome;
            Admission.Ticket ticket = Admission.jvm().enter(XlsbPackage.estimate(Files.size(source)));
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(xlsx), 1 << 16)) {
                outcome = XlsbPackage.write(source, os);
            } finally {
                ticket.close();
            }
            stopIfInterrupted();
            Result r = render(xlsx, Format.XLSX, sink, options, (s, job) -> renderer.render(source, job), REWRITTEN);
            List<String> warnings = new ArrayList<>(r.warnings());
            for (String w : outcome.warnings()) {
                String c = RenderJob.clean(w);
                if (c != null && !warnings.contains(c)) {
                    warnings.add(c);
                }
            }
            return new Result(r.pages(), r.truncated() || outcome.lost(), warnings, r.pageLimitReached());
        } finally {
            deleteQuietly(xlsx);
        }
    }

    private static Result richText(Path source, OutputStream sink, Options options, Renderer renderer)
            throws IOException {
        if (!RtfPackage.isRtf(source)) {
            return null;
        }
        Path docx = Files.createTempFile("office-to-pdf-", ".docx");
        try {
            RtfPackage.Outcome outcome;
            Admission.Ticket ticket = Admission.jvm().enter(RtfPackage.estimate(Files.size(source)));
            try (InputStream in = Files.newInputStream(source);
                    OutputStream os = new BufferedOutputStream(Files.newOutputStream(docx), 1 << 16)) {
                outcome = RtfPackage.write(in, os);
            } finally {
                ticket.close();
            }
            stopIfInterrupted();
            Result r = render(docx, Format.DOCX, sink, options, (s, job) -> renderer.render(source, job), REWRITTEN);
            List<String> warnings = new ArrayList<>(r.warnings());
            for (String w : outcome.warnings()) {
                String c = RenderJob.clean(w);
                if (c != null && !warnings.contains(c)) {
                    warnings.add(c);
                }
            }
            return new Result(r.pages(), r.truncated() || outcome.lost(), warnings, r.pageLimitReached());
        } finally {
            deleteQuietly(docx);
        }
    }

    private static Result openDocument(Path source, OutputStream sink, Options options, Renderer renderer)
            throws IOException {
        OdfDocument.Kind kind = OdfPackage.sniff(source);
        if (kind == null) {
            return null;
        }
        Format format = switch (kind) {
            case TEXT -> Format.DOCX;
            case SPREADSHEET -> Format.XLSX;
            case PRESENTATION -> Format.PPTX;
        };
        Path rewritten = null;
        try {
            rewritten = Files.createTempFile("office-to-pdf-", "." + format.name().toLowerCase(Locale.ROOT));
            OdfPackage.Outcome outcome;
            Admission.Ticket ticket = Admission.jvm().enter(OdfPackage.estimate(source));
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(rewritten), 1 << 16)) {
                outcome = OdfPackage.write(source, os, options.fontLibrary(), options.password());
            } finally {
                ticket.close();
            }
            stopIfInterrupted();
            Result r = render(rewritten, format, sink, options, renderer, REWRITTEN);
            List<String> warnings = new ArrayList<>(r.warnings());
            for (String w : outcome.warnings()) {
                String c = RenderJob.clean(w);
                if (c != null && !warnings.contains(c)) {
                    warnings.add(c);
                }
            }
            return new Result(r.pages(), r.truncated() || outcome.lost(), warnings, r.pageLimitReached());
        } finally {
            if (rewritten != null) {
                deleteQuietly(rewritten);
            }
        }
    }

    // A renderer that fails after its first page keeps the pages it drew, unless leaving out a damaged part may still
    // convert them all; a failure on the first page (a broken master, say) usually spoils every page
    static boolean keepsPages(Throwable e, int pages) {
        if (pages < 2 || Thread.currentThread().isInterrupted() || damagedPart(e) != null) {
            return false;
        }
        if (SecureXml.refusedDoctype(e) || scratchExceeded(e)) {
            return false;
        }
        int depth = 0;
        for (Throwable t = e; t != null && depth++ < 16; t = t.getCause()) {
            if (t instanceof InterruptedIOException || t instanceof InterruptedException || t instanceof TimedOut) {
                return false;
            }
        }
        return true;
    }

    static String stoppedEarly(Throwable e, int pages) {
        String reason = e instanceof StackOverflowError ? "the document nests too deeply"
                : e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
        return "Only the first " + (pages == 1 ? "page was" : pages + " pages were") + " converted: an error stopped"
                + " the conversion, so the rest of the document is missing (" + reason + ")";
    }

    static Format detect(OfficeZip zip, Format requested) throws IOException {
        String type = zip.mainContentType();
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (t.contains("wordprocessingml") || t.startsWith("application/vnd.ms-word.")) {
            return Format.DOCX;
        }
        if (t.contains("presentationml") || t.startsWith("application/vnd.ms-powerpoint.")) {
            return Format.PPTX;
        }
        if (t.contains("spreadsheetml") || t.startsWith("application/vnd.ms-excel.")) {
            return Format.XLSX;
        }
        if (t.contains("drawingml") || t.contains("visio") || t.contains("xps")) {
            throw new IOException("The file is not a Word, PowerPoint or Excel document (its main part is " + type + ")");
        }
        return switch (requested) {
            case PPT -> Format.PPTX;
            case TEXT -> Format.DOCX;
            case CSV, TSV -> Format.XLSX;
            default -> requested;
        };
    }

    @FunctionalInterface
    interface Work<T> {
        T call() throws IOException;
    }

    static <T> T run(Duration timeout, Work<T> work) throws IOException {
        return run(timeout, System.nanoTime(), new AtomicBoolean(), STOP_MILLIS, work);
    }

    static <T> T run(Duration timeout, long started, AtomicBoolean abandoned, long stopMillis, Work<T> work)
            throws IOException {
        stopIfInterrupted();
        long limit = limitNanos(timeout);
        long wait = limit - (System.nanoTime() - started);
        if (limit > 0 && wait <= 0) {
            throw new TimedOut(timeout);
        }
        FutureTask<T> task = new FutureTask<>(() -> guarded(work));
        Thread worker = Thread.ofPlatform().name("office-to-pdf").daemon().stackSize(STACK_BYTES).unstarted(task);
        boolean finished = false;
        try {
            worker.start();
            T result = limit == 0 ? task.get() : task.get(wait, TimeUnit.NANOSECONDS);
            finished = true;
            return result;
        } catch (TimeoutException e) {
            throw new TimedOut(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while converting");
        } catch (ExecutionException e) {
            finished = true;
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
        } finally {
            if (!finished) {
                abandoned.set(true);
                task.cancel(true);
                join(worker, stopMillis);
            }
        }
    }

    private static long limitNanos(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException e) {
            return 0;
        }
    }

    static final String DOCTYPE = "The document has a DOCTYPE or entity declaration, which Office files never"
            + " contain, so it was not read";

    private static <T> T guarded(Work<T> work) throws IOException {
        try {
            return work.call();
        } catch (InterruptedIOException e) {
            if (outOfMemory(e)) {
                throw new IOException(RenderJob.NEEDS_MEMORY, e);
            }
            throw e;
        } catch (IOException e) {
            if (outOfMemory(e)) {
                throw new IOException(RenderJob.NEEDS_MEMORY, e);
            }
            if (scratchExceeded(e)) {
                throw new OutputTooLarge(e);
            }
            throw plain(e);
        } catch (RuntimeException e) {
            Throwable real = selfSuppressed(e);
            if (real != e) {
                return guarded(() -> {
                    throw rethrow(real);
                });
            }
            if (outOfMemory(e)) {
                throw new IOException(RenderJob.NEEDS_MEMORY, e);
            }
            if (scratchExceeded(e)) {
                throw new OutputTooLarge(e);
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Conversion interrupted");
            }
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new IOException("The document could not be converted: " + reason, e);
        } catch (StackOverflowError e) {
            throw new IOException("The document nests too deeply to convert", e);
        } catch (OutOfMemoryError e) {
            throw new IOException(RenderJob.NEEDS_MEMORY, e);
        }
    }

    static Throwable selfSuppressed(Throwable e) {
        Throwable t = e;
        for (int depth = 0; depth < 16 && t instanceof IllegalArgumentException && t.getCause() != null
                && "Self-suppression not permitted".equals(t.getMessage()); depth++) {
            t = t.getCause();
        }
        return t;
    }

    private static IOException rethrow(Throwable t) throws IOException {
        if (t instanceof IOException io) {
            throw io;
        }
        if (t instanceof RuntimeException r) {
            throw r;
        }
        if (t instanceof Error err) {
            throw err;
        }
        throw new IOException(t);
    }

    private static boolean outOfMemory(Throwable e) {
        int depth = 0;
        for (Throwable t = e; t != null && depth++ < 16; t = t.getCause()) {
            if (t instanceof RenderJob.OutOfMemory || t instanceof Admission.Stopped) {
                return true;
            }
        }
        return false;
    }

    // PDFBox's own words when the output passes maxScratchBytes
    static boolean scratchExceeded(Throwable e) {
        int depth = 0;
        for (Throwable t = e; t != null && depth++ < 16; t = t.getCause()) {
            if (t instanceof OutputTooLarge || t instanceof IOException && t.getMessage() != null
                    && t.getMessage().startsWith("Maximum allowed scratch file memory exceeded")) {
                return true;
            }
        }
        return false;
    }

    // One plain reason for a refused DOCTYPE, whichever parser (ours or POI's) met it first
    private static IOException plain(IOException e) {
        return SecureXml.refusedDoctype(e) ? new IOException(DOCTYPE, e) : e;
    }

    private static void copy(InputStream in, Path target, Duration timeout, long started) throws IOException {
        long limit = limitNanos(timeout);
        long total = 0;
        byte[] buffer = new byte[1 << 16];
        try (OutputStream os = Files.newOutputStream(target)) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                stopIfInterrupted();
                if (limit > 0 && System.nanoTime() - started > limit) {
                    throw new TimedOut(timeout);
                }
                total += n;
                if (total > MAX_INPUT_BYTES) {
                    throw tooLarge();
                }
                os.write(buffer, 0, n);
            }
        }
    }

    private static IOException tooLarge() {
        return new OfficeZip.Oversized("The document is too large: over " + (MAX_INPUT_BYTES >> 20) + " MB");
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException e) {
            file.toFile().deleteOnExit();
        }
    }

    private static void join(Thread worker, long millis) {
        try {
            worker.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Admission.abandoned(worker);
    }

    static void stopIfInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Conversion interrupted");
        }
    }
}
