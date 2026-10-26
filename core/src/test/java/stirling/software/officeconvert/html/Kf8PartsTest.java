package stirling.software.officeconvert.html;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
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
        List<Tbs.Node> toc = List.of(node(0, 310, 196, 0, null), node(1, 506, 2853, 0, null),
                node(2, 3359, 1750, 0, null), node(3, 5109, 4000, 0, null));
        List<byte[]> tbs = Tbs.sequences(toc, 10_000, 4096);
        assertEquals(List.of("868803", "968802", "9a88"), hex(tbs));
        assertArrayEquals(new byte[] {(byte) 0x81}, Tbs.trailingEntry(new byte[0]));
        byte[] entry = Tbs.trailingEntry(new byte[] {1, 2, 3});
        assertEquals((byte) 0x84, entry[entry.length - 1]);
    }

    @Test
    void trailingSequencesMatchCalibreForNestedEntries() {
        List<Tbs.Node> twoLevels = List.of(node(0, 100, 8900, 0, null), node(1, 9000, 6000, 0, null),
                node(2, 15000, 5000, 0, null), node(3, 500, 4500, 1, 0), node(4, 5000, 4000, 1, 0),
                node(5, 9500, 5500, 1, 1), node(6, 12000, 3000, 2, 5));
        assertEquals(List.of("8288b0", "838880b402", "8288c0b8c090", "8a88c090c8", "9288"),
                hex(Tbs.sequences(twoLevels, 20_000, 4096)));
        List<Tbs.Node> deep = List.of(node(0, 0, 30000, 0, null), node(1, 40000, 1000, 0, null),
                node(2, 100, 2000, 1, 0), node(3, 2100, 27900, 1, 0), node(4, 3000, 9000, 2, 3),
                node(5, 12000, 18000, 2, 3), node(6, 12500, 100, 3, 5), node(7, 12600, 17400, 3, 5));
        assertEquals(List.of("8288a40290", "8288b09180", "8288b1809402", "8288b0a1809402", "8288b0a0a180",
                "8288b0a0a180", "8288b0a0a180", "8288b0a0a0", "", "8a88", "8a88"),
                hex(Tbs.sequences(deep, 41_000, 4096)));
    }

    private static Tbs.Node node(int index, long start, long length, int depth, Integer parent) {
        return new Tbs.Node(index, start, length, depth, parent);
    }

    private static List<String> hex(List<byte[]> sequences) {
        return sequences.stream().map(HexFormat.of()::formatHex).toList();
    }

    @Test
    void base32IsUpperCaseAndPadded() {
        assertEquals("0000", Kf8Writer.base32(0, 4));
        assertEquals("000V", Kf8Writer.base32(31, 4));
        assertEquals("0000000010", Kf8Writer.base32(32, 10));
    }
}
