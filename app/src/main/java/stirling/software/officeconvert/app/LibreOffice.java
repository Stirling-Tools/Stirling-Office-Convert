package stirling.software.officeconvert.app;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class LibreOffice {

    private record Route(String importFilter, String target, String ext) {}

    private record Setting(String path, String name, String value) {}

    private static final Map<String, Route> ROUTES = Map.of(
            "docx", new Route("writer_pdf_import", "docx:MS Word 2007 XML", "docx"),
            "odt", new Route("writer_pdf_import", "odt", "odt"),
            "rtf", new Route("writer_pdf_import", "rtf", "rtf"),
            "xml", new Route("writer_pdf_import", "xml", "xml"),
            "pptx", new Route("impress_pdf_import", "pptx:Impress MS PowerPoint 2007 XML", "pptx"),
            "odp", new Route("impress_pdf_import", "odp", "odp"));

    private static final Map<String, String> PDF_EXPORTS = Map.of(
            "docx", "writer_pdf_Export", "pptx", "impress_pdf_Export", "xlsx", "calc_pdf_Export");

    private static final String SCRIPTING = "/org.openoffice.Office.Common/Security/Scripting";
    private static final String PROXY = "/org.openoffice.Inet/Settings";

    // Writer's link update 0 and Calc's 1 both mean never; the proxy is a closed local port, so no fetch can leave
    private static final List<Setting> LOCKDOWN = List.of(
            new Setting(SCRIPTING, "MacroSecurityLevel", "3"),
            new Setting(SCRIPTING, "DisableMacrosExecution", "true"),
            new Setting(SCRIPTING, "BlockUntrustedRefererLinks", "true"),
            new Setting(SCRIPTING, "DisableActiveContent", "true"),
            new Setting("/org.openoffice.Office.Writer/Content/Update", "Link", "0"),
            new Setting("/org.openoffice.Office.Calc/Content/Update", "Link", "1"),
            new Setting("/org.openoffice.Office.Common/Misc", "CrashReport", "false"),
            new Setting("/org.openoffice.Office.Jobs/Jobs/org.openoffice.Office.Jobs:Job['UpdateCheck']/Arguments",
                    "AutoCheckEnabled", "false"),
            new Setting(PROXY, "ooInetProxyType", "2"),
            new Setting(PROXY, "ooInetHTTPProxyName", "127.0.0.1"),
            new Setting(PROXY, "ooInetHTTPProxyPort", "9"),
            new Setting(PROXY, "ooInetHTTPSProxyName", "127.0.0.1"),
            new Setting(PROXY, "ooInetHTTPSProxyPort", "9"),
            new Setting(PROXY, "ooInetFTPProxyName", "127.0.0.1"),
            new Setting(PROXY, "ooInetFTPProxyPort", "9"),
            new Setting(PROXY, "ooInetNoProxy", ""));

    private static final String[] PLACES = {
        "/usr/bin/soffice", "/usr/lib/libreoffice/program/soffice", "/opt/libreoffice/program/soffice",
        "C:\\Program Files\\LibreOffice\\program\\soffice.exe", "/Applications/LibreOffice.app/Contents/MacOS/soffice"};

    private static final String DEAD_PROXY = "http://127.0.0.1:9";

    private final Path soffice;
    private final Path template;
    private final int slots;

    private LibreOffice(Path soffice, Path template, int slots) {
        this.soffice = soffice;
        this.template = template;
        this.slots = slots;
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
        Path profile = Files.createDirectories(base.resolve("template"));
        if (seed != null && Files.isDirectory(seed.resolve("user"))) {
            copy(seed, profile);
        }
        harden(profile);
        return new LibreOffice(soffice, profile, Math.max(1, slots));
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

    static void harden(Path profile) throws IOException {
        Path xcu = Files.createDirectories(profile.resolve("user")).resolve("registrymodifications.xcu");
        String text = Files.isRegularFile(xcu) ? Files.readString(xcu, StandardCharsets.UTF_8)
                : "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<oor:items xmlns:oor=\"http://openoffice.org/2001/registry\""
                        + " xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n"
                        + "</oor:items>\n";
        int end = text.lastIndexOf("</oor:items>");
        if (end < 0) {
            throw new IOException("The LibreOffice profile's registrymodifications.xcu is not a registry file");
        }
        StringBuilder ours = new StringBuilder();
        for (Setting s : LOCKDOWN) {
            String path = s.path().replace("'", "&apos;");
            text = Pattern.compile("<item oor:path=\"" + Pattern.quote(path) + "\"><prop oor:name=\""
                    + Pattern.quote(s.name()) + "\"[^>]*>.*?</prop></item>\\s*", Pattern.DOTALL).matcher(text).replaceAll("");
            ours.append("<item oor:path=\"").append(path).append("\"><prop oor:name=\"")
                    .append(s.name()).append("\" oor:op=\"fuse\"><value>").append(s.value()).append("</value></prop></item>\n");
        }
        end = text.lastIndexOf("</oor:items>");
        Files.writeString(xcu, text.substring(0, end) + ours + text.substring(end), StandardCharsets.UTF_8);
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> tree = Files.walk(from)) {
            for (Path src : (Iterable<Path>) tree::iterator) {
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else if (Files.isRegularFile(src)) {
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
        return slots;
    }

    static Set<String> formats() {
        Set<String> all = new TreeSet<>(ROUTES.keySet());
        all.add("pdf");
        return all;
    }

    static boolean converts(String format) {
        return ROUTES.containsKey(format) || "pdf".equals(format);
    }

    void convert(Path pdf, Path out, String format, Path work) throws IOException {
        Route route = ROUTES.get(format);
        if (route == null) {
            throw new IOException("LibreOffice cannot convert a PDF to " + format);
        }
        run(List.of("--infilter=" + route.importFilter(), "--convert-to", route.target()), pdf, route.ext(), out, work,
                "PDF");
    }

    void toPdf(Path office, Path out, int maxPages, Path work) throws IOException {
        String name = office.getFileName().toString();
        String family = OfficeFiles.family(name.substring(name.lastIndexOf('.') + 1));
        run(List.of("--convert-to", pdfTarget(family, maxPages)), office, "pdf", out, work, "document");
    }

    static String pdfTarget(String family, int maxPages) {
        String target = "pdf:" + PDF_EXPORTS.get(family);
        return maxPages > 0 ? target + ":{\"PageRange\":{\"type\":\"string\",\"value\":\"1-" + maxPages + "\"}}" : target;
    }

    static List<String> command(Path soffice, Path profile, List<String> conversion, Path outdir, Path in) {
        List<String> cmd = new ArrayList<>(List.of(soffice.toString(), "-env:UserInstallation=" + profile.toUri(),
                "--headless", "--invisible", "--nologo", "--norestore", "--nodefault", "--nolockcheck",
                "--nofirststartwizard"));
        cmd.addAll(conversion);
        cmd.addAll(List.of("--outdir", outdir.toString(), in.toAbsolutePath().toString()));
        return cmd;
    }

    private void run(List<String> conversion, Path in, String ext, Path out, Path work, String what) throws IOException {
        Path profile = work.resolve("libreoffice-profile");
        copy(template, profile);
        Path outdir = Files.createDirectories(work.resolve("libreoffice"));
        ProcessBuilder pb = new ProcessBuilder(command(soffice, profile, conversion, outdir, in)).redirectErrorStream(true)
                .redirectOutput(outdir.resolve("soffice.log").toFile());
        Map<String, String> env = pb.environment();
        env.putIfAbsent("SAL_USE_VCLPLUGIN", "svp");
        env.putIfAbsent("SAL_DISABLE_PRINTERLIST", "1");
        env.putIfAbsent("OOO_FORCE_DESKTOP", "none");
        for (String proxy : List.of("http_proxy", "https_proxy", "ftp_proxy", "all_proxy", "HTTP_PROXY", "HTTPS_PROXY",
                "FTP_PROXY", "ALL_PROXY")) {
            env.put(proxy, DEAD_PROXY);
        }
        env.remove("no_proxy");
        env.remove("NO_PROXY");
        env.put("TMPDIR", Files.createDirectories(work.resolve("libreoffice-tmp")).toString());
        Process p = pb.start();
        try {
            p.waitFor();
        } catch (InterruptedException e) {
            kill(p);
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Conversion interrupted");
        } finally {
            reap(profile.toUri().toString());
        }
        String name = in.getFileName().toString();
        Path produced = outdir.resolve(name.substring(0, name.lastIndexOf('.')) + "." + ext);
        if (!Files.isRegularFile(produced) || Files.size(produced) == 0) {
            throw new IOException("LibreOffice could not convert this " + what + " (exit code " + p.exitValue() + ")");
        }
        Files.move(produced, out, StandardCopyOption.REPLACE_EXISTING);
    }

    // A child LibreOffice left behind is no longer our descendant once its parent exits, so find it by its profile
    private static void reap(String profile) {
        ProcessHandle.allProcesses()
                .filter(h -> h.info().commandLine().map(c -> c.contains(profile)).orElse(false))
                .forEach(ProcessHandle::destroyForcibly);
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
