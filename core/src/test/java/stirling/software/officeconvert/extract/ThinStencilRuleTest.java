package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.junit.jupiter.api.Test;

class ThinStencilRuleTest {

    @Test
    void aOnePixelStencilStretchedIntoAThinBarIsARule() throws IOException {
        PageGraphics g = read("0 0 1 rg 180 0 0 -0.48 10 25 cm BI /IM true /W 1 /H 1 /BPC 1 ID \u0000 EI");
        assertEquals(0, g.images().size());
        assertEquals(1, g.rules().size());
        PageGraphics.Rule rule = g.rules().getFirst();
        assertTrue(rule.horizontal());
        assertEquals(180, rule.length(), 0.1f);
        assertEquals(0x0000FF, rule.rgb());
    }

    @Test
    void aStencilWithNothingPaintedIsNoRule() throws IOException {
        PageGraphics g = read("180 0 0 -0.48 10 25 cm BI /IM true /W 1 /H 1 /BPC 1 ID ÿ EI");
        assertEquals(0, g.rules().size());
    }

    @Test
    void aStencilHalfOutsideItsClipKeepsTheVisibleStrip() throws IOException {
        PageGraphics g = read("0 24.8 200 1 re W n 0 g 180 0 0 -2 10 25 cm BI /IM true /W 1 /H 1 /BPC 1 ID \u0000 EI");
        assertEquals(1, g.rules().size());
        assertEquals(0.2f, g.rules().getFirst().thickness(), 0.05f);
    }

    @Test
    void aStencilWithGapsIsNoRule() throws IOException {
        PageGraphics g = read("0 g 180 0 0 -0.48 10 25 cm BI /IM true /W 4 /H 1 /BPC 1 ID \u0020 EI");
        assertEquals(0, g.rules().size());
    }

    @Test
    void aTranslucentStencilRuleKeepsItsLightness() throws IOException {
        PageGraphics g = read("/A gs 0 g 180 0 0 -0.48 10 25 cm BI /IM true /W 1 /H 1 /BPC 1 ID \u0000 EI");
        assertEquals(1, g.rules().size());
        assertTrue((g.rules().getFirst().rgb() & 0xFF) > 0x60, Integer.toHexString(g.rules().getFirst().rgb()));
    }

    @Test
    void manyStencilRulesStopAtTheImageCap() throws IOException {
        StringBuilder content = new StringBuilder("0 g");
        for (int i = 0; i < 10_050; i++) {
            content.append(" q 180 0 0 -0.48 10 25 cm BI /IM true /W 1 /H 1 /BPC 1 ID \u0000 EI Q");
        }
        PageGraphics g = read(content.toString());
        assertTrue(g.rules().size() <= 10_000, "rules: " + g.rules().size());
    }

    private static PageGraphics read(String content) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(200, 50));
            PDResources resources = new PDResources();
            org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState translucent =
                    new org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState();
            translucent.setNonStrokingAlphaConstant(0.5f);
            resources.put(org.apache.pdfbox.cos.COSName.getPDFName("A"), translucent);
            page.setResources(resources);
            document.addPage(page);
            PDStream stream = new PDStream(document);
            try (OutputStream out = stream.createOutputStream()) {
                out.write(content.getBytes(StandardCharsets.ISO_8859_1));
            }
            page.setContents(stream);
            return GraphicsCollector.read(page, PageReader.displayTransform(page.getCropBox(), 0), 200, 50,
                    new ParsedStreams());
        }
    }
}
