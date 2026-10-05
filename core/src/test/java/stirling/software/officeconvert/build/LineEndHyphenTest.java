package stirling.software.officeconvert.build;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.layout.DocStats;
import stirling.software.officeconvert.layout.LineBuilder;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;

class LineEndHyphenTest {

    private static final FontInfo TIMES = new FontInfo("Times New Roman", false, false, true, false, false, "NimbusRomNo9L-Regu", true);

    private static String joined(String... rows) {
        List<Glyph> glyphs = new ArrayList<>();
        for (int r = 0; r < rows.length; r++) {
            float x = 72;
            for (char c : rows[r].toCharArray()) {
                glyphs.add(new Glyph(String.valueOf(c), x, 5f, 100 + 12 * r, 10, 7f, 2f, TIMES, 0, glyphs.size(), 2.5f,
                        false, false));
                x += 5f;
            }
        }
        Paragraph p = new Paragraph();
        new RunBuilder(true, new DocStats()).fill(p, LineBuilder.build(glyphs), 0, 72, 540, 10);
        StringBuilder sb = new StringBuilder();
        for (Inline in : p.inlines) {
            if (in instanceof Inline.Text t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    @Test
    void aCompoundBrokenAtItsHyphenKeepsIt() {
        assertEquals("the WMT 2014 English-to-German task", joined("the WMT 2014 English-", "to-German task"));
    }

    @Test
    void aSyllableBreakStillJoinsTheWord() {
        assertEquals("sequence trans­duction models", joined("sequence trans-", "duction models"));
    }
}
