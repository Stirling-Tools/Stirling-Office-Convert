package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.extract.PageGraphics.Fill;

class VectorBulletsBudgetTest {

    private static final FontInfo FONT = new FontInfo("Arial", false, false, false, false, false, "ArialMT", false);

    @Test
    void manySmallFillsAreCheckedQuickly() {
        List<Fill> fills = new ArrayList<>();
        for (int i = 0; i < 80_000; i++) {
            float x = (i % 400) * 20f;
            float top = (i / 400) * 20f;
            fills.add(new Fill(x, top, x + 4, top + 4, 0));
        }
        List<Glyph> out = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> VectorBullets.synthesize(new ArrayList<>(), List.of(), fills, new HashSet<>()));
        assertTrue(out.isEmpty());
    }

    @Test
    void aSquareAfterAnotherIsNotABullet() {
        List<Glyph> glyphs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            glyphs.add(new Glyph(String.valueOf("Item".charAt(i)), 90 + i * 5, 5, 104, 10, 7.5f, 2.5f, FONT, 0, i, 3,
                    false, false));
        }
        Fill lone = new Fill(80, 97, 84, 101, 0);
        assertEquals(1, VectorBullets.synthesize(glyphs, List.of(), List.of(lone), new HashSet<>()).size());
        Fill before = new Fill(74, 97, 78.5f, 101, 0);
        assertEquals(1, VectorBullets.synthesize(glyphs, List.of(), List.of(before, lone), new HashSet<>()).size());
    }
}
