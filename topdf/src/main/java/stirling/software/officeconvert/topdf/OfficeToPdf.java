package stirling.software.officeconvert.topdf;

import java.awt.geom.Rectangle2D;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;

import stirling.software.officeconvert.topdf.docx.DocxRenderer;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.io.ActiveContent;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.PictureDecoder;
import stirling.software.officeconvert.topdf.io.SecureXml;
import stirling.software.officeconvert.topdf.pdf.DocumentInfo;
import stirling.software.officeconvert.topdf.pdf.PageSize;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.PdfOutput;
import stirling.software.officeconvert.topdf.pdf.TextStyle;
import stirling.software.officeconvert.topdf.pptx.PptxRenderer;
import stirling.software.officeconvert.topdf.xlsx.XlsxRenderer;

/** Bad input fails with an IOException. On Java 24 and later, very deep or large slides need the JVM-wide XML limits
 * that {@link stirling.software.officeconvert.topdf.io.PoiXml#raiseProcessLimits()} raises once at startup. */
public final class OfficeToPdf {

    public enum Format {
        DOCX,
        PPTX,
        XLSX;

        public static Format of(Path file) {
            Objects.requireNonNull(file, "file");
            String ext = extension(file);
            return switch (ext) {
                case "docx", "docm", "dotx", "dotm" -> DOCX;
                case "pptx", "pptm", "ppsx", "ppsm", "potx", "potm" -> PPTX;
                case "xlsx", "xlsm", "xltx", "xltm" -> XLSX;
                case "doc", "dot" -> throw legacy("Word", ext, "docx");
                case "ppt", "pps", "pot" -> throw legacy("PowerPoint", ext, "pptx");
                case "xls", "xlt" -> throw legacy("Excel", ext, "xlsx");
                case "xlsb" -> throw new IllegalArgumentException(
                        "Excel binary workbooks (.xlsb) are not supported; save the file as .xlsx");
                default -> throw new IllegalArgumentException("Not an Office document: " + file.getFileName()
                        + "; use .docx, .docm, .dotx, .dotm, .pptx, .pptm, .ppsx, .ppsm, .potx, .potm, .xlsx, .xlsm,"
                        + " .xltx or .xltm");
            };
        }

        public static boolean recognises(Path file) {
            Objects.requireNonNull(file, "file");
            return switch (extension(file)) {
                case "docx", "docm", "dotx", "dotm", "pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "xlsx", "xlsm",
                        "xltx", "xltm", "doc", "dot", "ppt", "pps", "pot", "xls", "xlt", "xlsb" -> true;
                default -> false;
            };
        }

        private static String extension(Path file) {
            Path name = file.getFileName();
            String n = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
            int dot = n.lastIndexOf('.');
            return dot < 0 ? "" : n.substring(dot + 1);
        }

        private static IllegalArgumentException legacy(String app, String ext, String modern) {
            return new IllegalArgumentException("Legacy " + app + " 97-2003 files (." + ext
                    + ") are not supported yet; save the file as ." + modern);
        }
    }

    public record Options(Duration timeout, List<Path> fontDirs, int maxPages, long maxScratchBytes) {

        public static final int DEFAULT_MAX_PAGES = 10_000;

        public static final long DEFAULT_MAX_SCRATCH_BYTES = 2L << 30;

        public Options {
            Objects.requireNonNull(timeout, "timeout");
            Objects.requireNonNull(fontDirs, "fontDirs");
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
        }

        public Options(Duration timeout, List<Path> fontDirs, int maxPages) {
            this(timeout, fontDirs, maxPages, DEFAULT_MAX_SCRATCH_BYTES);
        }

        public static Options defaults() {
            return new Options(Duration.ofMinutes(5), List.of(), DEFAULT_MAX_PAGES, DEFAULT_MAX_SCRATCH_BYTES);
        }

        public Options timeout(Duration limit) {
            return new Options(limit, fontDirs, maxPages, maxScratchBytes);
        }

        public Options fontDirs(List<Path> dirs) {
            return new Options(timeout, dirs, maxPages, maxScratchBytes);
        }

        public Options maxPages(int pages) {
            return new Options(timeout, fontDirs, pages, maxScratchBytes);
        }

        public Options maxScratchBytes(long bytes) {
            return new Options(timeout, fontDirs, maxPages, bytes);
        }
    }

    public record Result(int pages, boolean truncated, List<String> warnings) {

        public Result {
            Objects.requireNonNull(warnings, "warnings");
            warnings = List.copyOf(warnings);
        }
    }

    public static final class TimedOut extends IOException {
        TimedOut(Duration limit) {
            super("The conversion took longer than " + limit.toMillis() + " ms and was stopped");
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
        Format format;
        try {
            format = Format.of(in);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
        AtomicReference<Result> result = new AtomicReference<>();
        writeAtomically(out.toAbsolutePath(), options.timeout(), STOP_MILLIS,
                os -> result.set(render(in, format, os, options)));
        return result.get();
    }

    public static Result convert(InputStream in, Format format, OutputStream out, Options options) throws IOException {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(options, "options");
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
        for (Format f : formats) {
            Path tmp = null;
            try {
                tmp = Files.createTempFile("office-warm-up-", "." + f.name().toLowerCase(Locale.ROOT));
                Files.write(tmp, sample(f));
                render(tmp, f, OutputStream.nullOutputStream(), Options.defaults().maxPages(2));
            } catch (IOException | RuntimeException ignored) {
                // warming up is only an optimisation
            } finally {
                if (tmp != null) {
                    deleteQuietly(tmp);
                }
            }
        }
    }

    private static final byte[] SAMPLE_PNG = HexFormat.of().parseHex("89504e470d0a1a0a0000000d494844520000000200000002"
            + "080600000072b60d240000001249444154789c63f8cfc0d0c0f01f0c210c003c5f06fb4398423e0000000049454e44ae426082");

    // Loads only what every format shares (fonts, PDF writing, pictures), for a caller converting at the same time
    // whose document needs other classes than a sample would load. Never throws.
    public static void warmUpFoundation() {
        try {
            FontLibrary fonts = FontLibrary.system();
            try (PdfOutput out = new PdfOutput(fonts)) {
                try (PdfCanvas c = out.newPage(PageSize.LETTER)) {
                    c.text("Warm up", 72, 72, TextStyle.of(fonts.find("Calibri", false, false), 11));
                    c.text("Warm up", 72, 90, TextStyle.of(fonts.find("Arial", true, false), 11));
                    c.image(PictureDecoder.decode(out.document(), SAMPLE_PNG), 72, 100, 20, 20);
                }
                out.save(OutputStream.nullOutputStream());
            }
        } catch (IOException | RuntimeException ignored) {
            // warming up is only an optimisation
        }
    }

    private static final String CT = "application/vnd.openxmlformats-officedocument.";

    private static final String RELS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";

    static byte[] sample(Format format) throws IOException {
        if (format == Format.PPTX) {
            try (XMLSlideShow ppt = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                XSLFTextBox box = ppt.createSlide().createTextBox();
                box.setAnchor(new Rectangle2D.Double(40, 40, 400, 60));
                box.setText("Warm up");
                ppt.write(out);
                return out.toByteArray();
            }
        }
        boolean word = format == Format.DOCX;
        String main = word ? "word/document.xml" : "xl/workbook.xml";
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/" + main
                + "\" ContentType=\"" + CT + (word ? "wordprocessingml.document.main+xml" : "spreadsheetml.sheet.main+xml")
                + "\"/>" + (word ? "" : "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"" + CT
                        + "spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"" + CT
                        + "spreadsheetml.styles+xml\"/>") + "</Types>");
        parts.put("_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"" + RELS + "officeDocument\" Target=\"" + main + "\"/></Relationships>");
        if (word) {
            String w = "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Warm up</w:t></w:r></w:p>";
            parts.put(main, "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                    + w + "<w:tbl><w:tr><w:tc>" + w + "</w:tc><w:tc>" + w + "</w:tc></w:tr></w:tbl>" + w
                    + "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/></w:sectPr></w:body></w:document>");
        } else {
            String ns = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"";
            parts.put(main, "<workbook " + ns + " xmlns:r=\"" + RELS.substring(0, RELS.length() - 1) + "\"><sheets><sheet"
                    + " name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            parts.put("xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/"
                    + "relationships\"><Relationship Id=\"rId1\" Type=\"" + RELS + "worksheet\" Target=\"worksheets/"
                    + "sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"" + RELS + "styles\" Target=\"styles.xml\"/>"
                    + "</Relationships>");
            parts.put("xl/styles.xml", "<styleSheet " + ns + "><fonts count=\"1\"><font><sz val=\"11\"/><name"
                    + " val=\"Calibri\"/></font></fonts><fills count=\"1\"><fill><patternFill patternType=\"none\"/>"
                    + "</fill></fills><borders count=\"1\"><border/></borders><cellXfs count=\"1\"><xf numFmtId=\"0\""
                    + " fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellXfs></styleSheet>");
            parts.put("xl/worksheets/sheet1.xml", "<worksheet " + ns + "><sheetData><row r=\"1\"><c r=\"A1\""
                    + " t=\"inlineStr\"><is><t>Warm up</t></is></c><c r=\"B1\"><v>1.5</v></c></row></sheetData></worksheet>");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> e : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    static void dispatch(Path source, RenderJob job) throws IOException {
        switch (job.format()) {
            case DOCX -> DocxRenderer.render(source, job);
            case PPTX -> PptxRenderer.render(source, job);
            case XLSX -> XlsxRenderer.render(source, job);
        }
    }

    static final int MAX_DAMAGED_PARTS = 8;

    static Result render(Path source, Format requested, OutputStream sink, Options options, Renderer renderer)
            throws IOException {
        stopIfInterrupted();
        if (Files.size(source) > MAX_INPUT_BYTES) {
            throw tooLarge();
        }
        FontLibrary fonts = FontLibrary.withSystem(options.fontDirs());
        Attempts attempts = new Attempts();
        while (true) {
            try {
                return render(source, requested, sink, options, renderer, fonts, attempts);
            } catch (IOException | RuntimeException e) {
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
            return true;
        }
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
            FontLibrary fonts, Attempts attempts) throws IOException {
        try (OfficeZip zip = OfficeZip.open(source, OfficeZip.Limits.DEFAULT, attempts.damaged.keySet(),
                attempts.salvaged)) {
            attempts.main = zip.mainPart();
            Format format = detect(zip, requested);
            try (PdfOutput output = new PdfOutput(fonts, options.maxScratchBytes())) {
                output.info(DocumentInfo.read(zip));
                RenderJob job = new RenderJob(zip, format, options, fonts, output);
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
                return new Result(output.pageCount(), job.truncated() || failed != null, warnings);
            }
        }
    }

    // A renderer that fails after its first page keeps the pages it drew, unless leaving out a damaged part may still
    // convert them all; a failure on the first page (a broken master, say) usually spoils every page
    static boolean keepsPages(Throwable e, int pages) {
        if (pages < 2 || Thread.currentThread().isInterrupted() || damagedPart(e) != null) {
            return false;
        }
        if (SecureXml.refusedDoctype(e)) {
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
        if (t.contains("sheet.binary")) {
            throw new IOException("The file is an Excel binary workbook (.xlsb), which is not supported");
        }
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
        return requested;
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
            throw e;
        } catch (IOException e) {
            throw plain(e);
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Conversion interrupted");
            }
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new IOException("The document could not be converted: " + reason, e);
        } catch (StackOverflowError e) {
            throw new IOException("The document nests too deeply to convert", e);
        } catch (OutOfMemoryError e) {
            throw new IOException("The document needs more memory to convert than is available", e);
        }
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
        return new IOException("The document is too large: over " + (MAX_INPUT_BYTES >> 20) + " MB");
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path file) {
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
    }

    private static void stopIfInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Conversion interrupted");
        }
    }
}
