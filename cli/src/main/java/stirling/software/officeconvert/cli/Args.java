package stirling.software.officeconvert.cli;

import static stirling.software.officeconvert.cli.Usage.number;
import static stirling.software.officeconvert.cli.Usage.path;
import static stirling.software.officeconvert.cli.Usage.value;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import stirling.software.officeconvert.EbookOptions;
import stirling.software.officeconvert.PdfToXlsx;
import stirling.software.officeconvert.Pictures;
import stirling.software.officeconvert.lineart.LineArt;
import stirling.software.officeconvert.optimize.PdfOptimizer;
import stirling.software.officeconvert.pdfa.PdfALevel;
import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.WebAssets;
import stirling.software.officeconvert.topdf.font.FontSet;

record Args(
        List<Path> inputs,
        List<String> urls,
        Path output,
        String format,
        boolean formatGiven,
        int first,
        int last,
        boolean pagesGiven,
        boolean tables,
        boolean pictureFallback,
        float dpi,
        String password,
        Pictures pictures,
        PdfToXlsx.Sheets sheets,
        EbookOptions ebook,
        OfficeToPdf.Options office,
        FontSet fonts,
        WebAssets.Access web,
        boolean localFiles,
        Set<Mode> rewrites,
        PdfALevel pdfaLevel,
        LineArt.Options lineArt,
        boolean lineArtTuned,
        PdfOptimizer.Effort effort,
        boolean keepPrivateData,
        boolean quiet,
        boolean overwrite) {

    boolean asksForPdf() {
        return formatGiven && OutputFormat.PDF.equals(format);
    }

    boolean asksForOfficeFormat() {
        return formatGiven && !OutputFormat.PDF.equals(format);
    }

    static Args parse(String[] args) throws Usage {
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
        Set<Mode> rewrites = EnumSet.noneOf(Mode.class);
        PdfALevel pdfaLevel = null;
        LineArt.Options lineArt = LineArt.Options.defaults();
        boolean lineArtTuned = false;
        PdfOptimizer.Effort effort = null;
        boolean keepPrivateData = false;
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
                    format = OutputFormat.known(value(args, ++i, a).toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""));
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
                case "--pdfa" -> {
                    pdfaLevel = level(value(args, ++i, a));
                    rewrites.add(Mode.PDFA);
                }
                case "--line-art" -> {
                    lineArt = lineArt.method(lineArtMethod(value(args, ++i, a)));
                    rewrites.add(Mode.LINE_ART);
                }
                case "--threshold" -> {
                    lineArt = lineArt.threshold(number(value(args, ++i, a), a));
                    lineArtTuned = true;
                }
                case "--edge-level" -> {
                    lineArt = lineArt.edgeLevel(count(value(args, ++i, a), a));
                    lineArtTuned = true;
                }
                case "--window" -> {
                    lineArt = lineArt.window(count(value(args, ++i, a), a));
                    lineArtTuned = true;
                }
                case "--sauvola-k" -> {
                    lineArt = lineArt.k(number(value(args, ++i, a), a));
                    lineArtTuned = true;
                }
                case "--codec" -> {
                    lineArt = lineArt.codec(codec(value(args, ++i, a)));
                    lineArtTuned = true;
                }
                case "--ink-colour", "--ink-color" -> {
                    lineArt = lineArt.inkColour(true);
                    lineArtTuned = true;
                }
                case "--optimize" -> rewrites.add(Mode.OPTIMIZE);
                case "--effort" -> effort = effort(value(args, ++i, a));
                case "--keep-private-data" -> keepPrivateData = true;
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
                    return null;
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
        FontSet fonts = fontSet.directories(fontDirs).build();
        office = office.fonts(fonts).password(password);
        return new Args(List.copyOf(inputs), List.copyOf(urls), output, format, formatGiven, first, last, pagesGiven, tables,
                pictureFallback, dpi, password, pictures, sheets, ebook, office, fonts, web, localFiles, Set.copyOf(rewrites),
                pdfaLevel, lineArt, lineArtTuned, effort, keepPrivateData, quiet, overwrite);
    }

    static int[] pages(String s) throws Usage {
        if (!s.strip().matches("\\d{0,9}\\s*(-\\s*\\d{0,9})?") || s.isBlank()) {
            throw new Usage("--pages needs a range like 2-5, 3, 2- or -5, not '" + s + "'");
        }
        String[] ab = s.strip().split("-", -1);
        int first = ab[0].isBlank() ? 0 : Integer.parseInt(ab[0].strip());
        int last = ab.length < 2 ? first : ab[1].isBlank() ? 0 : Integer.parseInt(ab[1].strip());
        return new int[] {first, last};
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

    private static LineArt.Method lineArtMethod(String s) throws Usage {
        try {
            return LineArt.Method.valueOf(s.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new Usage("--line-art is sauvola, otsu, fixed or edges, not '" + s + "'");
        }
    }

    private static LineArt.Codec codec(String s) throws Usage {
        return switch (s.strip().toLowerCase(Locale.ROOT)) {
            case "smallest" -> LineArt.Codec.SMALLEST;
            case "jbig2" -> LineArt.Codec.JBIG2;
            case "g4", "ccitt" -> LineArt.Codec.CCITT_G4;
            case "flate" -> LineArt.Codec.FLATE;
            default -> throw new Usage("--codec is smallest, jbig2, g4 or flate, not '" + s + "'");
        };
    }

    private static PdfOptimizer.Effort effort(String s) throws Usage {
        try {
            return PdfOptimizer.Effort.valueOf(s.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new Usage("--effort is fast, default or max, not '" + s + "'");
        }
    }

    private static Pictures pictures(String s) throws Usage {
        try {
            return Pictures.valueOf(s.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new Usage("--pictures is compact or lossless, not '" + s + "'");
        }
    }
}
