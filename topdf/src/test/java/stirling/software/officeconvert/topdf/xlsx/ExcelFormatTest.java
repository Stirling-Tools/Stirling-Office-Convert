package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Random;

import org.junit.jupiter.api.Test;

class ExcelFormatTest {

    private static String show(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (FormatCode.isSpacer(c)) {
                b.append('_').append(FormatCode.marked(c));
            } else if (FormatCode.isFill(c)) {
                b.append('*').append(FormatCode.marked(c));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static String fmt(double value, String format) {
        int index = FormatCode.numberSectionIndex(format, value);
        String section = FormatCode.sections(format).get(index);
        return show(ExcelFormat.format(value, section, index != 1, false));
    }

    @Test
    void wholeNumbersPrintAsTheRoundedDecimalDoes() {
        Random random = new Random(7);
        double[] edges = {0, -0.0, 1, -1, 7, 1e14, -1e14, 999_999_999_999_999d, -999_999_999_999_999d, 1e15, 1e16,
            123_456_789_012_345d, 4_503_599_627_370_496d, Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NaN};
        for (int d = 0; d < 6; d++) {
            for (double v : edges) {
                if (Double.isFinite(v)) {
                    assertEquals(ExcelFormat.round(v, d).toPlainString(), ExcelFormat.plain(v, d), v + " at " + d);
                }
            }
            for (int i = 0; i < 2000; i++) {
                double v = random.nextLong() % (i < 1000 ? 1_000_000_000_000_000L : 100_000L);
                assertEquals(ExcelFormat.round(v, d).toPlainString(), ExcelFormat.plain(v, d), v + " at " + d);
                double x = v + random.nextInt(1000) / 1000.0;
                assertEquals(ExcelFormat.round(x, d).toPlainString(), ExcelFormat.plain(x, d), x + " at " + d);
            }
        }
    }

    @Test
    void longDecimalsRoundAsTheirExactBinaryValueDoes() {
        Random random = new Random(11);
        MathContext fifteen = new MathContext(15, RoundingMode.HALF_EVEN);
        for (int i = 0; i < 400_000; i++) {
            double v = switch (i % 4) {
                case 0 -> random.nextDouble() * Math.pow(10, random.nextInt(40) - 20);
                case 1 -> Double.longBitsToDouble(random.nextLong());
                case 2 -> Double.parseDouble((random.nextBoolean() ? "-" : "") + (random.nextLong() & 0xFFFFFFFFFFFFFL)
                        % 1_000_000_000_000_000L + "5e" + (random.nextInt(30) - 25));
                default -> random.nextInt(100_000) / 100.0 * random.nextInt(1000) / 7.0;
            };
            if (!Double.isFinite(v)) {
                continue;
            }
            int d = random.nextInt(6);
            BigDecimal shortest = BigDecimal.valueOf(v);
            BigDecimal exact = shortest.precision() <= 15 ? shortest : new BigDecimal(v).round(fifteen);
            assertEquals(exact.setScale(d, RoundingMode.HALF_UP).toPlainString(),
                    ExcelFormat.round(v, d).toPlainString(), v + " at " + d);
        }
    }

    @Test
    void thousandsScalingKeepsDecimalsAndLiterals() {
        assertEquals("12.3", fmt(12345, "0.0,"));
        assertEquals("12.3", fmt(12345678, "#,##0.0,,"));
        assertEquals("12.3K", fmt(12345, "0.0,\"K\""));
        assertEquals("12.3M", fmt(12345678, "0.0,,\"M\""));
        assertEquals("$12M", fmt(12345678, "$#,##0,,\"M\""));
        assertEquals("1,235", fmt(1234567, "#,##0,"));
        assertEquals("1,235K", fmt(1234567, "#,##0,\"K\""));
        assertEquals("12.35", fmt(12345, "0.00,"));
    }

    @Test
    void placeholdersCurrencyTagsAndExponentsFollowExcel() {
        assertEquals("USD 5.00", fmt(5, "[$USD] #,##0.00"));
        assertEquals("€5.00", fmt(5, "[$€-407]#,##0.00"));
        assertEquals("", fmt(0, "#,###"));
        assertEquals("", fmt(0, "###,###"));
        assertEquals(".", fmt(0, "#.##"));
        assertEquals(".5", fmt(0.5, "#.##"));
        assertEquals("12.3E+3", fmt(12345, "##0.0E+0"));
        assertEquals("1.23E+04", fmt(12345, "0.00E+00"));
        assertEquals("1.23E-04", fmt(0.000123, "0.00E+00"));
        assertEquals("1,234.50", fmt(1234.5, "#,##0.00"));
        assertEquals("-1,234.50", fmt(-1234.5, "#,##0.00"));
        assertEquals("(1,234.50)", fmt(-1234.5, "#,##0.00;(#,##0.00)"));
        assertEquals("25.6%", fmt(0.256, "0.0%"));
        assertEquals("123-45-6789", fmt(123456789, "000-00-0000"));
        assertEquals("005", fmt(5, "000"));
        assertEquals("-$5.00", fmt(-5, "$#,##0.00"));
        assertEquals("1,000", fmt(999.5, "#,##0"));
        assertEquals("3", fmt(2.5, "0"));
        assertEquals("0.00", fmt(0, "0.00"));
        assertEquals("1,000,000", fmt(1_000_000, "#,##0"));
        assertEquals("+5.0%", fmt(0.05, "\\+0.0%;\\-0.0%;0.0%"));
        assertEquals("-5.0%", fmt(-0.05, "\\+0.0%;\\-0.0%;0.0%"));
        assertEquals("5 kg", fmt(5, "General\" kg\""));
        assertEquals("5.5 EUR", fmt(5.5, "0.0 EUR"));
        assertEquals("1:02:03.5", fmt((3723.5) / 86400, "h:mm:ss.0"));
    }

    @Test
    void accountingZeroShowsOnlyTheDash() {
        String acct = "_(\"$\"* #,##0.00_);_(\"$\"* \\(#,##0.00\\);_(\"$\"* \"-\"??_);_(@_)";
        assertEquals("_($* -_0_0_)", fmt(0, acct));
        assertEquals("_($* 1,234.50_)", fmt(1234.5, acct));
        assertEquals("_($* (1,234.50)", fmt(-1234.5, acct));
        assertEquals("_(* -_0_0_)", fmt(0, "_(* #,##0.00_);_(* \\(#,##0.00\\);_(* \"-\"??_);_(@_)"));
        assertEquals("_-* -_0_0 €_-", fmt(0, "_-* #,##0.00\\ \"€\"_-;\\-* #,##0.00\\ \"€\"_-;_-* \"-\"??\\ \"€\"_-;_-@_-"));
        assertEquals("_(* -_)", fmt(0, "_(* #,##0_);_(* \\(#,##0\\);_(* \"-\"_);_(@_)"));
    }

    @Test
    void datesWithLiteralsLocalesAndElapsedTime() {
        assertEquals("3/15/2023", fmt(45000, "m/d/yyyy"));
        assertEquals("15/03/2023", fmt(45000, "dd/mm/yyyy"));
        assertEquals("2023-03-15", fmt(45000, "yyyy\\-mm\\-dd"));
        assertEquals("Mar 15, 2023", fmt(45000, "mmm d, yyyy"));
        assertEquals("Wednesday, March 15, 2023", fmt(45000, "dddd, mmmm dd, yyyy"));
        assertEquals("15 de março de 2023", fmt(45000, "[$-416]d\" de \"mmmm\" de \"yyyy;@"));
        assertEquals("2023年3月15日", fmt(45000, "yyyy\"年\"m\"月\"d\"日\""));
        assertEquals("12:00 h", fmt(0.5, "hh:mm\" h\""));
        assertEquals("Mittwoch, 15. März 2023", fmt(45000, "[$-407]dddd, d. mmmm yyyy"));
        assertEquals("15.03.2023 Uhr", fmt(45000, "dd.mm.yyyy \"Uhr\""));
        assertEquals("Mar 15, 2023 at 12:00", fmt(45000.5, "mmm d, yyyy \"at\" h:mm"));
        assertEquals("15 de March", fmt(45000, "d\\ \\d\\e\\ mmmm"));
        assertEquals("15 days", fmt(45000, "d \"days\""));
        assertEquals("36 hours", fmt(1.5, "[h] \"hours\""));
        assertEquals("Wednesday, March 15", fmt(45000, "dddd\", \"mmmm d"));
        assertEquals("6:00 PM", fmt(45000.75, "h:mm AM/PM"));
        assertEquals("6:00 PM", fmt(0.75, "[$-409]h:mm AM/PM;@"));
        assertEquals("Mar-23", fmt(45000, "[$-409]mmm\\-yy"));
        assertEquals("30:00:00", fmt(1.25, "[h]:mm:ss"));
        assertEquals("00:01", fmt(1.0 / 86400, "mm:ss"));
        assertEquals("2:57:46.598", fmt(45000.123456, "h:mm:ss.000"));
        assertEquals("Wednesday, March 15, 2023", fmt(45000, "[$-F800]dddd\\,\\ mmmm\\ dd\\,\\ yyyy"));
        assertEquals("1/0/1900", fmt(0, "m/d/yyyy"));
        assertEquals("2/29/1900", fmt(60, "m/d/yyyy"));
        assertEquals("m/d/yyyy", ExcelFormat.builtin(14, "m/d/yy"));
    }

    @Test
    void fractionsAreLeftToTheFallback() {
        assertNull(ExcelFormat.format(1.5, "# ?/?", true, false));
    }
}
