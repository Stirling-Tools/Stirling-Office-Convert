package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.biff5.Biff5Hostile;
import stirling.software.officeconvert.topdf.doc.DocHostile;
import stirling.software.officeconvert.topdf.doc6.Word6Hostile;
import stirling.software.officeconvert.topdf.testing.ClassScan;
import stirling.software.officeconvert.topdf.testing.Fixtures;
import stirling.software.officeconvert.topdf.testing.ForbiddenProbe;
import stirling.software.officeconvert.topdf.testing.FormatPackageProbe;
import stirling.software.officeconvert.topdf.testing.HostileFormats;
import stirling.software.officeconvert.topdf.testing.NoNetwork;
import stirling.software.officeconvert.topdf.vsdx.VisioHostile;
import stirling.software.officeconvert.topdf.xlsb.XlsbHostile;

class NetworkSafetyTest {

    @TempDir
    Path dir;

    @Test
    void mainCodeReferencesNothingThatCanReachTheNetworkOrRunScripts() throws Exception {
        Path main = Path.of(OfficeToPdf.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Map<String, byte[]> classes = ClassScan.classes(main);
        assertTrue(classes.size() > 30, "only " + classes.size() + " classes found in " + main);
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            if (!e.getKey().startsWith("stirling/software/officeconvert/topdf/")) {
                continue;
            }
            for (String v : ClassScan.ownCodeViolations(ClassScan.read(e.getValue()))) {
                problems.add(e.getKey() + " " + v);
            }
        }
        assertEquals(List.of(), problems);
    }

    private static final String OC = "stirling/software/officeconvert/";

    private static final List<String> SERVER = List.of("uses sun/net/", "uses com/sun/net/",
            "uses java/net/InetSocketAddress");

    private static final Map<String, List<String>> MODULE_ALLOWED = Map.ofEntries(
            Map.entry(OC + "jpx/JpxImageIO", List.of("uses javax/imageio/spi/IIORegistry")),
            Map.entry(OC + "odp/OdpWriter", List.of("calls javax/imageio/ImageIO.read")),
            Map.entry(OC + "sink/ImageShaping", List.of("calls javax/imageio/ImageIO.read")),
            Map.entry(OC + "slides/SlideMedia", List.of("calls javax/imageio/ImageIO.read")),
            Map.entry(OC + "sink/Links", List.of("uses java/net/URLDecoder")),
            Map.entry(OC + "pdfa/CMapFixer", List.of("uses java/net/URL")),
            Map.entry(OC + "app/App", SERVER),
            Map.entry(OC + "app/ConvertHandler", List.of("uses sun/net/", "uses com/sun/net/",
                    "uses java/net/InetSocketAddress", "uses java/net/InetAddress", "uses java/net/URLEncoder",
                    "uses java/net/URLDecoder")),
            Map.entry(OC + "app/LibreOffice", List.of("uses java/lang/ProcessBuilder", "uses java/lang/ProcessHandle")),
            Map.entry(OC + "app/Limits", List.of("uses sun/net/")),
            Map.entry(OC + "app/PageHandler", List.of("uses sun/net/", "uses com/sun/net/")));

    @Test
    void everyModuleReferencesOnlyAllowedNetworkOrScriptCode() throws Exception {
        Map<String, List<String>> allowed = MODULE_ALLOWED;
        String dirs = System.getProperty("topdf.scanClasses", "");
        List<String> problems = new ArrayList<>();
        TreeSet<String> used = new TreeSet<>();
        int modules = 0;
        for (String d : dirs.split(File.pathSeparator)) {
            if (d.isBlank()) {
                continue;
            }
            Map<String, byte[]> classes = ClassScan.classes(Path.of(d));
            assertFalse(classes.isEmpty(), "no classes in " + d);
            modules++;
            for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                String name = e.getKey().replaceFirst("\\.class$", "");
                String owner = name.replaceFirst("\\$.*", "");
                List<String> ok = allowed.getOrDefault(owner, List.of());
                for (String v : ClassScan.ownCodeViolations(ClassScan.read(e.getValue()))) {
                    if (ok.contains(v)) {
                        used.add(owner + " " + v);
                    } else {
                        problems.add(name + " " + v);
                    }
                }
            }
        }
        assertEquals(5, modules, "the build passes topdf.scanClasses for core, legacy, pdfa, cli and app");
        assertEquals(List.of(), problems);
        List<String> stale = new ArrayList<>();
        allowed.forEach((owner, vs) -> vs.stream().filter(v -> !used.contains(owner + " " + v))
                .forEach(v -> stale.add(owner + " " + v)));
        assertEquals(List.of(), stale, "allowed references no class makes any more");
    }

    @Test
    void theScannerCatchesForbiddenReferences() throws Exception {
        byte[] self = read(NoNetwork.class);
        List<String> v = ClassScan.ownCodeViolations(ClassScan.read(self));
        assertTrue(v.stream().anyMatch(s -> s.contains("java/net/ServerSocket")), v.toString());
        assertTrue(v.stream().anyMatch(s -> s.contains("java/net/ProxySelector")), v.toString());
        List<String> clean = ClassScan.ownCodeViolations(ClassScan.read(read(OfficeToPdf.class)));
        assertEquals(List.of(), clean);
    }

    @Test
    void theScannerCatchesFormulasScriptsAndUnguardedPictureCode() throws Exception {
        ClassScan.Refs probe = ClassScan.read(read(ForbiddenProbe.class));
        List<String> v = ClassScan.ownCodeViolations(probe);
        List<String> expected = List.of("createFormulaEvaluator", "evaluateAll", "setForceFormulaRecalculation",
                "evaluateAllFormulaCells", "XSSFFormulaEvaluator", "java/beans/", "java/io/ObjectInputStream",
                "javax/xml/validation/", "MethodHandles$Lookup.findClass", "execQuery", "Toolkit.getImage",
                "ImageIO.read", "ImageIO.getImageReadersByFormatName outside PictureDecoder",
                "PDImageXObject.createFromByteArray outside PictureDecoder",
                "JPEGFactory.createFromByteArray outside PictureDecoder", "XSSFPicture.resize",
                "XSSFPicture.getImageDimension", "XSLFPictureData.getImageDimension", "org/apache/poi/ss/util/ImageUtils",
                "XSLFSlide.draw outside SafeImageRenderer", "Drawable.IMAGE_RENDERER outside SafeImageRenderer",
                "org/apache/poi/xslf/draw/", "java/sql/Driver", "java/awt/print/", "javax/swing/JEditorPane",
                "javax/swing/ImageIcon");
        List<String> missed = new ArrayList<>();
        for (String e : expected) {
            if (v.stream().noneMatch(s -> s.contains(e))) {
                missed.add(e);
            }
        }
        assertEquals(List.of(), missed, v.toString());

        String io = "stirling/software/officeconvert/topdf/io/";
        List<String> asRenderer = ClassScan.ownCodeViolations(
                new ClassScan.Refs(io + "SafeImageRenderer", probe.classes(), probe.members(), probe.strings()));
        assertTrue(asRenderer.stream().noneMatch(s -> s.contains("outside SafeImageRenderer")), asRenderer.toString());
        assertTrue(asRenderer.stream().anyMatch(s -> s.contains("evaluateAll")), asRenderer.toString());
        List<String> asDecoder = ClassScan.ownCodeViolations(
                new ClassScan.Refs(io + "PictureDecoder", probe.classes(), probe.members(), probe.strings()));
        assertTrue(asDecoder.stream().noneMatch(s -> s.contains("outside PictureDecoder")), asDecoder.toString());
        assertTrue(asDecoder.stream().anyMatch(s -> s.contains("ImageIO.read")), asDecoder.toString());
    }

    @Test
    void formatPackagesMayNotOpenFilesOrMakeTheirOwnParsers() throws Exception {
        ClassScan.Refs probe = ClassScan.read(read(FormatPackageProbe.class));
        List<String> expected = List.of("java/io/File ", "java/io/FileInputStream", "java/io/RandomAccessFile",
                "java/nio/file/Files", "Files.newInputStream", "Files.newByteChannel", "Files.readAllBytes",
                "java/nio/file/Paths", "java/nio/file/Path.of", "java/nio/file/Path.resolve", "java/nio/file/Path.toFile",
                "java/util/Scanner.<init>(Ljava/nio/file/Path;", "java/io/PrintWriter.<init>(Ljava/lang/String;",
                "javax/xml/parsers/DocumentBuilderFactory", "javax/xml/parsers/SAXParserFactory",
                "javax/xml/stream/XMLInputFactory", "javax/xml/transform/TransformerFactory",
                "org/apache/poi/util/XMLHelper", "OPCPackage.open", "XSSFWorkbook.<init>(Ljava/lang/String;");
        String topdf = "stirling/software/officeconvert/topdf/";
        for (String pkg : ClassScan.FORMAT_PACKAGES) {
            List<String> v = ClassScan.ownCodeViolations(probe.as(pkg + "Probe"));
            List<String> missed = new ArrayList<>();
            for (String e : expected) {
                if (v.stream().noneMatch(s -> s.contains(e))) {
                    missed.add(e);
                }
            }
            assertEquals(List.of(), missed, pkg + ": " + v);
        }
        for (String elsewhere : new String[] {"io/Probe", "Probe", "font/Probe", "testing/FormatPackageProbe"}) {
            List<String> v = ClassScan.ownCodeViolations(probe.as(topdf + elsewhere));
            assertTrue(v.stream().noneMatch(s -> s.startsWith("opens files") || s.startsWith("makes its own XML parser")),
                    elsewhere + ": " + v);
        }
        assertTrue(ClassScan.ownCodeViolations(probe).stream().anyMatch(s -> s.contains("javax/xml/transform/")));
        assertFalse(ClassScan.inFormatPackage(topdf + "docxextra/X"));
        for (String renderer : new String[] {"docx/DocxRenderer", "pptx/PptxRenderer", "xlsx/XlsxRenderer",
            "ppt/PptRenderer"}) {
            assertTrue(ClassScan.inFormatPackage(topdf + renderer));
        }
    }

    @Test
    void reportsNetworkCapableCodeInThirdPartyJars() throws Exception {
        String cp = System.getProperty("topdf.runtimeClasspath", "");
        List<String> jars = new ArrayList<>();
        for (String s : cp.split(File.pathSeparator)) {
            if (s.endsWith(".jar")) {
                jars.add(s);
            }
        }
        assertFalse(jars.isEmpty(), "the build passes topdf.runtimeClasspath to the tests");
        StringBuilder report = new StringBuilder();
        report.append("Network-capable references in the runtime classpath of :topdf (report only)\n");
        for (String jar : jars) {
            Map<String, TreeSet<String>> byKind = new TreeMap<>();
            for (Map.Entry<String, byte[]> e : ClassScan.classes(Path.of(jar)).entrySet()) {
                ClassScan.Refs refs;
                try {
                    refs = ClassScan.read(e.getValue());
                } catch (IOException bad) {
                    continue;
                }
                for (String kind : ClassScan.networkCapable(refs)) {
                    byKind.computeIfAbsent(kind, k -> new TreeSet<>()).add(refs.name());
                }
            }
            report.append("\n== ").append(Path.of(jar).getFileName()).append('\n');
            if (byKind.isEmpty()) {
                report.append("   none\n");
            }
            for (Map.Entry<String, TreeSet<String>> k : byKind.entrySet()) {
                report.append("   ").append(k.getKey()).append(" (").append(k.getValue().size()).append("): ");
                report.append(String.join(", ", k.getValue())).append('\n');
            }
        }
        Path out = Path.of(System.getProperty("topdf.reportDir", "build/reports")).resolve("network-scan.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);
    }

    @Test
    void hostileDocumentsNeverTouchTheNetwork() throws Exception {
        try (NoNetwork net = NoNetwork.start()) {
            Map<String, byte[]> docs = new TreeMap<>();
            docs.put("hostile.docx", Fixtures.hostileDocx(net));
            docs.put("doctype.docx", Fixtures.doctypeDocx(net));
            docs.put("hostile.pptx", Fixtures.hostilePptx(net));
            docs.put("hostile.xlsx", Fixtures.hostileXlsx(net));
            docs.putAll(HostileFormats.all(net));
            docs.put("hostile.doc", DocHostile.build(net));
            docs.put("hostile6.doc", Word6Hostile.build(net));
            docs.put("hostile.xlsb", XlsbHostile.build(net));
            docs.put("hostile95.xls", Biff5Hostile.build(net));
            docs.put("hostile.vsdx", VisioHostile.build(net));
            TreeSet<String> refused = new TreeSet<>();
            for (Map.Entry<String, byte[]> d : docs.entrySet()) {
                Path in = Fixtures.write(dir, d.getKey(), d.getValue());
                Path out = dir.resolve(d.getKey() + ".pdf");
                try {
                    OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
                    assertTrue(Files.size(out) > 0);
                } catch (IOException e) {
                    assertFalse(e instanceof OfficeToPdf.TimedOut, d.getKey() + " hung");
                    refused.add(d.getKey());
                }
            }
            net.assertNothingConnected();
            assertEquals(Set.of("doctype.docx", "doctype.fodt", "doctype.odt"), refused, "only DOCTYPEs are refused");
        }
    }

    @Test
    void theDnsCanaryIsWired() throws Exception {
        try (NoNetwork net = NoNetwork.start()) {
            assumeTrue(NoNetwork.resolverHookActive(), "the JVM did not load the recording resolver");
            assertThrows(UnknownHostException.class, () -> InetAddress.getByName(NoNetwork.CANARY_HOST));
            assertTrue(net.attempts().stream().anyMatch(a -> a.contains(NoNetwork.CANARY_HOST)), net.attempts().toString());
        }
    }

    @Test
    void theDnsHookRefusesEveryNameButLocalhost() throws Exception {
        try (NoNetwork net = NoNetwork.start()) {
            assumeTrue(NoNetwork.resolverHookActive(), "the JVM did not load the recording resolver");
            assertThrows(UnknownHostException.class, () -> InetAddress.getByName("nonet-probe.example"));
            assertThrows(UnknownHostException.class, () -> InetAddress.getAllByName("nonet-probe.test"));
            assertTrue(InetAddress.getByName("localhost").isLoopbackAddress());
            assertTrue(InetAddress.getByName("app.localhost").isLoopbackAddress());
            InetAddress remote = InetAddress.getByAddress(new byte[] {(byte) 192, 0, 2, 7});
            assertEquals("192.0.2.7", remote.getCanonicalHostName());
            List<String> seen = net.attempts();
            assertTrue(seen.contains("DNS lookup of nonet-probe.example"), seen.toString());
            assertTrue(seen.contains("DNS lookup of nonet-probe.test"), seen.toString());
            assertTrue(seen.contains("reverse DNS lookup of 192.0.2.7"), seen.toString());
            assertEquals(3, seen.size(), seen.toString());
        }
    }

    private static byte[] read(Class<?> type) throws IOException {
        try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            return in.readAllBytes();
        }
    }
}
