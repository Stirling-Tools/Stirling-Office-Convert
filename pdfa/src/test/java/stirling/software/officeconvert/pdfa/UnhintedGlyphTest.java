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

    @Test
    void aSubsetOfOnlyEmptyGlyphsIsStillAReadableFont() throws Exception {
        TrueTypeWriter w = new TrueTypeWriter(1000);
        w.addEmpty(250);
        byte[] font = w.build("Empty", Cmaps.table(java.util.List.of(new Cmaps.Subtable(3, 0, new java.util.TreeMap<>(
                java.util.Map.of(0xF020, 1))))));
        try (org.apache.fontbox.ttf.TrueTypeFont t = new org.apache.fontbox.ttf.TTFParser()
                .parse(new org.apache.pdfbox.io.RandomAccessReadBuffer(font))) {
            org.junit.jupiter.api.Assertions.assertEquals(2, t.getNumberOfGlyphs());
            org.junit.jupiter.api.Assertions.assertNotNull(t.getGlyph());
            org.junit.jupiter.api.Assertions.assertEquals(250, t.getAdvanceWidth(1));
        }
    }

    @Test
    void aFontOfOnlyAnEmptyNotdefStillReads() throws Exception {
        byte[] font = new TrueTypeWriter(1000).build("Notdef", Cmaps.table(java.util.List.of(new Cmaps.Subtable(3, 0,
                new java.util.TreeMap<>()))));
        try (org.apache.fontbox.ttf.TrueTypeFont t = new org.apache.fontbox.ttf.TTFParser()
                .parse(new org.apache.pdfbox.io.RandomAccessReadBuffer(font))) {
            org.junit.jupiter.api.Assertions.assertNotNull(t.getIndexToLocation());
            org.junit.jupiter.api.Assertions.assertEquals(2, t.getNumberOfGlyphs());
        }
    }
}
