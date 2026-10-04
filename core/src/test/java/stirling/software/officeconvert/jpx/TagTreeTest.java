package stirling.software.officeconvert.jpx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;

import org.junit.jupiter.api.Test;

class TagTreeTest {
    private static HeaderBits bits(String hex) {
        byte[] data = HexFormat.of().parseHex(hex);
        HeaderBits bits = new HeaderBits();
        bits.reset(data, 0, data.length);
        return bits;
    }

    @Test
    void decodesSharedAncestorsAndCachedValuesWithoutConsumingTheFollowingBits() throws Exception {
        TagTree tree = new TagTree(3, 2);
        HeaderBits bits = bits("c50cda54");
        int[] expected = {3, 1, 4, 2, 0, 5};
        for (int leaf = 0; leaf < expected.length; leaf++) {
            assertEquals(expected[leaf], tree.value(leaf, 64, bits));
        }
        for (int leaf : new int[] {5, 0, 4, 2, 1, 3}) {
            assertEquals(expected[leaf], tree.value(leaf, expected[leaf] + 1, bits));
        }
        assertEquals(0xa54, bits.bits(12));
    }

    @Test
    void keepsTheValueLimitAndTheBitAfterItsLastAllowedThreshold() throws Exception {
        TagTree tree = new TagTree(1, 1);
        HeaderBits bits = bits("1a4b");
        assertThrows(JpxException.class, () -> tree.value(0, 3, bits));
        assertEquals(3, tree.value(0, 4, bits));
        assertThrows(JpxException.class, () -> tree.value(0, 3, bits));
        assertEquals(3, tree.value(0, 64, bits));
        assertEquals(0xa4b, bits.bits(12));
    }

    @Test
    void aZeroValueUsesOneBitEvenAtAZeroLimitAndLeavesStuffedBitsForTheNextField() throws Exception {
        TagTree tree = new TagTree(1, 1);
        HeaderBits bits = bits("ffbf");
        assertEquals(0, tree.value(0, 0, bits));
        assertEquals(127, bits.bits(7));
        assertEquals(63, bits.bits(7));
    }

    @Test
    void aTruncatedValueStillReportsThePacketHeaderFailure() {
        HeaderBits bits = bits("00");
        JpxException failure = assertThrows(JpxException.class, () -> new TagTree(1, 1).value(0, 64, bits));
        assertEquals("JPEG 2000 packet header ends early", failure.getMessage());
        assertEquals(1, bits.pos());
    }
}
