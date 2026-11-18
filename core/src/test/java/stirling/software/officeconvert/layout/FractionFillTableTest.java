package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.PageGraphics.Fill;
import stirling.software.officeconvert.extract.PageGraphics.Rule;

class FractionFillTableTest {

    @Test
    void aThickTableDividerCrossingAVerticalIsNotAFractionBar() {
        Fill divider = new Fill(100, 99, 160, 102, 0);
        Rule vertical = new Rule(false, 130, 80, 140, 0.5f, 0);
        List<Fill> out = TexMathGrowth.fractionFills(List.of(vertical), List.of(divider), List.of(), 14);
        assertFalse(out.contains(divider));
    }

    @Test
    void anIsolatedThickFillStaysAFractionBarCandidate() {
        Fill bar = new Fill(100, 99, 130, 102, 0);
        List<Fill> out = TexMathGrowth.fractionFills(List.of(), List.of(bar), List.of(), 14);
        assertTrue(out.contains(bar));
    }
}
