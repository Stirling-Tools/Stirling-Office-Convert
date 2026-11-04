package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InterruptedIOException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.io.RandomAccessReadView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InterruptibleReadTest {

    @TempDir Path directory;

    @Test
    void memoryBackedViewsKeepIndependentLogicalPositionsAndObserveParentClosure() throws Exception {
        byte[] bytes = new byte[30_000];
        new Random(3218192).nextBytes(bytes);
        InterruptibleRead source = new InterruptibleRead(new RandomAccessReadBuffer(bytes));
        try (RandomAccessReadView first = source.createView(17, 20_000);
                RandomAccessReadView second = source.createView(25_000, 1000)) {
            source.seek(3);
            assertEquals(bytes[17] & 255, first.read());
            assertEquals(bytes[25_000] & 255, second.read());
            assertEquals(bytes[18] & 255, first.peek());
            first.seek(8190);
            byte[] crossing = new byte[20];
            int count = first.read(crossing);
            assertTrue(count > 0);
            assertArrayEquals(Arrays.copyOfRange(bytes, 17 + 8190, 17 + 8190 + count),
                    Arrays.copyOf(crossing, count));
            assertEquals(bytes[25_001] & 255, second.read());
            assertEquals(2, second.getPosition());
            assertEquals(3, source.getPosition());
            source.close();
            assertTrue(first.isClosed());
            assertThrows(IOException.class, first::read);
            assertThrows(IOException.class, () -> first.seek(1));
            assertThrows(IOException.class, second::peek);
        } finally {
            source.close();
        }
    }

    @Test
    void seekingBeyondEofAndBackPreservesContentsAndRejectsNegativePositions() throws Exception {
        try (InterruptibleRead source = new InterruptibleRead(new RandomAccessReadBuffer(new byte[] {3, 4, 5}));
                RandomAccessReadView view = source.createView(0, 3)) {
            assertEquals(3, view.read());
            view.seek(Long.MAX_VALUE);
            assertEquals(Long.MAX_VALUE, view.getPosition());
            assertTrue(view.isEOF());
            assertEquals(-1, view.read());
            assertEquals(-1, view.read(new byte[0]));
            view.seek(1);
            assertThrows(IOException.class, () -> view.seek(-1));
            assertEquals(1, view.getPosition());
            assertEquals(4, view.read());
            view.rewind(2);
            assertEquals(3, view.peek());
        }
    }

    @Test
    void emptyViewsAndZeroLengthReadsKeepNativeEndOfViewSemantics() throws Exception {
        try (InterruptibleRead source = new InterruptibleRead(new RandomAccessReadBuffer(new byte[] {3, 4}));
                RandomAccessReadView empty = source.createView(0, 0);
                RandomAccessReadView content = source.createView(0, 2)) {
            assertEquals(-1, empty.read());
            assertEquals(-1, empty.peek());
            assertEquals(-1, empty.read(new byte[0]));
            assertEquals(0, empty.available());
            assertEquals(0, content.read(new byte[0]));
            content.seek(2);
            assertEquals(-1, content.read(new byte[0]));
        }
    }

    @Test
    void mixedReadsAndPositionChangesKeepExactViewContents() throws Exception {
        byte[] bytes = new byte[30_000];
        Random random = new Random(8192305);
        random.nextBytes(bytes);
        Path file = directory.resolve("mixed.bin");
        Files.write(file, bytes);
        int start = 513;
        int length = 24_001;
        try (InterruptibleRead source = new InterruptibleRead(new RandomAccessReadBufferedFile(file));
                RandomAccessReadView view = source.createView(start, length)) {
            long position = 0;
            for (int iteration = 0; iteration < 5000; iteration++) {
                switch (random.nextInt(6)) {
                    case 0 -> {
                        position = random.nextInt(length + 100);
                        view.seek(position);
                    }
                    case 1 -> {
                        int actual = view.read();
                        assertEquals(position < length ? bytes[start + (int) position] & 255 : -1, actual);
                        if (actual >= 0) position++;
                    }
                    case 2 -> assertEquals(position < length ? bytes[start + (int) position] & 255 : -1, view.peek());
                    case 3 -> {
                        int requested = 1 + random.nextInt(12_000);
                        byte[] target = new byte[requested + 4];
                        Arrays.fill(target, (byte) 99);
                        int count = view.read(target, 2, requested);
                        if (position >= length) {
                            assertEquals(-1, count);
                        } else {
                            assertTrue(count > 0 && count <= requested && count <= length - position);
                            assertArrayEquals(Arrays.copyOfRange(bytes, start + (int) position,
                                    start + (int) position + count), Arrays.copyOfRange(target, 2, 2 + count));
                            assertEquals(99, target[0]);
                            assertEquals(99, target[count + 2]);
                            position += count;
                        }
                    }
                    case 4 -> {
                        int rewind = random.nextInt((int) position + 1);
                        view.rewind(rewind);
                        position -= rewind;
                    }
                    case 5 -> {
                        int skip = random.nextInt(30);
                        view.skip(skip);
                        position += skip;
                    }
                    default -> throw new AssertionError();
                }
                assertEquals(position, view.getPosition());
                assertEquals(position >= length, view.isEOF());
                assertEquals(length - position, view.available());
            }
        }
    }

    @Test
    void cachedBytesStillRespectCancellationAndClose() throws Exception {
        Path file = directory.resolve("cached.bin");
        Files.write(file, new byte[20_000]);
        try (InterruptibleRead source = new InterruptibleRead(new RandomAccessReadBufferedFile(file))) {
            RandomAccessReadView view = source.createView(0, 20_000);
            assertEquals(0, view.read());
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, view::read);
                assertThrows(InterruptedIOException.class, () -> view.read(new byte[4]));
                assertThrows(InterruptedIOException.class, view::peek);
                assertThrows(InterruptedIOException.class, () -> view.seek(0));
                assertThrows(InterruptedIOException.class, () -> view.rewind(1));
                assertThrows(InterruptedIOException.class, () -> view.skip(1));
            } finally {
                Thread.interrupted();
                view.close();
            }
            assertThrows(IOException.class, view::read);
            assertThrows(IOException.class, view::peek);
            assertThrows(IOException.class, view::getPosition);
            assertThrows(IOException.class, () -> view.seek(0));
        }
    }

    @Test
    void viewsRetainIndependentPositionsAndRespectTheirBounds() throws Exception {
        byte[] bytes = new byte[128];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        Path file = directory.resolve("input.bin");
        Files.write(file, bytes);
        try (InterruptibleRead source = new InterruptibleRead(new RandomAccessReadBufferedFile(file));
                RandomAccessReadView first = source.createView(10, 4);
                RandomAccessReadView second = source.createView(90, 3)) {
            assertEquals(10, first.read());
            assertEquals(90, second.read());
            assertEquals(11, first.peek());
            assertEquals(91, second.read());
            byte[] tail = new byte[8];
            assertEquals(3, first.read(tail));
            assertArrayEquals(new byte[] {11, 12, 13}, Arrays.copyOf(tail, 3));
            assertEquals(-1, first.read());
            first.seek(1);
            assertEquals(11, first.read());
            assertEquals(92, second.read());
            assertEquals(-1, second.read());
        }
    }

    @Test
    void closingAViewKeepsItsSourceAndOtherViewsOpen() throws Exception {
        Path file = directory.resolve("input.bin");
        Files.write(file, new byte[] {10, 20, 30});
        try (InterruptibleRead source = new InterruptibleRead(new RandomAccessReadBufferedFile(file));
                RandomAccessReadView second = source.createView(1, 2)) {
            RandomAccessReadView first = source.createView(0, 1);
            first.close();
            assertTrue(first.isClosed());
            assertFalse(source.isClosed());
            assertFalse(second.isClosed());
            assertEquals(20, second.read());
            source.seek(0);
            assertEquals(10, source.read());
        }
    }

    @Test
    void viewReadsAndSeeksKeepCancellationChecks() throws Exception {
        Path file = directory.resolve("input.bin");
        Files.write(file, new byte[] {10, 20, 30});
        try (InterruptibleRead source = new InterruptibleRead(new RandomAccessReadBufferedFile(file));
                RandomAccessReadView view = source.createView(0, 3)) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, view::read);
                assertThrows(InterruptedIOException.class, () -> view.read(new byte[3]));
                assertThrows(InterruptedIOException.class, view::peek);
                assertThrows(InterruptedIOException.class, () -> view.seek(1));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void fileViewsDoNotMoveTheirSourcesPosition() throws Exception {
        Path file = directory.resolve("input.bin");
        Files.write(file, new byte[] {10, 20, 30, 40});
        try (InterruptibleRead source = new InterruptibleRead(new RandomAccessReadBufferedFile(file))) {
            source.seek(3);
            try (RandomAccessReadView view = source.createView(0, 2)) {
                assertEquals(10, view.read());
                assertEquals(20, view.peek());
                assertEquals(3, source.getPosition());
                assertEquals(40, source.read());
                assertEquals(20, view.read());
            }
        }
    }
}
