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
import stirling.software.officeconvert.topdf.io.XmlElements;
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

    @Test
    void aSlideWithTooManyXmlElementsIsLeftOutWithAWarning() throws IOException {
        String path = "<a:lnTo><a:pt x=\"1\" y=\"1\"/></a:lnTo>".repeat((int) XmlElements.MAX_POI_PART / 2 + 1);
        String shape = "<p:sp><p:nvSpPr><p:cNvPr id=\"9\" name=\"c\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>"
                + "<a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"900000\" cy=\"600000\"/></a:xfrm><a:custGeom>"
                + "<a:pathLst><a:path w=\"10\" h=\"10\"><a:moveTo><a:pt x=\"0\" y=\"0\"/></a:moveTo>" + path
                + "</a:path></a:pathLst></a:custGeom></p:spPr></p:sp>";
        Fixtures.Zip zip = Fixtures.edit(Fixtures.pptx("first", "second"));
        String slide = zip.text("ppt/slides/slide2.xml");
        zip.put("ppt/slides/slide2.xml", slide.replace("</p:spTree>", shape + "</p:spTree>"));
        Path in = Fixtures.write(dir, "dense.pptx", zip.bytes());
        OfficeToPdf.Result r = OfficeToPdf.convert(in, dir.resolve("dense.pdf"), OfficeToPdf.Options.defaults());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("more than " + XmlElements.MAX_POI_PART
                + " XML elements")), r.warnings().toString());
        assertTrue(text(dir.resolve("dense.pdf")).contains("first"));
    }
}
