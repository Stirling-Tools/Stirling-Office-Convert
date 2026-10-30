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

    @Test
    void colourEmojiClustersTakeTheirJoinersModifiersAndSelectors() {
        String family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67";
        assertEquals(family.length(), ColorEmoji.clusterEnd(family + "x", 0));
        assertEquals(4, ColorEmoji.clusterEnd("\uD83D\uDC4D\uD83C\uDFFDx", 0));
        assertEquals(4, ColorEmoji.clusterEnd("\uD83C\uDDEC\uD83C\uDDE7\uD83C\uDDEC", 0));
        assertEquals(3, ColorEmoji.clusterEnd("1\uFE0F\u20E3", 0));
        assertEquals(2, ColorEmoji.clusterEnd("\u2764\uFE0F", 0));
        assertEquals(1, ColorEmoji.clusterEnd("\u2705 ", 0));
        assertEquals(-1, ColorEmoji.clusterEnd("\u2764 ", 0));
        assertEquals(-1, ColorEmoji.clusterEnd("\u2B50\uFE0E", 0));
        assertEquals(-1, ColorEmoji.clusterEnd("1 ", 0));
        assertEquals(-1, ColorEmoji.clusterEnd("\u00A9", 0));
    }
}
