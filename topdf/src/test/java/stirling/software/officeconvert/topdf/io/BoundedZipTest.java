package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.ZipBytes;

class BoundedZipTest {

    @TempDir
    Path dir;

    private static byte[] sharedPart(String name, byte[] content, int copies) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
        d.setInput(content);
        d.finish();
        ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        while (!d.finished()) {
            deflated.write(buf, 0, d.deflate(buf));
        }
        d.end();
        byte[] data = deflated.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(content);
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        ByteBuffer out = ByteBuffer.allocate(30 + n.length + data.length + copies * (46 + n.length) + 22)
                .order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x04034b50).putShort((short) 20).putShort((short) 0).putShort((short) 8).putInt(0)
                .putInt((int) crc.getValue()).putInt(data.length).putInt(content.length).putShort((short) n.length)
                .putShort((short) 0).put(n).put(data);
        int directory = out.position();
        for (int i = 0; i < copies; i++) {
            out.putInt(0x02014b50).putShort((short) 20).putShort((short) 20).putShort((short) 0).putShort((short) 8)
                    .putInt(0).putInt((int) crc.getValue()).putInt(data.length).putInt(content.length)
                    .putShort((short) n.length).putShort((short) 0).putShort((short) 0).putShort((short) 0)
                    .putShort((short) 0).putInt(0).putInt(0).put(n);
        }
        int size = out.position() - directory;
        out.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) copies)
                .putShort((short) copies).putInt(size).putInt(directory).putShort((short) 0);
        return out.array();
    }

    private static byte[] twoCopies(String name, byte[] first, byte[] second) {
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        byte[][] contents = {first, second};
        ByteBuffer out = ByteBuffer.allocate(2 * (30 + n.length) + first.length + second.length + 2 * (46 + n.length) + 22)
                .order(ByteOrder.LITTLE_ENDIAN);
        int[] offsets = new int[2];
        long[] crcs = new long[2];
        for (int i = 0; i < 2; i++) {
            CRC32 crc = new CRC32();
            crc.update(contents[i]);
            crcs[i] = crc.getValue();
            offsets[i] = out.position();
            out.putInt(0x04034b50).putShort((short) 10).putShort((short) 0).putShort((short) 0).putInt(0)
                    .putInt((int) crcs[i]).putInt(contents[i].length).putInt(contents[i].length)
                    .putShort((short) n.length).putShort((short) 0).put(n).put(contents[i]);
        }
        int directory = out.position();
        for (int i = 0; i < 2; i++) {
            out.putInt(0x02014b50).putShort((short) 20).putShort((short) 10).putShort((short) 0).putShort((short) 0)
                    .putInt(0).putInt((int) crcs[i]).putInt(contents[i].length).putInt(contents[i].length)
                    .putShort((short) n.length).putShort((short) 0).putShort((short) 0).putShort((short) 0)
                    .putShort((short) 0).putInt(0).putInt(offsets[i]).put(n);
        }
        int size = out.position() - directory;
        out.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) 2).putShort((short) 2)
                .putInt(size).putInt(directory).putShort((short) 0);
        return out.array();
    }

    private static byte[] filled(int n) {
        byte[] b = new byte[n];
        Arrays.fill(b, (byte) 'x');
        return b;
    }

    @Test
    void entryLimitsAreAppliedBeforeListingOrEstimatingParts() throws IOException {
        Path file = Files.write(dir.resolve("entries.zip"), new ZipBytes().add("one", filled(1))
                .add("two", filled(1)).bytes());
        OfficeZip.Limits limits = new OfficeZip.Limits(1, 100, 100, 0, 0);
        assertThrows(OfficeZip.Oversized.class, () -> BoundedZip.open(file, limits));
        ZipBytes many = new ZipBytes();
        for (int i = 0; i <= OfficeZip.Limits.DEFAULT.maxEntries(); i++) {
            many.add("p" + i, new byte[0]);
        }
        Path oversized = Files.write(dir.resolve("many.zip"), many.bytes());
        assertThrows(OfficeZip.Oversized.class, () -> BoundedZip.inflatedSize(oversized, List.of("content.xml"), 100));
    }

    @Test
    void manyDirectoryEntriesSharingOnePartAreRefusedBeforeAnythingIsInflated() throws IOException {
        byte[] content = new byte[8 << 20];
        Arrays.fill(content, (byte) ' ');
        Path file = Files.write(dir.resolve("shared.odt"), sharedPart("content.xml", content, 300));
        IOException e = assertThrows(IOException.class,
                () -> BoundedZip.inflatedSize(file, List.of("content.xml"), 256L << 20));
        assertTrue(e.getMessage().contains("overlap"), e.getMessage());
    }

    @Test
    void aRepeatedNameIsMeasuredAtEveryCopyAReaderCanOpen() throws IOException {
        byte[] small = filled(10);
        byte[] large = filled(5000);
        Path file = Files.write(dir.resolve("two.odt"), twoCopies("content.xml", small, large));
        try (BoundedZip z = BoundedZip.open(file)) {
            long opened = z.read(z.entry("content.xml"), Long.MAX_VALUE).length;
            assertTrue(BoundedZip.inflatedSize(file, List.of("content.xml"), 1L << 20) >= opened);
        }
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

    @Test
    void partsShareOneBudgetForWhatTheyInflateTo() throws IOException {
        Path file = Files.write(dir.resolve("shared.zip"), new ZipBytes().add("a", filled(60)).add("b", filled(60))
                .bytes());
        try (BoundedZip z = BoundedZip.open(file, new OfficeZip.Limits(10, 100, 100, 0, 0))) {
            assertArrayEquals(filled(60), z.read(z.entry("a"), 100));
            assertThrows(OfficeZip.Oversized.class, () -> z.read(z.entry("b"), 100));
        }
    }
}
