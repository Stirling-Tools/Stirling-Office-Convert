package stirling.software.officeconvert.app;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

final class LibreOffice {

    private record Route(String importFilter, String target, String ext) {}

    private static final Map<String, Route> ROUTES = Map.of(
            "docx", new Route("writer_pdf_import", "docx:MS Word 2007 XML", "docx"),
            "odt", new Route("writer_pdf_import", "odt", "odt"),
            "rtf", new Route("writer_pdf_import", "rtf", "rtf"),
            "pptx", new Route("impress_pdf_import", "pptx:Impress MS PowerPoint 2007 XML", "pptx"),
            "odp", new Route("impress_pdf_import", "odp", "odp"));

    private static final String[] PLACES = {
        "/usr/bin/soffice", "/usr/lib/libreoffice/program/soffice", "/opt/libreoffice/program/soffice",
        "C:\\Program Files\\LibreOffice\\program\\soffice.exe", "/Applications/LibreOffice.app/Contents/MacOS/soffice"};

    private final Path soffice;
    private final BlockingQueue<Path> profiles;

    private LibreOffice(Path soffice, List<Path> profiles) {
        this.soffice = soffice;
        this.profiles = new ArrayBlockingQueue<>(profiles.size(), false, profiles);
    }

    static LibreOffice find(String configured, String template, int slots) throws IOException {
        if ("off".equalsIgnoreCase(configured)) {
            return null;
        }
        Path soffice = "auto".equalsIgnoreCase(configured) ? search() : Path.of(configured);
        if (soffice == null || !Files.isExecutable(soffice)) {
            if (!"auto".equalsIgnoreCase(configured)) {
                throw new IOException("LIBREOFFICE names " + configured + ", which is not an executable soffice");
            }
            return null;
        }
        Path base = Files.createTempDirectory("office-convert-lo");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> delete(base)));
        Path seed = template == null || template.isBlank() ? null : Path.of(template);
        List<Path> profiles = new ArrayList<>();
        for (int i = 0; i < Math.max(1, slots); i++) {
            Path p = Files.createDirectories(base.resolve("p" + i));
            if (seed != null && Files.isDirectory(seed.resolve("user"))) {
                copy(seed, p);
            }
            profiles.add(p);
        }
        return new LibreOffice(soffice, profiles);
    }

    private static Path search() {
        String exe = System.getProperty("os.name", "").startsWith("Windows") ? "soffice.exe" : "soffice";
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            Path p = dir.isBlank() ? null : Path.of(dir, exe);
            if (p != null && Files.isExecutable(p)) {
                return p;
            }
        }
        for (String place : PLACES) {
            Path p = Path.of(place);
            if (Files.isExecutable(p)) {
                return p;
            }
        }
        return null;
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> tree = Files.walk(from)) {
            for (Path src : (Iterable<Path>) tree::iterator) {
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void delete(Path dir) {
        try (Stream<Path> tree = Files.walk(dir)) {
            tree.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
        }
    }

    Path executable() {
        return soffice;
    }

    int slots() {
        return profiles.remainingCapacity() + profiles.size();
    }

    static Set<String> formats() {
        return ROUTES.keySet();
    }

    static boolean converts(String format) {
        return ROUTES.containsKey(format);
    }

    void convert(Path pdf, Path out, String format) throws IOException {
        Route route = ROUTES.get(format);
        if (route == null) {
            throw new IOException("LibreOffice cannot convert a PDF to " + format);
        }
        Path profile;
        try {
            profile = profiles.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Conversion interrupted");
        }
        try {
            Path outdir = Files.createDirectories(out.toAbsolutePath().getParent().resolve("libreoffice"));
            List<String> cmd = List.of(soffice.toString(), "-env:UserInstallation=" + profile.toUri(), "--headless",
                    "--nologo", "--norestore", "--nodefault", "--nolockcheck", "--nofirststartwizard",
                    "--infilter=" + route.importFilter(), "--convert-to", route.target(), "--outdir", outdir.toString(),
                    pdf.toAbsolutePath().toString());
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(outdir.resolve("soffice.log").toFile());
            Map<String, String> env = pb.environment();
            env.putIfAbsent("SAL_USE_VCLPLUGIN", "svp");
            env.putIfAbsent("SAL_DISABLE_PRINTERLIST", "1");
            env.putIfAbsent("OOO_FORCE_DESKTOP", "none");
            Process p = pb.start();
            try {
                p.waitFor();
            } catch (InterruptedException e) {
                kill(p);
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Conversion interrupted");
            }
            String name = pdf.getFileName().toString();
            Path produced = outdir.resolve(name.substring(0, name.lastIndexOf('.')) + "." + route.ext());
            if (!Files.isRegularFile(produced) || Files.size(produced) == 0) {
                throw new IOException("LibreOffice could not convert this PDF (exit code " + p.exitValue() + ")");
            }
            Files.move(produced, out, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            profiles.add(profile);
        }
    }

    private static void kill(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        try {
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
