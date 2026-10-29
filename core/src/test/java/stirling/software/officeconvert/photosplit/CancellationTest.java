package stirling.software.officeconvert.photosplit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    void interruptedCallerStopsWorkSpreadOverOtherThreads() {
        AtomicInteger ran = new AtomicInteger();
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> Parallel.each(8, i -> ran.incrementAndGet()));
            assertEquals(0, ran.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void workersStopWhenTheCallerIsInterruptedWhileItWaits() throws Exception {
        assumeTrue(Parallel.enabled());
        Thread caller = Thread.currentThread();
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        Thread interrupter = Thread.ofPlatform().start(() -> {
            try {
                if (waiting.await(5, TimeUnit.SECONDS)) {
                    Thread.sleep(100);
                    caller.interrupt();
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                release.countDown();
            }
        });
        try {
            assertThrows(CancellationException.class, () -> Parallel.each(16, i -> {
                if (Thread.currentThread() == caller) {
                    return;
                }
                waiting.countDown();
                while (release.getCount() > 0) {
                    Thread.onSpinWait();
                }
                PhotoSplitter.checkpoint();
                ran.incrementAndGet();
            }));
            assertEquals(0, ran.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            interrupter.join();
            Thread.interrupted();
        }
    }

    @Test
    void workerSeesTheCallerInterruptedPartWayThrough() {
        Thread caller = Thread.currentThread();
        AtomicInteger stopped = new AtomicInteger();
        try {
            assertThrows(CancellationException.class, () -> Parallel.each(8, i -> {
                caller.interrupt();
                try {
                    PhotoSplitter.checkpoint();
                } catch (CancellationException e) {
                    stopped.incrementAndGet();
                    throw e;
                }
            }));
            assertTrue(stopped.get() > 0);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aFailingTaskWaitsForTheOthersBeforeItIsReported() {
        assumeTrue(Parallel.enabled());
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> Parallel.each(4, i -> {
            if (i == 0) {
                try {
                    started.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("first");
            }
            running.incrementAndGet();
            started.countDown();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            running.decrementAndGet();
        }));
        assertEquals("first", thrown.getMessage());
        assertEquals(0, running.get());
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
