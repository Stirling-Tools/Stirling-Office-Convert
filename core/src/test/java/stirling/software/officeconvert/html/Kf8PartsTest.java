package stirling.software.officeconvert.html;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class Kf8PartsTest {

    private static byte[] decompress(byte[] in) {
        byte[] out = new byte[1 << 16];
        int o = 0;
        int i = 0;
        while (i < in.length) {
            int c = in[i++] & 0xFF;
            if (c >= 1 && c <= 8) {
                System.arraycopy(in, i, out, o, c);
                i += c;
                o += c;
            } else if (c < 0x80) {
                out[o++] = (byte) c;
            } else if (c < 0xC0) {
                int m = c << 8 | (in[i++] & 0xFF);
                int distance = (m >> 3) & 0x7FF;
                for (int k = 0; k < (m & 7) + 3; k++, o++) {
                    out[o] = out[o - distance];
                }
            } else {
                out[o++] = ' ';
                out[o++] = (byte) (c ^ 0x80);
            }
        }
        return java.util.Arrays.copyOf(out, o);
    }

    @Test
    void palmDocRoundTripsTextAndBinary() {
        byte[] text = ("<p class=\"x\">The quick brown fox jumps over the lazy dog. The quick brown fox é中</p>"
                .repeat(60)).getBytes(StandardCharsets.UTF_8);
        byte[] slice = java.util.Arrays.copyOf(text, 4096);
        byte[] packed = PalmDoc.compress(slice, 0, slice.length);
        assertArrayEquals(slice, decompress(packed));
        assertTrue(packed.length < slice.length / 3, packed.length + " bytes");
        byte[] noise = new byte[4096];
        new Random(7).nextBytes(noise);
        assertArrayEquals(noise, decompress(PalmDoc.compress(noise, 0, noise.length)));
        byte[] offset = PalmDoc.compress(text, 100, 300);
        assertArrayEquals(java.util.Arrays.copyOfRange(text, 100, 300), decompress(offset));
    }

    @Test
    void varintsMarkTheirLastByte() {
        assertArrayEquals(new byte[] {(byte) 0x80}, Kf8Index.varint(0));
        assertArrayEquals(new byte[] {(byte) 0xFF}, Kf8Index.varint(127));
        assertArrayEquals(new byte[] {0x02, (byte) 0xAC}, Kf8Index.varint(300));
    }

    @Test
    void trailingSequencesFollowTheTableOfContents() {
        List<long[]> toc = List.of(new long[] {310, 196}, new long[] {506, 2853}, new long[] {3359, 1750},
                new long[] {5109, 4000});
        List<byte[]> tbs = Tbs.sequences(toc, 10_000, 4096);
        assertEquals(3, tbs.size());
        assertArrayEquals(new byte[] {(byte) 0x86, (byte) 0x88, 3}, tbs.get(0));
        assertArrayEquals(new byte[] {(byte) 0x96, (byte) 0x88, 2}, tbs.get(1));
        assertArrayEquals(new byte[] {(byte) 0x9A, (byte) 0x88}, tbs.get(2));
        assertArrayEquals(new byte[] {(byte) 0x81}, Tbs.trailingEntry(new byte[0]));
        byte[] entry = Tbs.trailingEntry(new byte[] {1, 2, 3});
        assertEquals((byte) 0x84, entry[entry.length - 1]);
    }

    @Test
    void base32IsUpperCaseAndPadded() {
        assertEquals("0000", Kf8Writer.base32(0, 4));
        assertEquals("000V", Kf8Writer.base32(31, 4));
        assertEquals("0000000010", Kf8Writer.base32(32, 10));
    }
}
