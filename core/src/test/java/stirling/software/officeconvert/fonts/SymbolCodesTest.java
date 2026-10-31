package stirling.software.officeconvert.fonts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class SymbolCodesTest {

    @Test
    void wingdingsCheckMarksAndBoxesReadAsUnicode() {
        assertEquals("✔ ✗ ☒ ☑ ● ■ ◆ ➢ ✉ ✍",
                SymbolCodes.plain("         ", "Wingdings"));
    }

    @Test
    void symbolPrivateUseReadsAsGreekAndMaths() {
        assertEquals("α ≤ ∑", SymbolCodes.plain("  ", "Symbol"));
    }

    @Test
    void otherFontsAndCharactersAreLeftAlone() {
        String text = " plain";
        assertSame(text, SymbolCodes.plain(text, "Arial"));
        assertSame(text, SymbolCodes.plain(text, null));
        assertEquals("abc", SymbolCodes.plain("abc", "Wingdings"));
    }
}
