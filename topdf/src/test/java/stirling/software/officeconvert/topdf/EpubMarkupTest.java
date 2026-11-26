package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.Random;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class EpubMarkupTest {

    @Test
    void unclosedDoctypesInAChapterAreSkippedInLinearTime() {
        String chapter = "<!DOCTYPE".repeat(1 << 20);
        assertEquals(chapter, assertTimeoutPreemptively(Duration.ofSeconds(5), () -> EpubBook.xhtmlAsHtml(chapter)));
    }

    @Test
    void doctypeRemovalMatchesTheRegexItReplaced() {
        Pattern doctype = Pattern.compile("<!DOCTYPE[^>]*>", Pattern.CASE_INSENSITIVE);
        String[] pieces = {"<!DOCTYPE", "<!doctype", "<!DocType", "<!", "<", ">", " html", "DOCTYPE", "x"};
        Random random = new Random(11);
        for (int run = 0; run < 20_000; run++) {
            StringBuilder s = new StringBuilder();
            for (int k = random.nextInt(8); k >= 0; k--) {
                s.append(pieces[random.nextInt(pieces.length)]);
            }
            String in = s.toString();
            assertEquals(doctype.matcher(in).replaceFirst(""), EpubBook.withoutDoctype(in), in);
        }
    }
}
