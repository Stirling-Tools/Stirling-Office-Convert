package stirling.software.officeconvert.cli;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import stirling.software.officeconvert.OfficeConvert;
import stirling.software.officeconvert.PdfToDocx;
import stirling.software.officeconvert.PdfToPptx;
import stirling.software.officeconvert.PdfToXlsx;
import stirling.software.officeconvert.lineart.LineArt;
import stirling.software.officeconvert.optimize.PdfOptimizer;
import stirling.software.officeconvert.pdfa.PdfToPdfA;
import stirling.software.officeconvert.topdf.HtmlAssets;
import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.UnsupportedFormats;
import stirling.software.officeconvert.topdf.WebAssets;
import stirling.software.officeconvert.topdf.crypt.Passwords;
import stirling.software.officeconvert.topdf.io.PoiXml;
import stirling.software.officeconvert.topdf.text.TextFormats;

public final class Main {

    private static final String PDF = OutputFormat.PDF;

    private Main() {}

    private record Batch(Args args, Mode mode, OutputFormat.Settings settings) {}

    private record Outcome(List<String> warnings, List<String> notes) {}

    @FunctionalInterface
    private interface Conversion {
        Outcome convert(Path in, Path target) throws IOException;
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] argv) {
        if (argv.length > 0 && SplitPhotos.COMMAND.equals(argv[0])) {
            return SplitPhotos.run(Arrays.copyOfRange(argv, 1, argv.length));
        }
        Batch batch;
        try {
            Args args = Args.parse(argv);
            if (args == null) {
                usage();
                return 0;
            }
            if (!args.urls().isEmpty()) {
                checkUrl(args);
                return url(args.urls().get(0), args.output(), args.office(), args.web(), args.overwrite(), args.quiet());
            }
            batch = validate(args);
        } catch (Usage | IllegalArgumentException e) {
            System.err.println("office-convert: " + e.getMessage());
            System.err.println("Run office-convert --help for the options.");
            return 2;
        }
        return convert(batch);
    }

    private static void checkUrl(Args a) throws Usage {
        if (!a.rewrites().isEmpty() || a.lineArtTuned() || a.effort() != null || a.keepPrivateData()) {
            throw new Usage("--pdfa, --line-art and --optimize rewrite PDF input; a URL converts to PDF without them");
        }
        if (a.urls().size() > 1 || !a.inputs().isEmpty() || a.output() == null || !PDF.equals(extension(a.output()))
                || a.pagesGiven() || a.asksForOfficeFormat()) {
            throw new Usage("a URL converts on its own to the PDF named by -o out.pdf");
        }
        if (!a.web().network()) {
            throw new Usage("a URL is fetched only with --allow-network");
        }
    }

    private static Batch validate(Args a) throws Usage {
        if (a.web().linkedFromFiles() && !a.web().network()) {
            throw new Usage("--fetch-linked fetches only with --allow-network");
        }
        boolean anyOffice = false;
        boolean anyPdf = false;
        for (Path in : a.inputs()) {
            if (Files.isDirectory(in)) {
                anyOffice |= a.asksForPdf();
                anyPdf |= a.asksForOfficeFormat();
                continue;
            }
            boolean officeInput = officeOrPage(in);
            String unsupported = officeInput ? null : UnsupportedFormats.byExtension(extension(in));
            if (unsupported != null) {
                throw new Usage(in.getFileName() + ": " + unsupported);
            }
            anyOffice |= officeInput;
            anyPdf |= !officeInput;
        }
        if (a.lineArtTuned() && !a.rewrites().contains(Mode.LINE_ART)) {
            throw new Usage("--threshold, --edge-level, --window, --sauvola-k, --codec and --ink-colour go with --line-art");
        }
        Mode mode = mode(a.rewrites());
        if ((a.effort() != null || a.keepPrivateData()) && mode != Mode.OPTIMIZE) {
            throw new Usage("--effort and --keep-private-data go with --optimize");
        }
        if (mode.rewritesPdf() && (anyOffice || a.asksForOfficeFormat() || a.pagesGiven())) {
            throw new Usage(mode.flag() + " " + mode.purpose() + "; give it PDF input without --pages or --format");
        }
        if (anyOffice && a.pagesGiven()) {
            throw new Usage("--pages is for PDF input; Office documents convert whole (--max-pages n limits them)");
        }
        if (anyOffice && a.asksForOfficeFormat()) {
            throw new Usage("Office documents convert to PDF only; use --format pdf or leave it out");
        }
        if (anyPdf && a.asksForPdf() && !mode.rewritesPdf()) {
            throw new Usage("PDF input converts to an Office format, not pdf");
        }
        OutputFormat.Settings settings = settings(a);
        if (namesOneFile(a)) {
            checkOutputName(a, mode);
        }
        return new Batch(a, mode, settings);
    }

    private static Mode mode(Set<Mode> rewrites) throws Usage {
        if (rewrites.contains(Mode.LINE_ART) && rewrites.contains(Mode.PDFA)) {
            throw new Usage("--line-art and --pdfa are separate runs; give one of them");
        }
        if (rewrites.contains(Mode.OPTIMIZE) && rewrites.contains(Mode.LINE_ART)) {
            throw new Usage("--optimize and --line-art are separate passes; give one of them");
        }
        if (rewrites.contains(Mode.OPTIMIZE) && rewrites.contains(Mode.PDFA)) {
            throw new Usage("--optimize and --pdfa are separate passes; give one of them");
        }
        return rewrites.isEmpty() ? Mode.CONVERT : rewrites.iterator().next();
    }

    private static OutputFormat.Settings settings(Args a) {
        PdfToDocx.Options document = new PdfToDocx.Options(a.first(), a.last(), a.tables(), a.dpi(), a.password(),
                a.pictureFallback(), a.pictures());
        PdfToPptx.Options slides = new PdfToPptx.Options(a.first(), a.last(), a.tables(), a.dpi(), a.password(),
                a.pictureFallback(), a.pictures());
        PdfToXlsx.Options books = PdfToXlsx.Options.defaults().withPages(a.first(), a.last()).withTables(a.tables())
                .withPassword(a.password()).withSheets(a.sheets()).withTextFallback(a.pictureFallback());
        return new OutputFormat.Settings(document, slides, books, a.ebook());
    }

    private static boolean namesOneFile(Args a) {
        return a.output() != null && !Files.isDirectory(a.output()) && a.inputs().size() == 1
                && !Files.isDirectory(a.inputs().get(0));
    }

    private static void checkOutputName(Args a, Mode mode) throws Usage {
        if (mode.rewritesPdf()) {
            if (!PDF.equals(extension(a.output()))) {
                throw new Usage(mode.flag() + " writes a PDF; name the output .pdf");
            }
            return;
        }
        String ext = OutputFormat.known(extension(a.output()));
        boolean officeInput = officeOrPage(a.inputs().get(0));
        if (officeInput != PDF.equals(ext)) {
            throw new Usage(officeInput ? "an Office document converts to PDF; name the output .pdf"
                    : "PDF input converts to an Office format; the output cannot be .pdf");
        }
    }

    private static int convert(Batch batch) {
        Args a = batch.args();
        List<Path> files = new ArrayList<>();
        int failures = expand(batch, files);
        if (failures < 0) {
            return 1;
        }
        if (!a.quiet() && (batch.mode() == Mode.PDFA || files.stream().anyMatch(Main::officeOrPage))) {
            for (String problem : a.fonts().problems()) {
                System.err.println("warning: fonts: " + oneLine(problem));
            }
        }
        warmUp(files);
        List<Path> targets = Targets.of(files, a.output(), batch.mode().rewritesPdf() ? batch.mode().suffix() : a.format(),
                a.inputs().stream().anyMatch(Files::isDirectory));
        Guard guard = new Guard(files, a.overwrite());
        Conversion conversion = conversion(batch);
        for (int k = 0; k < files.size(); k++) {
            Path in = files.get(k);
            Path target = targets.get(k);
            String refusal = guard.refusal(in, target);
            if (refusal != null) {
                failures++;
                System.err.println("SKIP " + in + ": " + oneLine(refusal));
                continue;
            }
            if (!convertOne(in, target, conversion, guard, a.quiet())) {
                failures++;
            }
        }
        return failures == 0 ? 0 : 1;
    }

    private static int expand(Batch batch, List<Path> files) {
        Mode mode = batch.mode();
        Args a = batch.args();
        boolean officeFolder = !mode.rewritesPdf() && !a.asksForOfficeFormat();
        boolean pdfFolder = mode.rewritesPdf() || !a.asksForPdf();
        boolean textFolder = a.formatGiven();
        boolean packages = a.office().allowPackages();
        int failures = 0;
        for (Path in : a.inputs()) {
            if (!Files.isDirectory(in)) {
                files.add(in);
                continue;
            }
            int before = files.size();
            try (Stream<Path> s = Files.list(in)) {
                s.filter(p -> Files.isRegularFile(p) && !lockFile(p) && (officeFolder && folderDocument(p, packages)
                        && (textFolder || !plainSource(p))
                        || pdfFolder && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")
                        && !(mode.rewritesPdf()
                                && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith("." + mode.suffix()))))
                        .sorted().forEach(files::add);
            } catch (IOException e) {
                System.err.println("office-convert: cannot list " + in + ": " + e.getMessage());
                return -1;
            }
            if (files.size() == before) {
                failures++;
                System.err.println("office-convert: nothing to convert in " + in);
            }
        }
        return failures;
    }

    private static Conversion conversion(Batch batch) {
        Args a = batch.args();
        OfficeToPdf.Options office = a.office();
        return switch (batch.mode()) {
            case PDFA -> {
                PdfToPdfA.Options archival = PdfToPdfA.Options.defaults().level(a.pdfaLevel()).password(a.password())
                        .timeout(office.timeout()).maxPages(office.maxPages()).fonts(a.fonts());
                yield (in, target) -> notes(PdfToPdfA.convert(in, target, archival).warnings());
            }
            case LINE_ART -> {
                LineArt.Options bilevel = a.lineArt().password(a.password());
                yield (in, target) -> lineArtNotes(LineArt.convert(in, target, bilevel));
            }
            case OPTIMIZE -> {
                PdfOptimizer.Options shrink = PdfOptimizer.Options.defaults().password(a.password())
                        .timeout(office.timeout()).effort(a.effort() == null ? PdfOptimizer.Effort.DEFAULT : a.effort())
                        .keepPrivateData(a.keepPrivateData());
                yield (in, target) -> optimizeNotes(PdfOptimizer.optimize(in, target, shrink));
            }
            case CONVERT -> (in, target) -> {
                if (officeOrPage(in)) {
                    OfficeToPdf.Options page = page(in) ? office.htmlAssets(assets(in, a.localFiles(), a.web())) : office;
                    return new Outcome(officeToPdf(in, target, page).warnings(), List.of());
                }
                OfficeConvert.run(office.timeout(), () -> {
                    pdfToOffice(in, target, batch.settings());
                    return null;
                });
                return notes(List.of());
            };
        };
    }

    private static Outcome notes(List<String> notes) {
        return new Outcome(List.of(), notes);
    }

    private static Outcome lineArtNotes(LineArt.Result art) {
        List<String> notes = new ArrayList<>(art.warnings());
        notes.add(art.converted() + " of " + art.images() + " pictures made 1-bit, " + art.bytesBefore() + " -> "
                + art.bytesAfter() + " bytes");
        return notes(notes);
    }

    private static Outcome optimizeNotes(PdfOptimizer.Result r) {
        List<String> notes = new ArrayList<>(r.warnings());
        if (r.rewritten()) {
            notes.add(String.format(Locale.ROOT, "%d -> %d bytes (%.1f%% %s)", r.inputBytes(), r.outputBytes(),
                    100.0 * Math.abs((double) r.inputBytes() - r.outputBytes()) / Math.max(1, r.inputBytes()),
                    r.outputBytes() <= r.inputBytes() ? "smaller" : "larger"));
        } else if (r.warnings().isEmpty()) {
            notes.add("already as small as the rewrite; copied unchanged; any private data is retained");
        }
        return notes(notes);
    }

    private static void pdfToOffice(Path pdf, Path target, OutputFormat.Settings settings) throws IOException {
        OutputFormat format = OutputFormat.of(extension(target));
        if (format == null) {
            throw new IOException("unknown output format ." + extension(target));
        }
        format.convert(pdf, target, settings);
    }

    private static boolean convertOne(Path in, Path target, Conversion conversion, Guard guard, boolean quiet) {
        resetPeaks();
        long start = System.nanoTime();
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Outcome outcome = conversion.convert(in, target);
            guard.wrote(target);
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (!quiet) {
                Runtime rt = Runtime.getRuntime();
                long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                System.out.printf("OK %s -> %s (%d ms, heap %d MB, peak %d MB)%n", in, target, ms, usedMb, peakMb());
                for (String w : outcome.warnings()) {
                    System.err.println("warning: " + in.getFileName() + ": " + oneLine(w));
                }
                for (String w : outcome.notes()) {
                    System.err.println("note: " + in.getFileName() + ": " + oneLine(w));
                }
            }
            return true;
        } catch (IOException e) {
            System.err.println("FAIL " + in + ": " + oneLine(describe(e)));
        } catch (RuntimeException | OutOfMemoryError e) {
            System.err.println("FAIL " + in + ": unexpected " + e);
            e.printStackTrace();
        }
        return false;
    }

    private static void resetPeaks() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                pool.resetPeakUsage();
            }
        }
    }

    // Heap pools peak apart, so their sum is an upper bound of the conversion's peak heap
    private static long peakMb() {
        long peak = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP && pool.getPeakUsage() != null) {
                peak += pool.getPeakUsage().getUsed();
            }
        }
        return peak >> 20;
    }

    // Messages quote document content; keep each to one line without terminal control codes
    static String oneLine(String s) {
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (Character.isISOControl(cp) || cp >= 0x202A && cp <= 0x202E || cp >= 0x2066 && cp <= 0x2069) {
                b.append(' ');
            } else {
                b.appendCodePoint(cp);
            }
        });
        return b.toString();
    }

    // Office keeps "~$name" owner files beside open documents; they are never documents themselves
    private static boolean lockFile(Path p) {
        return p.getFileName().toString().startsWith("~$");
    }

    private static String describe(IOException e) {
        if (e instanceof InvalidPasswordException) {
            return "the PDF is password protected; give its password with --password";
        }
        if (e instanceof Passwords.Refused) {
            return Passwords.PROTECTED.equals(e.getMessage())
                    ? "the document is password protected; give its password with --password" : e.getMessage();
        }
        if (e instanceof NoSuchFileException missing) {
            return "no such file or folder: " + missing.getFile() + (missing.getReason() == null ? "" : " (" + missing.getReason() + ")");
        }
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private static HtmlAssets assets(Path page, boolean localFiles, WebAssets.Access web) throws IOException {
        HtmlAssets local = localFiles ? HtmlAssets.folder(page.toAbsolutePath().getParent(),
                page.getFileName().toString()) : HtmlAssets.NONE;
        return web.linkedFromFiles() ? WebAssets.linkedFrom(local, web) : local;
    }

    private static int url(String url, Path target, OfficeToPdf.Options office, WebAssets.Access web, boolean overwrite,
            boolean quiet) {
        if (Files.exists(target) && !overwrite) {
            System.err.println("SKIP " + url + ": " + target + " exists; --overwrite replaces it");
            return 1;
        }
        long start = System.nanoTime();
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            PoiXml.raiseProcessLimits();
            OfficeToPdf.Result result = OfficeToPdf.convertUrl(url, target, office, web);
            if (!quiet) {
                System.out.printf("OK %s -> %s (%d ms)%n", url, target, (System.nanoTime() - start) / 1_000_000);
                for (String w : result.warnings()) {
                    System.err.println("warning: " + oneLine(w));
                }
            }
            return 0;
        } catch (IOException e) {
            System.err.println("FAIL " + url + ": " + oneLine(describe(e)));
            return 1;
        }
    }

    private static OfficeToPdf.Result officeToPdf(Path in, Path target, OfficeToPdf.Options office) throws IOException {
        PoiXml.raiseProcessLimits();
        return OfficeToPdf.convert(in, target, office);
    }

    // A spare core loads the converter's classes on a tiny document while the first real one is opened; a deck only
    // warms up the fonts and PDF writing, so it does not compete for the jar with the POI classes the deck needs
    private static void warmUp(List<Path> inputs) {
        List<OfficeToPdf.Format> formats = new ArrayList<>();
        boolean deck = false;
        for (Path in : inputs) {
            try {
                OfficeToPdf.Format f = isOffice(in) ? OfficeToPdf.Format.of(in) : null;
                boolean slides = f == OfficeToPdf.Format.PPTX || f == OfficeToPdf.Format.PPT;
                deck |= slides;
                if (f != null && !slides && !formats.contains(f)) {
                    formats.add(f);
                }
            } catch (IllegalArgumentException ignored) {
                // legacy files fail on their own turn
            }
        }
        if (formats.isEmpty() && !deck || Runtime.getRuntime().availableProcessors() < 2) {
            return;
        }
        PoiXml.raiseProcessLimits();
        Runnable work = formats.isEmpty() ? OfficeToPdf::warmUpFoundation
                : () -> OfficeToPdf.warmUp(formats.toArray(OfficeToPdf.Format[]::new));
        Thread t = new Thread(work, "office-warm-up");
        t.setDaemon(true);
        t.start();
    }

    private static boolean isOffice(Path file) {
        return file.getFileName() != null && OfficeToPdf.Format.recognises(file);
    }

    private static boolean page(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".xhtml");
    }

    static boolean officeOrPage(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        return isOffice(file) || name.endsWith(".zip") || name.endsWith(".epub") || name.endsWith(".pdb");
    }

    private static boolean folderDocument(Path file, boolean packages) {
        return switch (extension(file)) {
            case "zip" -> packages;
            case "pdb" -> palmBook(file);
            default -> officeOrPage(file);
        };
    }

    private static boolean palmBook(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(68);
            String type = head.length < 68 ? "" : new String(head, 60, 8, StandardCharsets.ISO_8859_1);
            return type.equals("BOOKMOBI") || type.equals("TEXtREAd") || type.equals("zTXTGPlm");
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean plainSource(Path file) {
        return TextFormats.kind(file) != null || page(file) || "json".equals(extension(file));
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    private static void usage() {
        System.out.println(
                "Usage: office-convert <in.pdf|dir>... [-o out.docx|dir] [--format ext] [--sheets page|table|single]"
                        + " [--pages a-b] [--no-tables] [--dpi n] [--password p] [--picture-fallback]"
                        + " [--pictures compact|lossless] [--no-chapters] [--device tablet|kindle] [--overwrite] [-q]"
                        + System.lineSeparator()
                        + "       office-convert <in.docx|in.pptx|in.xlsx|in.doc|in.rtf|in.xls|in.ppt|in.odt|in.ods|in.odp|in.txt|in.csv|in.html|in.json|dir>..."
                        + " [-o out.pdf|dir] [--format pdf] [--password p]"
                        + " [--max-pages n (default 10000, 0 = all)] [--timeout s (default 300, 0 = none)]"
                        + " [--fonts dir]... [--font-map Family=Installed]... [--font-width Family=scale]..."
                        + " [--no-system-fonts] [--allow-packages] [--allow-local-files] [--toc] [--page-numbers] [--overwrite] [-q]"
                        + System.lineSeparator()
                        + "       office-convert <http(s)://url> -o out.pdf --allow-network [--follow-redirects] [--allow-cookies]"
                        + " [--allow-private-addresses] [--timeout s] [--overwrite] [-q]; an HTML file with"
                        + " --allow-network --fetch-linked draws http(s) pictures, style sheets and fonts it links"
                        + System.lineSeparator()
                        + "       office-convert <in.pdf|dir>... --pdfa 1a|1b|2a|2b|2u|3a|3b|3u|4 [-o out.pdf|dir] [--password p]"
                        + " [--timeout s] [--fonts dir]... [--font-map Family=Installed]... [--no-system-fonts] [--overwrite] [-q]"
                        + System.lineSeparator()
                        + "       office-convert split-photos <scan.png|scan.pdf>... [-o dir] (finds the photos on a flatbed scan;"
                        + " office-convert split-photos --help lists its options)"
                        + System.lineSeparator()
                        + "       office-convert <in.pdf|dir>... --line-art sauvola|otsu|fixed|edges [-o out.pdf|dir] [--threshold 0-100]"
                        + " [--edge-level 1|2|3] [--window px] [--sauvola-k 0-1] [--codec smallest|jbig2|g4|flate] [--ink-colour]"
                        + " [--password p] [--overwrite] [-q]"
                        + System.lineSeparator()
                        + "       office-convert <in.pdf|dir>... --optimize [--effort fast|default|max] [--keep-private-data]"
                        + " [-o out.pdf|dir] [--password p] [--timeout s] [--overwrite] [-q]"
                        + System.lineSeparator()
                        + "Word, PowerPoint and Excel files (.docx .docm .dotx .dotm .pptx .pptm .ppsx .ppsm .potx .potm"
                        + " .xlsx .xlsm .xltx .xltm .xlsb, binary .doc .dot .xls .xlt .ppt .pps .pot back to Word 6.0 and"
                        + " Excel 2.0, and Word 2003 XML, XML Spreadsheet 2003 and Flat OPC .xml), Visio drawings (.vsdx"
                        + " .vsdm .vssx .vstx), RTF (.rtf), OpenDocument files (.odt"
                        + " .ott .fodt .odm .ods .ots .fods .odp .otp .fodp .odg .otg .fodg), OpenOffice.org 1.x files (.sxw"
                        + " .stw .sxc .stc .sxi .sti .sxd .std), SYLK, DIF and dBASE tables (.slk .dif .dbf), Lotus 1-2-3"
                        + " worksheets (.wk1 .wks .wk3 .wk4 .123), Apple iWork"
                        + " files, Publisher publications and Visio 2003-2010 drawings from their preview (.pages .numbers"
                        + " .key .pub .vsd .vss .vst .vsw), plain text (.txt .text .log .asc) and comma or"
                        + " tab separated tables (.csv .tsv .tab), HTML (.html .htm .xhtml) and the JSON this tool writes (.json) convert to PDF; --password opens a protected one. A folder converts its PDFs, Office files and eBooks,"
                        + " and its .zip files with --allow-packages; --format pdf takes all but its PDFs, and its text, HTML and JSON files too. Nothing a document"
                        + " links to is fetched and no macro, field or formula is run. A .zip of an HTML page converts only"
                        + " with --allow-packages; --allow-local-files lets an HTML page draw the pictures and style sheets"
                        + " in its own folder (never outside it). eBooks use only what they hold; an EPUB inside another .zip, or"
                        + " a FictionBook inside a .zip not named .fbz or .fb2.zip, needs --allow-packages. --toc adds a table of"
                        + " contents with page numbers and links after the document and --page-numbers numbers the pages at"
                        + " their foot; every font the PDF uses is embedded."
                        + System.lineSeparator()
                        + "A URL is fetched, with the pictures, style sheets and fonts it links, only with --allow-network:"
                        + " http and https only, never a loopback, private, link-local or reserved address unless"
                        + " --allow-private-addresses lets intranet hosts through (link-local and metadata addresses stay"
                        + " refused); redirects are followed only with --follow-redirects (5 hops, each checked) and"
                        + " cookies kept only with --allow-cookies. Fetching stops after 60 s, 64 MB or 300 requests."
                        + System.lineSeparator()
                        + "The output's extension picks the format: .docx, .odt, .fodt, .xml (flat ODT), .rtf, .doc (RTF content),"
                        + " .txt, .html (one file, pictures inline), .json (the document model), .md, .epub, .azw3, .pptx, .odp, .ppt, .xlsx or .ods; --format names it for a directory of outputs."
                        + System.lineSeparator()
                        + "--pdfa makes an archival PDF/A copy of each PDF (in.pdfa.pdf unless -o names it): fonts are embedded,"
                        + " scripts and actions removed, colours given an sRGB output intent; 1a and 1b draw transparency as pictures;"
                        + " the a levels need a tagged PDF."
                        + System.lineSeparator()
                        + "--line-art makes every picture 1-bit black and white (in.lineart.pdf unless -o names it). sauvola, the"
                        + " choice for scans, picks a threshold for each neighbourhood (--window, default from the picture size;"
                        + " --sauvola-k, default 0.5, higher keeps less ink); otsu picks one for the whole picture; fixed uses --threshold percent of"
                        + " white (default 50); edges draws outlines like ImageMagick's -edge (--edge-level, default 1), as"
                        + " Stirling-PDF's compress tool does, and cuts them at --threshold (higher keeps more ink). --codec picks the compression, smallest by default; --ink-colour keeps"
                        + " a coloured ink instead of black."
                        + System.lineSeparator()
                        + "--optimize tries to shrink each PDF without changing how any page draws (in.optimized.pdf unless"
                        + " -o names it), and repairs a damaged one on the way: unused objects and resources are dropped,"
                        + " duplicates merged, streams recompressed and objects packed into object streams; editing"
                        + " applications' private data goes too unless --keep-private-data. An output that would not be"
                        + " smaller is normally a copy of the input; repaired PDFs may be larger. A copied input retains its private data."
                        + " Supported encrypted PDFs keep their encryption and permissions; use --password when required."
                        + System.lineSeparator()
                        + "--fonts adds a folder of .ttf, .ttc, .otf or .otc fonts (searched to a depth of 8, links not"
                        + " followed); --font-map draws a family with an installed one (Aptos=Inter); --font-width scales"
                        + " a substituted family's widths (0.5 to 2); --no-system-fonts uses only the given fonts and the"
                        + " bundled Liberation Sans. Fonts whose licence forbids embedding are not used."
                        + System.lineSeparator()
                        + "--pictures lossless keeps every pixel without JPEG compression; compact, the default, is smaller."
                        + System.lineSeparator()
                        + "An .epub or .azw3 book starts a chapter at each top-level heading unless --no-chapters is given;"
                        + " --device kindle sizes its text relative to the reader's own font setting, tablet (the default)"
                        + " keeps the PDF's sizes."
                        + System.lineSeparator()
                        + "An output that exists already is left alone and its input skipped unless --overwrite is given."
                        + " An input is never overwritten: an output that would take an input's name keeps that input's"
                        + " extension instead (b.docx.pdf), and nothing written in a run is converted again by it."
                        + System.lineSeparator()
                        + "Exit status: 0 all converted, 1 some failed or were skipped or a folder held nothing to convert,"
                        + " 2 a mistake in the arguments.");
    }
}
