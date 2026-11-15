package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;

import org.junit.jupiter.api.Test;

class LogicalOrderDirectionTest {

    @Test
    void directionMatchesCodePointClassificationAcrossUnicodeAndMixedRuns() {
        for (int cp = Character.MIN_CODE_POINT; cp <= Character.MAX_CODE_POINT; cp++) {
            String text = new String(Character.toChars(cp));
            assertEquals(reference(text), LogicalOrder.isRtl(text));
        }
        Random random = new Random(1313);
        for (int sample = 0; sample < 20000; sample++) {
            StringBuilder text = new StringBuilder();
            for (int i = random.nextInt(12); i > 0; i--) {
                text.appendCodePoint(random.nextInt(Character.MAX_CODE_POINT + 1));
            }
            String run = text.toString();
            assertEquals(reference(run), LogicalOrder.isRtl(run));
        }
    }

    private static boolean reference(String text) {
        return text.codePoints().anyMatch(cp -> {
            byte direction = Character.getDirectionality(cp);
            return direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                    || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC;
        });
    }
}
