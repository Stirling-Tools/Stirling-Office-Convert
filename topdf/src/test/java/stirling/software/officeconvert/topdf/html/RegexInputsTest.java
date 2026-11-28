package stirling.software.officeconvert.topdf.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class RegexInputsTest {

    private static final Duration QUICK = Duration.ofSeconds(5);

    @Test
    void anUnclosedMediaQueryOfAnyLengthIsAnsweredAtOnce() {
        String media = "(a:".repeat(1 << 20);
        assertTrue(assertTimeoutPreemptively(QUICK, () -> Media.matches(media, 600)));
        String range = "(width<".repeat(1 << 18);
        assertTrue(assertTimeoutPreemptively(QUICK, () -> Media.matches(range, 600)));
    }

    @Test
    void shortMediaQueriesAreStillEvaluated() {
        assertTrue(Media.matches("print and (min-width: 100px)", 600));
        assertFalse(Media.matches("print and (min-width: 1000px)", 600));
        assertFalse(Media.matches("screen", 600));
        assertTrue(Media.matches("(width >= 300px)", 600));
        assertFalse(Media.matches("(width < 300px)", 600));
        assertTrue(Media.matches("(a:" + "x".repeat(900), 600));
    }

    @Test
    void longQueriesStillJudgeTheMediaType() {
        String gap = " ".repeat(Media.MAX_QUERY_CHARS + 1);
        assertFalse(Media.matches("screen" + gap + "and (color)", 600));
        assertTrue(Media.matches("print" + gap + "and (color)", 600));
        assertTrue(Media.matches("not screen" + gap + "and (color)", 600));
        assertTrue(Media.matches("screen, print" + gap + "and (color)", 600));
    }

    @Test
    void nestedUrlOpenersInASheetAreRebasedInLinearTime() {
        String sheet = "a{b:url(" + "url(".repeat(2 << 20);
        assertEquals(sheet, assertTimeoutPreemptively(QUICK, () -> Cascade.rebased(sheet, "https://x.test/c/s.css")));
        String quoted = "url(".repeat(1 << 20) + "\"";
        assertEquals(quoted, assertTimeoutPreemptively(QUICK, () -> Cascade.rebased(quoted, "https://x.test/s.css")));
    }

    @Test
    void urlRebasingMatchesTheRegexItReplaced() {
        Pattern url = Pattern.compile("url\\(\\s*([\"']?)([^\"')]*)\\1\\s*\\)", Pattern.CASE_INSENSITIVE);
        String[] pieces = {"url(", "URL(", "uRl(", "(", ")", "\"", "'", " ", "\t", "x.png", "#f", "data:a", "u", "rl",
            "https://y.test/z", "a b", ";"};
        Random random = new Random(7);
        for (int run = 0; run < 20_000; run++) {
            StringBuilder s = new StringBuilder();
            for (int k = random.nextInt(12); k >= 0; k--) {
                s.append(pieces[random.nextInt(pieces.length)]);
            }
            String sheet = s.toString();
            assertEquals(oracle(url, sheet, "https://x.test/c/s.css"), Cascade.rebased(sheet, "https://x.test/c/s.css"),
                    sheet);
        }
    }

    // The url( rewrite as it was written with a regex, kept as the reference for the hand-written scan
    private static String oracle(Pattern url, String sheet, String ref) {
        Pattern scheme = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:.*", Pattern.DOTALL);
        Matcher m = url.matcher(sheet);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = m.group(2).strip();
            String replacement = m.group();
            if (!v.isEmpty() && !v.startsWith("#") && !scheme.matcher(v).matches()) {
                String resolved = HtmlRefs.resolve(ref, v);
                if (resolved != null && resolved.indexOf('"') < 0 && resolved.indexOf('\\') < 0) {
                    replacement = "url(\"" + (scheme.matcher(resolved).matches() ? resolved : "/" + resolved) + "\")";
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        return m.appendTail(out).toString();
    }

    @Test
    void collapsingWhiteSpaceMatchesTheCssRules() {
        Random random = new Random(7);
        String alphabet = " \t\n\f\r\u00a0ab";
        for (int k = 0; k < 20_000; k++) {
            StringBuilder b = new StringBuilder();
            for (int i = random.nextInt(12); i > 0; i--) {
                b.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String raw = b.toString();
            assertEquals(raw.replaceAll("[ \\t\\n\\f\\r]+", " "), Flow.collapse(raw, false), raw);
            assertEquals(raw.replaceAll("[ \\t\\f\\r]+", " "), Flow.collapse(raw, true), raw);
        }
    }
}
