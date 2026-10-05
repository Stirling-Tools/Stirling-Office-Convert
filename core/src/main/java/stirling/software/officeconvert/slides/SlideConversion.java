package stirling.software.officeconvert.slides;

import java.awt.geom.AffineTransform;
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
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;

import stirling.software.officeconvert.PdfToPptx;
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

public final class SlideConversion {

    private static final Log LOG = LogFactory.getLog(SlideConversion.class);

    @FunctionalInterface
    public interface Writer {
        SlideSink open(OutputStream out) throws IOException;
    }

    private static final int SAMPLE_ALL_BELOW = 40;

    private static final int CACHE_BUDGET = 60_000;

    static final float MIN_SIDE = 72f;

    static final float MAX_SIDE = 4032f;

    private SlideConversion() {}

    public static void convert(Path pdf, Path target, PdfToPptx.Options options, Writer writer) throws IOException {
        Objects.requireNonNull(pdf, "pdf");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        PdfFiles.stopIfInterrupted();
        Path part = Files.createTempFile(PdfFiles.outputFolder(target), ".office-convert-", ".part");
        try {
            try (PDDocument doc = load(pdf, options.password());
                    OutputStream out = Files.newOutputStream(part)) {
                convert(doc, out, options, writer);
            }
            PdfFiles.stopIfInterrupted();
            moveIntoPlace(part, target);
        } catch (IOException e) {
            throw PdfFiles.interrupted(e);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    public static void convert(PDDocument doc, OutputStream out, PdfToPptx.Options options, Writer writer)
            throws IOException {
        Objects.requireNonNull(doc, "doc");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(options, "options");
        PdfFiles.checkOpen(doc);
        JpxImageIO.install();
        PdfFiles.stopIfInterrupted();
        Admission.Ticket ticket = Admission.jvm().enter(
                PdfFootprint.estimate(doc, options.firstPage(), options.lastPage(), options.figureDpi()));
        try {
            write(doc, out, options, writer);
        } catch (RuntimeException e) {
            throw new IOException("Conversion failed: " + (e.getMessage() != null ? e.getMessage() : e.toString()), e);
        } finally {
            ticket.close();
        }
    }

    private static PDDocument load(Path pdf, String password) throws IOException {
        return PdfFiles.open(pdf, password);
    }

    private static void moveIntoPlace(Path part, Path target) throws IOException {
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void write(PDDocument doc, OutputStream out, PdfToPptx.Options options, Writer writer)
            throws IOException {
        StreamGuard.check(doc);
        int[] range = PdfFiles.pageRange(doc.getNumberOfPages(), options.firstPage(), options.lastPage());
        int first = range[0];
        int last = range[1];
        PageReader reader = new PageReader(doc);
        DocStats stats = new DocStats();
        Map<Integer, PageData> cache = new HashMap<>();
        Map<Long, Integer> sizes = new HashMap<>();
        int count = last - first + 1;
        boolean cacheAll = count <= SAMPLE_ALL_BELOW;
        int[] cacheWeight = {0};
        boolean fallback = options.pictureFallback();
        for (int[] run : sampleRuns(first, last)) {
            readPages(reader, run[0], run[1], page -> {
                stopIfInterrupted();
                sizes.merge(sizeKey(page.width(), page.height()), 1, Integer::sum);
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
        float[] slide = slideSize(sizes, doc, first);

        try (SlideSink sink = writer.open(out)) {
            sink.scripts(stats.scripts);
            sink.begin(slide[0], slide[1], first, count);
            boolean hyphenated = autoHyphenated(doc) || stats.autoHyphenated();
            SlideBuilder builder = new SlideBuilder(doc, stats, sink, options.figureDpi(), options.pictures(), hyphenated,
                    slide[0], slide[1]);
            PageAnalyzer analyzer = new PageAnalyzer(stats, options.tables(), true, false);
            PageReader.PageConsumer consume = page -> {
                stopIfInterrupted();
                sink.slide(slide(page, doc, analyzer, builder, slide, fallback));
            };
            PageReader.PageConsumer unreadable = fallback ? page -> sink.slide(pictureSlide(page, doc, builder, slide)) : null;
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
            sink.finish(info == null ? null : PdfFiles.property(info.getTitle()), info == null ? null : PdfFiles.property(info.getAuthor()));
        }
    }

    private static Slide slide(PageData read, PDDocument doc, PageAnalyzer analyzer, SlideBuilder builder, float[] size,
            boolean fallback) throws IOException {
        PageFit fit = PageFit.of(read, size[0], size[1]);
        PageData page = fit.apply(read, size[0], size[1]);
        AffineTransform toDisplay = fit.toSlide(PageReader.displayTransform(
                doc.getPage(page.index()).getCropBox(), page.direction()));
        PaintOrder paint = PaintOrder.read(doc.getPage(page.index()), toDisplay, page.width(), page.height());
        SlantedText.Split slanted = SlantedText.split(page, paint);
        PageLayout layout;
        try {
            layout = analyzer.analyze(slanted.page());
        } catch (RuntimeException | StackOverflowError e) {
            layout = pictureOf(page, e, fallback);
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug(LayoutDump.dump(layout));
        }
        try {
            return builder.build(layout, toDisplay, paint, slanted.groups());
        } catch (RuntimeException | StackOverflowError e) {
            return builder.build(pictureOf(page, e, fallback), toDisplay, paint, List.of());
        }
    }

    private static Slide pictureSlide(PageData read, PDDocument doc, SlideBuilder builder, float[] size) throws IOException {
        PageFit fit = PageFit.of(read, size[0], size[1]);
        PageData page = fit.apply(read, size[0], size[1]);
        AffineTransform toDisplay = fit.toSlide(PageReader.displayTransform(doc.getPage(page.index()).getCropBox(), 0));
        PaintOrder paint = PaintOrder.read(doc.getPage(page.index()), toDisplay, page.width(), page.height());
        return builder.build(FallbackPage.of(page), toDisplay, paint, List.of());
    }

    private static PageLayout pictureOf(PageData page, Throwable e, boolean fallback) throws IOException {
        if (!fallback) {
            throw new IOException("Page " + (page.index() + 1) + " could not be converted", e);
        }
        LOG.warn("Page " + (page.index() + 1) + " could not be converted; kept as a picture", e);
        return FallbackPage.of(page);
    }

    static float[] slideSize(Map<Long, Integer> sizes, PDDocument doc, int first) {
        long best = -1;
        int bestCount = 0;
        for (Map.Entry<Long, Integer> e : new TreeMap<>(sizes).entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        float w;
        float h;
        if (best >= 0) {
            w = (best >>> 32) / 4f;
            h = (best & 0xFFFFFFFFL) / 4f;
        } else {
            var box = doc.getPage(first).getCropBox();
            boolean turned = Math.floorMod(doc.getPage(first).getRotation(), 180) == 90;
            w = turned ? box.getHeight() : box.getWidth();
            h = turned ? box.getWidth() : box.getHeight();
        }
        return new float[] {Math.clamp(w, MIN_SIDE, MAX_SIDE), Math.clamp(h, MIN_SIDE, MAX_SIDE)};
    }

    private static long sizeKey(float w, float h) {
        return ((long) Math.round(w * 4) << 32) | (Math.round(h * 4) & 0xFFFFFFFFL);
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
