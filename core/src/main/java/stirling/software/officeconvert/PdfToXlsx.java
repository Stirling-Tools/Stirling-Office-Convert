package stirling.software.officeconvert;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;

import stirling.software.officeconvert.extract.PageData;
import stirling.software.officeconvert.extract.PageReader;
import stirling.software.officeconvert.extract.PdfFiles;
import stirling.software.officeconvert.extract.PdfFootprint;
import stirling.software.officeconvert.extract.StreamGuard;
import stirling.software.officeconvert.jpx.JpxImageIO;
import stirling.software.officeconvert.layout.DocStats;
import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.LineBuilder;
import stirling.software.officeconvert.layout.OcrText;
import stirling.software.officeconvert.layout.PageAnalyzer;
import stirling.software.officeconvert.layout.PageLayout;
import stirling.software.officeconvert.layout.Word;
import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.ods.OdsWriter;
import stirling.software.officeconvert.sheet.ConventionEvidence;
import stirling.software.officeconvert.sheet.MirroredSheets;
import stirling.software.officeconvert.sheet.SheetBuilder;
import stirling.software.officeconvert.sheet.WorkbookSink;
import stirling.software.officeconvert.xlsx.XlsxWriter;

public final class PdfToXlsx {

    private static final Log LOG = LogFactory.getLog(PdfToXlsx.class);

    private static final int CACHE_BUDGET = 60_000;

    public enum Format {
        XLSX,
        ODS;

        public static Format of(Path file) {
            return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ods") ? ODS : XLSX;
        }
    }

    public enum Sheets {
        PAGE,
        TABLE,
        SINGLE
    }

    public record Options(
            int firstPage,
            int lastPage,
            boolean tables,
            String password,
            Sheets sheets,
            boolean splitLargeTables,
            boolean typedValues,
            Format format,
            boolean textFallback) {

        public static Options defaults() {
            return new Options(0, 0, true, null, Sheets.PAGE, true, true, Format.XLSX, false);
        }

        public Options {
            if (firstPage < 0 || lastPage < 0) {
                throw new IllegalArgumentException("Page numbers are 1-based, or 0 for the first or last page");
            }
            if (firstPage > 0 && lastPage > 0 && lastPage < firstPage) {
                throw new IllegalArgumentException("The page range ends before it starts: " + firstPage + "-" + lastPage);
            }
            sheets = sheets == null ? Sheets.PAGE : sheets;
            format = format == null ? Format.XLSX : format;
        }

        public Options withPages(int first, int last) {
            return new Options(first, last, tables, password, sheets, splitLargeTables, typedValues, format, textFallback);
        }

        public Options withPassword(String pw) {
            return new Options(firstPage, lastPage, tables, pw, sheets, splitLargeTables, typedValues, format, textFallback);
        }

        public Options withSheets(Sheets s) {
            return new Options(firstPage, lastPage, tables, password, s, splitLargeTables, typedValues, format, textFallback);
        }

        public Options withFormat(Format f) {
            return new Options(firstPage, lastPage, tables, password, sheets, splitLargeTables, typedValues, f, textFallback);
        }

        public Options withTextFallback(boolean on) {
            return new Options(firstPage, lastPage, tables, password, sheets, splitLargeTables, typedValues, format, on);
        }

        public Options withTables(boolean on) {
            return new Options(firstPage, lastPage, on, password, sheets, splitLargeTables, typedValues, format, textFallback);
        }

        public Options withTypedValues(boolean on) {
            return new Options(firstPage, lastPage, tables, password, sheets, splitLargeTables, on, format, textFallback);
        }

        public Options withSplitLargeTables(boolean on) {
            return new Options(firstPage, lastPage, tables, password, sheets, on, typedValues, format, textFallback);
        }

        @Override
        public String toString() {
            return "Options[pages=" + firstPage + "-" + lastPage + ", tables=" + tables + ", password="
                    + (password == null ? "none" : "***") + ", sheets=" + sheets + ", splitLargeTables=" + splitLargeTables
                    + ", typedValues=" + typedValues + ", format=" + format + ", textFallback=" + textFallback + "]";
        }
    }

    private PdfToXlsx() {}

    public static void convert(Path pdf, Path out, Options options) throws IOException {
        Objects.requireNonNull(pdf, "pdf");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(options, "options");
        String name = out.getFileName().toString().toLowerCase(Locale.ROOT);
        Options opts = name.endsWith(".xlsx") || name.endsWith(".ods") ? options.withFormat(Format.of(out)) : options;
        PdfFiles.stopIfInterrupted();
        Path part = Files.createTempFile(PdfFiles.outputFolder(out), ".office-convert-", ".part");
        try {
            try (PDDocument doc = PageStream.load(pdf, opts.password());
                    OutputStream stream = Files.newOutputStream(part)) {
                convert(doc, stream, opts);
            }
            PdfFiles.stopIfInterrupted();
            PageStream.moveIntoPlace(part, out);
        } catch (IOException e) {
            throw PdfFiles.interrupted(e);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    public static void convert(PDDocument doc, OutputStream out, Options options) throws IOException {
        Objects.requireNonNull(doc, "doc");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(options, "options");
        PdfFiles.checkOpen(doc);
        JpxImageIO.install();
        PdfFiles.stopIfInterrupted();
        Admission.Ticket ticket = Admission.jvm().enter(
                PdfFootprint.estimate(doc, options.firstPage(), options.lastPage(), 72f));
        try {
            write(doc, out, options);
        } catch (RuntimeException e) {
            throw new IOException("Conversion failed: " + e.getMessage(), e);
        } finally {
            ticket.close();
        }
    }

    private static void write(PDDocument doc, OutputStream out, Options options) throws IOException {
        StreamGuard.check(doc);
        int total = doc.getNumberOfPages();
        int[] range = PdfFiles.pageRange(total, options.firstPage(), options.lastPage());
        int first = range[0];
        int last = range[1];
        PageReader reader = new PageReader(doc);
        DocStats stats = new DocStats();
        ConventionEvidence evidence = new ConventionEvidence();
        Map<Integer, PageData> cache = new HashMap<>();
        int count = last - first + 1;
        boolean cacheAll = count <= PageStream.SAMPLE_ALL_BELOW;
        int[] cacheWeight = {0};
        boolean fallback = options.textFallback();
        for (int[] run : PageStream.sampleRuns(first, last)) {
            PageStream.readPages(reader, run[0], run[1], page -> {
                PageStream.stopIfInterrupted();
                try {
                    List<Line> lines = LineBuilder.build(OcrText.pageGlyphs(page));
                    stats.add(page, lines);
                    for (Line l : lines) {
                        for (Word w : l.words) {
                            evidence.add(w.text);
                        }
                    }
                } catch (RuntimeException | StackOverflowError e) {
                    if (!fallback) {
                        throw new IOException("Page " + (page.index() + 1) + " could not be converted", e);
                    }
                    LOG.warn("Page " + (page.index() + 1) + " left out of the document statistics", e);
                }
                if (cacheAll && cacheWeight[0] + PageStream.weight(page) <= CACHE_BUDGET) {
                    cache.put(page.index(), page);
                    cacheWeight[0] += PageStream.weight(page);
                }
            }, fallback ? page -> { } : null);
        }
        stats.finish(count);

        SheetBuilder.Settings settings = new SheetBuilder.Settings(split(options.sheets()), options.splitLargeTables(),
                options.typedValues(), PageStream.autoHyphenated(doc) || stats.autoHyphenated());
        try (WorkbookSink sink = options.format() == Format.ODS ? new OdsWriter(out) : new XlsxWriter(out)) {
            SheetBuilder builder = new SheetBuilder(new MirroredSheets(sink, stats.scripts.rightToLeft()), stats, evidence.conventions(), settings, total);
            PageAnalyzer analyzer = new PageAnalyzer(stats, options.tables());
            PageReader.PageConsumer consume = page -> {
                PageStream.stopIfInterrupted();
                PageLayout layout;
                try {
                    layout = analyzer.analyze(page);
                } catch (RuntimeException | StackOverflowError e) {
                    plainPage(builder, page, e, fallback);
                    return;
                }
                try {
                    builder.page(layout);
                } catch (RuntimeException | StackOverflowError e) {
                    throw new IOException("Page " + (page.index() + 1) + " could not be converted", e);
                }
            };
            PageReader.PageConsumer unreadable = fallback ? page -> builder.plainPage(page, List.of()) : null;
            if (cacheAll) {
                for (int i = first; i <= last; i++) {
                    PageData cached = cache.remove(i);
                    if (cached != null) {
                        consume.accept(cached);
                    } else {
                        PageStream.readPages(reader, i, i, consume, unreadable);
                    }
                }
            } else {
                PageStream.readPages(reader, first, last, consume, unreadable);
            }
            PDDocumentInformation info = doc.getDocumentInformation();
            builder.finish(info == null ? null : PdfFiles.property(info.getTitle()),
                    info == null ? null : PdfFiles.property(info.getAuthor()));
        }
    }

    private static void plainPage(SheetBuilder builder, PageData page, Throwable e, boolean fallback) throws IOException {
        if (!fallback) {
            throw new IOException("Page " + (page.index() + 1) + " could not be converted", e);
        }
        LOG.warn("Page " + (page.index() + 1) + " could not be analysed; kept as lines of text", e);
        List<Line> lines;
        try {
            lines = LineBuilder.build(OcrText.pageGlyphs(page));
        } catch (RuntimeException | StackOverflowError again) {
            lines = List.of();
        }
        builder.plainPage(page, lines);
    }

    private static SheetBuilder.Split split(Sheets sheets) {
        return switch (sheets) {
            case PAGE -> SheetBuilder.Split.PAGE;
            case TABLE -> SheetBuilder.Split.TABLE;
            case SINGLE -> SheetBuilder.Split.SINGLE;
        };
    }
}
