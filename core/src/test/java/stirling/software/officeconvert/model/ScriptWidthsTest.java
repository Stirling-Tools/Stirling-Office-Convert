package stirling.software.officeconvert.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScriptWidthsTest {

    private static final Character.UnicodeScript DEVA = Character.UnicodeScript.DEVANAGARI;

    @Test
    void findsTheOneModelledScriptOfARun() {
        assertEquals(Character.UnicodeScript.TELUGU, ScriptWidths.script("\u0C24\u0C46\u0C32\u0C41\u0C17\u0C41"));
        assertEquals(DEVA, ScriptWidths.script("\u0915\u094D\u200D\u0937"));
        assertNull(ScriptWidths.script("abc"));
        assertNull(ScriptWidths.script("\u0C24\u0C46 a"));
        assertTrue(ScriptWidths.clustered(Character.UnicodeScript.MYANMAR));
        assertTrue(!ScriptWidths.clustered(Character.UnicodeScript.ARMENIAN) && !ScriptWidths.clustered(null));
    }

    @Test
    void conjunctsAndWordFinalViramasHaveTheirOwnWidths() {
        float ka = ScriptWidths.width("\u0915", DEVA, false);
        float ssa = ScriptWidths.width("\u0937", DEVA, false);
        float finalVirama = ScriptWidths.width("\u0915\u094D", DEVA, false) - ka;
        float conjunct = ScriptWidths.width("\u0915\u094D\u0937", DEVA, false);
        assertTrue(ka > 0.3f && ka < 1f, "ka " + ka);
        assertTrue(conjunct < ka + ssa, "a conjunct is narrower than its letters");
        assertTrue(finalVirama > conjunct - ka - ssa, "a halant ending a word takes room");
        assertTrue(Float.isNaN(ScriptWidths.width("\u0915a", DEVA, false)), "no guessing for other scripts");
        assertTrue(ScriptWidths.width("\u0915", DEVA, true) >= ka);
    }

    @Test
    void countsSpacingUnitsAsGraphemeClusters() {
        assertEquals(4, ScriptWidths.units("\u092A\u094D\u0930\u0938\u094D\u0924\u093E\u0935\u0928\u093E"));
        assertEquals(6, ScriptWidths.units("\u092A\u094D\u0930\u0924\u094D\u092F\u0947\u0915 \u0935\u094D\u092F\u0915\u094D\u0924\u093F"));
        assertEquals(0.283f, ScriptWidths.space(Character.UnicodeScript.TELUGU, false), 0.0001f);
        float unit = ScriptWidths.perUnit("\u0915\u093E \u0915\u093E", false);
        float expected = (2 * ScriptWidths.width("\u0915\u093E", DEVA, false) + ScriptWidths.space(DEVA, false)) / 3;
        assertEquals(expected, unit, 0.0001f);
        assertTrue(Float.isNaN(ScriptWidths.perUnit("\u0540\u0561", false)), "simple scripts are spaced per character");
    }
}
