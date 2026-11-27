package stirling.software.officeconvert.topdf.xps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.testing.NoNetwork;

class XpsHostileTest {

    static final String CANARY = "CANARY-7f3a9c-SECRET";

    @TempDir
    Path dir;

    private OfficeToPdf.Result convert(Path in, Path out) throws IOException {
        return OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(1)));
    }

    private Path write(String name, byte[] data) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, data);
        return p;
    }

    private static String raw(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            StringBuilder b = new StringBuilder(new org.apache.pdfbox.text.PDFTextStripper().getText(doc));
            for (PDPage p : doc.getPages()) {
                for (PDAnnotation a : p.getAnnotations()) {
                    b.append(a.getCOSObject().toString());
                }
            }
            b.append(doc.getDocumentCatalog().getCOSObject().toString());
            return b.toString();
        }
    }

    @Test
    void remoteLocalAndScriptReferencesAreNeitherFetchedNorKept() throws Exception {
        Path secret = dir.resolve("secret.ttf");
        Files.writeString(secret, CANARY);
        Path beside = dir.resolve("sub");
        Files.createDirectories(beside);
        try (NoNetwork net = NoNetwork.start()) {
            Path in = write("sub/hostile.xps", XpsHostile.zip(XpsHostile.parts(net, List.of(secret.toUri().toString(),
                    secret.toString(), "../secret.ttf", "/../secret.ttf"))));
            Path out = dir.resolve("hostile.pdf");
            OfficeToPdf.Result r = convert(in, out);
            assertEquals(1, r.pages());
            net.assertNothingConnected();
            byte[] pdf = Files.readAllBytes(out);
            assertFalse(new String(pdf, StandardCharsets.ISO_8859_1).contains(CANARY));
            String all = raw(out);
            assertFalse(all.contains(CANARY));
            assertFalse(all.contains("/JavaScript") || all.contains("/JS") || all.contains("/Launch"), all);
            try (PDDocument doc = Loader.loadPDF(out.toFile())) {
                for (PDAnnotation a : doc.getPage(0).getAnnotations()) {
                    if (a instanceof PDAnnotationLink l && l.getAction() instanceof PDActionURI u) {
                        String s = u.getURI().toLowerCase(java.util.Locale.ROOT);
                        assertTrue(s.startsWith("http://") || s.startsWith("https://") || s.startsWith("mailto:"), s);
                    }
                }
                assertTrue(new org.apache.pdfbox.text.PDFTextStripper().getText(doc).contains("Hostile page"));
            }
        }
    }

    @Test
    void zipSlipEntriesAreNeverResolvedOutsideThePackage() throws IOException {
        Map<String, byte[]> parts = XpsToPdfTest.xps(List.of(XpsToPdfTest.page(
                "<Glyphs Fill='#FF000000' FontUri='../../../evil.ttf' FontRenderingEmSize='20' OriginX='10'"
                + " OriginY='100' UnicodeString='Slip'/>")));
        parts.put("../../../evil.ttf", CANARY.getBytes(StandardCharsets.UTF_8));
        parts.put("/abs/evil.ttf", CANARY.getBytes(StandardCharsets.UTF_8));
        Path in = write("slip.xps", XpsHostile.zip(parts));
        Path out = dir.resolve("slip.pdf");
        convert(in, out);
        assertFalse(Files.exists(dir.getParent().resolve("evil.ttf")));
        assertFalse(raw(out).contains(CANARY));
    }

    @Test
    void anExternalEntityIsNeverRead() throws IOException {
        Path secret = dir.resolve("xxe.txt");
        Files.writeString(secret, CANARY);
        String xxe = "<?xml version='1.0'?><!DOCTYPE FixedPage [<!ENTITY x SYSTEM '" + secret.toUri() + "'>]>"
                + "<FixedPage xmlns='" + XpsToPdfTest.NS + "' Width='816' Height='1056'><Glyphs Fill='#FF000000'"
                + " FontUri='/Resources/font.ttf' FontRenderingEmSize='20' OriginX='10' OriginY='100'"
                + " UnicodeString='&x;'/></FixedPage>";
        Path in = write("xxe.xps", XpsHostile.zip(XpsToPdfTest.xps(List.of(xxe))));
        Path out = dir.resolve("xxe.pdf");
        IOException e = assertThrows(IOException.class, () -> convert(in, out));
        assertTrue(e.getMessage().contains("DOCTYPE"), e.getMessage());
        assertFalse(Files.exists(out));
    }

    @Test
    void aDecompressionBombIsRefused() throws IOException {
        Path in = dir.resolve("bomb.xps");
        Map<String, byte[]> parts = XpsToPdfTest.xps(List.of(XpsToPdfTest.page("")));
        parts.remove("Documents/1/Pages/1.fpage");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(in))) {
            for (Map.Entry<String, byte[]> e : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("Documents/1/Pages/1.fpage"));
            zip.write(("<FixedPage xmlns='" + XpsToPdfTest.NS + "' Width='816' Height='1056'>")
                    .getBytes(StandardCharsets.UTF_8));
            byte[] spaces = new byte[1 << 20];
            java.util.Arrays.fill(spaces, (byte) ' ');
            for (int i = 0; i < 600; i++) {
                zip.write(spaces);
            }
            zip.write("</FixedPage>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertTrue(Files.size(in) < 4 << 20);
        IOException e = assertThrows(IOException.class, () -> convert(in, dir.resolve("bomb.pdf")));
        assertTrue(e.getMessage().contains("too large") || e.getMessage().contains("zip bomb"), e.getMessage());
    }

    @Test
    void anOversizedImageHeaderIsLeftOutNotDecoded() throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(200_000).putInt(200_000).put((byte) 8).put((byte) 2)
                .put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(png, "IHDR", ihdr.array());
        chunk(png, "IDAT", new byte[] {0x78, (byte) 0x9C, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01});
        chunk(png, "IEND", new byte[0]);
        Map<String, byte[]> parts = XpsToPdfTest.xps(List.of(XpsToPdfTest.page("<Path Data='M 0,0 L 100,0 100,100 Z'>"
                + "<Path.Fill><ImageBrush ImageSource='/huge.png' Viewbox='0,0,10,10' ViewboxUnits='Absolute'"
                + " Viewport='0,0,100,100' ViewportUnits='Absolute'/></Path.Fill></Path>"
                + XpsToPdfTest.glyphs("Still converts", 10, 300, ""))));
        parts.put("huge.png", png.toByteArray());
        Path out = dir.resolve("huge.pdf");
        OfficeToPdf.Result r = assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> convert(write("huge.xps", XpsHostile.zip(parts)), out));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("picture")), r.warnings().toString());
        assertTrue(raw(out).contains("Still converts"));
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] t = type.getBytes(StandardCharsets.ISO_8859_1);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(t);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    @Test
    void deepNestingIsBoundedWithoutAStackOverflow() throws IOException {
        Path ok = write("deep.xps", XpsHostile.zip(XpsToPdfTest.xps(List.of(XpsToPdfTest.page(
                "<Canvas RenderTransform='1,0,0,1,0.1,0'>".repeat(900) + XpsToPdfTest.glyphs("Deep", 10, 100, "")
                        + "</Canvas>".repeat(900))))));
        Path out = dir.resolve("deep.pdf");
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> convert(ok, out));
        assertTrue(raw(out).contains("Deep"));
        Path tooDeep = write("deeper.xps", XpsHostile.zip(XpsToPdfTest.xps(List.of(XpsToPdfTest.page(
                "<Canvas>".repeat(5000) + "</Canvas>".repeat(5000))))));
        IOException e = assertThrows(IOException.class, () -> convert(tooDeep, dir.resolve("deeper.pdf")));
        assertFalse(e.getMessage() == null, e.toString());
    }

    @Test
    void manyNestedVisualBrushesStopAtTheirDepth() throws IOException {
        StringBuilder res = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            res.append("<VisualBrush x:Key='v").append(i).append("' Viewbox='0,0,10,10' Viewport='0,0,5,5'")
                    .append(" ViewboxUnits='Absolute' ViewportUnits='Absolute' TileMode='Tile'><VisualBrush.Visual>")
                    .append("<Canvas><Path Data='M 0,0 L 10,0 10,10 Z' Fill='{StaticResource v").append(i + 1)
                    .append("}'/><Path Data='M 0,0 L 10,10 0,10 Z' Fill='{StaticResource v").append(i + 1)
                    .append("}'/></Canvas></VisualBrush.Visual></VisualBrush>");
        }
        Path in = write("fan.xps", XpsHostile.zip(XpsToPdfTest.xps(List.of(XpsToPdfTest.page("<Canvas><Canvas.Resources>"
                + "<ResourceDictionary>" + res + "</ResourceDictionary></Canvas.Resources><Path Data='M 0,0 L 500,0"
                + " 500,500 0,500 Z' Fill='{StaticResource v0}'/></Canvas>")))));
        Path out = dir.resolve("fan.pdf");
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> convert(in, out));
        assertTrue(Files.size(out) < 2_000_000, "" + Files.size(out));
    }
}
