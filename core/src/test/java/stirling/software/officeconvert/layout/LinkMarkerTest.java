package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.extract.PageData;

class LinkMarkerTest {

    private static final FontInfo FONT = new FontInfo("Arial", false, false, false, false, false, "Arial", false);

    private static final float[] SPECIAL = {0f, -0f, 0.5f, -0.5f, 1f, -1f, Float.NaN, Float.POSITIVE_INFINITY,
        Float.NEGATIVE_INFINITY, Float.MAX_VALUE, -Float.MAX_VALUE, Float.MIN_VALUE};

    @Test
    void marksTheSameLinksAsComparingEveryPair() {
        Random random = new Random(7);
        for (int round = 0; round < 3_000; round++) {
            int links = 1 + random.nextInt(round % 10 == 0 ? 200 : 12);
            int glyphs = 1 + random.nextInt(round % 10 == 0 ? 400 : 30);
            List<PageData.Link> ls = new ArrayList<>();
            for (int i = 0; i < links; i++) {
                float x = coordinate(random);
                float top = coordinate(random);
                float right = random.nextInt(8) == 0 ? coordinate(random) : x + random.nextInt(6) * 0.5f;
                float bottom = random.nextInt(8) == 0 ? coordinate(random) : top + random.nextInt(4);
                ls.add(new PageData.Link(x, top, right, bottom, random.nextBoolean() ? "u" + i : null, i));
            }
            List<Glyph> expected = new ArrayList<>();
            List<Glyph> actual = new ArrayList<>();
            for (int i = 0; i < glyphs; i++) {
                float x = coordinate(random);
                float width = random.nextInt(3);
                float baseline = coordinate(random);
                float size = random.nextInt(3) * 10 / 3f;
                expected.add(glyph(x, width, baseline, size));
                actual.add(glyph(x, width, baseline, size));
            }
            everyPair(expected, ls);
            LinkMarker.mark(actual, ls);
            for (int i = 0; i < glyphs; i++) {
                assertEquals(expected.get(i).link, actual.get(i).link, "round " + round + " glyph " + i);
            }
        }
    }

    @Test
    void keepsAnExistingLinkWhenNoRectangleHolds() {
        Glyph g = glyph(10, 2, 10, 10);
        g.link = "kept";
        LinkMarker.mark(List.of(g), List.of(new PageData.Link(100, 100, 120, 110, "other", -1)));
        assertEquals("kept", g.link);
        Glyph h = glyph(10, 2, 10, 10);
        LinkMarker.mark(List.of(h), List.of(new PageData.Link(Float.NaN, 0, 20, 20, "nan", -1)));
        assertNull(h.link);
    }

    @Test
    void manyStackedLinksAndGlyphsStayFast() {
        List<PageData.Link> links = new ArrayList<>();
        for (int i = 0; i < 50_000; i++) {
            links.add(new PageData.Link(i % 600 * 0.01f, 0, i % 600 * 0.01f + 0.001f, 800, "https://e.x/" + i, -1));
        }
        links.add(new PageData.Link(300, 0, 320, 800, "https://e.x/last", -1));
        List<Glyph> glyphs = new ArrayList<>();
        for (int i = 0; i < 200_000; i++) {
            glyphs.add(glyph(10 + i % 1000 * 0.5f, 0.4f, 10 + i / 1000 * 3.8f, 1));
        }
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> LinkMarker.mark(glyphs, links));
        long marked = glyphs.stream().filter(g -> "https://e.x/last".equals(g.link)).count();
        assertEquals(200L * 42, marked);
    }

    private static float coordinate(Random random) {
        return random.nextInt(20) == 0 ? SPECIAL[random.nextInt(SPECIAL.length)] : random.nextInt(40) * 0.5f;
    }

    private static Glyph glyph(float x, float width, float baseline, float size) {
        return new Glyph("a", x, width, baseline, size, size * 0.8f, size * 0.2f, FONT, 0, 0, size / 4, false, false);
    }

    private static void everyPair(List<Glyph> glyphs, List<PageData.Link> links) {
        for (Glyph g : glyphs) {
            float cx = g.centreX();
            float cy = g.baseline - g.size * 0.3f;
            for (PageData.Link l : links) {
                if (cx >= l.x() - 0.5f && cx <= l.right() + 0.5f && cy >= l.top() - 1 && cy <= l.bottom() + 1) {
                    g.link = l.uri() != null ? l.uri() : "#page" + l.targetPage();
                    break;
                }
            }
        }
    }
}
