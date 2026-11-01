package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.PageGraphics.Fill;
import stirling.software.officeconvert.extract.PageGraphics.Rule;

class BoxedLinesTest {

    @Test
    void aColumnOfBoxedLinesKeepsEveryEdgeWhenItHasMoreRulesThanTheShapeCap() {
        List<Rule> rules = new ArrayList<>();
        Map<Object, Integer> order = new IdentityHashMap<>();
        for (int i = 0; i < 60; i++) {
            float top = 50 + i * 12;
            for (Rule r : List.of(new Rule(true, top, 40, 560, 0.5f, 0), new Rule(true, top + 12, 40, 560, 0.5f, 0),
                    new Rule(false, 40, top, top + 12, 0.5f, 0), new Rule(false, 560, top, top + 12, 0.5f, 0))) {
                order.put(r, order.size());
                rules.add(r);
            }
        }
        List<PageLayout.Decoration> shown = Decorations.leftovers(List.of(), rules, List.of(), List.of(), List.of(),
                false, o -> order.getOrDefault(o, Integer.MAX_VALUE));
        assertEquals(63, shown.size());
        assertTrue(shown.stream().anyMatch(d -> d.box().width() < 1 && d.box().height() >= 719), shown.toString());
        assertTrue(shown.stream().allMatch(d -> d.order() < rules.size()));
    }

    @Test
    void rulesUnderTheShapeCapAreKeptAsDrawn() {
        List<Rule> rules = List.of(new Rule(true, 100, 40, 300, 0.5f, 0), new Rule(true, 100, 300, 560, 0.5f, 0));
        List<PageLayout.Decoration> shown = Decorations.leftovers(List.of(), rules, List.of(), List.of(), List.of(),
                false, o -> 0);
        assertEquals(2, shown.size());
    }

    @Test
    void aPageBackdropStaysWhenTheShapesOnTopOfItAreOverTheCap() {
        List<Fill> fills = new ArrayList<>();
        fills.add(new Fill(0, 0, 612, 792, 0x70D0E0));
        for (int i = 0; i < 200; i++) {
            float x = 20 + (i % 20) * 28;
            float y = 20 + (i / 20) * 28;
            fills.add(new Fill(x, y, x + 20, y + 20, 0x0000FF));
        }
        List<PageLayout.Decoration> shown = Decorations.leftovers(fills, List.of(), List.of(), List.of(), List.of(),
                false, o -> 0);
        assertEquals(1, shown.size());
        assertEquals(0x70D0E0, shown.getFirst().rgb());
        assertEquals(612, shown.getFirst().box().width(), 0.01f);
    }

    @Test
    void shapesOverTheCapBecomeOneFigurePerCluster() {
        List<Fill> fills = new ArrayList<>();
        fills.add(new Fill(0, 0, 612, 792, 0x70D0E0));
        for (int i = 0; i < 200; i++) {
            float x = 20 + (i % 20) * 28;
            float y = 20 + (i / 20) * 28;
            fills.add(new Fill(x, y, x + 20, y + 20, 0x0000FF));
        }
        List<Box> figures = Decorations.overCap(fills, List.of(), List.of(), List.of(), false, List.of());
        assertEquals(200, figures.size());
        assertEquals(20, figures.getFirst().width(), 0.01f);
        List<PageLayout.Decoration> shown = Decorations.leftovers(fills, List.of(), List.of(), figures, List.of(),
                false, o -> 0);
        assertEquals(1, shown.size());
    }

    @Test
    void shapesUnderTheCapMakeNoFigures() {
        List<Fill> fills = List.of(new Fill(20, 20, 40, 40, 0x0000FF), new Fill(60, 20, 80, 40, 0x0000FF));
        assertTrue(Decorations.overCap(fills, List.of(), List.of(), List.of(), false, List.of()).isEmpty());
    }
}
