package stirling.software.officeconvert.jpx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayOutputStream;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class PackedHeaderSegmentsTest {

    @Test
    void repeatedPackedHeaderSegmentsAreJoinedInLinearTime() {
        int segments = 1 << 20;
        int body = 4;
        ByteArrayOutputStream main = new ByteArrayOutputStream();
        for (int i = 0; i < segments; i++) {
            main.write(0xFF);
            main.write(0x60);
            main.write(0);
            main.write(body + 3);
            main.write(0);
            main.write(new byte[body], 0, body);
        }
        main.write(0xFF);
        main.write(0x90);
        byte[] data = main.toByteArray();
        MarkerSet into = new MarkerSet();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            HeaderParser.read(new Bytes(data, 0, data.length), 1, into, true);
            assertEquals((long) segments * body, into.packedHeaders().length);
        });
    }
}
