package stirling.software.officeconvert.photosplit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;

class RegionBudgetTest {

    private static byte[] rings(int size) {
        byte[] mask = new byte[size * size];
        for (int inset = 2; inset < size / 2 - 2; inset += 3) {
            int end = size - inset - 1;
            for (int p = inset; p <= end; p++) {
                mask[inset * size + p] = 1;
                mask[end * size + p] = 1;
                mask[p * size + inset] = 1;
                mask[p * size + end] = 1;
            }
        }
        return mask;
    }

    @Test
    void nestedDisconnectedOutlinesCannotExceedTheRegionAreaBudget() {
        int size = 257;
        byte[] mask = rings(size);
        assertNull(Region.label(mask, size, size, 8L * size * size));
    }

    @Test
    void budgetExhaustionLeavesTheOriginalStrongMaskUntouched() {
        int size = 257;
        byte[] mask = rings(size);
        byte[] original = mask.clone();
        PaleFrames.seed(mask, mask.clone(), new byte[mask.length], size, size, PhotoSplitter.Options.defaults());
        assertArrayEquals(original, mask);
    }

    @Test
    void boundedLabelsPreserveOrdinaryRegionsWithinBudget() {
        byte[] mask = new byte[20 * 20];
        for (int y = 3; y < 12; y++) {
            for (int x = 4; x < 16; x++) {
                mask[y * 20 + x] = 1;
            }
        }
        var ordinary = Region.label(mask.clone(), 20, 20);
        var bounded = Region.label(mask.clone(), 20, 20, 12L * 9);
        assertNotNull(bounded);
        assertEquals(ordinary.size(), bounded.size());
        assertEquals(ordinary.getFirst().rect(), bounded.getFirst().rect());
        assertArrayEquals(ordinary.getFirst().mask, bounded.getFirst().mask);
    }

    @Test
    void boundedRegionLabelingHonorsCancellation() {
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> Region.label(new byte[257 * 257], 257, 257, 257L * 257));
        } finally {
            Thread.interrupted();
        }
    }
}
