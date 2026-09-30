package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.topdf.dml.Numerals;

class WorldNumbersTest {

    @Test
    void listNumbersUseTheirOwnNumberingSystems() {
        assertEquals("\u05D8\u05D5", NumberFormat.format(15, "hebrew1"));
        assertEquals("\u05D2", NumberFormat.format(3, "hebrew1"));
        assertEquals("\u4E00\u767E\u96F6\u4E94", NumberFormat.format(105, "chineseCounting"));
        assertEquals("\u5341\u4E8C", NumberFormat.format(12, "chineseCounting"));
        assertEquals("\u0E51\u0E52", NumberFormat.format(12, "thaiNumbers"));
        assertEquals("\u062C", NumberFormat.format(3, "arabicAbjad"));
        assertEquals("\u0967\u0969", NumberFormat.format(13, "hindiNumbers"));
        assertEquals("\uB098", NumberFormat.format(2, "ganada"));
        assertEquals("\u0627\u0627", NumberFormat.format(29, "arabicAlpha"));
        assertEquals("\u0969.", Numerals.autoNumber("hindiNumPeriod", 3));
        assertEquals("\u4E8C.", Numerals.autoNumber("ea1ChsPeriod", 2));
        assertEquals("\u4E8C\uFF0E", Numerals.autoNumber("ea1JpnChsDbPeriod", 2));
        assertEquals("\u05D0-", Numerals.autoNumber("hebrew2Minus", 1));
        assertEquals("\u0E52.", Numerals.autoNumber("thaiNumPeriod", 2));
        assertEquals("\u0628-", Numerals.autoNumber("arabic1Minus", 2));
        assertNull(Numerals.autoNumber("arabicPeriod", 2));
    }
}
