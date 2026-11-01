package stirling.software.officeconvert.lineart;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PaperTileTest {

    @Test
    void aSmallWindowStillUsesTilesOfAtLeastFifteenPixels() {
        assertEquals(Paper.lookahead(2480, 3508, 15), Paper.lookahead(2480, 3508, 3));
    }
}
