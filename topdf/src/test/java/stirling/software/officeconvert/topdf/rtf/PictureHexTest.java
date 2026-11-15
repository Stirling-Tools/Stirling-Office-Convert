package stirling.software.officeconvert.topdf.rtf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class PictureHexTest {

    @Test
    void bulkDecoderKeepsPerByteSemanticsForEveryByteValueAndChunkBoundary() {
        byte[] bytes = new byte[256 * 3];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        Picture reference = new Picture();
        for (byte value : bytes) {
            reference.hex(value & 255);
        }
        Picture bulk = new Picture();
        for (int offset = 0; offset < bytes.length; offset += 7) {
            bulk.hex(bytes, offset, Math.min(7, bytes.length - offset));
        }
        assertArrayEquals(reference.data.toByteArray(), bulk.data.toByteArray());
    }

    @Test
    void pictureDataStopsBeforeControlWordsAndKeepsFollowingBinaryData() throws Exception {
        RtfTokenizer tokenizer = new RtfTokenizer(new ByteArrayInputStream(
                "0011Aa zzbb\r\nCC\\bin3 xyz}".getBytes(StandardCharsets.US_ASCII)));
        Picture picture = new Picture();
        tokenizer.pictureHex(picture);
        assertArrayEquals(new byte[] {0, 0x11, (byte) 0xAA, (byte) 0xBB, (byte) 0xCC}, picture.data.toByteArray());
        assertEquals(RtfTokenizer.WORD, tokenizer.next());
        assertEquals("bin", tokenizer.word);
        assertEquals(3, tokenizer.param);
        assertEquals(3, tokenizer.copyBinary(3, picture.data, picture.room()));
        assertArrayEquals(new byte[] {0, 0x11, (byte) 0xAA, (byte) 0xBB, (byte) 0xCC, 'x', 'y', 'z'},
                picture.data.toByteArray());
        assertEquals(RtfTokenizer.CLOSE, tokenizer.next());
        assertFalse(picture.truncated);
    }

    @Test
    void oddHexDigitsSurviveInputBufferAndGroupBoundaries() throws Exception {
        byte[] source = (" ".repeat(65_535) + "A{0f}").getBytes(StandardCharsets.US_ASCII);
        RtfTokenizer tokenizer = new RtfTokenizer(new ByteArrayInputStream(source));
        Picture picture = new Picture();
        tokenizer.pictureHex(picture);
        assertEquals(RtfTokenizer.OPEN, tokenizer.next());
        tokenizer.pictureHex(picture);
        assertEquals(RtfTokenizer.CLOSE, tokenizer.next());
        picture.hex('1');
        assertArrayEquals(new byte[] {(byte) 0xA0, (byte) 0xF1}, picture.data.toByteArray());
    }

    @Test
    void bulkPictureDataKeepsTheByteLimit() {
        Picture picture = new Picture();
        byte[] prefix = new byte[Picture.MAX_BYTES - 9001];
        picture.data.writeBytes(prefix);
        byte[] hex = "010203".repeat(4096).getBytes(StandardCharsets.US_ASCII);
        picture.hex(hex, 0, hex.length);
        assertTrue(picture.truncated);
        assertEquals(Picture.MAX_BYTES, picture.data.size());
        byte[] bytes = picture.data.toByteArray();
        assertEquals(2, bytes[prefix.length + 8191]);
        assertEquals(1, bytes[Picture.MAX_BYTES - 1]);
    }

    @Test
    void bulkPictureReadStopsOnInterruption() {
        RtfTokenizer tokenizer = new RtfTokenizer(new ByteArrayInputStream(new byte[100_000]));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> tokenizer.pictureHex(new Picture()));
        } finally {
            Thread.interrupted();
        }
    }
}
