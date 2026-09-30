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
    void parsesWorldScriptMarkers() {
        assertMarker(Marker.Kind.HEBREW, 2, "\u05D1.");
        assertMarker(Marker.Kind.HEBREW, 11, "\u05D9\u05D0.");
        assertMarker(Marker.Kind.ARABIC_ABJAD, 3, "\u062C.");
        assertMarker(Marker.Kind.ARABIC_ALPHA, 3, "\u062A.");
        assertMarker(Marker.Kind.CHINESE, 12, "\u5341\u4E8C\u3001");
        assertMarker(Marker.Kind.CHINESE, 3, "\uFF08\u4E09\uFF09");
        assertMarker(Marker.Kind.FULL_WIDTH, 3, "\uFF13\uFF0E");
        assertMarker(Marker.Kind.GANADA, 3, "\uB2E4.");
        assertNull(Marker.parse("\u05D1\u05D0."));
        assertNull(Marker.parse("\u05E9\u05DC\u05D5\u05DD."));
        assertNull(Marker.parse("\u4E00"));
        assertEquals("hebrew1", Marker.parse("\u05D0.").wordFormat());
        assertEquals("chineseCounting", Marker.parse("\u4E00.").wordFormat());
    }

    private static void assertMarker(Marker.Kind kind, int value, String word) {
        Marker m = Marker.parse(word);
        assertNotNull(m, word);
        assertEquals(kind, m.kind(), word);
        assertEquals(value, m.value(), word);
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
