package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.topdf.pdf.Stroke;

class FootnoteSplitTest {

    @Test
    void aNoteWhoseLinesAllFitStaysWholeThoughItsSpaceAfterDoesNot() {
        PageBox.NotePart part = note(25, 10, 10);
        PageBox.NotePart[] pieces = FootnoteFlow.splitNote(part, 21, false);
        assertSame(part, pieces[0]);
        assertNull(pieces[1]);
    }

    @Test
    void aNoteBreaksAfterTheLinesThatFit() {
        PageBox.NotePart[] pieces = FootnoteFlow.splitNote(note(30, 10, 10, 10), 21, false);
        assertEquals(2, pieces[0].strips().size());
        assertEquals(1, pieces[1].strips().size());
        assertEquals(0, pieces[1].strips().get(0).y);
    }

    private static PageBox.NotePart note(float height, float... lines) {
        List<Placed> strips = new ArrayList<>();
        float y = 0;
        for (float h : lines) {
            Strip s = new Strip();
            s.height = h;
            s.ops.add(new Op.Line(0, 0, 10, 0, Stroke.solid(0.5f, Color.BLACK)));
            strips.add(new Placed(s, 0, y, 0, 0, 0));
            y += h;
        }
        return new PageBox.NotePart(null, strips, height, false);
    }
}
