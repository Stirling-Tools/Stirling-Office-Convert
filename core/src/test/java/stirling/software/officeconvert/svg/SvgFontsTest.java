package stirling.software.officeconvert.svg;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SvgFontsTest {

    @Test
    void codePointsABrowserWouldJoinToTheirNeighbourAreDrawnAsOutlines() {
        assertTrue(SvgFonts.shaped(0x1033));
        assertTrue(SvgFonts.shaped(0x0301));
        assertTrue(SvgFonts.shaped(0x200D));
        assertFalse(SvgFonts.shaped('A'));
        assertFalse(SvgFonts.shaped(0x0627));
        assertFalse(SvgFonts.shaped(0x4E2D));
    }
}
