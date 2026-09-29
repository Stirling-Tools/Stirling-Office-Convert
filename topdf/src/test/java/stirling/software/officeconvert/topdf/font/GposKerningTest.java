package stirling.software.officeconvert.topdf.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class GposKerningTest {

    private static final class Bytes {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Bytes u16(int... values) {
            for (int v : values) {
                out.write(v >> 8 & 0xFF);
                out.write(v & 0xFF);
            }
            return this;
        }

        Bytes tag(String t) {
            for (char c : t.toCharArray()) {
                out.write(c);
            }
            return this;
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    // A 'kern' feature with a glyph pair list (5 then 7: -50) and, behind an extension, class pairs (9 then 11-12: -30)
    static byte[] gpos() {
        return new Bytes()
                .u16(1, 0, 10, 12, 28)
                .u16(0)
                .u16(1).tag("kern").u16(8)
                .u16(0, 2, 0, 1)
                .u16(2, 6, 38)
                .u16(2, 0, 1, 8)
                .u16(1, 12, 4, 0, 1, 18)
                .u16(1, 1, 5)
                .u16(1, 7, -50 & 0xFFFF)
                .u16(9, 0, 1, 8)
                .u16(1, 2, 0, 8)
                .u16(2, 24, 4, 0, 30, 38, 2, 2)
                .u16(0, 0, 0, -30 & 0xFFFF)
                .u16(1, 1, 9)
                .u16(1, 9, 1, 1)
                .u16(2, 1, 11, 12, 1)
                .bytes();
    }

    @Test
    void pairListsAndClassPairsOfTheKernFeatureAreRead() {
        GposKerning k = GposKerning.of(gpos());
        assertEquals(-50, k.kerning(5, 7));
        assertEquals(0, k.kerning(5, 8));
        assertEquals(-30, k.kerning(9, 11));
        assertEquals(-30, k.kerning(9, 12));
        assertEquals(0, k.kerning(9, 13));
        assertEquals(0, k.kerning(0, 7));
    }

    @Test
    void damagedTablesGiveNoKerningInsteadOfFailing() {
        byte[] data = gpos();
        for (int cut = 0; cut < data.length; cut++) {
            GposKerning k = GposKerning.of(Arrays.copyOf(data, cut));
            if (k != null) {
                k.kerning(5, 7);
                k.kerning(9, 11);
            }
        }
        byte[] wild = gpos();
        for (int i = 10; i < wild.length; i += 2) {
            byte[] copy = wild.clone();
            copy[i] = (byte) 0xFF;
            GposKerning k = GposKerning.of(copy);
            if (k != null) {
                k.kerning(5, 7);
                k.kerning(9, 12);
            }
        }
        assertNull(GposKerning.of(new byte[4]));
    }
}
