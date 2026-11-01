package stirling.software.officeconvert.topdf.font;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class CoverageScreenTest {

    @Test
    void aFaceWhoseCmapCannotBeScannedIsNotScreenedOut() {
        FontEntry broken = new FontEntry(null, new byte[] {1, 2, 3, 4}, -1, 0, "Broken", "Broken", "Regular", "Broken",
                List.of(), List.of(), 400, false, false, false, false, false, null);
        assertTrue(broken.mayCover(0x4E00));
    }
}
