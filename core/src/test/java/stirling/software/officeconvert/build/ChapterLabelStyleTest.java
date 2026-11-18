package stirling.software.officeconvert.build;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.layout.DocStats;
import stirling.software.officeconvert.layout.LineBuilder;
import stirling.software.officeconvert.layout.ParaDraft;
import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.StyleSheet;

class ChapterLabelStyleTest {

    private static final FontInfo TIMES = new FontInfo("Times New Roman", true, false, true, false, false, "NimbusRomNo9L-Medi", true);

    private static String style(DocStats stats, String text, float size) {
        List<Glyph> glyphs = new ArrayList<>();
        float x = 72;
        for (char c : text.toCharArray()) {
            glyphs.add(new Glyph(String.valueOf(c), x, size * 0.5f, 100, size, size * 0.7f, size * 0.2f, TIMES, 0,
                    glyphs.size(), size * 0.25f, true, false));
            x += size * 0.5f;
        }
        ParaDraft d = new ParaDraft(72, 540);
        d.lines.addAll(LineBuilder.build(glyphs));
        RunStyle normal = new RunStyle("Times New Roman", 11, false, false, false, false, 0, -1, 0, false);
        ParagraphFactory factory = new ParagraphFactory(stats, new RunBuilder(true, stats), new StyleSheet(normal),
                new Numbering());
        return factory.paragraph(d, 72, 540, 0, false).style;
    }

    @Test
    void recurringChapterLabelsAreHeadingsNotTitles() {
        DocStats stats = new DocStats();
        stats.bodySize = 11;
        stats.tiers = List.of(new DocStats.Tier(24, true, 1, 0, 0, false, true),
                new DocStats.Tier(20, true, 5, 1, 9, true, false));
        assertEquals("Heading1", style(stats, "Chapter 3", 20));
        assertEquals("Title", style(stats, "A Study of Things", 24));
    }
}
