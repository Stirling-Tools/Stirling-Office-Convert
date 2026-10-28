package stirling.software.officeconvert.cli;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import stirling.software.officeconvert.photosplit.PhotoSplitter;

final class SplitPhotos {

    static final String COMMAND = "split-photos";

    private SplitPhotos() {}

    private static final class Usage extends Exception {
        Usage(String message) {
            super(message, null, false, false);
        }
    }

    static int run(String[] args) {
        List<Path> inputs = new ArrayList<>();
        Path output = null;
        boolean quiet = false;
        PhotoSplitter.Options options = PhotoSplitter.Options.defaults();
        List<String> bases;
        try {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a.replace('_', '-')) {
                    case "-o", "--output" -> output = path(value(args, ++i, a));
                    case "--angle-threshold" -> options = options.angleThreshold(integer(value(args, ++i, a), a));
                    case "--tolerance" -> options = options.tolerance(integer(value(args, ++i, a), a));
                    case "--min-area" -> options = options.minArea(integer(value(args, ++i, a), a));
                    case "--min-contour-area" -> options = options.minContourArea(integer(value(args, ++i, a), a));
                    case "--border-size" -> options = options.borderSize(integer(value(args, ++i, a), a));
                    case "--dpi" -> options = options.dpi(dpi(value(args, ++i, a), a));
                    case "--method" -> options = options.method(method(value(args, ++i, a)));
                    case "--overwrite" -> options = options.overwrite(true);
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
                throw new Usage("no scan given");
            }
            if (output != null && Files.exists(output) && !Files.isDirectory(output)) {
                throw new Usage("-o names the folder for the photos; " + output + " is a file");
            }
            bases = bases(inputs, output);
            if (options.overwrite()) {
                protectInputs(inputs, bases, output);
            }
        } catch (Usage | IllegalArgumentException e) {
            System.err.println("office-convert " + COMMAND + ": " + e.getMessage());
            System.err.println("Run office-convert " + COMMAND + " --help for the options.");
            return 2;
        }
        int failures = 0;
        for (int i = 0; i < inputs.size(); i++) {
            Path in = inputs.get(i);
            Path dir = folder(in, output);
            try {
                if (Files.isDirectory(in)) {
                    throw new IOException("is a folder; give the scans themselves");
                }
                long start = System.nanoTime();
                List<Path> photos = PhotoSplitter.split(in, dir, bases.get(i), options);
                long ms = (System.nanoTime() - start) / 1_000_000;
                if (photos.isEmpty()) {
                    System.err.println("FAIL " + in + ": no photos found");
                    failures++;
                } else if (!quiet) {
                    System.out.printf("OK %s -> %d photo%s in %s (%d ms)%n", in, photos.size(), photos.size() == 1 ? "" : "s",
                            dir, ms);
                }
            } catch (FileAlreadyExistsException e) {
                System.err.println("SKIP " + in + ": " + e.getFile() + " exists; --overwrite replaces it");
                failures++;
            } catch (NoSuchFileException e) {
                System.err.println("FAIL " + in + ": no such file");
                failures++;
            } catch (IOException | RuntimeException e) {
                System.err.println("FAIL " + in + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                failures++;
            } catch (OutOfMemoryError e) {
                System.err.println("FAIL " + in + ": out of memory (" + e.getMessage() + ")");
                failures++;
            }
        }
        return failures == 0 ? 0 : 1;
    }

    private static String value(String[] args, int i, String option) throws Usage {
        if (i >= args.length) {
            throw new Usage(option + " needs a value");
        }
        return args[i];
    }

    private static Path folder(Path input, Path output) {
        return output != null ? output : input.toAbsolutePath().getParent();
    }

    private static List<String> bases(List<Path> inputs, Path output) {
        List<String> bases = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();
        for (Path input : inputs) {
            String base = input.getFileName() == null ? "" : PhotoSplitter.baseName(input);
            bases.add(base);
            counts.merge(key(input, output, base), 1, Integer::sum);
        }
        Set<String> used = new HashSet<>();
        for (int i = 0; i < inputs.size(); i++) {
            Path input = inputs.get(i);
            String base = bases.get(i);
            if (input.getFileName() == null) {
                continue;
            }
            if (counts.get(key(input, output, base)) > 1) {
                base = input.getFileName().toString();
            }
            String unique = base;
            for (int n = 2; !used.add(key(input, output, unique)) && n < 10_000; n++) {
                unique = base + " (" + n + ")";
            }
            bases.set(i, unique);
        }
        return bases;
    }

    private static String key(Path input, Path output, String base) {
        Path folder = folder(input, output);
        return Targets.key(folder == null ? Path.of(base) : folder.resolve(base));
    }

    private static void protectInputs(List<Path> inputs, List<String> bases, Path output) throws Usage {
        for (int i = 0; i < inputs.size(); i++) {
            Path input = inputs.get(i);
            Path name = input.getFileName();
            Path folder = folder(input, output);
            if (name == null || folder == null) {
                continue;
            }
            String base = bases.get(i);
            Pattern targetName = Pattern.compile(Pattern.quote(base + "_") + "[1-9][0-9]*\\.png",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            Path destination = canonical(folder);
            for (Path protectedInput : inputs) {
                Path source = canonical(protectedInput);
                if (destination.equals(source.getParent()) && targetName.matcher(source.getFileName().toString()).matches()) {
                    throw collision(protectedInput);
                }
            }
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (var files = Files.list(folder)) {
                for (Path target : files.filter(p -> targetName.matcher(p.getFileName().toString()).matches()).toList()) {
                    for (Path protectedInput : inputs) {
                        if (Files.exists(protectedInput) && Files.isSameFile(target, protectedInput)) {
                            throw collision(protectedInput);
                        }
                    }
                }
            } catch (IOException e) {
                throw new Usage("cannot verify photo output folder " + folder + ": " + e.getMessage());
            }
        }
    }

    private static Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static Usage collision(Path input) {
        return new Usage("photo output could overwrite input " + input + "; choose another -o folder");
    }

    private static Path path(String s) throws Usage {
        try {
            return Path.of(s);
        } catch (InvalidPathException e) {
            throw new Usage("not a valid path: " + s);
        }
    }

    private static int integer(String s, String option) throws Usage {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            throw new Usage(option + " needs a whole number, not '" + s + "'");
        }
    }

    private static float dpi(String s, String option) throws Usage {
        try {
            return Float.parseFloat(s.strip());
        } catch (NumberFormatException e) {
            throw new Usage(option + " needs a number, not '" + s + "'");
        }
    }

    private static PhotoSplitter.Method method(String s) throws Usage {
        try {
            return PhotoSplitter.Method.valueOf(s.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new Usage("--method is straighten or script, not '" + s + "'");
        }
    }

    private static void usage() {
        System.out.println(
                "Usage: office-convert " + COMMAND + " <scan.png|scan.jpg|scan.pdf>... [-o dir] [--angle-threshold n]"
                        + " [--tolerance n] [--min-area n] [--min-contour-area n] [--border-size n] [--dpi n]"
                        + " [--method straighten|script] [--overwrite] [-q]"
                        + System.lineSeparator()
                        + "Finds the photos on a flatbed scan and writes each as name_1.png, name_2.png and so on, in the scan's"
                        + " folder unless -o names another. Every page of a PDF is drawn at --dpi (default 300) and searched, as is"
                        + " every page of a TIFF; numbering continues across pages. Scans given together that would share a"
                        + " name keep their extension in it, as name.jpg_1.png."
                        + System.lineSeparator()
                        + "--method straighten (default) cuts each photo out along its edges and turns it level, in reading order."
                        + " --method script reproduces Stirling-PDF's split_photos.py: the upright box around each photo, turned"
                        + " only when the detected angle is at least --angle-threshold degrees (default 10)."
                        + System.lineSeparator()
                        + "--tolerance (default 30) is how far a colour may stray from the scanner bed and still count as bed;"
                        + " a region smaller than --min-area pixels (default 10000) in its box or --min-contour-area (default"
                        + " 500) in its outline is ignored; --border-size (default 0) trims that many pixels from each side of"
                        + " every photo. The underscore spellings split_photos.py takes (--min_area) work too."
                        + System.lineSeparator()
                        + "Existing photos are left alone unless --overwrite is given; crops completed before a later failure remain."
                        + System.lineSeparator()
                        + "Exit status: 0 every scan gave photos, 1 a scan failed, was skipped or had no photos, 2 a mistake in"
                        + " the arguments.");
    }
}
