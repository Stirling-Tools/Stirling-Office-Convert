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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import stirling.software.officeconvert.PdfToDocx;
import stirling.software.officeconvert.PdfToOdp;
import stirling.software.officeconvert.PdfToOdt;
import stirling.software.officeconvert.PdfToPptx;
import stirling.software.officeconvert.PdfToRtf;
import stirling.software.officeconvert.PdfToText;
import stirling.software.officeconvert.PdfToXlsx;
import stirling.software.officeconvert.Pictures;
import stirling.software.officeconvert.legacy.PdfToPpt;
import stirling.software.officeconvert.pdfa.PdfALevel;
import stirling.software.officeconvert.pdfa.PdfToPdfA;
import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.crypt.Passwords;
import stirling.software.officeconvert.topdf.font.FontSet;
import stirling.software.officeconvert.topdf.io.PoiXml;
import stirling.software.officeconvert.topdf.text.TextFormats;

public final class Main {

    private static final Set<String> FORMATS = Set.of("docx", "odt", "fodt", "xml", "rtf", "doc", "txt", "pptx",
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
        List<Path> inputs = new ArrayList<>();
        Path output = null;
        int first = 0;
        int last = 0;
        boolean tables = true;
        boolean pictureFallback = false;
        float dpi = 150f;
        String password = null;
        boolean quiet = false;
        String format = "docx";
        boolean formatGiven = false;
        boolean pagesGiven = false;
        OfficeToPdf.Options office = OfficeToPdf.Options.defaults();
        List<Path> fontDirs = new ArrayList<>();
        FontSet.Builder fontSet = FontSet.builder();
        PdfToXlsx.Sheets sheets = PdfToXlsx.Sheets.PAGE;
        Pictures pictures = Pictures.COMPACT;
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
                    case "-q", "--quiet" -> quiet = true;
                    case "-h", "--help" -> {
                        usage();
                        return 0;
                    }
                    default -> {
                        if (a.startsWith("-") && a.length() > 1) {
                            throw new Usage("unknown option " + a);
                        }
                        inputs.add(path(a));
                    }
                }
            }
            if (inputs.isEmpty()) {
                throw new Usage("no PDF or Office document given");
            }
            fonts = fontSet.directories(fontDirs).build();
            office = office.fonts(fonts).password(password);
            boolean anyOffice = false;
            boolean anyPdf = false;
            for (Path in : inputs) {
                if (Files.isDirectory(in)) {
                    anyOffice |= formatGiven && "pdf".equals(format);
                    anyPdf |= formatGiven && !"pdf".equals(format);
                    continue;
                }
                boolean officeInput = isOffice(in);
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
                boolean officeInput = isOffice(inputs.get(0));
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
        for (Path in : inputs) {
            if (Files.isDirectory(in)) {
                try (Stream<Path> s = Files.list(in)) {
                    s.filter(p -> Files.isRegularFile(p) && !lockFile(p) && (officeFolder && isOffice(p)
                            && (textFolder || TextFormats.kind(p) == null)
                            || pdfFolder && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")
                            && !(archive && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdfa.pdf"))))
                            .sorted().forEach(pdfs::add);
                } catch (IOException e) {
                    System.err.println("office-convert: cannot list " + in + ": " + e.getMessage());
                    return 1;
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
        int failures = 0;
        List<Path> targets = targets(pdfs, output, pdfa != null ? "pdfa.pdf" : format,
                inputs.stream().anyMatch(Files::isDirectory));
        PdfToPdfA.Options archival = pdfa == null ? null : PdfToPdfA.Options.defaults().level(pdfa).password(password)
                .timeout(office.timeout()).fonts(fonts);
        for (int k = 0; k < pdfs.size(); k++) {
            Path pdf = pdfs.get(k);
            boolean officeInput = isOffice(pdf);
            Path target = targets.get(k);
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
                    result = officeToPdf(pdf, target, office);
                } else {
                    convert(pdf, target, options, slides, books);
                }
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

    // Inputs that would share an output name keep their own extension in it instead of overwriting each other
    static List<Path> targets(List<Path> inputs, Path output, String format, boolean folder) {
        List<Path> out = new ArrayList<>();
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (Path in : inputs) {
            Path t = target(in, output, folder || inputs.size() > 1, isOffice(in) ? "pdf" : format);
            out.add(t);
            counts.merge(key(t), 1, Integer::sum);
        }
        java.util.Set<String> used = new java.util.HashSet<>();
        for (int i = 0; i < out.size(); i++) {
            Path t = out.get(i);
            if (counts.get(key(t)) > 1) {
                String ext = isOffice(inputs.get(i)) ? "pdf" : format;
                t = t.resolveSibling(inputs.get(i).getFileName().toString() + "." + ext);
            }
            Path unique = t;
            for (int n = 2; !used.add(key(unique)) && n < 10_000; n++) {
                String name = t.getFileName().toString();
                int dot = name.lastIndexOf('.');
                unique = t.resolveSibling(name.substring(0, dot) + " (" + n + ")" + name.substring(dot));
            }
            out.set(i, unique);
        }
        return out;
    }

    private static String key(Path p) {
        return p.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
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
            PdfToXlsx.Options books) throws IOException {
        switch (extension(target)) {
            case "odt" -> PdfToOdt.convert(pdf, target, options);
            case "fodt", "xml" -> PdfToOdt.convertFlat(pdf, target, options);
            case "rtf", "doc" -> PdfToRtf.convert(pdf, target, options);
            case "txt" -> PdfToText.convert(pdf, target, options);
            case "pptx" -> PdfToPptx.convert(pdf, target, slides);
            case "odp" -> PdfToOdp.convert(pdf, target, slides);
            case "ppt" -> PdfToPpt.convert(pdf, target, slides);
            case "xlsx", "ods" -> PdfToXlsx.convert(pdf, target, books);
            case "docx" -> PdfToDocx.convert(pdf, target, options);
            default -> throw new IOException("unknown output format ." + extension(target));
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
                    + "': use docx, odt, fodt, xml, rtf, doc, txt, pptx, odp, ppt, xlsx or ods (pdf for Office input)");
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

    private static Pictures pictures(String s) throws Usage {
        try {
            return Pictures.valueOf(s.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new Usage("--pictures is compact or lossless, not '" + s + "'");
        }
    }

    private static Path target(Path pdf, Path output, boolean many, String format) {
        String base = pdf.getFileName().toString();
        String name = ("pdf".equals(format) ? base.replaceFirst("\\.[^.]+$", "") : base.replaceFirst("(?i)\\.pdf$", ""))
                + "." + format;
        if (output == null) {
            return pdf.resolveSibling(name);
        }
        if (many || Files.isDirectory(output)) {
            return output.resolve(name);
        }
        return output;
    }

    private static void usage() {
        System.out.println(
                "Usage: office-convert <in.pdf|dir>... [-o out.docx|dir] [--format ext] [--sheets page|table|single]"
                        + " [--pages a-b] [--no-tables] [--dpi n] [--password p] [--picture-fallback]"
                        + " [--pictures compact|lossless] [-q]"
                        + System.lineSeparator()
                        + "       office-convert <in.docx|in.pptx|in.xlsx|in.doc|in.rtf|in.xls|in.ppt|in.odt|in.ods|in.odp|in.txt|in.csv|dir>..."
                        + " [-o out.pdf|dir] [--format pdf] [--password p]"
                        + " [--max-pages n (default 10000, 0 = all)] [--timeout s (default 300, 0 = none)]"
                        + " [--fonts dir]... [--font-map Family=Installed]... [--font-width Family=scale]..."
                        + " [--no-system-fonts] [-q]"
                        + System.lineSeparator()
                        + "       office-convert <in.pdf|dir>... --pdfa 1a|1b|2a|2b|2u|3a|3b|3u [-o out.pdf|dir] [--password p]"
                        + " [--timeout s] [--fonts dir]... [--font-map Family=Installed]... [--no-system-fonts] [-q]"
                        + System.lineSeparator()
                        + "Word, PowerPoint and Excel files (.docx .docm .dotx .dotm .pptx .pptm .ppsx .ppsm .potx .potm"
                        + " .xlsx .xlsm .xltx .xltm and 97-2003 .doc .dot .xls .xlt .ppt .pps .pot), RTF (.rtf), OpenDocument"
                        + " files (.odt .ott .fodt .ods .ots .fods .odp .otp .fodp), plain text (.txt .text .log .asc) and comma or"
                        + " tab separated tables (.csv .tsv .tab) convert to PDF. A folder converts its PDFs and Office files;"
                        + " --format pdf takes only its Office and text files. Nothing a document"
                        + " links to is fetched and no macro, field or formula is run."
                        + System.lineSeparator()
                        + "The output's extension picks the format: .docx, .odt, .fodt, .xml (flat ODT), .rtf, .doc (RTF content),"
                        + " .txt, .pptx, .odp, .ppt, .xlsx or .ods; --format names it for a directory of outputs."
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
                        + "Exit status: 0 all converted, 1 some failed, 2 a mistake in the arguments.");
    }
}
