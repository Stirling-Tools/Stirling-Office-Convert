package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
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
    void recorderKeepsOnlyTheOperatorsTheStreamItselfHolds() {
        ParsedStreams parsed = new ParsedStreams();
        StreamRecorder recorder = new StreamRecorder(parsed);
        COSDictionary key = new COSDictionary();
        List<COSBase> operands = List.of(new COSFloat(2f));

        recorder.begin(key, true);
        recorder.operator(op("TL"), operands);
        recorder.enter();
        recorder.operator(op("T*"), List.of());
        recorder.leave();
        recorder.end(true);

        assertArrayEquals(new Object[] {operands.getFirst(), op("TL")}, parsed.take(key));
        assertArrayEquals(new Object[] {operands.getFirst(), op("TL")}, parsed.take(key));
    }

    @Test
    void abandonedStreamsAreNotKept() {
        ParsedStreams parsed = new ParsedStreams();
        StreamRecorder recorder = new StreamRecorder(parsed);
        COSDictionary broken = new COSDictionary();
        COSDictionary inline = new COSDictionary();

        recorder.begin(broken, true);
        recorder.operator(op("q"), List.of());
        recorder.end(false);
        recorder.begin(inline, true);
        recorder.operator(op("BI"), List.of());
        recorder.end(true);

        assertNull(parsed.take(broken));
        assertEquals(1, parsed.take(inline).length);
        assertNull(parsed.take(inline));
    }

    private static Operator op(String name) {
        return Operator.getOperator(name);
    }
}
