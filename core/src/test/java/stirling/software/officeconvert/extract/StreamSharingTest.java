package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.io.IOException;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

class StreamSharingTest {

    @Test
    void graphicsReplayTheRecordedOperatorsInsteadOfParsingAgain() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.setNonStrokingColor(Color.RED);
                content.addRect(100, 100, 200, 50);
                content.fill();
            }
            AffineTransform toDisplay = PageReader.displayTransform(page.getCropBox(), 0);
            ParsedStreams parsed = new ParsedStreams();
            parsed.put(page.getCOSObject(), new Object[] {
                COSInteger.get(0), COSInteger.get(0), COSInteger.get(1), op("rg"),
                COSInteger.get(10), COSInteger.get(20), COSInteger.get(30), COSInteger.get(40), op("re"), op("f")
            }, false);

            PageGraphics replayed = GraphicsCollector.read(page, toDisplay, 612, 792, parsed);
            PageGraphics parsedAgain = GraphicsCollector.read(page, toDisplay, 612, 792, parsed);

            assertEquals(1, replayed.fills().size());
            assertEquals(0x0000FF, replayed.fills().getFirst().rgb());
            assertEquals(10f, replayed.fills().getFirst().x(), 0.01f);
            assertEquals(1, parsedAgain.fills().size());
            assertEquals(0xFF0000, parsedAgain.fills().getFirst().rgb());
            assertEquals(100f, parsedAgain.fills().getFirst().x(), 0.01f);
        }
    }

    @Test
    void aParsedPageIsKeptOnceForTheNextPass() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.addRect(100, 100, 200, 50);
                content.fill();
            }
            ParsedStreams parsed = new ParsedStreams();
            GraphicsCollector.read(page, PageReader.displayTransform(page.getCropBox(), 0), 612, 792, parsed);

            Object[] kept = parsed.take(page.getCOSObject());
            assertEquals(6, kept.length);
            assertEquals(op("f"), kept[5]);
            assertNull(parsed.take(page.getCOSObject()));
        }
    }

    private static Operator op(String name) {
        return Operator.getOperator(name);
    }
}
