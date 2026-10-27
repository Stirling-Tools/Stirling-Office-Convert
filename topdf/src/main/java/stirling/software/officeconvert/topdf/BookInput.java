package stirling.software.officeconvert.topdf;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

final class BookInput {

    private BookInput() {}

    static byte[] read(Path source, long limit, String what) throws IOException {
        OfficeToPdf.stopIfInterrupted();
        if (Files.size(source) > limit) {
            throw tooLarge(what, limit);
        }
        try (InputStream in = bounded(Files.newInputStream(source), limit, what)) {
            return in.readAllBytes();
        }
    }

    static InputStream bounded(InputStream input, long limit, String what) throws IOException {
        return new FilterInputStream(input) {
            private long count;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                OfficeToPdf.stopIfInterrupted();
                if (count > limit) {
                    throw tooLarge(what, limit);
                }
                int n = in.read(bytes, offset, (int) Math.min(length, limit - count + 1));
                if (n > 0 && (count += n) > limit) {
                    throw tooLarge(what, limit);
                }
                return n;
            }

            @Override
            public long skip(long n) throws IOException {
                byte[] bytes = new byte[8192];
                long skipped = 0;
                while (skipped < n) {
                    int read = read(bytes, 0, (int) Math.min(bytes.length, n - skipped));
                    if (read < 0) {
                        break;
                    }
                    skipped += read;
                }
                return skipped;
            }

            @Override
            public boolean markSupported() {
                return false;
            }

            @Override
            public synchronized void reset() throws IOException {
                throw new IOException("Mark/reset is not supported");
            }
        };
    }

    private static IOException tooLarge(String what, long limit) {
        return new IOException(what + " is larger than " + (limit >> 20) + " MB");
    }
}
