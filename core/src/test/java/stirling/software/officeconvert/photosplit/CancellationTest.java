package stirling.software.officeconvert.photosplit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CancellationTest {

    @TempDir
    Path dir;

    @Test
    void cancelledBufferedScanDoesNotRunEitherDetector() {
        BufferedImage scan = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        for (PhotoSplitter.Method method : PhotoSplitter.Method.values()) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(CancellationException.class,
                        () -> PhotoSplitter.split(scan, PhotoSplitter.Options.defaults().method(method)));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void interruptionWhileEncodingStopsBeforeWritingTheWholePicture() {
        BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream() {
            @Override
            public synchronized void write(byte[] data, int offset, int length) {
                super.write(data, offset, length);
                Thread.currentThread().interrupt();
            }
        };
        try {
            assertThrows(CancellationException.class, () -> PngWriter.write(image, out));
            assertTrue(out.size() < 200);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void cancelledFileRequestDoesNotCreateTheOutputDirectory() {
        Path output = dir.resolve("photos");
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,
                    () -> PhotoSplitter.split(dir.resolve("scan.png"), output, PhotoSplitter.Options.defaults()));
            assertFalse(Files.exists(output));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void cancelledEncodingPreservesExistingFilesAndRemovesTemporaryFiles() throws Exception {
        byte[] original = {1, 2, 3, 4};
        Path existing = dir.resolve("old.png");
        Files.write(existing, original);
        for (boolean overwrite : new boolean[] {false, true}) {
            Path target = overwrite ? existing : dir.resolve("new.png");
            BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB) {
                @Override
                public WritableRaster getRaster() {
                    Thread.currentThread().interrupt();
                    return super.getRaster();
                }
            };
            try {
                assertThrows(CancellationException.class, () -> PhotoSplitter.writeImage(image, target, overwrite));
            } finally {
                Thread.interrupted();
            }
            assertArrayEquals(original, Files.readAllBytes(existing));
            assertFalse(Files.exists(dir.resolve("new.png")));
            try (var files = Files.list(dir)) {
                assertEquals(1, files.count());
            }
        }
    }


    @Test
    void scriptPaddingCannotOverflowOrBypassThePixelLimit() {
        BufferedImage scan = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        for (int border : new int[] {Integer.MAX_VALUE, 10000}) {
            assertThrows(IllegalArgumentException.class, () -> PhotoSplitter.split(scan,
                    PhotoSplitter.Options.defaults().method(PhotoSplitter.Method.SCRIPT).borderSize(border)));
        }
    }


    @Test
    void stagedPublicationCannotReplaceAnExistingTargetWithoutOverwrite() throws Exception {
        Path target = dir.resolve("photo.png");
        byte[] original = {1, 2, 3, 4};
        Files.write(target, original);
        BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        assertThrows(FileAlreadyExistsException.class, () -> PhotoSplitter.writeImage(image, target, false));
        assertArrayEquals(original, Files.readAllBytes(target));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
    }

}
