package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.extract.PageData;
import stirling.software.officeconvert.extract.PageGraphics;

class HeadingTierLimitTest {

    private static final FontInfo TIMES = new FontInfo("Times New Roman", false, false, true, false, false, "NimbusRomNo9L-Regu", true);

    private static float baseline;

    private static void line(List<Glyph> out, String text, float size, boolean bold) {
        baseline += size * 1.5f;
        float x = 72;
        for (char c : text.toCharArray()) {
            out.add(new Glyph(String.valueOf(c), x, size * 0.5f, baseline, size, size * 0.7f, size * 0.2f, TIMES, 0,
                    out.size(), size * 0.25f, bold, false));
            x += size * 0.5f;
        }
    }

    @Test
    void aChapterLabelTierLeavesRoomForSixHeadingTiers() {
        DocStats stats = new DocStats();
        for (int page = 0; page < 3; page++) {
            baseline = 0;
            List<Glyph> glyphs = new ArrayList<>();
            line(glyphs, "Chapter " + (page + 1), 22, true);
            float[] sizes = {20, 18, 16, 14, 13, 11.5f};
            for (int h = 0; h < sizes.length; h++) {
                line(glyphs, "Heading " + h, sizes[h], true);
                for (int b = 0; b < 4; b++) {
                    line(glyphs, "body text that fills the page so headings stay rare " + b, 11, false);
                }
            }
            PageData data = new PageData(page, 612, 792, 0, glyphs, List.of(), List.of(),
                    new PageGraphics(List.of(), List.of(), List.of(), List.of()), List.of());
            stats.add(data, LineBuilder.build(glyphs));
        }
        stats.finish(3);
        assertEquals(6, stats.headingLevel(11.5f, true));
    }
}
