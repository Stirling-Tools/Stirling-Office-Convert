package stirling.software.officeconvert.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.layout.DocStats;
import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.LineBuilder;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;

class TightLastLineTest {

    private static final FontInfo TIMES = new FontInfo("Times New Roman", false, false, true, false, false, "NimbusRomNo9L-Regu", true);

    private static final float EDGE = 300;

    private static final String[] ROWS = {
        "the quarterly region approves the sample alongside",
        "the final protocol through the optional survey and",
        "sult, the moderate dashboard delivers the threshold during the",
    };

    private static float width(String s) {
        return SubstituteMetrics.width(s, "Times New Roman", false, false, 10);
    }

    private static List<Line> lines(String last, boolean full) {
        List<Glyph> glyphs = new ArrayList<>();
        List<String> rows = List.of(ROWS[0], ROWS[1], last);
        for (int r = 0; r < rows.size(); r++) {
            String[] words = rows.get(r).split(" ");
            float ink = 0;
            for (String w : words) {
                ink += width(w);
            }
            float gap = r < 2 || full ? (EDGE - 72 - ink) / (words.length - 1) : width(" ");
            float x = 72;
            for (String w : words) {
                for (char c : w.toCharArray()) {
                    float cw = width(String.valueOf(c));
                    glyphs.add(new Glyph(String.valueOf(c), x, cw, 100 + 12 * r, 10, 7f, 2f, TIMES, 0, glyphs.size(),
                            width(" "), false, false));
                    x += cw;
                }
                x += gap;
            }
        }
        return LineBuilder.build(glyphs);
    }

    private static float lastLineSpacing(List<Line> lines) {
        Paragraph p = new Paragraph();
        new RunBuilder(true, new DocStats()).fill(p, lines, 0, 72, EDGE, 10, new BitSet(), new BitSet(), false,
                new RunBuilder.Fit(Float.NaN, 1.5f, true));
        Inline.Text last = null;
        for (Inline in : p.inlines) {
            if (in instanceof Inline.Text t && t.text().contains("threshold")) {
                last = t;
            }
        }
        return last == null ? Float.NaN : last.style().spacing();
    }

    @Test
    void aLastLineSetTighterThanItsNaturalWidthIsCondensedToFit() {
        List<Line> lines = lines(ROWS[2], true);
        assertEquals(3, lines.size());
        assertEquals(EDGE, lines.getLast().right, 0.01f);
        assertTrue(lastLineSpacing(lines) < 0, "the squeezed last line must be condensed");
    }

    @Test
    void aShortLastLineIsLeftAlone() {
        assertEquals(0f, lastLineSpacing(lines("the threshold during the", false)), 0.001f);
    }
}
