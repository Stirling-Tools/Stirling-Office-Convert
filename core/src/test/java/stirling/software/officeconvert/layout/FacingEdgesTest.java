package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.PageGraphics.Rule;

class FacingEdgesTest {

    @Test
    void manyLongVerticalRulesStayCheap() {
        List<Rule> rules = new ArrayList<>();
        for (int i = 0; i < 6000; i++) {
            float x = 200 + (i % 30) * 2.5f;
            float y = 100 + (i / 30) * 0.37f;
            rules.add(new Rule(false, x, y, y + 500, 0.5f, 0));
        }
        List<ColumnFinder.Item> items = List.of(new ColumnFinder.Item(20, 380, 120, 392, 390, true, 15),
                new ColumnFinder.Item(400, 380, 500, 392, 390, true, 16));
        assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> ColumnFinder.find(items, rules, 20, 590, 792, 10));
    }
}
