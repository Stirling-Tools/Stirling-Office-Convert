package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.extract.PageGraphics;
import stirling.software.officeconvert.extract.PageGraphics.VectorMark;

class OutlinedWordsTest {

    private static final FontInfo FONT = new FontInfo("Arial", false, false, false, false, false, "Arial", false);

    @Test
    void chainedLetteringTakesTheFirstDrawnNeighboursAnchor() {
        for (int run = 0; run < 24; run++) {
            Glyph left = new Glyph("a", 0, 6, 100, 10, 7, 2, FONT, 0, 0, 3, false, false);
            Glyph right = new Glyph("b", 55, 6, 103, 10, 7, 2, FONT, 0, 1, 3, false, false);
            VectorMark first = mark(10, 20);
            VectorMark second = mark(40, 50);
            VectorMark between = mark(25, 35);
            List<VectorMark> marks = List.of(first, second, between);
            Map<Object, Shape> lettering = new HashMap<>();
            for (VectorMark m : marks) {
                lettering.put(m, new Rectangle2D.Float(m.x(), m.top(), m.width(), m.height()));
            }
            PageGraphics gfx = new PageGraphics(List.of(), List.of(), List.of(), marks, List.of(), Map.of(), Set.of(),
                    Map.of(), List.of(), lettering);
            List<Glyph> out = OutlinedWords.synthesize(List.of(left, right), gfx, new HashSet<>(), 595 * 842);
            assertEquals(3, out.size());
            assertEquals(100f, out.get(0).baseline);
            assertEquals(103f, out.get(1).baseline);
            assertEquals(100f, out.get(2).baseline);
        }
    }

    private static VectorMark mark(float x, float right) {
        return new VectorMark(x, 93, right, 101, 4, false, false, true, false, 0, false, 0, 0, false);
    }
}
