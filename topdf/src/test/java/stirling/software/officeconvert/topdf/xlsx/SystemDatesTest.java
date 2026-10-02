package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SystemDatesTest {

    @TempDir
    Path dir;

    @Test
    void theShortDateFollowsTheLocaleWithFourYearDigits() {
        assertEquals("m/d/yyyy", SystemDates.of(Locale.US).shortDate());
        assertEquals("m/d/yyyy", SystemDates.of(Locale.ENGLISH).shortDate());
        assertEquals("dd/mm/yyyy", SystemDates.of(Locale.UK).shortDate());
        assertEquals("dd.mm.yyyy", SystemDates.of(Locale.GERMANY).shortDate());
        assertEquals("yyyy/mm/dd", SystemDates.of(Locale.JAPAN).shortDate());
        assertEquals("m/d/yyyy", SystemDates.of(null).shortDate());
    }

    @Test
    void patternsWithTextOrLongMonthsFallBackToTheUsOrder() {
        assertEquals("m/d/yyyy", SystemDates.excel("d MMM y"));
        assertEquals("m/d/yyyy", SystemDates.excel("G y/MM/dd"));
        assertEquals("m/d/yyyy", SystemDates.excel("MM/yy"));
        assertEquals("d-m-yyyy", SystemDates.excel("d-M-yy"));
    }

    @Test
    void builtInDateFormatsUseTheSystemOrderAndOthersStayAsWritten() {
        SystemDates uk = SystemDates.of(Locale.UK);
        assertTrue(uk.hour24());
        assertFalse(SystemDates.US.hour24());
        assertEquals("dd/mm/yyyy", uk.builtin(14, "m/d/yy"));
        assertEquals("dd/mm/yyyy hh:mm", uk.builtin(22, "m/d/yy h:mm"));
        assertEquals("m/d/yyyy h:mm", SystemDates.US.builtin(22, "m/d/yy h:mm"));
        assertEquals("m/d/yy", uk.builtin(165, "m/d/yy"));
        assertEquals("0.00", uk.builtin(2, "0.00"));
        assertEquals("15/03/2023", ExcelFormat.format(45000, uk.shortDate(), true, false));
        assertEquals("15.03.2023", ExcelFormat.format(45000, SystemDates.of(Locale.GERMANY).shortDate(), true, false));
        assertEquals("15/03/2023 12:30", ExcelFormat.format(45000.5208333, uk.shortDateTime(), true, false));
        assertEquals("3/15/2023 12:30", ExcelFormat.format(45000.5208333, SystemDates.US.shortDateTime(), true,
                false));
    }

    @Test
    void headerDatesAndTimesUseTheSameOrder() {
        LocalDateTime when = LocalDateTime.of(2026, 9, 28, 14, 5);
        assertEquals("28/09/2026", SystemDates.of(Locale.UK).date(when));
        assertEquals("14:05", SystemDates.of(Locale.UK).time(when));
        assertEquals("9/28/2026", SystemDates.US.date(when));
        assertEquals("2:05 PM", SystemDates.US.time(when));
    }

    @Test
    void aShortDateCellPrintsInTheHostsRegionalOrder() throws Exception {
        String styles = "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills"
                + " count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills><borders count=\"1\"><border/>"
                + "</borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>"
                + "</cellStyleXfs><cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\""
                + " xfId=\"0\"/><xf numFmtId=\"14\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\""
                + " applyNumberFormat=\"1\"/></cellXfs>";
        String sheet = "<cols><col min=\"1\" max=\"1\" width=\"20\" customWidth=\"1\"/></cols><sheetData><row"
                + " r=\"1\"><c r=\"A1\" s=\"1\"><v>43276</v></c></row></sheetData>";
        byte[] xlsx = new RawXlsx().styles(styles).sheet("S", sheet).bytes();
        Locale before = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.UK);
            assertTrue(XlsxTesting.convert(dir, "uk.xlsx", xlsx).all().contains("25/06/2018"));
            Locale.setDefault(Locale.Category.FORMAT, Locale.US);
            assertTrue(XlsxTesting.convert(dir, "us.xlsx", xlsx).all().contains("6/25/2018"));
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, before);
        }
    }
}
