package stirling.software.officeconvert.topdf.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.TestFonts;

class EmojiFallbackTest {

    @TempDir
    Path dir;

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

    @Test
    void aBitmapIndexThatRepeatsOneHugeSubtableIsRefused() throws IOException {
        int subtables = 4096;
        int pairs = 100_000;
        int array = 8 + 48;
        int sub = array + 8 * subtables;
        ByteBuffer b = ByteBuffer.allocate(sub + 12 + 4 * (pairs + 1));
        b.putShort(0, (short) 3).putInt(4, 1).putInt(8, array).putInt(16, subtables).put(8 + 45, (byte) 109);
        for (int t = 0; t < subtables; t++) {
            b.putShort(array + 8 * t, (short) 1).putShort(array + 8 * t + 2, (short) 2).putInt(array + 8 * t + 4,
                    sub - array);
        }
        b.putShort(sub, (short) 4).putShort(sub + 2, (short) 17).putInt(sub + 8, pairs);
        for (int i = 0; i <= pairs; i++) {
            b.putShort(sub + 12 + 4 * i, (short) (1 + (i & 1))).putShort(sub + 14 + 4 * i, (short) ((i & 1) * 8));
        }
        Path font = Files.write(dir.resolve("emoji.ttf"),
                TestFonts.replaced(TestFonts.colourEmoji(0x00C000, 0xFF0000), "CBLC", b.array()));
        assertNull(ColorEmoji.open(font));
    }
}
