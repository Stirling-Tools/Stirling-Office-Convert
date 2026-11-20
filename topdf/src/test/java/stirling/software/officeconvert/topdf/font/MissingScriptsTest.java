package stirling.software.officeconvert.topdf.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.Character.UnicodeScript;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MissingScriptsTest {

    @Test
    void oneWarningPerScriptNamingItAndItsCount() {
        Map<UnicodeScript, Long> uncovered = new EnumMap<>(UnicodeScript.class);
        uncovered.put(UnicodeScript.ETHIOPIC, 24L);
        uncovered.put(UnicodeScript.HANGUL, 1L);
        uncovered.put(UnicodeScript.COMMON, 3L);
        uncovered.put(UnicodeScript.OLD_ITALIC, 2L);
        assertEquals(List.of(
                "Text in the Hangul script (Korean) has no installed font: 1 character is drawn as boxes;"
                        + " install a font that covers it",
                "Text in the Ethiopic script has no installed font: 24 characters are drawn as boxes;"
                        + " install a font that covers it",
                "Text in the Old Italic script has no installed font: 2 characters are drawn as boxes;"
                        + " install a font that covers it"),
                MissingScripts.warnings(uncovered, null));
    }

    @Test
    void theDocumentLanguageNamesTheLanguageWhenItUsesTheScript() {
        assertEquals("Amharic, am-ET", MissingScripts.language(UnicodeScript.ETHIOPIC, "am-ET"));
        assertEquals("Persian, fa-IR", MissingScripts.language(UnicodeScript.ARABIC, "fa_IR"));
        assertNull(MissingScripts.language(UnicodeScript.ARABIC, "en-US"));
        assertNull(MissingScripts.language(UnicodeScript.ARABIC, null));
        assertEquals("Thai", MissingScripts.language(UnicodeScript.THAI, "en-US"));
        assertNull(MissingScripts.language(UnicodeScript.RUNIC, "en"));
    }

    @Test
    void scriptNamesReadAsWords() {
        assertEquals("Canadian Aboriginal", MissingScripts.scriptName(UnicodeScript.CANADIAN_ABORIGINAL));
        assertEquals("Han", MissingScripts.scriptName(UnicodeScript.HAN));
    }
}
