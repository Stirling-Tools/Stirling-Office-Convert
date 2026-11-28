package stirling.software.officeconvert.topdf.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;

class SvgHostileTest {

    private static final String CANARY = "CANARY-7f3a9c";

    @TempDir
    Path dir;

    private String convert(String name, byte[] svg) throws IOException {
        Path in = dir.resolve("in").resolve(name);
        Files.createDirectories(in.getParent());
        Files.write(in, svg);
        Path out = dir.resolve("in").resolve(name + ".pdf");
        OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults());
        byte[] pdf = Files.readAllBytes(out);
        String raw = new String(pdf, StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("/JavaScript") || raw.contains("/JS ") || raw.contains("/Launch")
                || raw.contains("/OpenAction") || raw.contains("/URI"), "no active content or links in the PDF");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private String convert(String svg) throws IOException {
        return convert("in.svg", svg.getBytes(StandardCharsets.UTF_8));
    }

    private Path canary() throws IOException {
        Path secret = dir.resolve("canary.txt");
        Files.writeString(secret, CANARY);
        Files.writeString(dir.resolve("canary.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\"><text id=\"t\" y=\"10\">"
                + CANARY + "</text></svg>");
        return secret;
    }

    @Test
    void entitiesAndDtdsAreNeverResolved() throws IOException {
        Path secret = canary();
        String text = convert("<?xml version=\"1.0\"?><!DOCTYPE svg SYSTEM \"http://127.0.0.1:9/svg.dtd\" [\n"
                + "<!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">\n<!ENTITY inner \"INNER\">\n"
                + "<!ENTITY % remote SYSTEM \"http://127.0.0.1:9/p.dtd\"> %remote;]>\n"
                + "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"200\" height=\"50\">"
                + "<text x=\"5\" y=\"20\">a&xxe;b&inner;c</text></svg>");
        assertFalse(text.contains(CANARY));
        assertFalse(text.contains("INNER"), "no DTD entity is expanded");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void remoteReferencesAreNeverFetched() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(200);
            AtomicInteger connections = new AtomicInteger();
            Thread listener = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        server.accept().close();
                        connections.incrementAndGet();
                    } catch (SocketTimeoutException e) {
                        continue;
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            listener.setDaemon(true);
            listener.start();
            String base = "http://127.0.0.1:" + server.getLocalPort();
            convert("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\""
                    + " width=\"100\" height=\"100\"><style>@import url(" + base + "/a.css);"
                    + "@font-face{font-family:R;src:url(" + base + "/f.ttf)} text{font-family:R}"
                    + " rect{fill:url(" + base + "/p.svg#g)}</style>"
                    + "<image xlink:href=\"" + base + "/i.png\" width=\"10\" height=\"10\"/>"
                    + "<image href=\"https://127.0.0.1:" + server.getLocalPort() + "/j.png\" width=\"10\" height=\"10\"/>"
                    + "<use xlink:href=\"" + base + "/u.svg#x\"/><rect width=\"10\" height=\"10\" mask=\"url(" + base
                    + "/m.svg#m)\" filter=\"url(" + base + "/f.svg#f)\"/>"
                    + "<filter id=\"f\"><feImage href=\"" + base + "/fe.png\"/></filter>"
                    + "<circle r=\"5\" filter=\"url(#f)\"/><text y=\"50\">t<tref xlink:href=\"" + base + "/t.svg#t\"/></text></svg>");
            Thread.sleep(500);
            assertEquals(0, connections.get(), "nothing connected to the listener");
        }
    }

    @Test
    void filesBesideOrOutsideTheInputAreNeverRead() throws IOException {
        Path secret = canary();
        String text = convert("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\""
                + " width=\"100\" height=\"100\"><image href=\"" + secret.toUri() + "\" width=\"10\" height=\"10\"/>"
                + "<image href=\"../canary.svg\" width=\"10\" height=\"10\"/><image href=\"/etc/passwd\" width=\"10\""
                + " height=\"10\"/><use xlink:href=\"../canary.svg#t\"/><text y=\"30\"><tref xlink:href=\"../canary.svg#t\"/>"
                + "</text><style>@import \"../canary.css\";</style></svg>");
        assertFalse(text.contains(CANARY));
    }

    @Test
    void scriptsHandlersAndForeignContentAreInert() throws IOException {
        String text = convert("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"200\" height=\"60\""
                + " onload=\"alert('ONLOAD')\"><script>document.write('SCRIPTED')</script>"
                + "<script type=\"text/ecmascript\"><![CDATA[ throw 'CDATASCRIPT' ]]></script>"
                + "<a href=\"javascript:alert(1)\"><text x=\"5\" y=\"20\" onclick=\"alert('CLICK')\">visible</text></a>"
                + "<foreignObject width=\"100\" height=\"40\"><div xmlns=\"http://www.w3.org/1999/xhtml\">FOREIGN</div>"
                + "</foreignObject><set attributeName=\"fill\" to=\"red\"/><animate attributeName=\"x\"/></svg>");
        assertTrue(text.contains("visible"));
        for (String inert : new String[] {"ONLOAD", "SCRIPTED", "CDATASCRIPT", "CLICK", "FOREIGN", "alert"}) {
            assertFalse(text.contains(inert), inert);
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void anSvgzBombIsRefused() throws IOException {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream z = new GZIPOutputStream(gz)) {
            z.write("<svg xmlns=\"http://www.w3.org/2000/svg\"><desc>".getBytes(StandardCharsets.UTF_8));
            byte[] zeros = new byte[1 << 20];
            for (int i = 0; i < 100; i++) {
                z.write(zeros);
            }
        }
        IOException e = assertThrows(IOException.class, () -> convert("bomb.svgz", gz.toByteArray()));
        assertTrue(e.getMessage().contains("unpacks to more than"), e.getMessage());
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void anOversizedPictureHeaderIsNotDecoded() throws IOException {
        String png = Base64.getEncoder().encodeToString(png(200_000, 200_000));
        String text = convert("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\">"
                + "<image href=\"data:image/png;base64," + png + "\" width=\"100\" height=\"100\"/>"
                + "<text y=\"50\">after</text></svg>");
        assertTrue(text.contains("after"));
    }

    @Test
    void deepNestingIsRefusedNotOverflowed() throws IOException {
        String deep = "<g>".repeat(50_000) + "<text y=\"5\">deep</text>" + "</g>".repeat(50_000);
        IOException e = assertThrows(IOException.class,
                () -> convert("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\">" + deep + "</svg>"));
        assertTrue(e.getMessage().contains("nest"), e.getMessage());
        String text = convert("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\""
                + " width=\"10\" height=\"10\"><g id=\"a\"><use xlink:href=\"#a\"/><text y=\"5\">loop</text></g>"
                + "<use xlink:href=\"#a\"/></svg>");
        assertTrue(text.contains("loop"));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void newFilterPrimitivesStayBounded() throws IOException {
        String kernel = "1 ".repeat(32 * 32);
        String hugeKernel = "1 ".repeat(200_000);
        String text = convert("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\""
                + " width=\"2000\" height=\"2000\"><text x=\"10\" y=\"1900\" font-size=\"40\">bounded</text>"
                + "<filter id=\"k\" x=\"0\" y=\"0\" width=\"1\" height=\"1\"><feConvolveMatrix order=\"32\" kernelMatrix=\""
                + kernel + "\"/></filter>"
                + "<filter id=\"big\"><feConvolveMatrix order=\"100000 100000\" kernelMatrix=\"" + hugeKernel + "\"/></filter>"
                + "<filter id=\"d\"><feTurbulence baseFrequency=\"0.05\" result=\"t\"/>"
                + "<feDisplacementMap in=\"SourceGraphic\" in2=\"t\" scale=\"1e30\" xChannelSelector=\"R\"/></filter>"
                + "<filter id=\"l\"><feSpecularLighting specularExponent=\"128\" surfaceScale=\"1e30\">"
                + "<feSpotLight x=\"1e30\" y=\"-1e30\" z=\"1e30\" specularExponent=\"1e30\" limitingConeAngle=\"1e30\"/>"
                + "</feSpecularLighting><feDiffuseLighting diffuseConstant=\"1e30\"><fePointLight z=\"NaN\"/>"
                + "</feDiffuseLighting></filter>"
                + "<filter id=\"r\"><feImage xlink:href=\"#loop\"/><feImage href=\"#loop2\"/></filter>"
                + "<g id=\"loop\" filter=\"url(#r)\"><rect id=\"loop2\" width=\"10\" height=\"10\" filter=\"url(#r)\"/></g>"
                + "<rect width=\"2000\" height=\"2000\" fill=\"#36c\" filter=\"url(#k)\"/>"
                + "<rect width=\"100\" height=\"100\" filter=\"url(#big)\"/>"
                + "<rect width=\"100\" height=\"100\" filter=\"url(#d)\"/>"
                + "<rect width=\"100\" height=\"100\" filter=\"url(#l)\"/></svg>");
        assertTrue(text.contains("bounded"));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void aHugePathLengthNeverMakesMicroscopicDashes() throws IOException {
        String text = convert("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"2000\" height=\"2000\">"
                + "<text x=\"10\" y=\"40\">dashed</text><path d=\"M0 100H2000V2000H0Z\" stroke=\"black\""
                + " stroke-dasharray=\"1 1\" pathLength=\"1e30\" fill=\"none\"/></svg>");
        assertTrue(text.contains("dashed"));
        try (PDDocument doc = Loader.loadPDF(dir.resolve("in").resolve("in.svg.pdf").toFile())) {
            String content = new String(doc.getPage(0).getContents().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(Pattern.compile("\\]\\s*\\S+\\s+d\\b").matcher(content).find(), "no dash pattern");
        }
    }

    private static byte[] png(int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(w).putInt(h).put((byte) 8).put((byte) 2).put((byte) 0)
                .put((byte) 0).put((byte) 0);
        chunk(out, "IHDR", ihdr.array());
        chunk(out, "IDAT", new byte[] {0x78, (byte) 0x9C, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01});
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        out.write(ByteBuffer.allocate(4).putInt(data.length).array());
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.write(t);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
