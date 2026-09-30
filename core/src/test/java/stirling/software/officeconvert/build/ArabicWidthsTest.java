package stirling.software.officeconvert.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ArabicWidthsTest {

    private static float w(String s) {
        return ArabicWidths.width(s, false, 1000);
    }

    @Test
    void lettersTakeTheirJoiningForms() {
        float isolated = w("\u0628");
        float three = w("\u0628\u0628\u0628");
        assertTrue(three < 3 * isolated, "joined beh is narrower than three isolated ones");
        assertEquals(2 * isolated, w("\u0628\u200C\u0628"), 0.01f, "ZWNJ stops the join");
        assertEquals(w("\u0627") + isolated, w("\u0627\u0628"), 0.01f, "alef never joins the next letter");
        assertEquals(w("\u0628\u0628"), w("\u0628\u064E\u0628"), 0.01f, "marks are transparent to joining");
        assertTrue(w("\u0644\u0627") < w("\u0644") + w("\u0627"), "lam-alef is one ligature");
        assertTrue(Float.isNaN(w("\u0628x")), "letters outside the table are not guessed");
    }
}
