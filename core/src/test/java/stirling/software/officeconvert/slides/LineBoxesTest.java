package stirling.software.officeconvert.slides;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LineBoxesTest {

    @Test
    void singleSpacingSplitsInTheFontsProportion() {
        assertEquals(19.45f, LineBoxes.baseline(24, 20, "Arial"), 0.15f);
        assertEquals(18.73f, LineBoxes.baseline(24, 20, "Calibri"), 0.15f);
        assertEquals(19.81f, LineBoxes.baseline(24, 20, "Verdana"), 0.15f);
    }

    @Test
    void tighterBoxesKeepTheDescentBelow() {
        assertEquals(17.41f, LineBoxes.baseline(22, 20, "Arial"), 0.15f);
        assertEquals(16.69f, LineBoxes.baseline(22, 20, "Calibri"), 0.15f);
        assertEquals(12.01f, LineBoxes.baseline(16, 20, "Arial"), 0.15f);
    }

    @Test
    void tallerBoxesPutTheBaselineThreeQuartersDown() {
        assertEquals(19.57f, LineBoxes.baseline(26, 20, "Arial"), 0.15f);
        assertEquals(30.06f, LineBoxes.baseline(40, 20, "Calibri"), 0.15f);
    }

    @Test
    void aHairOverSingleIsSingle() {
        assertEquals(21.6f, LineBoxes.settle(21.63f, 18), 0.001f);
        assertEquals(22.5f, LineBoxes.settle(22.5f, 18), 0.001f);
    }
}
