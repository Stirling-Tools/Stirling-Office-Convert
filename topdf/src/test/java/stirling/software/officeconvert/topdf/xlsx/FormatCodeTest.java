package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.List;

import org.junit.jupiter.api.Test;

class FormatCodeTest {

    @Test
    void sectionsSplitOnSemicolonsOutsideQuotesAndBrackets() {
        assertEquals(List.of("#,##0", "[Red](#,##0)", "\"a;b\""), FormatCode.sections("#,##0;[Red](#,##0);\"a;b\""));
        assertEquals(List.of("0\\;0"), FormatCode.sections("0\\;0"));
    }

    @Test
    void theSectionFollowsTheSignOrTheConditions() {
        String f = "0.00;[Red]-0.00;\"zero\";@";
        assertEquals("0.00", FormatCode.numberSection(f, 3));
        assertEquals("[Red]-0.00", FormatCode.numberSection(f, -3));
        assertEquals("\"zero\"", FormatCode.numberSection(f, 0));
        assertEquals("@", FormatCode.textSection(f));
        assertEquals("[<100]\"small\"", FormatCode.numberSection("[<100]\"small\";\"big\"", 5));
        assertEquals("\"big\"", FormatCode.numberSection("[<100]\"small\";\"big\"", 500));
        assertEquals("0", FormatCode.numberSection("0", -7));
    }

    @Test
    void colorsAreReadFromTheSection() {
        assertEquals(Color.RED, FormatCode.color("[Red]-0.00"));
        assertEquals(Color.BLUE, FormatCode.color("[BLUE]0"));
        assertEquals(new Color(0xFF0000), FormatCode.color("[Color3]0"));
        assertNull(FormatCode.color("\"[Red]\"0"));
        assertNull(FormatCode.color("0.00"));
    }

    @Test
    void spacersAndFillsBecomeMarkers() {
        String marked = FormatCode.withMarkers("_(* #,##0.00_)");
        assertTrue(FormatCode.isSpacer(marked.charAt(0)));
        assertEquals('(', FormatCode.marked(marked.charAt(0)));
        assertTrue(FormatCode.isFill(marked.charAt(1)));
        assertEquals(' ', FormatCode.marked(marked.charAt(1)));
        assertEquals("\"_\"", FormatCode.withMarkers("\"_\""));
        String text = FormatCode.applyText("_(\"Mr \"@_)", "Smith");
        assertEquals("Mr Smith", text.substring(1, text.length() - 1));
        assertTrue(FormatCode.isGeneral("General") && FormatCode.isGeneral("") && !FormatCode.isGeneral("0"));
    }

    @Test
    void generalShowsElevenCharactersLikeExcel() {
        assertEquals("0.3", ValueFormatter.general(0.1 + 0.2, 11));
        assertEquals("0.333333333", ValueFormatter.general(1.0 / 3, 11));
        assertEquals("-1234.5678", ValueFormatter.general(-1234.5678, 11));
        assertEquals("12345678901", ValueFormatter.general(12345678901d, 11));
        assertEquals("1.23457E+11", ValueFormatter.general(123456789012d, 11));
        assertEquals("1E-10", ValueFormatter.general(1e-10, 11));
        assertEquals("3.14", ValueFormatter.general(Math.PI, 4));
        assertEquals("0", ValueFormatter.general(0, 11));
    }
}
