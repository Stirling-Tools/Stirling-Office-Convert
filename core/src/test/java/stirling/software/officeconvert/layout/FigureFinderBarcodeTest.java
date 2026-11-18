package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.PageGraphics.Fill;
import stirling.software.officeconvert.extract.PageGraphics.Rule;

class FigureFinderBarcodeTest {

    @Test
    void widelySpacedBarsGroupIntoOneBarcode() {
        List<Fill> fills = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            fills.add(new Fill(100 + i * 7, 200, 102 + i * 7, 234, 0));
        }
        List<Box> found = FigureFinder.figures(List.of(), fills, List.of(), List.of(), List.of(), 612, 792);
        assertEquals(1, found.size());
        assertTrue(found.getFirst().width() >= 270);
    }

    @Test
    void aShortRuledGridIsNotPictured() {
        List<Rule> rules = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            rules.add(new Rule(false, 100 + i * 5, 100, 130, 0.5f, 0));
        }
        rules.add(new Rule(true, 100, 100, 200, 0.5f, 0));
        rules.add(new Rule(true, 115, 100, 200, 0.5f, 0));
        rules.add(new Rule(true, 130, 100, 200, 0.5f, 0));
        assertTrue(FigureFinder.figures(List.of(), List.of(), rules, List.of(), List.of(), 612, 792).isEmpty());
    }
}
