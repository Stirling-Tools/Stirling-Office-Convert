package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PdfFootprintAccountingTest {

    @Test
    void chargesStaySaturatedThroughAdditionAndMultiplication() {
        assertEquals(30, PdfFootprint.add(10, 20));
        assertEquals(600, PdfFootprint.multiply(20, 30));
        assertEquals(Long.MAX_VALUE, PdfFootprint.add(Long.MAX_VALUE - 10, 20));
        assertEquals(Long.MAX_VALUE, PdfFootprint.add(Long.MAX_VALUE, 1));
        assertEquals(Long.MAX_VALUE, PdfFootprint.multiply(Long.MAX_VALUE / 96 + 1, 96));
        assertEquals(0, PdfFootprint.multiply(Long.MAX_VALUE, 0));
    }

    @Test
    void shortRangesRetainEveryPage() {
        List<int[]> runs = PageSamples.runs(5, 44);
        assertEquals(1, runs.size());
        assertArrayEquals(new int[] {5, 44}, runs.getFirst());
    }

    @Test
    void longRangesRetainBothEndsAndDistributedPairs() {
        Set<Integer> sampled = new LinkedHashSet<>();
        int previous = -1;
        for (int[] run : PageSamples.runs(0, 999)) {
            assertTrue(run[0] > previous);
            for (int page = run[0]; page <= run[1]; page++) {
                assertTrue(sampled.add(page));
            }
            previous = run[1];
        }
        for (int page = 0; page < 20; page++) {
            assertTrue(sampled.contains(page));
        }
        for (int page = 995; page < 1000; page++) {
            assertTrue(sampled.contains(page));
        }
        assertTrue(sampled.containsAll(List.of(62, 63, 937, 938)));
        assertTrue(sampled.size() <= PdfFootprint.SAMPLED_PAGES);
    }
}
