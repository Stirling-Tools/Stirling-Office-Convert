package stirling.software.officeconvert.fonts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class CharStringExpansionTest {

    @Test
    void accentedComponentsThatReferBackToTheirGlyphAreRefused() {
        CharStringExpansion.Result[] glyphs = {new CharStringExpansion.Result(10, new int[] {1, 1}),
            new CharStringExpansion.Result(10, new int[] {0, 0})};

        assertThrows(IOException.class, () -> CharStringExpansion.check(glyphs, code -> code));
    }

    @Test
    void aSharedComponentIsCountedWithoutBeingTakenForACycle() {
        CharStringExpansion.Result[] glyphs = {new CharStringExpansion.Result(10, new int[] {2, 2}),
            new CharStringExpansion.Result(10, new int[] {2, 2}), new CharStringExpansion.Result(10, new int[0])};

        assertDoesNotThrow(() -> CharStringExpansion.check(glyphs, code -> code));
    }

    @Test
    void glyphsEachWithinTheirBudgetStillHitTheFontBudget() {
        int each = CharStringExpansion.MAX_TOKENS;
        int count = (int) (CharStringExpansion.MAX_FONT_TOKENS / each) + 1;
        CharStringExpansion.Result[] glyphs = new CharStringExpansion.Result[count];
        Arrays.fill(glyphs, new CharStringExpansion.Result(each, new int[0]));

        assertThrows(IOException.class, () -> CharStringExpansion.check(glyphs, code -> -1));
        assertDoesNotThrow(() -> CharStringExpansion.check(Arrays.copyOf(glyphs, count - 1), code -> -1));
    }

    @Test
    void subroutinesNestedPastTheDepthLimitAreRefusedWithinTheTokenBudget() throws IOException {
        byte[][] chain = new byte[CharStringExpansion.MAX_DEPTH + 2][];
        int bias = CffCodec.subrBias(chain.length);
        for (int i = 0; i < chain.length - 1; i++) {
            chain[i] = new byte[] {28, (byte) ((i + 1 - bias) >> 8), (byte) (i + 1 - bias), 10, 11};
        }
        chain[chain.length - 1] = new byte[] {11};
        byte[] call = {28, (byte) (-bias >> 8), (byte) -bias, 10, 14};

        byte[][] shallow = Arrays.copyOfRange(chain, chain.length - 4, chain.length);
        int shallowBias = CffCodec.subrBias(shallow.length);
        for (int i = 0; i < shallow.length - 1; i++) {
            shallow[i] = new byte[] {28, (byte) ((i + 1 - shallowBias) >> 8), (byte) (i + 1 - shallowBias), 10, 11};
        }
        assertDoesNotThrow(() -> CharStringExpansion.type2(new byte[] {28, (byte) (-shallowBias >> 8),
            (byte) -shallowBias, 10, 14}, new byte[0][], shallow));
        assertThrows(IOException.class, () -> CharStringExpansion.type2(call, new byte[0][], chain));
    }
}
