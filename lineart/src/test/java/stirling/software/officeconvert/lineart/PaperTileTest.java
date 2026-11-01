package stirling.software.officeconvert.lineart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PaperTileTest {

    @Test
    void aTinyWindowOnALargePageGetsTilesFewEnoughToLevel() {
        int tile = Paper.tile(2480, 3508, 3);
        assertTrue((long) Math.ceilDiv(2480, tile) * Math.ceilDiv(3508, tile) <= 1 << 16, "tile " + tile);
    }

    @Test
    void ordinaryPagesKeepTheirWindowAsTheTile() {
        assertEquals(3, Paper.tile(211, 157, 3));
        assertEquals(25, Paper.tile(2480, 3508, 25));
    }
}
