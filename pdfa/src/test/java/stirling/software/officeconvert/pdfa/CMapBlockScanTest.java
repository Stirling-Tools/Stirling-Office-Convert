package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class CMapBlockScanTest {

    private static final String[] PIECES = {"begincidrange", "endcidrange", "begincidchar", "endcidchar", " ", "\n",
        "\t", "12", "3", "<00>", "<FF> 5", "x", "begin", "end", "cidrange", "1 ", "\r\n"};

    private static List<String> regex(String text, String begin, String end) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\d+\\s+" + begin + ".*?" + end, Pattern.DOTALL).matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    @Test
    void scanMatchesTheRegexItReplaces() {
        Random r = new Random(7);
        for (int i = 0; i < 20_000; i++) {
            StringBuilder sb = new StringBuilder();
            int n = r.nextInt(30);
            for (int k = 0; k < n; k++) {
                sb.append(PIECES[r.nextInt(PIECES.length)]);
            }
            String text = sb.toString();
            assertEquals(regex(text, "begincidrange", "endcidrange"),
                    CMapFixer.countedBlocks(text, "begincidrange", "endcidrange"), text);
            assertEquals(regex(text, "begincidchar", "endcidchar"),
                    CMapFixer.countedBlocks(text, "begincidchar", "endcidchar"), text);
        }
    }

    @Test
    void unclosedBlocksAndLongDigitRunsStayLinear() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200_000; i++) {
            sb.append("1 begincidrange ");
        }
        sb.append("9".repeat(2_000_000));
        String text = sb.toString();
        long start = System.nanoTime();
        assertTrue(CMapFixer.countedBlocks(text, "begincidrange", "endcidrange").isEmpty());
        assertTrue(CMapFixer.countedBlocks(text, "begincodespacerange", "endcodespacerange").isEmpty());
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000, "took too long");
    }
}
