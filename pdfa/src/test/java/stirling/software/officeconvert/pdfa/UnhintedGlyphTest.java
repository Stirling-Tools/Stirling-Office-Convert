package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

class UnhintedGlyphTest {

    @Test
    void simpleGlyphsLoseTheirInstructionsAndKeepTheirPoints() {
        ByteBuffer b = ByteBuffer.allocate(10 + 2 + 2 + 3 + 3 + 6);
        b.putShort((short) 1).putShort((short) 0).putShort((short) 0).putShort((short) 10).putShort((short) 10);
        b.putShort((short) 2).putShort((short) 3).put(new byte[] {1, 2, 3}).put(new byte[] {1, 1, 1});
        b.put(new byte[] {0, 5, 0, 5, 0, 5});
        ByteBuffer e = ByteBuffer.allocate(10 + 2 + 2 + 3 + 6);
        e.putShort((short) 1).putShort((short) 0).putShort((short) 0).putShort((short) 10).putShort((short) 10);
        e.putShort((short) 2).putShort((short) 0).put(new byte[] {1, 1, 1}).put(new byte[] {0, 5, 0, 5, 0, 5});
        assertArrayEquals(e.array(), TrueTypeWriter.unhinted(b.array()));
    }

    @Test
    void compositeGlyphsDropTheTrailingInstructionsAndTheirFlag() {
        ByteBuffer b = ByteBuffer.allocate(10 + 6 + 6 + 2 + 2);
        b.putShort((short) -1).putShort((short) 0).putShort((short) 0).putShort((short) 10).putShort((short) 10);
        b.putShort((short) 0x0020).putShort((short) 3).put(new byte[] {0, 0});
        b.putShort((short) 0x0100).putShort((short) 4).put(new byte[] {1, 1});
        b.putShort((short) 2).put(new byte[] {7, 7});
        ByteBuffer e = ByteBuffer.allocate(10 + 6 + 6);
        e.putShort((short) -1).putShort((short) 0).putShort((short) 0).putShort((short) 10).putShort((short) 10);
        e.putShort((short) 0x0020).putShort((short) 3).put(new byte[] {0, 0});
        e.putShort((short) 0).putShort((short) 4).put(new byte[] {1, 1});
        assertArrayEquals(e.array(), TrueTypeWriter.unhinted(b.array()));
        byte[] plain = java.util.Arrays.copyOf(e.array(), e.capacity());
        assertArrayEquals(plain, TrueTypeWriter.unhinted(plain));
    }
}
