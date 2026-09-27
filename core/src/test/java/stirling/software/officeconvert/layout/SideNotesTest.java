package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;

class SideNotesTest {

    private static final String BODY = "The unbalanced pre-fade insert point is a break in the signal path";

    private static Line line(String text, float x, float baseline, float size, boolean bold) {
        List<Glyph> glyphs = new ArrayList<>();
        float advance = size * 0.5f;
        for (char c : text.toCharArray()) {
            glyphs.add(new Glyph(String.valueOf(c), x, advance, baseline, size, size * 0.75f, size * 0.25f,
                    FontInfo.DEFAULT, 0, glyphs.size(), size * 0.25f, bold, false));
            x += advance;
        }
        return LineBuilder.build(glyphs).getFirst();
    }

    private static List<Line> page(float labelSize, boolean labelBold, String... labels) {
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            lines.add(line(BODY, 190, 100 + i * 14, 10, false));
        }
        for (int i = 0; i < labels.length; i++) {
            lines.add(line(labels[i], 70, 104 + i * 20, labelSize, labelBold));
        }
        return lines;
    }

    @Test
    void takesSmallMarginLabelsOutOfTheFlow() {
        List<Line> lines = page(7, false, "Signal +", "Signal -", "Screen");

        SideNotes.Result r = SideNotes.extract(lines);

        assertEquals(3, r.notes().size());
        assertEquals(6, lines.size());
        assertEquals(188.5f, r.textLeft(), 2f);
    }

    @Test
    void leavesSideHeadingsAndClauseNumbersInTheFlow() {
        assertTrue(SideNotes.extract(page(10, true, "Profile", "Education", "Skills")).notes().isEmpty());
        assertTrue(SideNotes.extract(page(7, false, "1.1.", "1.2.", "2.1.")).notes().isEmpty());
    }

    @Test
    void needsSeveralLabels() {
        assertTrue(SideNotes.extract(page(7, false, "Tip", "Ring")).notes().isEmpty());
    }
}
