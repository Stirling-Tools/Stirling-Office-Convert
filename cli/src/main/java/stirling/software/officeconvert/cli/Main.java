package stirling.software.officeconvert.cli;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import stirling.software.officeconvert.EbookOptions;
import stirling.software.officeconvert.PdfToAzw3;
import stirling.software.officeconvert.PdfToDocx;
import stirling.software.officeconvert.PdfToEpub;
import stirling.software.officeconvert.PdfToHtml;
import stirling.software.officeconvert.PdfToJson;
import stirling.software.officeconvert.PdfToOdp;
import stirling.software.officeconvert.PdfToOdt;
import stirling.software.officeconvert.PdfToPptx;
import stirling.software.officeconvert.PdfToRtf;
import stirling.software.officeconvert.PdfToMarkdown;
import stirling.software.officeconvert.PdfToText;
import stirling.software.officeconvert.PdfToXlsx;
import stirling.software.officeconvert.Pictures;
import stirling.software.officeconvert.legacy.PdfToPpt;
import stirling.software.officeconvert.pdfa.PdfALevel;
import stirling.software.officeconvert.pdfa.PdfToPdfA;
import stirling.software.officeconvert.topdf.HtmlAssets;
import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.UnsupportedFormats;
import stirling.software.officeconvert.topdf.WebAssets;
import stirling.software.officeconvert.topdf.crypt.Passwords;
import stirling.software.officeconvert.topdf.font.FontSet;
import stirling.software.officeconvert.topdf.io.PoiXml;
import stirling.software.officeconvert.topdf.text.TextFormats;

public final class Main {

    private static final Set<String> FORMATS = Set.of("docx", "odt", "fodt", "xml", "rtf", "doc", "txt", "html", "json", "md", "epub", "azw3", "pptx",
            "odp", "ppt", "xlsx", "ods", "pdf");

    private Main() {}

    private static final class Usage extends Exception {
        Usage(String message) {
            super(message, null, false, false);
        }
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length > 0 && SplitPhotos.COMMAND.equals(args[0])) {
            return SplitPhotos.run(Arrays.copyOfRange(args, 1, args.length));
        }
        List<Path> inputs = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        WebAssets.Access web = WebAssets.Access.none();
        Path output = null;
        int first = 0;
        int last = 0;
        boolean tables = true;
        boolean pictureFallback = false;
        float dpi = 150f;
        String password = null;
        boolean quiet = false;
        boolean overwrite = false;
        boolean localFiles = false;
        String format = "docx";
        boolean formatGiven = false;
        boolean pagesGiven = false;
        OfficeToPdf.Options office = OfficeToPdf.Options.defaults();
        List<Path> fontDirs = new ArrayList<>();
        FontSet.Builder fontSet = FontSet.builder();
        PdfToXlsx.Sheets sheets = PdfToXlsx.Sheets.PAGE;
        Pictures pictures = Pictures.COMPACT;
        EbookOptions ebook = EbookOptions.defaults();
        PdfToDocx.Options options;
        PdfToPptx.Options slides;
        PdfToXlsx.Options books;
        PdfALevel pdfa = null;
        FontSet fonts;
        try {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "-o", "--output" -> output = path(value(args, ++i, a));
                    case "--pages" -> {
                        int[] range = pages(value(args, ++i, a));
                        first = range[0];
                        last = range[1];
                        pagesGiven = true;
                    }
                    case "--no-tables" -> tables = false;
                    case "--dpi" -> dpi = number(value(args, ++i, a), a);
                    case "--password" -> password = value(args, ++i, a);
                    case "--picture-fallback" -> pictureFallback = true;
                    case "--format" -> {
                        format = known(value(args, ++i, a).toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""));
                        formatGiven = true;
                    }
                    case "--timeout" -> office = office.timeout(Duration.ofMillis((long) Math.ceil(seconds(value(args, ++i, a),
                            a) * 1000)));
                    case "--max-pages" -> office = office.maxPages(count(value(args, ++i, a), a));
                    case "--fonts" -> fontDirs.add(folder(value(args, ++i, a), a));
                    case "--font-map" -> {
                        String[] pair = pair(value(args, ++i, a), a);
                        fontSet.substitute(pair[0], pair[1]);
                    }
                    case "--font-width" -> {
                        String[] pair = pair(value(args, ++i, a), a);
                        fontSet.widthScale(pair[0], number(pair[1], a));
                    }
                    case "--no-system-fonts" -> fontSet.systemFonts(false);
                    case "--sheets" -> sheets = sheets(value(args, ++i, a));
                    case "--pdfa" -> pdfa = level(value(args, ++i, a));
                    case "--pictures" -> pictures = pictures(value(args, ++i, a));
                    case "--no-chapters" -> ebook = ebook.chapters(false);
                    case "--device" -> ebook = ebook.device(device(value(args, ++i, a)));
                    case "-q", "--quiet" -> quiet = true;
                    case "--overwrite" -> overwrite = true;
                    case "--allow-packages" -> office = office.allowPackages(true);
                    case "--toc" -> office = office.tableOfContents(true);
                    case "--page-numbers" -> office = office.pageNumbers(true);
                    case "--allow-local-files" -> localFiles = true;
                    case "--allow-network" -> web = web.network(true);
                    case "--follow-redirects" -> web = web.redirects(true);
                    case "--allow-cookies" -> web = web.cookies(true);
                    case "--allow-private-addresses" -> web = web.privateAddresses(true);
                    case "--fetch-linked" -> web = web.linkedFromFiles(true);
                    case "-h", "--help" -> {
                        usage();
                        return 0;
                    }
                    default -> {
                        if (a.startsWith("-") && a.length() > 1) {
                            throw new Usage("unknown option " + a);
                        }
                        if (a.regionMatches(true, 0, "http://", 0, 7) || a.regionMatches(true, 0, "https://", 0, 8)) {
                            urls.add(a);
                        } else {
                            inputs.add(path(a));
                        }
                    }
                }
            }
            if (inputs.isEmpty() && urls.isEmpty()) {
                throw new Usage("no PDF or Office document given");
            }
            fonts = fontSet.directories(fontDirs).build();
            office = office.fonts(fonts).password(password);
            if (!urls.isEmpty()) {
                if (urls.size() > 1 || !inputs.isEmpty() || output == null || !"pdf".equals(extension(output))
                        || pdfa != null || pagesGiven || formatGiven && !"pdf".equals(format)) {
                    throw new Usage("a URL converts on its own to the PDF named by -o out.pdf");
                }
                if (!web.network()) {
                    throw new Usage("a URL is fetched only with --allow-network");
                }
                return url(urls.get(0), output, office, web, overwrite, quiet);
            }
            if (web.linkedFromFiles() && !web.network()) {
                throw new Usage("--fetch-linked fetches only with --allow-network");
            }
            boolean anyOffice = false;
            boolean anyPdf = false;
            for (Path in : inputs) {
                if (Files.isDirectory(in)) {
                    anyOffice |= formatGiven && "pdf".equals(format);
                    anyPdf |= formatGiven && !"pdf".equals(format);
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
            if (pdfa != null && (anyOffice || formatGiven && !"pdf".equals(format) || pagesGiven)) {
                throw new Usage("--pdfa converts whole PDFs to PDF/A; give it PDF input without --pages or --format");
            }
            if (anyOffice && pagesGiven) {
                throw new Usage("--pages is for PDF input; Office documents convert whole (--max-pages n limits them)");
            }
            if (anyOffice && formatGiven && !"pdf".equals(format)) {
                throw new Usage("Office documents convert to PDF only; use --format pdf or leave it out");
            }
            if (anyPdf && "pdf".equals(format) && pdfa == null) {
                throw new Usage("PDF input converts to an Office format, not pdf");
            }
            options = new PdfToDocx.Options(first, last, tables, dpi, password, pictureFallback, pictures);
            slides = new PdfToPptx.Options(first, last, tables, dpi, password, pictureFallback, pictures);
            books = PdfToXlsx.Options.defaults().withPages(first, last).withTables(tables).withPassword(password)
                    .withSheets(sheets).withTextFallback(pictureFallback);
            if (pdfa != null && output != null && !Files.isDirectory(output) && inputs.size() == 1
                    && !Files.isDirectory(inputs.get(0)) && !"pdf".equals(extension(output))) {
                throw new Usage("--pdfa writes a PDF; name the output .pdf");
            }
            if (pdfa == null && output != null && !Files.isDirectory(output) && inputs.size() == 1
                    && !Files.isDirectory(inputs.get(0))) {
                String ext = known(extension(output));
                boolean officeInput = officeOrPage(inputs.get(0));
                if (officeInput != "pdf".equals(ext)) {
                    throw new Usage(officeInput ? "an Office document converts to PDF; name the output .pdf"
                            : "PDF input converts to an Office format; the output cannot be .pdf");
                }
            }
        } catch (Usage | IllegalArgumentException e) {
            System.err.println("office-convert: " + e.getMessage());
            System.err.println("Run office-convert --help for the options.");
            return 2;
        }
        List<Path> pdfs = new ArrayList<>();
        boolean archive = pdfa != null;
        boolean officeFolder = !archive && (!formatGiven || "pdf".equals(format));
        boolean pdfFolder = archive || !formatGiven || !"pdf".equals(format);
        boolean textFolder = formatGiven;
        int failures = 0;
        for (Path in : inputs) {
            if (Files.isDirectory(in)) {
                int before = pdfs.size();
                try (Stream<Path> s = Files.list(in)) {
                    s.filter(p -> Files.isRegularFile(p) && !lockFile(p) && (officeFolder && isOffice(p)
                            && (textFolder || !plainSource(p))
                            || pdfFolder && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")
                            && !(archive && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdfa.pdf"))))
                            .sorted().forEach(pdfs::add);
                } catch (IOException e) {
                    System.err.println("office-convert: cannot list " + in + ": " + e.getMessage());
                    return 1;
                }
                if (pdfs.size() == before) {
                    failures++;
                    System.err.println("office-convert: nothing to convert in " + in);
                }
            } else {
                pdfs.add(in);
            }
        }
        if (!quiet) {
            for (String problem : fonts.problems()) {
                System.err.println("warning: fonts: " + oneLine(problem));
            }
        }
        warmUp(pdfs);
        List<Path> targets = Targets.of(pdfs, output, pdfa != null ? "pdfa.pdf" : format,
                inputs.stream().anyMatch(Files::isDirectory));
        Guard guard = new Guard(pdfs, overwrite);
        PdfToPdfA.Options archival = pdfa == null ? null : PdfToPdfA.Options.defaults().level(pdfa).password(password)
                .timeout(office.timeout()).maxPages(office.maxPages()).fonts(fonts);
        for (int k = 0; k < pdfs.size(); k++) {
            Path pdf = pdfs.get(k);
            boolean officeInput = officeOrPage(pdf);
            Path target = targets.get(k);
            String refusal = guard.refusal(pdf, target);
            if (refusal != null) {
                failures++;
                System.err.println("SKIP " + pdf + ": " + oneLine(refusal));
                continue;
            }
            resetPeaks();
            long start = System.nanoTime();
            try {
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                OfficeToPdf.Result result = null;
                List<String> notes = List.of();
                if (archival != null) {
                    notes = PdfToPdfA.convert(pdf, target, archival).warnings();
                } else if (officeInput) {
                    result = officeToPdf(pdf, target, page(pdf) ? office.htmlAssets(assets(pdf, localFiles, web))
                            : office);
                } else {
                    convert(pdf, target, options, slides, books, ebook);
                }
                guard.wrote(target);
                long ms = (System.nanoTime() - start) / 1_000_000;
                if (!quiet) {
                    Runtime rt = Runtime.getRuntime();
                    long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                    System.out.printf("OK %s -> %s (%d ms, heap %d MB, peak %d MB)%n", pdf, target, ms, usedMb,
                            peakMb());
                    if (result != null) {
                        for (String w : result.warnings()) {
                            System.err.println("warning: " + pdf.getFileName() + ": " + oneLine(w));
                        }
                    }
                    for (String w : notes) {
                        System.err.println("note: " + pdf.getFileName() + ": " + oneLine(w));
                    }
                }
            } catch (IOException e) {
                failures++;
                System.err.println("FAIL " + pdf + ": " + oneLine(describe(e)));
            } catch (RuntimeException | OutOfMemoryError e) {
                failures++;
                System.err.println("FAIL " + pdf + ": unexpected " + e);
                e.printStackTrace();
            }
        }
        return failures == 0 ? 0 : 1;
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

    private static void convert(Path pdf, Path target, PdfToDocx.Options options, PdfToPptx.Options slides,
            PdfToXlsx.Options books, EbookOptions ebook) throws IOException {
        switch (extension(target)) {
            case "odt" -> PdfToOdt.convert(pdf, target, options);
            case "fodt", "xml" -> PdfToOdt.convertFlat(pdf, target, options);
            case "rtf", "doc" -> PdfToRtf.convert(pdf, target, options);
            case "txt" -> PdfToText.convert(pdf, target, options);
            case "html" -> PdfToHtml.convert(pdf, target, options);
            case "json" -> PdfToJson.convert(pdf, target, options);
            case "md" -> PdfToMarkdown.convert(pdf, target, options);
            case "epub" -> PdfToEpub.convert(pdf, target, options, ebook);
            case "azw3" -> PdfToAzw3.convert(pdf, target, options, ebook);
            case "pptx" -> PdfToPptx.convert(pdf, target, slides);
            case "odp" -> PdfToOdp.convert(pdf, target, slides);
            case "ppt" -> PdfToPpt.convert(pdf, target, slides);
            case "xlsx", "ods" -> PdfToXlsx.convert(pdf, target, books);
            case "docx" -> PdfToDocx.convert(pdf, target, options);
            default -> throw new IOException("unknown output format ." + extension(target));
        }
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

    private static boolean plainSource(Path file) {
        if (TextFormats.kind(file) != null) {
            return true;
        }
        OfficeToPdf.Format format = OfficeToPdf.Format.of(file);
        return format == OfficeToPdf.Format.HTML || format == OfficeToPdf.Format.JSON;
    }

    private static float seconds(String s, String option) throws Usage {
        float v = number(s, option);
        if (!(v >= 0 && v <= 86_400)) {
            throw new Usage(option + " needs seconds from 0 (no limit) to 86400, not '" + s + "'");
        }
        return v;
    }

    private static int count(String s, String option) throws Usage {
        try {
            int v = Integer.parseInt(s.strip());
            if (v < 0) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new Usage(option + " needs a whole number of 0 or more, not '" + s + "'");
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    private static String known(String format) throws Usage {
        if (!FORMATS.contains(format)) {
            throw new Usage("unknown output format '" + format
                    + "': use docx, odt, fodt, xml, rtf, doc, txt, html, json, md, epub, azw3, pptx, odp, ppt, xlsx or ods (pdf for Office input)");
        }
        return format;
    }

    private static String value(String[] args, int i, String option) throws Usage {
        if (i >= args.length) {
            throw new Usage(option + " needs a value");
        }
        return args[i];
    }

    private static String[] pair(String s, String option) throws Usage {
        int eq = s.lastIndexOf('=');
        if (eq <= 0 || eq == s.length() - 1 || s.substring(0, eq).isBlank() || s.substring(eq + 1).isBlank()) {
            throw new Usage(option + " needs Family=value, was " + s);
        }
        return new String[] {s.substring(0, eq).strip(), s.substring(eq + 1).strip()};
    }

    private static Path folder(String s, String option) throws Usage {
        Path p = path(s);
        if (!Files.isDirectory(p)) {
            throw new Usage(option + " needs a folder of fonts; " + s + " is not a folder");
        }
        return p;
    }

    private static Path path(String s) throws Usage {
        try {
            return Path.of(s);
        } catch (InvalidPathException e) {
            throw new Usage("not a valid path: " + s);
        }
    }

    private static float number(String s, String option) throws Usage {
        try {
            return Float.parseFloat(s.strip());
        } catch (NumberFormatException e) {
            throw new Usage(option + " needs a number, not '" + s + "'");
        }
    }

    private static int[] pages(String s) throws Usage {
        if (!s.strip().matches("\\d{0,9}\\s*(-\\s*\\d{0,9})?") || s.isBlank()) {
            throw new Usage("--pages needs a range like 2-5, 3, 2- or -5, not '" + s + "'");
        }
        String[] ab = s.strip().split("-", -1);
        int first = ab[0].isBlank() ? 0 : Integer.parseInt(ab[0].strip());
        int last = ab.length < 2 ? first : ab[1].isBlank() ? 0 : Integer.parseInt(ab[1].strip());
        return new int[] {first, last};
    }

    private static PdfToXlsx.Sheets sheets(String s) throws Usage {
        try {
            return PdfToXlsx.Sheets.valueOf(s.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new Usage("--sheets is page, table or single, not '" + s + "'");
        }
    }

    private static PdfALevel level(String s) throws Usage {
        try {
            return PdfALevel.parse(s);
        } catch (IllegalArgumentException e) {
            throw new Usage("--pdfa: " + e.getMessage());
        }
    }

    private static EbookOptions.Device device(String s) throws Usage {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "tablet" -> EbookOptions.Device.TABLET;
            case "kindle" -> EbookOptions.Device.KINDLE;
            default -> throw new Usage("--device is tablet or kindle, not '" + s + "'");
        };
    }

    private static Pictures pictures(String s) throws Usage {
        try {
            return Pictures.valueOf(s.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new Usage("--pictures is compact or lossless, not '" + s + "'");
        }
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
                        + "       office-convert <in.pdf|dir>... --pdfa 1a|1b|2a|2b|2u|3a|3b|3u [-o out.pdf|dir] [--password p]"
                        + " [--timeout s] [--fonts dir]... [--font-map Family=Installed]... [--no-system-fonts] [--overwrite] [-q]"
                        + System.lineSeparator()
                        + "       office-convert split-photos <scan.png|scan.pdf>... [-o dir] (finds the photos on a flatbed scan;"
                        + " office-convert split-photos --help lists its options)"
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
                        + " tab separated tables (.csv .tsv .tab), HTML (.html .htm .xhtml) and the JSON this tool writes (.json) convert to PDF; --password opens a protected one. A folder converts its PDFs and Office files;"
                        + " --format pdf takes only its Office and text files. Nothing a document"
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
