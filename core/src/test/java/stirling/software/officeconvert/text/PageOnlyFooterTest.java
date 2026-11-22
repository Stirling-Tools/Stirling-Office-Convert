package stirling.software.officeconvert.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.RunStyle;

class PageOnlyFooterTest {

    private static final RunStyle STYLE = new RunStyle("Arial", 10, false, false, false, false, 0, -1, 0, false);

    private static String running(String text) {
        Paragraph p = new Paragraph();
        p.inlines.add(new Inline.Text(text, STYLE, null, -1));
        p.inlines.add(new Inline.PageNumber(STYLE, false));
        StringBuilder sb = new StringBuilder();
        new TextBlocks().running(sb, p);
        return sb.toString();
    }

    @Test
    void longPunctuationInARunningLineIsCheap() {
        String out = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> running(".".repeat(5000) + "x"));
        assertTrue(out.contains("x"));
    }

    @Test
    void aBarePageNumberFooterIsStillLeftOut() {
        assertEquals("", running("Page "));
        assertEquals("", running("- p. "));
    }
}
