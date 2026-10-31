package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.jupiter.api.Test;

class ContentTokenCountTest {

    @Test
    void countsEachRunOfNonSpaceBytesAndEachDelimiterInsideOne() {
        assertEquals(0, ContentTokens.tokens(" \n\r\t\f\0".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals(7, ContentTokens.tokens("q 1 0 0 1 0 0".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals(7, ContentTokens.tokens("/F1 12 Tf[(a)<41>]TJ".getBytes(StandardCharsets.ISO_8859_1)));
        Random random = new Random(42);
        byte[] alphabet = " \n\r\t\f\0[]/(<>)a1.-ÿ".getBytes(StandardCharsets.ISO_8859_1);
        for (int run = 0; run < 200; run++) {
            byte[] content = new byte[random.nextInt(400)];
            for (int i = 0; i < content.length; i++) {
                content[i] = random.nextBoolean() ? alphabet[random.nextInt(alphabet.length)] : (byte) random.nextInt();
            }
            assertEquals(reference(content), ContentTokens.tokens(content));
        }
    }

    @Test
    void contentWithTooManyTokensIsRefused() {
        byte[] content = new byte[(int) ContentTokens.MAX_TOKENS * 2 + 2];
        java.util.Arrays.fill(content, (byte) ' ');
        for (int i = 0; i < content.length; i += 2) {
            content[i] = 'n';
        }
        assertThrows(Decoded.TooLarge.class, () -> ContentTokens.checkedTokens(content));
    }

    private static long reference(byte[] content) {
        long n = 0;
        boolean inside = false;
        for (byte b : content) {
            boolean space = b == ' ' || b == '\n' || b == '\r' || b == '\t' || b == '\f' || b == 0;
            boolean delimiter = b == '[' || b == ']' || b == '/' || b == '(' || b == '<';
            if (!space && (!inside || delimiter)) {
                n++;
            }
            inside = !space;
        }
        return n;
    }
}
