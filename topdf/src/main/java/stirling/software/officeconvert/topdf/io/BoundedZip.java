package stirling.software.officeconvert.topdf.io;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class BoundedZip implements Closeable {

    private final ZipFile zip;

    private final OfficeZip.Limits limits;

    private long budget;

    private BoundedZip(ZipFile zip, OfficeZip.Limits limits) {
        this.zip = zip;
        this.limits = limits;
        this.budget = limits.maxTotalBytes();
    }

    public static BoundedZip open(Path file) throws IOException {
        return open(file, OfficeZip.Limits.DEFAULT);
    }

    public static BoundedZip open(Path file, OfficeZip.Limits limits) throws IOException {
        OfficeZip.checkNotInterrupted();
        return new BoundedZip(new ZipFile(file.toFile()), limits);
    }

    public int size() {
        return zip.size();
    }

    public void checkEntries() throws OfficeZip.Oversized {
        if (zip.size() > limits.maxEntries()) {
            throw new OfficeZip.Oversized("The document is too large: it has more than " + limits.maxEntries()
                    + " parts");
        }
    }

    public ZipEntry entry(String name) {
        return zip.getEntry(name);
    }

    public List<? extends ZipEntry> entries() {
        return Collections.list(zip.entries());
    }

    public InputStream open(ZipEntry e, long max) throws IOException {
        long cap = Math.min(max, limits.maxEntryBytes());
        if (e.getSize() > cap) {
            throw partTooLarge(e, cap);
        }
        OfficeZip.checkNotInterrupted();
        return new Counted(zip.getInputStream(e), e, cap);
    }

    public byte[] read(ZipEntry e, long max) throws IOException {
        try (InputStream in = open(e, max)) {
            return in.readAllBytes();
        }
    }

    public long copy(ZipEntry e, OutputStream out, long max) throws IOException {
        try (InputStream in = open(e, max)) {
            return in.transferTo(out);
        }
    }

    public static long inflatedSize(Path file, List<String> names, long max) throws IOException {
        long total = 0;
        try (ZipFile z = new ZipFile(file.toFile())) {
            byte[] scratch = new byte[1 << 16];
            for (String name : names) {
                ZipEntry e = z.getEntry(name);
                if (e == null) {
                    continue;
                }
                long size = 0;
                try (InputStream in = z.getInputStream(e)) {
                    for (int n; size <= max && (n = in.read(scratch)) > 0;) {
                        OfficeZip.checkNotInterrupted();
                        size += n;
                    }
                }
                total += Math.min(size, max + 1);
            }
        }
        return total;
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }

    private static OfficeZip.Oversized partTooLarge(ZipEntry e, long cap) {
        return new OfficeZip.Oversized("The document is too large: the part /" + e.getName() + " is over "
                + Math.max(1, cap >> 20) + " MB");
    }

    private final class Counted extends FilterInputStream {

        private final ZipEntry entry;

        private final long cap;

        private long count;

        Counted(InputStream in, ZipEntry entry, long cap) {
            super(in);
            this.entry = entry;
            this.cap = cap;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            OfficeZip.checkNotInterrupted();
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
                budget -= n;
                if (count > cap) {
                    throw partTooLarge(entry, cap);
                }
                if (budget < 0) {
                    throw new OfficeZip.Oversized("The document is too large: over " + (limits.maxTotalBytes() >> 20)
                            + " MB uncompressed");
                }
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            byte[] scratch = new byte[8192];
            long skipped = 0;
            while (skipped < n) {
                int r = read(scratch, 0, (int) Math.min(scratch.length, n - skipped));
                if (r < 0) {
                    break;
                }
                skipped += r;
            }
            return skipped;
        }
    }
}
