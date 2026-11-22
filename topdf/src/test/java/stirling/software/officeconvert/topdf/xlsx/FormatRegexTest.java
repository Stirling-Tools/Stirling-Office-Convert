package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.Random;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class FormatRegexTest {

    private static final Duration QUICK = Duration.ofSeconds(5);

    private static final String HUGE_BRACKETS = "[".repeat(1 << 20) + "0";

    @Test
    void unclosedBracketsInAFormatAreStrippedInLinearTime() {
        assertEquals("", assertTimeoutPreemptively(QUICK, () -> ValueFormatter.blankFraction(HUGE_BRACKETS)));
        assertEquals(HUGE_BRACKETS, assertTimeoutPreemptively(QUICK,
                () -> ValueFormatter.withoutTags(HUGE_BRACKETS, false)));
    }

    @Test
    void unclosedLocaleTagsAreSearchedInLinearTime() {
        String section = "[$".repeat(1 << 20) + "0";
        assertEquals("12", assertTimeoutPreemptively(QUICK, () -> ExcelFormat.nativeDigits(section, "12")));
        String shared = "[$".repeat(1 << 20) + "-";
        assertEquals("12", assertTimeoutPreemptively(QUICK, () -> ExcelFormat.nativeDigits(shared, "12")));
    }

    @Test
    void aVmlLengthWithALongUnitRunIsReadInLinearTime() {
        String v = "a".repeat(1 << 21) + "1";
        assertEquals(v.length(), assertTimeoutPreemptively(QUICK, () -> HeaderPictures.unitStart(v)));
        assertEquals(2, HeaderPictures.unitStart("12pt"));
        assertEquals(3, HeaderPictures.unitStart("1.5%"));
        assertEquals(0, HeaderPictures.unitStart("in"));
    }

    @Test
    void manyLiteralsBetweenDigitsFormatInLinearTime() {
        String section = "0" + "\"-\"0".repeat(20_000);
        String text = assertTimeoutPreemptively(QUICK, () -> ExcelFormat.format(123, section, true, false));
        assertEquals(20_001 + 20_000, text.length());
        assertEquals("1-2-3", ExcelFormat.format(123, "0\"-\"0\"-\"0", true, false));
        assertEquals("0-0-1-2-3", ExcelFormat.format(123, "0\"-\"0\"-\"0\"-\"0\"-\"0", true, false));
        assertEquals("1-2 kg", ExcelFormat.format(12, "0\"-\"0\" kg\"", true, false));
    }

    @Test
    void formatCodesAreCutToExcelsLimitAndLongOnesAreNotShared() {
        String code = "0" + "\"x\"".repeat(200);
        assertEquals(FormatCode.MAX_CHARS, FormatCode.clamp(code).length());
        assertSame("0.00", FormatCode.clamp("0.00"));
        assertNull(FormatCode.clamp(null));
        String unique = "0;" + "y".repeat(FormatCode.MAX_CHARS) + System.nanoTime();
        assertEquals(2, FormatCode.sections(unique).size());
        assertEquals(FormatCode.sections(unique), FormatCode.sections(unique));
        assertNotSame(FormatCode.sections(unique), FormatCode.sections(unique));
        String small = "0;" + System.nanoTime();
        assertSame(FormatCode.sections(small), FormatCode.sections(small));
    }

    @Test
    void tagStrippingMatchesTheRegexesItReplaced() {
        Pattern brackets = Pattern.compile("\\[[^\\]]*\\]");
        Pattern both = Pattern.compile("\\[[^\\]]*\\]|\"[^\"]*\"");
        fuzz(new String[] {"[", "]", "\"", "0", "/", "Red", "$", " "}, 3,
                s -> brackets.matcher(s).replaceAll(""), s -> ValueFormatter.withoutTags(s, false));
        fuzz(new String[] {"[", "]", "\"", "0", "/", "Red", "$", " "}, 5,
                s -> both.matcher(s).replaceAll(""), s -> ValueFormatter.withoutTags(s, true));
    }

    @Test
    void localeTagSearchMatchesTheRegexItReplaced() {
        Pattern numerals = Pattern.compile("\\[\\$[^\\]-]*-([0-9A-Fa-f]{7,8})\\]");
        fuzz(new String[] {"[$", "[", "$", "]", "-", "0", "D", "f", "1", "g", "D000409", "2000401", "\u20AC"}, 9, s -> {
            Matcher m = numerals.matcher(s);
            return m.find() ? m.group(1) : null;
        }, ExcelFormat::numeralsTag);
    }

    @Test
    void unitStripMatchesTheRegexItReplaced() {
        fuzz(new String[] {"a", "z", "%", "1", ".", "-", "A", "\u00E9", "pt"}, 13,
                s -> s.replaceAll("[a-z%]+$", ""), s -> s.substring(0, HeaderPictures.unitStart(s)));
    }

    private static void fuzz(String[] pieces, long seed, Function<String, String> oracle,
            Function<String, String> actual) {
        Random random = new Random(seed);
        for (int run = 0; run < 20_000; run++) {
            StringBuilder s = new StringBuilder();
            for (int k = random.nextInt(10); k >= 0; k--) {
                s.append(pieces[random.nextInt(pieces.length)]);
            }
            String in = s.toString();
            Supplier<String> message = () -> in;
            assertEquals(oracle.apply(in), actual.apply(in), message);
        }
    }
}
