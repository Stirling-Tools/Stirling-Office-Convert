package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.PageGraphics.Rule;

class StrokedRulesTest {

    private interface Drawing {
        void draw(PDPageContentStream cs) throws IOException;
    }

    private static List<Rule> rules(Drawing drawing) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                drawing.draw(cs);
            }
            return GraphicsCollector.read(page, PageReader.displayTransform(page.getCropBox(), 0), 612, 792,
                    new ParsedStreams()).rules();
        }
    }

    @Test
    void aRuleIsAsThickAsItsStrokeAcrossTheLineUnderUnevenScaling() throws IOException {
        List<Rule> rules = rules(cs -> {
            cs.saveGraphicsState();
            cs.transform(new Matrix(1, 0, 0, 4, 0, 0));
            cs.setLineWidth(0.5f);
            cs.moveTo(100, 50);
            cs.lineTo(400, 50);
            cs.stroke();
            cs.restoreGraphicsState();
            cs.transform(new Matrix(4, 0, 0, 1, 0, 0));
            cs.setLineWidth(0.5f);
            cs.moveTo(120, 300);
            cs.lineTo(120, 600);
            cs.stroke();
        });
        assertEquals(2, rules.size());
        for (Rule r : rules) {
            assertEquals(2f, r.thickness(), 0.01f, r.toString());
        }
    }

    @Test
    void aBorderOnTheEdgeOfItsClipKeepsOnlyTheVisibleHalf() throws IOException {
        List<Rule> rules = rules(cs -> {
            cs.addRect(100, 100, 300, 300);
            cs.clip();
            cs.setLineWidth(3);
            cs.moveTo(100, 100);
            cs.lineTo(400, 100);
            cs.stroke();
            cs.moveTo(100, 250);
            cs.lineTo(400, 250);
            cs.stroke();
        });
        assertEquals(2, rules.size());
        Rule edge = rules.stream().filter(r -> r.pos() > 600).findFirst().orElseThrow();
        Rule inside = rules.stream().filter(r -> r.pos() < 600).findFirst().orElseThrow();
        assertEquals(1.5f, edge.thickness(), 0.01f);
        assertEquals(691.25f, edge.pos(), 0.01f);
        assertEquals(3f, inside.thickness(), 0.01f);
        assertTrue(inside.horizontal() && edge.horizontal());
    }
}
