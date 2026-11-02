package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.junit.jupiter.api.Test;

class BarcodeModulesTest {

    private static final float MODULE = 1.77f;

    @Test
    void modulesSmallerThanARuleThatTouchTheirNeighboursAreKeptAsFills() throws IOException {
        StringBuilder content = new StringBuilder("0 g");
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 20; col++) {
                if ((row + col) % 3 != 0) {
                    content.append(' ').append(20 + col * MODULE).append(' ').append(100 - row * MODULE).append(' ')
                            .append(MODULE).append(' ').append(-MODULE).append(" re f");
                }
            }
        }
        PageGraphics g = read(content.toString());
        assertEquals(80, g.fills().size());
        assertEquals(MODULE, g.fills().getFirst().right() - g.fills().getFirst().x(), 0.01f);
    }

    @Test
    void scatteredDotsStayOut() throws IOException {
        StringBuilder content = new StringBuilder("0 g");
        for (int i = 0; i < 30; i++) {
            content.append(' ').append(20 + i * 5).append(" 100 1.5 1.5 re f");
        }
        assertEquals(0, read(content.toString()).fills().size());
    }

    private static PageGraphics read(String content) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(200, 200));
            page.setResources(new PDResources());
            document.addPage(page);
            PDStream stream = new PDStream(document);
            try (OutputStream out = stream.createOutputStream()) {
                out.write(content.getBytes(StandardCharsets.ISO_8859_1));
            }
            page.setContents(stream);
            return GraphicsCollector.read(page, PageReader.displayTransform(page.getCropBox(), 0), 200, 200,
                    new ParsedStreams());
        }
    }
}
