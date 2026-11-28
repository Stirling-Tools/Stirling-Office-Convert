package stirling.software.officeconvert.topdf.font;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

class UnicodeCmapTest {

    private static byte[] cmap(int[]... records) {
        ByteBuffer b = ByteBuffer.allocate(4 + 8 * records.length + 4);
        b.putShort((short) 0).putShort((short) records.length);
        for (int i = 0; i < records.length; i++) {
            b.putShort((short) records[i][0]).putShort((short) records[i][1]).putInt(4 + 8 * records.length + i);
        }
        return b.array();
    }

    private static int[] record(byte[] cmap, int i) {
        ByteBuffer b = ByteBuffer.wrap(cmap);
        return new int[] {b.getShort(4 + 8 * i), b.getShort(6 + 8 * i), b.getInt(8 + 8 * i)};
    }

    @Test
    void onlyTheSubtableTheParserWouldPickIsKept() {
        byte[] in = cmap(new int[] {1, 0}, new int[] {3, 1}, new int[] {3, 10});
        byte[] out = FontScanner.unicodeOnly(in);
        assertEquals(1, ByteBuffer.wrap(out).getShort(2));
        assertArrayEquals(record(in, 2), record(out, 0));
        assertEquals(in.length, out.length);
    }

    @Test
    void aTableWithoutAUnicodeSubtableIsLeftAlone() {
        byte[] in = cmap(new int[] {1, 0}, new int[] {3, 2});
        assertArrayEquals(in, FontScanner.unicodeOnly(in));
    }
}
