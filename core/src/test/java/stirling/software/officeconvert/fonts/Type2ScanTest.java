package stirling.software.officeconvert.fonts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class Type2ScanTest {

    private static final int CALL_SUBR = 10;

    private static final int ENDCHAR = 14;

    private static byte[] callsTo(int subr, int times) {
        byte[] cs = new byte[times * 2];
        for (int i = 0; i < cs.length; i += 2) {
            cs[i] = (byte) (subr - 107 + 139);
            cs[i + 1] = CALL_SUBR;
        }
        return cs;
    }

    @Test
    void readsSeacAccentComponents() {
        byte[] cs = {(byte) 139, (byte) 139, (byte) (65 + 139), (byte) (66 + 139), ENDCHAR};

        assertArrayEquals(new int[] {65, 66}, new Type2Scan(List.of(), List.of()).seac(cs));
    }

    @Test
    void refusesSubroutineFanOutWithinTheDepthLimit() {
        List<byte[]> local = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            local.add(callsTo(i + 1, 100));
        }
        local.add(new byte[] {11});

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(IllegalStateException.class,
                () -> new Type2Scan(local, List.of()).seac(callsTo(0, 1))));
    }
}
