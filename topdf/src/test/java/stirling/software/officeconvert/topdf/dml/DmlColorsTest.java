package stirling.software.officeconvert.topdf.dml;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Color;

import org.junit.jupiter.api.Test;

class DmlColorsTest {

    @Test
    void shadesDarkenInLinearLightAsOfficeDoes() {
        assertEquals(new Color(0x2F528F), DmlColors.modify(new Color(0x4472C4), "shade", 50000));
    }

    @Test
    void tintsLightenInLinearLightAsOfficeDoes() {
        assertEquals(new Color(208, 216, 232), DmlColors.modify(new Color(0x4F81BD), "tint", 40000));
    }

    @Test
    void fullTintsAndShadesKeepTheColourAndAlpha() {
        Color c = new Color(10, 200, 30, 128);
        assertEquals(c, DmlColors.modify(c, "tint", 100000));
        assertEquals(c, DmlColors.modify(c, "shade", 100000));
        assertEquals(new Color(255, 255, 255, 128), DmlColors.modify(c, "tint", 0));
        assertEquals(new Color(0, 0, 0, 128), DmlColors.modify(c, "shade", 0));
    }
}
