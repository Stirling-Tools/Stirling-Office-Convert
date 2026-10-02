package stirling.software.officeconvert.topdf.pptx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.font.CloudFonts;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.testing.Fixtures;
import stirling.software.officeconvert.topdf.testing.TestFonts;

class PptxHardeningTest {

    @TempDir
    Path dir;

    // One slide whose text box holds the given paragraphs, in a box of the given width in EMU
    private byte[] deck(String paragraphs, long width) {
        Fixtures.Zip zip = Fixtures.edit(Fixtures.pptx("MARKER"));
        String slide = zip.text("ppt/slides/slide1.xml");
        slide = slide.replaceFirst("<a:p>(?:(?!</a:p>).)*MARKER(?:(?!</a:p>).)*</a:p>", paragraphs);
        slide = slide.replace("cx=\"6350000\"", "cx=\"" + width + "\"");
        return zip.put("ppt/slides/slide1.xml", slide).bytes();
    }

    private Path convert(String name, byte[] pptx, Duration timeout) throws IOException {
        Path in = Fixtures.write(dir, name + ".pptx", pptx);
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().timeout(timeout));
        return out;
    }

    private static String text(Path pdf) throws IOException {
        try (PDDocument d = Loader.loadPDF(Files.readAllBytes(pdf))) {
            return new PDFTextStripper().getText(d);
        }
    }

    @Test
    void aTextBoxWithManyParagraphsTakesLinearTime() throws IOException {
        String p = "<a:p><a:r><a:rPr lang=\"en-US\"/><a:t>line</a:t></a:r></a:p>";
        Path pdf = convert("many", deck(p.repeat(20_000), 6_350_000), Duration.ofSeconds(60));
        assertTrue(text(pdf).contains("line"));
    }

    @Test
    void textOverflowingANarrowBoxStopsAtTheSlideEdge() throws IOException {
        String p = "<a:p><a:r><a:rPr lang=\"en-US\" sz=\"1800\"/><a:t>" + "a".repeat(200_000) + "</a:t></a:r></a:p>";
        Path pdf = convert("narrow", deck(p, 300_000), Duration.ofSeconds(60));
        String shown = text(pdf).replaceAll("\\s", "");
        assertTrue(shown.length() > 20 && shown.length() < 200, "characters on the slide: " + shown.length());
        assertTrue(Files.size(pdf) < 100_000, "PDF bytes: " + Files.size(pdf));
    }

    @Test
    void aMetricCompatibleNarrowFaceIsUsedAsItIs() throws IOException {
        Path fonts = Files.createDirectories(dir.resolve("narrow"));
        Files.write(fonts.resolve("LiberationSansNarrow-Regular.ttf"), TestFonts.renamed("Liberation Sans Narrow"));
        FontLibrary library = FontLibrary.of(List.of(fonts));
        Standins.Emulation e = new Standins(new CloudFonts(library)).emulate("Arial Narrow", false, false);
        assertEquals("Liberation Sans Narrow", e.face().family());
        assertEquals(100, e.scale());
        assertTrue(e.face().substituted());
        assertNull(e.face().note(), "a metric-compatible stand-in is not reported");
    }
}
