package stirling.software.officeconvert.topdf.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CellValueTest {

    static String shown(String field) {
        CellValue v = CellValue.of(field);
        assertTrue(v.number(), field);
        return v.text();
    }

    static void text(String field) {
        CellValue v = CellValue.of(field);
        assertFalse(v.number(), field);
        assertEquals(field, v.text());
    }

    @Test
    void plainNumbersAreShownTheWayLibreOfficeShowsThem() {
        assertEquals("42", shown("42"));
        assertEquals("123", shown("00123"));
        assertEquals("5", shown("+5"));
        assertEquals("7", shown(" 7 "));
        assertEquals("0.5", shown(".5"));
        assertEquals("5", shown("5."));
        assertEquals("2.5", shown("2.50"));
        assertEquals("1234567", shown("1,234,567"));
        assertEquals("-1234", shown("-1,234"));
        assertEquals("12345.67", shown("12,345.67"));
        assertEquals("0", shown("-0"));
    }

    @Test
    void scientificAndLongNumbersFollowTheGeneralFormat() {
        assertEquals("15000000000", shown("1.5E+10"));
        assertEquals("1000000000000000", shown("1e15"));
        assertEquals("1234567890123456", shown("1234567890123456"));
        assertEquals("1E+016", shown("1e16"));
        assertEquals("1.23456789012346E+016", shown("12345678901234567"));
        assertEquals("9.00719925474099E+015", shown("9007199254740993"));
        assertEquals("1234567890123.46", shown("1234567890123.4567"));
        assertEquals("0.123456789012346", shown("0.1234567890123456"));
        assertEquals("0.3", shown("0.30000000000000004"));
        assertEquals("0.000000001", shown("1e-9"));
        assertEquals("1E-10", shown("1e-10"));
        assertEquals("1.5E-12", shown("1.5e-12"));
        assertEquals("1E+300", shown("1e300"));
        assertEquals("-1.5E+017", shown("-1.5e17"));
        assertEquals("1000000000000000", shown("999999999999999.9"));
    }

    @Test
    void anythingElseStaysText() {
        for (String s : new String[] {"1,00", "1,2345", "-1,5", "1 000", "1e", "e5", "1.2.3", "--5", "$12.50", "45%",
                "(12)", "TRUE", "inf", "NaN", "0x1F", "1/2", "1e309", "=1+2", "+1+1", "@cmd", "03/04/2021", "14:05"}) {
            text(s);
        }
    }

    @Test
    void onlyValidIsoDatesAreAlignedAsValues() {
        assertEquals("2026-09-30", shown("2026-09-30"));
        assertEquals("2026-09-30T14:05:00", shown("2026-09-30T14:05:00"));
        for (String s : new String[] {"2026-02-30", "2026-9-3", "2026-09-30T14:05", "2026-09-30T14:05:00Z",
                "2026-09-30 14:05:00", "2026-09-30T25:00:00", "0000-01-01"}) {
            text(s);
        }
    }
}
