package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

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
}
