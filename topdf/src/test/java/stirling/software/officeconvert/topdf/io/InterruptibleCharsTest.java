package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class InterruptibleCharsTest {

    @Test
    void aQuadraticRegexStopsOnceTheThreadIsInterrupted() {
        CharSequence text = InterruptibleChars.of("(".repeat(1 << 20));
        Matcher m = Pattern.compile("\\(([^)]*)\\)").matcher(text);
        UncheckedIOException e = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Thread.currentThread().interrupt();
            try {
                return assertThrows(UncheckedIOException.class, m::find);
            } finally {
                assertTrue(Thread.interrupted(), "the interrupt is left set for the next poll");
            }
        });
        assertInstanceOf(InterruptedIOException.class, e.getCause());
    }

    @Test
    void matchingIsUnchangedWhileNotInterrupted() {
        String s = "a(b) ".repeat(2_000);
        CharSequence text = InterruptibleChars.of(s);
        assertEquals(s, text.toString());
        assertSame(text, InterruptibleChars.of(text));
        assertEquals(Pattern.compile("\\(b\\)").matcher(s).replaceAll("x"),
                Pattern.compile("\\(b\\)").matcher(text).replaceAll("x"));
        String small = "short";
        assertSame(small, InterruptibleChars.of(small));
    }
}
