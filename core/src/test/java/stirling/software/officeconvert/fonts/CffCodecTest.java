package stirling.software.officeconvert.fonts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.junit.jupiter.api.Test;

class CffCodecTest {

    private static byte[] shortInt(int v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CffCodec.writeShortInt(out, v);
        return out.toByteArray();
    }

    private static int read(byte[] d) {
        int[] p = {0};
        int v = CffCodec.dictOperand(d, p, d.length);
        assertEquals(d.length, p[0]);
        return v;
    }

    @Test
    void shortIntsUseTheSmallestFormAndReadBack() {
        int[][] cases = {{0, 1}, {-107, 1}, {107, 1}, {108, 2}, {-108, 2}, {1131, 2}, {-1131, 2}, {1132, 3},
                {-1132, 3}, {32767, 3}, {-32768, 3}};
        for (int[] c : cases) {
            byte[] b = shortInt(c[0]);
            assertEquals(c[1], b.length, "length of " + c[0]);
            assertEquals(c[0], read(b));
        }
        assertArrayEquals(new byte[] {(byte) 247, 0}, shortInt(108));
        assertArrayEquals(new byte[] {(byte) 254, (byte) 255}, shortInt(-1131));
        assertArrayEquals(new byte[] {28, 127, -1}, shortInt(32767));
    }

    @Test
    void twoByteIntsCoverBothRanges() {
        assertEquals(108, CffCodec.twoByteInt(247, 0));
        assertEquals(1131, CffCodec.twoByteInt(250, 255));
        assertEquals(-108, CffCodec.twoByteInt(251, 0));
        assertEquals(-1131, CffCodec.twoByteInt(254, 255));
    }

    @Test
    void int5HoldsAnyInt() {
        for (int v : new int[] {0, 32768, -32769, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            byte[] b = CffCodec.int5(v);
            assertEquals(5, b.length);
            assertEquals(29, b[0]);
            assertEquals(v, read(b));
        }
    }

    @Test
    void realOperandsAreSkippedAsZero() {
        byte[] d = {30, 0x1a, 0x2f, (byte) 139};
        int[] p = {0};
        assertEquals(0, CffCodec.dictOperand(d, p, d.length));
        assertEquals(3, p[0]);
        assertEquals(0, CffCodec.dictOperand(d, p, d.length));
    }

    @Test
    void reservedOperandBytesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> read(new byte[] {31}));
        assertThrows(IllegalArgumentException.class, () -> read(new byte[] {(byte) 255, 0, 0, 0, 0}));
    }

    @Test
    void fixedWritesTheSixteenDotSixteenForm() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CffCodec.writeFixed(out, 0x00018000);
        assertArrayEquals(new byte[] {-1, 0, 1, -128, 0}, out.toByteArray());
    }

    @Test
    void subroutineBiasFollowsTheCount() {
        assertEquals(107, CffCodec.subrBias(1239));
        assertEquals(1131, CffCodec.subrBias(1240));
        assertEquals(1131, CffCodec.subrBias(33899));
        assertEquals(32768, CffCodec.subrBias(33900));
    }

    @Test
    void emptyIndexIsJustTheCount() {
        assertArrayEquals(new byte[] {0, 0}, CffCodec.index(List.of()));
    }

    @Test
    void indexPicksTheNarrowestOffsetSize() {
        assertEquals(1, offSize(254));
        assertEquals(2, offSize(255));
        assertEquals(2, offSize(65534));
        assertEquals(3, offSize(65535));
        assertEquals(3, offSize((1 << 24) - 2));
        assertEquals(4, offSize((1 << 24) - 1));
    }

    @Test
    void indexWritesCountOffsetsAndData() {
        byte[] index = CffCodec.index(List.of(new byte[] {7}, new byte[] {8, 9}));
        assertArrayEquals(new byte[] {0, 2, 1, 1, 2, 4, 7, 8, 9}, index);
    }

    private static int offSize(int dataBytes) {
        byte[] index = CffCodec.index(List.of(new byte[dataBytes]));
        int size = index[2];
        int last = 0;
        for (int k = 0; k < size; k++) {
            last = last << 8 | index[3 + size + k] & 0xFF;
        }
        assertEquals(dataBytes + 1, last);
        assertEquals(3 + 2 * size + dataBytes, index.length);
        return size;
    }
}
