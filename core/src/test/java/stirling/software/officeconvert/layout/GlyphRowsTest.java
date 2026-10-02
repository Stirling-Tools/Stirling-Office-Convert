package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;

class GlyphRowsTest {

    private static final FontInfo NASTALIQ = new FontInfo("Arial", false, false, false, false, false, "NotoNastaliqUrdu", true);

    private static void row(List<Glyph> out, float y, float x, int n) {
        for (int i = 0; i < n; i++) {
            out.add(new Glyph("\u0628", x + i * 6, 6, y, 11, 8, 3, NASTALIQ, 0, out.size(), 2.5f, false, false));
        }
    }

    @Test
    void nastaliqCascadesJoinTheLineTheyRiseFrom() {
        List<Glyph> ink = new ArrayList<>();
        row(ink, 182, 100, 10);
        row(ink, 196, 100, 10);
        row(ink, 189, 200, 4);
        List<GlyphRows.Row> rows = GlyphRows.of(ink, List.of());
        assertEquals(2, rows.size());
        assertEquals(10, rows.getFirst().glyphs.size());
        assertEquals(14, rows.getLast().glyphs.size(), "the cascade above the second line belongs to it");
    }

    @Test
    void naskhLinesKeepTheirOwnRows() {
        List<Glyph> ink = new ArrayList<>();
        row(ink, 182, 100, 10);
        row(ink, 183.5f, 170, 3);
        row(ink, 196, 100, 10);
        List<GlyphRows.Row> rows = GlyphRows.of(ink, List.of());
        assertEquals(2, rows.size());
        assertEquals(13, rows.getFirst().glyphs.size());
    }
}
