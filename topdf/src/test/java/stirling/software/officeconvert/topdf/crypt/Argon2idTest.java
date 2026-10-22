package stirling.software.officeconvert.topdf.crypt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

class Argon2idTest {

    private static byte[] filled(int length, int value) {
        byte[] b = new byte[length];
        Arrays.fill(b, (byte) value);
        return b;
    }

    @Test
    void matchesTheRfc9106TestVector() throws Exception {
        byte[] tag = Argon2id.hash(filled(32, 1), filled(16, 2), filled(8, 3), filled(12, 4), 3, 32, 4, 32);
        assertEquals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659", HexFormat.of().formatHex(tag));
    }

    @Test
    void blake2bMatchesTheRfc7693TestVector() {
        byte[] digest = new Blake2b(64).update("abc".getBytes(StandardCharsets.US_ASCII)).digest();
        assertEquals("ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d17d87c5392aab792dc252d5de4533cc95"
                + "18d38aa8dbf1925ab92386edd4009923", HexFormat.of().formatHex(digest));
    }
}
