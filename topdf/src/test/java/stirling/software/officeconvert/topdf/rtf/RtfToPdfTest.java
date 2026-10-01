package stirling.software.officeconvert.topdf.rtf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.testing.NoNetwork;

class RtfToPdfTest {

    @TempDir
    Path dir;

    private String convert(String name, String rtf) throws IOException {
        Path in = Files.write(dir.resolve(name), rtf.getBytes(StandardCharsets.ISO_8859_1));
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            return doc.getNumberOfPages() + "\n" + new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void anRtfFileConverts() throws IOException {
        String text = convert("letter.rtf", "{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Arial;}}\\pard Dear reader,\\par"
                + "\\pard\\qc Centred caf\\'e9\\page Second page\\par}");
        assertTrue(text.startsWith("2\n"), text);
        assertTrue(text.contains("Dear reader,") && text.contains("Centred caf\u00e9") && text.contains("Second page"),
                text);
    }

    @Test
    void aDocThatIsReallyRtfConverts() throws IOException {
        assertEquals(OfficeToPdf.Format.DOCX, OfficeToPdf.Format.of(Files.write(dir.resolve("old.doc"),
                "{\\rtf1 x}".getBytes(StandardCharsets.US_ASCII))));
        String text = convert("saved.doc", "{\\rtf1\\ansi Saved as doc\\par}");
        assertTrue(text.contains("Saved as doc"), text);
        Path real = Files.write(dir.resolve("real.doc"), new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0});
        assertThrows(IllegalArgumentException.class, () -> OfficeToPdf.Format.of(real));
    }

    @Test
    void linksAndIncludedPicturesAreNeverFetched() throws IOException {
        try (NoNetwork net = NoNetwork.start()) {
            String rtf = "{\\rtf1\\ansi\\pard{\\field{\\*\\fldinst HYPERLINK \"" + net.url("/a") + "\"}{\\fldrslt link}}"
                    + "{\\field{\\*\\fldinst INCLUDEPICTURE \"" + net.url("/p.png") + "\" \\\\d}{\\fldrslt kept}}"
                    + "{\\field{\\*\\fldinst INCLUDETEXT \"" + net.uncPath("x.rtf") + "\"}{\\fldrslt cached}}"
                    + "{\\*\\template " + net.url("/t.dot") + "}\\par}";
            String text = convert("links.rtf", rtf);
            assertTrue(text.contains("linkkeptcached"), text);
            net.assertNothingConnected();
        }
    }

    @Test
    void emptyAndBrokenRtfStillGiveAPage() throws IOException {
        String text = convert("empty.rtf", "{\\rtf1}");
        assertTrue(text.startsWith("1\n"), text);
        String broken = convert("broken.rtf", "{\\rtf1\\ansi{\\fonttbl{\\f0 A;}}\\pard text {\\b more");
        assertTrue(broken.contains("text more"), broken);
        assertFalse(broken.isEmpty());
        IOException e = assertThrows(IOException.class, () -> convert("fake.rtf", "{b{\\fi0 not rtf}"));
        assertTrue(e.getMessage().contains("not an RTF document"), e.getMessage());
    }
}
