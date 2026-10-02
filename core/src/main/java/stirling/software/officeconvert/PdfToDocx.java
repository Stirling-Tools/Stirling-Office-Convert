package stirling.software.officeconvert;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;

import stirling.software.officeconvert.build.DocSink;
import stirling.software.officeconvert.build.DocumentBuilder;
import stirling.software.officeconvert.docx.DocxWriter;
import stirling.software.officeconvert.extract.PageData;
import stirling.software.officeconvert.extract.PageReader;
import stirling.software.officeconvert.extract.PdfFiles;
import stirling.software.officeconvert.extract.PdfFootprint;
import stirling.software.officeconvert.extract.StreamGuard;
import stirling.software.officeconvert.jpx.JpxImageIO;
import stirling.software.officeconvert.layout.DocStats;
import stirling.software.officeconvert.layout.FallbackPage;
import stirling.software.officeconvert.layout.LayoutDump;
import stirling.software.officeconvert.layout.LineBuilder;
import stirling.software.officeconvert.layout.OcrText;
import stirling.software.officeconvert.layout.PageAnalyzer;
import stirling.software.officeconvert.layout.PageLayout;
import stirling.software.officeconvert.memory.Admission;

public final class PdfToDocx {

    private static final Log LOG = LogFactory.getLog(PdfToDocx.class);

    private static final int SAMPLE_ALL_BELOW = 40;

    private static final int CACHE_BUDGET = 60_000;

    public record Options(int firstPage, int lastPage, boolean tables, float figureDpi, String password,
            boolean pictureFallback, Pictures pictures) {

        public Options(int firstPage, int lastPage, boolean tables, float figureDpi, String password) {
            this(firstPage, lastPage, tables, figureDpi, password, false);
        }

        public Options(int firstPage, int lastPage, boolean tables, float figureDpi, String password,
                boolean pictureFallback) {
            this(firstPage, lastPage, tables, figureDpi, password, pictureFallback, Pictures.COMPACT);
        }

        public static Options defaults() {
            return new Options(0, 0, true, 150f, null, false, Pictures.COMPACT);
        }

        public Options {
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
        }

        public Options withPictureFallback(boolean on) {
            return new Options(firstPage, lastPage, tables, figureDpi, password, on, pictures);
        }

        public Options withPictures(Pictures how) {
            return new Options(firstPage, lastPage, tables, figureDpi, password, pictureFallback, how);
        }

        @Override
        public String toString() {
            return "Options[pages=" + firstPage + "-" + lastPage + ", tables=" + tables + ", figureDpi=" + figureDpi
                    + ", password=" + (password == null ? "none" : "***") + ", pictureFallback=" + pictureFallback
                    + ", pictures=" + pictures + "]";
        }
    }

    private PdfToDocx() {}

    @FunctionalInterface
    interface WriterFactory<W extends DocSink & Closeable> {
        W open(OutputStream out) throws IOException;
    }

    public static void convert(Path pdf, Path docx, Options options) throws IOException {
        convert(pdf, docx, options, DocxWriter::new);
    }

    static <W extends DocSink & Closeable> void convert(Path pdf, Path target, Options options, WriterFactory<W> format)
            throws IOException {
        Objects.requireNonNull(pdf, "pdf");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        PdfFiles.stopIfInterrupted();
        Path part = Files.createTempFile(PdfFiles.outputFolder(target), ".office-convert-", ".part");
        try {
            try (PDDocument doc = load(pdf, options.password());
                    OutputStream out = Files.newOutputStream(part)) {
                convert(doc, out, options, format);
            }
            PdfFiles.stopIfInterrupted();
            moveIntoPlace(part, target);
        } catch (IOException e) {
            throw PdfFiles.interrupted(e);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    private static PDDocument load(Path pdf, String password) throws IOException {
        return PdfFiles.open(pdf, password);
    }

    private static void moveIntoPlace(Path part, Path docx) throws IOException {
        try {
            Files.move(part, docx, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, docx, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void convert(PDDocument doc, OutputStream out, Options options) throws IOException {
        convert(doc, out, options, DocxWriter::new);
    }

    static <W extends DocSink & Closeable> void convert(PDDocument doc, OutputStream out, Options options,
            WriterFactory<W> format) throws IOException {
        Objects.requireNonNull(doc, "doc");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(options, "options");
        PdfFiles.checkOpen(doc);
        JpxImageIO.install();
        PdfFiles.stopIfInterrupted();
        Admission.Ticket ticket = Admission.jvm().enter(
                PdfFootprint.estimate(doc, options.firstPage(), options.lastPage(), options.figureDpi()));
        try {
            write(doc, out, options, format);
        } catch (RuntimeException e) {
            throw new IOException("Conversion failed: " + e.getMessage(), e);
        } finally {
            ticket.close();
        }
    }

    private static <W extends DocSink & Closeable> void write(PDDocument doc, OutputStream out, Options options,
            WriterFactory<W> format) throws IOException {
        StreamGuard.check(doc);
        int[] range = PdfFiles.pageRange(doc.getNumberOfPages(), options.firstPage(), options.lastPage());
        int first = range[0];
        int last = range[1];
        PageReader reader = new PageReader(doc);

        DocStats stats = new DocStats();
        Map<Integer, PageData> cache = new HashMap<>();
        int count = last - first + 1;
        boolean cacheAll = count <= SAMPLE_ALL_BELOW;
        int[] cacheWeight = {0};
        boolean fallback = options.pictureFallback();
        for (int[] run : sampleRuns(first, last)) {
            readPages(reader, run[0], run[1], page -> {
                stopIfInterrupted();
                try {
                    stats.add(page, LineBuilder.build(OcrText.pageGlyphs(page)));
                } catch (RuntimeException | StackOverflowError e) {
                    if (!fallback) {
                        throw new IOException("Page " + (page.index() + 1) + " could not be converted", e);
                    }
                    LOG.warn("Page " + (page.index() + 1) + " left out of the document statistics", e);
                }
                if (cacheAll && cacheWeight[0] + weight(page) <= CACHE_BUDGET) {
                    cache.put(page.index(), page);
                    cacheWeight[0] += weight(page);
                }
            }, fallback ? page -> { } : null);
        }
        stats.finish(count);

        try (W writer = format.open(out)) {
            boolean hyphenated = autoHyphenated(doc) || stats.autoHyphenated();
            DocumentBuilder builder =
                    new DocumentBuilder(doc, stats, writer, options.figureDpi(), hyphenated, options.pictures());
            PageAnalyzer analyzer = new PageAnalyzer(stats, options.tables());
            PageReader.PageConsumer consume = page -> {
                stopIfInterrupted();
                var toDisplay = PageReader.displayTransform(reader.cropBox(page.index()), page.direction());
                PageLayout layout;
                try {
                    layout = analyzer.analyze(page);
                } catch (RuntimeException | StackOverflowError e) {
                    layout = pictureOf(page, e, fallback);
                }
                if (LOG.isDebugEnabled()) {
                    LOG.debug(LayoutDump.dump(layout));
                }
                try {
                    builder.page(layout, toDisplay);
                } catch (RuntimeException | StackOverflowError e) {
                    builder.page(pictureOf(page, e, fallback), toDisplay);
                }
            };
            PageReader.PageConsumer unreadable =
                    fallback ? page -> builder.page(FallbackPage.of(page), PageReader.displayTransform(
                            reader.cropBox(page.index()), 0)) : null;
            if (cacheAll) {
                for (int i = first; i <= last; i++) {
                    PageData cached = cache.remove(i);
                    if (cached != null) {
                        consume.accept(cached);
                    } else {
                        readPages(reader, i, i, consume, unreadable);
                    }
                }
            } else {
                readPages(reader, first, last, consume, unreadable);
            }
            PDDocumentInformation info = doc.getDocumentInformation();
            builder.finish(info == null ? null : PdfFiles.property(info.getTitle()), info == null ? null : PdfFiles.property(info.getAuthor()));
        }
    }

    private static PageLayout pictureOf(PageData page, Throwable e, boolean fallback) throws IOException {
        if (!fallback) {
            throw new IOException("Page " + (page.index() + 1) + " could not be converted", e);
        }
        LOG.warn("Page " + (page.index() + 1) + " could not be converted; kept as a picture", e);
        return FallbackPage.of(page);
    }

    private static void stopIfInterrupted() throws InterruptedIOException {
        PdfFiles.stopIfInterrupted();
    }

    private static int weight(PageData page) {
        var g = page.graphics();
        return page.glyphs().size() + page.hidden().size() + page.rotated().size()
                + g.rules().size() + g.fills().size() + g.images().size() + g.marks().size();
    }

    private static void readPages(PageReader reader, int first, int last, PageReader.PageConsumer consumer,
            PageReader.PageConsumer onUnreadable) throws IOException {
        int from = first;
        while (from <= last) {
            int[] reading = {from};
            boolean[] consuming = {false};
            try {
                reader.read(from, last, true, page -> {
                    consuming[0] = true;
                    consumer.accept(page);
                    consuming[0] = false;
                    reading[0] = page.index() + 1;
                });
                return;
            } catch (IOException | RuntimeException | StackOverflowError e) {
                if (consuming[0] || e instanceof InterruptedIOException) {
                    throw e;
                }
                if (onUnreadable == null) {
                    throw new IOException("Page " + (reading[0] + 1) + " could not be read", e);
                }
                LOG.warn("Page " + (reading[0] + 1) + " could not be read; kept as a picture", e);
                onUnreadable.accept(reader.unreadable(reading[0]));
                from = reading[0] + 1;
            }
        }
    }

    static List<int[]> sampleRuns(int first, int last) {
        int count = last - first + 1;
        TreeSet<Integer> pages = new TreeSet<>();
        if (count <= SAMPLE_ALL_BELOW) {
            for (int i = first; i <= last; i++) {
                pages.add(i);
            }
        } else {
            for (int i = 0; i < 20; i++) {
                pages.add(first + i);
            }
            for (int i = 0; i < 5; i++) {
                pages.add(last - i);
            }
            for (int k = 1; k <= 15; k++) {
                int page = first + (int) ((long) count * k / 16);
                pages.add(page);
                pages.add(Math.min(last, page + 1));
            }
        }
        List<int[]> runs = new ArrayList<>();
        int start = -1;
        int prev = -2;
        for (int p : pages) {
            if (p != prev + 1) {
                if (start >= 0) {
                    runs.add(new int[] {start, prev});
                }
                start = p;
            }
            prev = p;
        }
        if (start >= 0) {
            runs.add(new int[] {start, prev});
        }
        return runs;
    }

    private static boolean autoHyphenated(PDDocument doc) {
        PDDocumentInformation info = doc.getDocumentInformation();
        if (info == null) {
            return false;
        }
        String s = ((info.getProducer() == null ? "" : info.getProducer()) + " "
                        + (info.getCreator() == null ? "" : info.getCreator()))
                .toLowerCase(Locale.ROOT);
        for (String k : new String[] {"tex", "indesign", "quark", "framemaker", "scribus", "arbortext", "dvipdf", "xsl"}) {
            if (s.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
