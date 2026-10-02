package stirling.software.officeconvert.jpx;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.stream.MemoryCacheImageInputStream;

import org.junit.jupiter.api.Test;

class JpxReaderFailureTest {

    private interface Reading {
        Object read(JpxImageReader reader) throws IOException;
    }

    private static final class FailingInput extends MemoryCacheImageInputStream {
        private final Throwable failure;

        FailingInput(byte[] bytes, Throwable failure) {
            super(new ByteArrayInputStream(bytes));
            this.failure = failure;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw (OutOfMemoryError) failure;
        }
    }

    @Test
    void imageIoEntrypointsWrapRuntimeAndAllocationFailures() throws IOException {
        byte[] data = SyntheticCodestream.samples(4, 4, 1, 8, Progression.LRCP, true, false, false, false, false);
        List<Reading> reads = List.of(r -> r.getWidth(0), r -> r.getHeight(0), r -> r.getImageTypes(0),
                r -> r.read(0, null), r -> r.read(0, new ImageReadParam()));
        for (Throwable failure : List.of(new IllegalArgumentException("malformed decoded state"),
                new OutOfMemoryError("decoder allocation refused"))) {
            for (Reading reading : reads) {
                JpxImageReader reader = new JpxImageReader(new JpxImageReaderSpi());
                try (FailingInput input = new FailingInput(data, failure)) {
                    reader.setInput(input);
                    IIOException exception = assertThrows(IIOException.class, () -> reading.read(reader));
                    assertSame(failure, exception.getCause());
                } finally {
                    reader.dispose();
                }
            }
        }
    }

    @Test
    void invalidImageIoUsageKeepsItsDocumentedExceptions() throws IOException {
        JpxImageReader reader = new JpxImageReader(new JpxImageReaderSpi());
        try {
            assertThrows(IllegalStateException.class, () -> reader.read(0, null));
            assertThrows(IndexOutOfBoundsException.class, () -> reader.getWidth(1));
        } finally {
            reader.dispose();
        }
    }
}
