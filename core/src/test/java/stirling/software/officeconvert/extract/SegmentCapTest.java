package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

class SegmentCapTest {

    @Test
    void tooManyLinesOnAPageAreRefusedLikeTooManyGlyphs() throws IOException {
        assertThrows(PageTooComplexException.class, () -> rules(50_001));
    }

    @Test
    void linesUpToTheCapAreKept() throws IOException {
        assertEquals(2_000, rules(2_000));
    }

    private static int rules(int count) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                cs.setLineWidth(0.1f);
                for (int i = 0; i < count; i++) {
                    float x = 20 + (i % 50) * 11;
                    float y = 20 + (i / 50) * 0.75f;
                    cs.moveTo(x, y);
                    cs.lineTo(x + 8, y);
                    cs.stroke();
                }
            }
            return GraphicsCollector.read(page, PageReader.displayTransform(page.getCropBox(), 0), 612, 792,
                    new ParsedStreams()).rules().size();
        }
    }
}
