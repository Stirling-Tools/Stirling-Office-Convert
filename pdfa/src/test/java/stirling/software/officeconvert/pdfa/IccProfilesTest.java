package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;

import org.junit.jupiter.api.Test;

class IccProfilesTest {

    @Test
    void theGeneratedSrgbProfileIsAVersionTwoDisplayProfile() {
        byte[] p = IccProfiles.srgb();
        IccProfiles.Header h = IccProfiles.header(p);
        assertEquals(p.length, h.size());
        assertEquals(2, h.major());
        assertEquals("mntr", h.deviceClass());
        assertEquals("RGB ", h.colourSpace());
        ICC_Profile icc = ICC_Profile.getInstance(p);
        assertEquals(ColorSpace.TYPE_RGB, icc.getColorSpaceType());
    }

    @Test
    void itMapsWhiteAndBlackLikeSrgb() {
        ColorSpace cs = new ICC_ColorSpace(ICC_Profile.getInstance(IccProfiles.srgb()));
        float[] white = ColorSpace.getInstance(ColorSpace.CS_sRGB).fromCIEXYZ(cs.toCIEXYZ(new float[] {1, 1, 1}));
        float[] mid = ColorSpace.getInstance(ColorSpace.CS_sRGB).fromCIEXYZ(cs.toCIEXYZ(new float[] {0.5f, 0.2f, 0.8f}));
        assertArrayEquals(new float[] {1, 1, 1}, white, 0.02f);
        assertArrayEquals(new float[] {0.5f, 0.2f, 0.8f}, mid, 0.03f);
    }

    @Test
    void grayAndCmykProfilesAreValidToo() throws Exception {
        assertEquals("GRAY", IccProfiles.header(IccProfiles.gray()).colourSpace());
        assertEquals("CMYK", IccProfiles.header(IccProfiles.cmyk()).colourSpace());
        assertEquals(2, IccProfiles.header(IccProfiles.cmyk()).major());
        assertEquals(ColorSpace.TYPE_GRAY, ICC_Profile.getInstance(IccProfiles.gray()).getColorSpaceType());
    }
}
