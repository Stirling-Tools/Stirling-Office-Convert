package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.ZipBytes;

class BoundedZipTest {

    @TempDir
    Path dir;

    private static byte[] filled(int n) {
        byte[] b = new byte[n];
        Arrays.fill(b, (byte) 'x');
        return b;
    }

    @Test
    void aPartLongerThanItsDeclaredSizeIsRefusedWhenReadToTheLimit() throws IOException {
        byte[] zip = ZipBytes.declareSize(new ZipBytes().add("part", filled(1000)).bytes(), "part", 100);
        Path file = Files.write(dir.resolve("lie.zip"), zip);
        try (BoundedZip z = BoundedZip.open(file)) {
            ZipEntry e = z.entry("part");
            try (InputStream in = z.open(e, e.getSize())) {
                assertThrows(OfficeZip.Oversized.class, () -> in.readNBytes((int) e.getSize()));
            }
        }
    }

    @Test
    void aPartExactlyAtTheLimitIsReadWhole() throws IOException {
        Path file = Files.write(dir.resolve("exact.zip"), new ZipBytes().add("part", filled(100)).bytes());
        try (BoundedZip z = BoundedZip.open(file)) {
            ZipEntry e = z.entry("part");
            try (InputStream in = z.open(e, 100)) {
                assertArrayEquals(filled(100), in.readNBytes(100));
            }
            assertArrayEquals(filled(100), z.read(e, 100));
        }
    }
}
