package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FontNamesTest {

    @Test
    void stripsSubsetTags() {
        assertEquals("Calibri-Bold", FontNames.stripSubset("ABCDEF+Calibri-Bold"));
        assertEquals("abcdef+Calibri", FontNames.stripSubset("abcdef+Calibri"));
        assertEquals("Calibri", FontNames.stripSubset("Calibri"));
    }

    @Test
    void resolvesCommonPostScriptNames() {
        assertEquals("Arial", FontNames.family(null, "ABCDEF+ArialMT"));
        assertEquals("Arial", FontNames.family(null, "Helvetica-Bold"));
        assertEquals("Times New Roman", FontNames.family(null, "TimesNewRomanPS-BoldItalicMT"));
        assertEquals("Courier New", FontNames.family(null, "Courier"));
        assertEquals("Segoe UI", FontNames.family(null, "SegoeUI-Bold"));
        assertEquals("Segoe UI Semibold", FontNames.family(null, "SegoeUI-Semibold"));
        assertEquals("Times New Roman", FontNames.family(null, "NimbusRomNo9L-Medi"));
        assertEquals("Calibri", FontNames.family(null, "BAAAAA+Carlito-Regular"));
        assertEquals("Cambria", FontNames.family(null, "Caladea-Bold"));
    }

    @Test
    void prefersTheDeclaredFamily() {
        assertEquals("Calibri", FontNames.family("Calibri", "XYZABC+Whatever"));
    }

    @Test
    void mapsTexFontsToOfficeStandIns() {
        assertEquals("Times New Roman", FontNames.family(null, "CMR10"));
        assertEquals("Courier New", FontNames.family(null, "CMTT10"));
        assertEquals("Arial", FontNames.family(null, "CMSS10"));
        assertEquals("Cambria Math", FontNames.family(null, "CMSY10"));
        assertTrue(FontNames.isTexFont("ABCDEF+CMR10"));
        assertFalse(FontNames.isTexFont("ArialMT"));
    }

    @Test
    void splitsUnknownCamelCaseFamilies() {
        assertEquals("Myriad Pro", FontNames.family(null, "MyriadPro-Regular"));
        assertEquals("ITC Avant Garde", FontNames.splitCamel("ITCAvantGarde"));
    }

    @Test
    void readsWeightAndSlantFromStyleSuffixes() {
        assertTrue(FontNames.styleIsBold("medi"));
        assertTrue(FontNames.styleIsBold("mediital"));
        assertTrue(FontNames.styleIsBold("bolditalicmt"));
        assertFalse(FontNames.styleIsBold("medium"));
        assertFalse(FontNames.styleIsBold("regular"));
        assertTrue(FontNames.styleIsItalic("mediital"));
        assertTrue(FontNames.styleIsItalic("oblique"));
        assertFalse(FontNames.styleIsItalic("bold"));
    }

    @Test
    void knowsWhichFamiliesOfficeShips() {
        assertTrue(FontNames.isOfficeFont("Calibri"));
        assertTrue(FontNames.isOfficeFont("Times New Roman"));
        assertFalse(FontNames.isOfficeFont("Myriad Pro"));
        assertFalse(FontNames.isOfficeFont(null));
    }
}
