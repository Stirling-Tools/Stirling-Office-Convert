package stirling.software.officeconvert.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
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

public final class Main {

    private static final Set<String> FORMATS = Set.of("docx", "odt", "fodt", "rtf", "doc", "txt", "pptx", "odp", "ppt", "xlsx",
            "ods");

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
        PdfToXlsx.Sheets sheets = PdfToXlsx.Sheets.PAGE;
        Pictures pictures = Pictures.COMPACT;
        PdfToDocx.Options options;
        PdfToPptx.Options slides;
        PdfToXlsx.Options books;
        try {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "-o", "--output" -> output = path(value(args, ++i, a));
                    case "--pages" -> {
                        int[] range = pages(value(args, ++i, a));
                        first = range[0];
                        last = range[1];
                    }
                    case "--no-tables" -> tables = false;
                    case "--dpi" -> dpi = number(value(args, ++i, a), a);
                    case "--password" -> password = value(args, ++i, a);
                    case "--picture-fallback" -> pictureFallback = true;
                    case "--format" -> format = known(value(args, ++i, a).toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""));
                    case "--sheets" -> sheets = sheets(value(args, ++i, a));
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
                throw new Usage("no PDF given");
            }
            options = new PdfToDocx.Options(first, last, tables, dpi, password, pictureFallback, pictures);
            slides = new PdfToPptx.Options(first, last, tables, dpi, password, pictureFallback, pictures);
            books = PdfToXlsx.Options.defaults().withPages(first, last).withTables(tables).withPassword(password)
                    .withSheets(sheets).withTextFallback(pictureFallback);
            if (output != null && !Files.isDirectory(output) && inputs.size() == 1 && !Files.isDirectory(inputs.get(0))) {
                known(extension(output));
            }
        } catch (Usage | IllegalArgumentException e) {
            System.err.println("office-convert: " + e.getMessage());
            System.err.println("Run office-convert --help for the options.");
            return 2;
        }
        List<Path> pdfs = new ArrayList<>();
        for (Path in : inputs) {
            if (Files.isDirectory(in)) {
                try (Stream<Path> s = Files.list(in)) {
                    s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")).sorted().forEach(pdfs::add);
                } catch (IOException e) {
                    System.err.println("office-convert: cannot list " + in + ": " + e.getMessage());
                    return 1;
                }
            } else {
                pdfs.add(in);
            }
        }
        int failures = 0;
        for (Path pdf : pdfs) {
            Path target = target(pdf, output, pdfs.size() > 1, format);
            long start = System.nanoTime();
            try {
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                convert(pdf, target, options, slides, books);
                long ms = (System.nanoTime() - start) / 1_000_000;
                if (!quiet) {
                    Runtime rt = Runtime.getRuntime();
                    long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                    System.out.printf("OK %s -> %s (%d ms, heap %d MB)%n", pdf, target, ms, usedMb);
                }
            } catch (IOException e) {
                failures++;
                System.err.println("FAIL " + pdf + ": " + describe(e));
            } catch (RuntimeException | OutOfMemoryError e) {
                failures++;
                System.err.println("FAIL " + pdf + ": unexpected " + e);
                e.printStackTrace();
            }
        }
        return failures == 0 ? 0 : 1;
    }

    private static String describe(IOException e) {
        if (e instanceof InvalidPasswordException) {
            return "the PDF is password protected; give its password with --password";
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
            case "fodt" -> PdfToOdt.convertFlat(pdf, target, options);
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

    private static String extension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    private static String known(String format) throws Usage {
        if (!FORMATS.contains(format)) {
            throw new Usage("unknown output format '" + format + "': use docx, odt, fodt, rtf, doc, txt, pptx, odp, ppt, xlsx or ods");
        }
        return format;
    }

    private static String value(String[] args, int i, String option) throws Usage {
        if (i >= args.length) {
            throw new Usage(option + " needs a value");
        }
        return args[i];
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

    private static Pictures pictures(String s) throws Usage {
        try {
            return Pictures.valueOf(s.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new Usage("--pictures is compact or lossless, not '" + s + "'");
        }
    }

    private static Path target(Path pdf, Path output, boolean many, String format) {
        String name = pdf.getFileName().toString().replaceFirst("(?i)\\.pdf$", "") + "." + format;
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
                        + "The output's extension picks the format: .docx, .odt, .fodt, .rtf, .doc (RTF content), .txt,"
                        + " .pptx, .odp, .ppt, .xlsx or .ods; --format names it for a directory of outputs."
                        + System.lineSeparator()
                        + "--pictures lossless keeps every pixel without JPEG compression; compact, the default, is smaller."
                        + System.lineSeparator()
                        + "Exit status: 0 all converted, 1 some failed, 2 a mistake in the arguments.");
    }
}
