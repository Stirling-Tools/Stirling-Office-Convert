package stirling.software.officeconvert.sheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class TextWidthTest {

    private record Fit(String text, float size, boolean bold, float points) {}

    private static final List<Fit> EXCEL = List.of(
            new Fit("0.908893877", 5, false, 35.25f), new Fit("0.908893877", 5, true, 46.5f),
            new Fit("0.908893877", 6, false, 42.75f), new Fit("0.908893877", 6, true, 52.5f),
            new Fit("0.908893877", 7, false, 44.25f), new Fit("0.908893877", 7, true, 54f),
            new Fit("0.908893877", 7.5f, false, 52.5f), new Fit("0.908893877", 7.5f, true, 62.25f),
            new Fit("0.908893877", 8, false, 52.5f), new Fit("0.908893877", 8, true, 52.5f),
            new Fit("0.908893877", 9, true, 52.5f), new Fit("0.908893877", 10, false, 60f),
            new Fit("0.908893877", 11, false, 60.75f), new Fit("0.908893877", 12, true, 69f),
            new Fit("0.809", 7.5f, true, 30.75f), new Fit("+30484", 7.5f, true, 38.25f),
            new Fit("(1,234.56)", 7.5f, true, 48.75f), new Fit("$12,400.50", 6.5f, true, 46.5f),
            new Fit("12", 14, false, 21.75f), new Fit("(12.5%)", 20, false, 72f), new Fit("-1,234.56", 24, true, 102f),
            new Fit("2024-01-05", 7.5f, true, 56.25f), new Fit("12-DEC-2023", 7, true, 55.5f),
            new Fit("10:30 AM", 7.5f, true, 44.25f), new Fit("January 5, 2024", 7.5f, true, 74.25f),
            new Fit("Monday, March 4, 2024", 9, false, 97.5f),
            new Fit("Wednesday, September 27, 2023", 9, false, 138.75f));

    @Test
    void valuesGetAtLeastExcelsOwnFit() {
        for (Fit f : EXCEL) {
            float estimate = TextWidth.ofValue(f.text(), f.size(), f.bold());
            assertTrue(estimate >= f.points(), f + ": " + estimate);
            assertTrue(estimate <= f.points() * 1.6f, f + " is far too wide: " + estimate);
        }
    }

    @Test
    void wrappedLinesCountBreaksAndWidth() {
        assertEquals(2, TextWidth.lines("one\ntwo", 11, false, 200));
        assertTrue(TextWidth.lines("word ".repeat(40), 11, false, 100) >= 8);
        assertEquals(15f, TextWidth.lineHeight(11), 0.01f, "the default row height");
    }
}
