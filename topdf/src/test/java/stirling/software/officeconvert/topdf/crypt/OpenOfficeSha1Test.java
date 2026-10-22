package stirling.software.officeconvert.topdf.crypt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.security.MessageDigest;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class OpenOfficeSha1Test {

    @Test
    void onlyLengthsTheOldDigestPaddedWronglyHaveASecondDigest() throws Exception {
        for (int length = 0; length < 200; length++) {
            byte[] message = new byte[length];
            Arrays.fill(message, (byte) '1');
            byte[] old = OpenOfficeSha1.digest(message);
            int tail = length % 64;
            if (tail < 52 || tail > 55) {
                assertNull(old, "length " + length);
            } else {
                assertEquals(20, old.length);
                assertFalse(Arrays.equals(MessageDigest.getInstance("SHA-1").digest(message), old), "length " + length);
            }
        }
    }

    @Test
    void theCompressionMatchesShaOneOnStandardPadding() throws Exception {
        for (int length : new int[] {0, 3, 55, 56, 64, 119, 200}) {
            byte[] message = new byte[length];
            Arrays.fill(message, (byte) 'z');
            int blocks = (length + 8) / 64 + 1;
            byte[] padded = Arrays.copyOf(message, blocks * 64);
            padded[length] = (byte) 0x80;
            long bits = (long) length * 8;
            for (int i = 0; i < 8; i++) {
                padded[padded.length - 1 - i] = (byte) (bits >>> (8 * i));
            }
            assertArrayEquals(MessageDigest.getInstance("SHA-1").digest(message), OpenOfficeSha1.compress(padded),
                    "length " + length);
        }
    }
}
