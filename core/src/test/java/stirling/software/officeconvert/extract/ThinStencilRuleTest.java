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

    private static PageGraphics read(String content) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(200, 50));
            page.setResources(new PDResources());
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
