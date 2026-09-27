package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;

class MarkerTest {

    @Test
    void parsesBullets() {
        assertTrue(Marker.parse("•").isBullet());
        assertTrue(Marker.parse("–").isBullet());
        assertTrue(Marker.parse(String.valueOf((char) 0xF0B7)).isBullet());
    }

    @Test
    void parsesNumberedMarkers() {
        Marker m = Marker.parse("12.");
        assertEquals(Marker.Kind.DECIMAL, m.kind());
        assertEquals(12, m.value());
        assertEquals(".", m.suffix());

        Marker letter = Marker.parse("(b)");
        assertEquals(Marker.Kind.LOWER_LETTER, letter.kind());
        assertEquals(2, letter.value());
        assertEquals("(", letter.prefix());

        Marker roman = Marker.parse("IV.");
        assertEquals(Marker.Kind.UPPER_ROMAN, roman.kind());
        assertEquals(4, roman.value());
    }

    @Test
    void rejectsWordsThatAreNotMarkers() {
        assertNull(Marker.parse("hello"));
        assertNull(Marker.parse(""));
        assertNull(Marker.parse(null));
        assertNull(Marker.parse("12"));
        assertNull(Marker.parse("0."));
        assertNull(Marker.parse("(a]"));
        assertNull(Marker.parse("iiii."));
    }

    @Test
    void acceptsWordsSecondLevelBulletOnlyInMonoOrSymbolFonts() {
        FontInfo courier =
                new FontInfo("Courier New", false, false, false, true, false, "CourierNewPSMT", false);
        assertNotNull(Marker.parse("o", courier));
        assertNull(Marker.parse("o", FontInfo.DEFAULT));
    }

    @Test
    void romanNumeralsRoundTrip() {
        for (int n = 1; n < 40; n++) {
            assertEquals(n, Marker.roman(Marker.toRoman(n)));
        }
    }
}
