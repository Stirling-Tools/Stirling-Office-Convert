package stirling.software.officeconvert.sheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.sheet.Conventions.DateOrder;
import stirling.software.officeconvert.sheet.Conventions.DecimalMark;

class CellTyperTest {

    private static final Conventions ANY = Conventions.UNKNOWN;
    private static final Conventions POINT = new Conventions(DecimalMark.POINT, DateOrder.MDY);
    private static final Conventions COMMA = new Conventions(DecimalMark.COMMA, DateOrder.DMY);

    private static void number(String text, double value, String format, Conventions c) {
        CellValue v = CellTyper.type(text, c);
        assertEquals(CellValue.Kind.NUMBER, v.kind(), text);
        assertEquals(value, v.number(), 1e-9, text);
        assertEquals(format, v.format().excelCode(), text);
        assertEquals(text, v.text());
    }

    private static void text(String text, Conventions c) {
        assertTrue(CellTyper.type(text, c).isText(), text + " should stay text");
    }

    private static double serial(int y, int m, int d) {
        return ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), LocalDate.of(y, m, d));
    }

    private static void date(String text, double serial, String format, Conventions c) {
        CellValue v = CellTyper.type(text, c);
        assertEquals(CellValue.Kind.DATE, v.kind(), text);
        assertEquals(serial, v.number(), 1e-9, text);
        assertEquals(format, v.format().excelCode(), text);
    }

    @Test
    void numbersInEveryGroupingStyle() {
        number("1,234.56", 1234.56, "#,##0.00", ANY);
        number("1.234,56", 1234.56, "#,##0.00", ANY);
        number("1 234 567", 1234567, "#,##0", ANY);
        number("1\u00A0234,5", 1234.5, "#,##0.0", ANY);
        number("1'234.50", 1234.5, "#,##0.00", ANY);
        number("12,34,567.89", 1234567.89, "#,##0.00", ANY);
        number("42", 42, "General", ANY);
        number("0.5", 0.5, "0.0", ANY);
        number(".75", 0.75, "0.00", ANY);
        number("12,5", 12.5, "0.0", ANY);
    }

    @Test
    void signsBracketsCurrencyAndPercent() {
        number("-1,234", -1234, "#,##0", ANY);
        number("−1,234.5", -1234.5, "#,##0.0", ANY);
        number("(1,234)", -1234, "#,##0;(#,##0)", ANY);
        number("$1,234.56", 1234.56, "\"$\"#,##0.00", ANY);
        number("($1,234.56)", -1234.56, "\"$\"#,##0.00;(\"$\"#,##0.00)", ANY);
        number("1.234,56 €", 1234.56, "#,##0.00\" €\"", ANY);
        number("€ 9.308,03", 9308.03, "\"€ \"#,##0.00", ANY);
        number("USD 1,000", 1000, "\"USD \"#,##0", ANY);
        number("12.5%", 0.125, "0.0%", ANY);
        number("12,5 %", 0.125, "0.0%", ANY);
        number("+5.2%", 0.052, "+0.0%;-0.0%;0.0%", ANY);
        number("1,234.00-", -1234, "#,##0.00", ANY);
    }

    @Test
    void ambiguousGroupingFollowsTheConventions() {
        number("1,234", 1234, "#,##0", ANY);
        number("1,234", 1234, "#,##0", POINT);
        number("1,234", 1.234, "0.000", COMMA);
        number("1.234", 1.234, "0.000", POINT);
        number("1.234", 1234, "#,##0", COMMA);
        text("12,5", POINT);
        text("12.5", COMMA);
    }

    @Test
    void identifiersStayText() {
        for (String s : List.of("02134", "007", "0123.45", "123456789012", "555-1234", "(555) 123-4567", "12345-6789",
                "123-45-6789", "1.2.3", "192.168.0.1", "+1 234 567", "3/4", "1-2", "1e5", "5-", "4 111 1111 1111 1111",
                "INV-2026-0042", "(2)")) {
            text(s, ANY);
        }
    }

    @Test
    void datesAndTimes() {
        double jan5 = serial(2024, 1, 5);
        date("2024-01-05", jan5, "yyyy-mm-dd", ANY);
        date("05.01.2024", jan5, "dd\\.mm\\.yyyy", ANY);
        date("01/05/2024", jan5, "mm\\/dd\\/yyyy", POINT);
        date("05/01/2024", jan5, "dd\\/mm\\/yyyy", COMMA);
        date("25/12/2024", serial(2024, 12, 25), "dd\\/mm\\/yyyy", ANY);
        date("12/25/2024", serial(2024, 12, 25), "mm\\/dd\\/yyyy", ANY);
        date("5 Jan 2024", jan5, "[$-409]d mmm yyyy", ANY);
        date("05-Jan-24", jan5, "[$-409]dd-mmm-yy", ANY);
        date("January 5, 2024", jan5, "[$-409]mmmm d, yyyy", ANY);
        date("Jan 2024", serial(2024, 1, 1), "[$-409]mmm yyyy", ANY);
        date("14:30", 14.5 / 24, "hh:mm", ANY);
        date("2:30 PM", 14.5 / 24, "[$-409]h:mm AM/PM", ANY);
        date("2024-01-05 09:15", jan5 + 9.25 / 24, "yyyy-mm-dd hh:mm", ANY);
        text("01/05/2024", ANY);
        text("31/02/2024", ANY);
        text("1899-12-31", ANY);
        text("25:00", ANY);
    }

    @Test
    void aColumnSettlesItsOwnAmbiguousCells() {
        List<CellValue> v = CellTyper.column(Arrays.asList("03/04/2025", "15/04/2025", null, "01/05/2025"), ANY);
        assertEquals(serial(2025, 4, 3), v.get(0).number(), 1e-9, "day first, as 15/04 proves");
        assertEquals(serial(2025, 5, 1), v.get(3).number(), 1e-9);
        List<CellValue> eu = CellTyper.column(List.of("1.234", "5.678,90", "12.345"), ANY);
        assertEquals(1234, eu.get(0).number(), 1e-9, "comma decimals, as 5.678,90 proves");
    }

    @Test
    void aColumnOfCodesOrWordsKeepsBareNumbersAsText() {
        List<CellValue> zips = CellTyper.column(List.of("02134", "90210", "01002", "10001"), ANY);
        assertTrue(zips.stream().allMatch(CellValue::isText), "zip codes sort as one kind");
        List<CellValue> names = CellTyper.column(List.of("Jagger", "TAM 107", "2137", "Karl"), ANY);
        assertTrue(names.get(2).isText(), "a variety named 2137 among names");
        List<CellValue> amounts = CellTyper.column(List.of("1,250", "(2)", "340", "(35)"), ANY);
        assertEquals(-2, amounts.get(1).number(), 1e-9, "brackets among amounts are a negative");
        List<CellValue> notes = CellTyper.column(List.of("(1)", "(2)", "Fixed"), ANY);
        assertTrue(notes.stream().allMatch(CellValue::isText), "a column of note markers stays text");
    }

    @Test
    void evidenceCountsOnlyUnambiguousForms() {
        ConventionEvidence e = new ConventionEvidence();
        for (String s : List.of("1.234,56", "3,5", "12,5", "1,234", "31.12.2024", "€")) {
            e.add(s);
        }
        assertEquals(DecimalMark.COMMA, e.conventions().decimals());
        assertEquals(DateOrder.DMY, e.conventions().dates());
        ConventionEvidence us = new ConventionEvidence();
        for (String s : List.of("$12.99,", "1,234.50", "12/31/2024")) {
            us.add(s);
        }
        assertEquals(new Conventions(DecimalMark.POINT, DateOrder.MDY), us.conventions());
    }
}
