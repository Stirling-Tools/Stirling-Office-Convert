package stirling.software.officeconvert.topdf.font;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EmojiFallbackTest {

    @Test
    void emojiPresentationSymbolsOutsideTheEmojiBlocksTryTheEmojiFontFirst() {
        for (int cp : new int[] {0x2B50, 0x2B55, 0x2B1B, 0x231A, 0x23F0, 0x2705, 0x1F389}) {
            assertEquals("Segoe UI Emoji", Substitutes.script(cp).get(0), Integer.toHexString(cp));
        }
        assertEquals("Arial", Substitutes.script(0x2B06).get(0));
    }
}
