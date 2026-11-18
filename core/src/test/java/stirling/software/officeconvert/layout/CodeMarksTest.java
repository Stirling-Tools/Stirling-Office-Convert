package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.PageGraphics.Fill;
import stirling.software.officeconvert.extract.PageGraphics.Rule;

class CodeMarksTest {

    @Test
    void aDenseRuledGridIsNotAMatrixCode() {
        List<Rule> rules = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            rules.add(new Rule(true, 100 + i * 4, 100, 160, 1f, 0));
            rules.add(new Rule(false, 100 + i * 4, 100, 160, 1f, 0));
        }
        assertTrue(CodeMarks.regions(rules, List.of()).isEmpty());
    }

    @Test
    void scatteredModulesStillReadAsAMatrixCode() {
        List<Fill> fills = new ArrayList<>();
        Random r = new Random(7);
        for (int y = 0; y < 25; y++) {
            for (int x = 0; x < 25; x++) {
                if (r.nextBoolean()) {
                    fills.add(new Fill(100 + x * 2.45f, 100 + y * 2.45f, 100 + (x + 1) * 2.45f, 100 + (y + 1) * 2.45f, 0));
                }
            }
        }
        assertEquals(1, CodeMarks.regions(List.of(), fills).size());
    }

    @Test
    void aShortRuledGridIsNotABarcode() {
        List<Rule> rules = dividers();
        rules.add(new Rule(true, 100, 100, 200, 0.5f, 0));
        rules.add(new Rule(true, 115, 100, 200, 0.5f, 0));
        rules.add(new Rule(true, 130, 100, 200, 0.5f, 0));
        assertTrue(CodeMarks.strips(rules, List.of()).isEmpty());
    }

    @Test
    void barsWithoutCrossingRulesAreABarcode() {
        assertEquals(1, CodeMarks.strips(dividers(), List.of()).size());
    }

    private static List<Rule> dividers() {
        List<Rule> rules = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            rules.add(new Rule(false, 100 + i * 5, 100, 130, 0.5f, 0));
        }
        return rules;
    }
}
