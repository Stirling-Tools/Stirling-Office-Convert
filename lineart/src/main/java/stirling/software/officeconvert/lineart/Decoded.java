package stirling.software.officeconvert.lineart;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.Filter;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import stirling.software.officeconvert.extract.PdfFiles;

final class Decoded {

    static final Set<COSName> STREAMING = Set.of(COSName.FLATE_DECODE, COSName.FLATE_DECODE_ABBREVIATION,
            COSName.LZW_DECODE, COSName.LZW_DECODE_ABBREVIATION, COSName.RUN_LENGTH_DECODE,
            COSName.RUN_LENGTH_DECODE_ABBREVIATION, COSName.ASCII_HEX_DECODE, COSName.ASCII_HEX_DECODE_ABBREVIATION,
            COSName.ASCII85_DECODE, COSName.ASCII85_DECODE_ABBREVIATION, COSName.CCITTFAX_DECODE,
            COSName.CCITTFAX_DECODE_ABBREVIATION, COSName.JBIG2_DECODE);

    private static final Set<COSName> FAX = Set.of(COSName.CCITTFAX_DECODE, COSName.CCITTFAX_DECODE_ABBREVIATION);

    private static final Set<Integer> REGIONS = Set.of(4, 6, 7, 20, 22, 23, 36, 38, 39, 40, 42, 43);

    private static final int PIPE = 1 << 16;

    private Decoded() {}

    static long rowBytes(PDImageXObject image) {
        int components;
        try {
            components = image.getColorSpace().getNumberOfComponents();
        } catch (IOException e) {
            components = 1;
        }
        return ((long) image.getWidth() * components * Math.max(1, image.getBitsPerComponent()) + 7) / 8;
    }

    static long limit(long need) {
        return need + Math.max(PIPE, need >> 6);
    }

    private static long stageLimit(long need) {
        return need > (Long.MAX_VALUE >> 3) ? Long.MAX_VALUE : 4 * need + (1 << 20);
    }

    static InputStream open(COSStream stream, List<COSName> names, long need) throws IOException {
        return open(stream, names, need, limit(need));
    }

    static void check(PDImageXObject image) throws IOException {
        COSStream stream = image.getCOSObject();
        List<COSName> names = Source.filters(stream);
        if (names == null) {
            return;
        }
        long need = Math.multiplyExact(rowBytes(image), (long) image.getHeight());
        int streaming = 0;
        while (streaming < names.size() && STREAMING.contains(names.get(streaming))) {
            streaming++;
        }
        if (streaming == names.size()) {
            drain(open(stream, names, need));
        } else if (streaming > 0) {
            drain(open(stream, names.subList(0, streaming), need, stageLimit(need)));
        }
    }

    static void drain(InputStream in) throws IOException {
        try (in) {
            byte[] skip = new byte[8192];
            while (in.read(skip) >= 0) {
                PdfFiles.stopIfInterrupted();
            }
        }
    }

    private static InputStream open(COSStream stream, List<COSName> names, long need, long limit)
            throws IOException {
        List<Filter> filters = new ArrayList<>();
        List<COSName> kept = new ArrayList<>();
        for (COSName name : names) {
            Filter filter = FilterFactory.INSTANCE.getFilter(name);
            if (!filters.contains(filter)) {
                filters.add(filter);
                kept.add(name);
            }
        }
        for (int i = 0; i < filters.size(); i++) {
            if (FAX.contains(kept.get(i))) {
                long cap = i == filters.size() - 1 ? limit : stageLimit(need);
                if (faxBytes(stream, i) > cap) {
                    throw new TooLarge(cap);
                }
            }
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> writers = new ArrayList<>();
        InputStream in = stream.createRawInputStream();
        try {
            for (int i = 0; i < filters.size(); i++) {
                Pipe pipe = new Pipe();
                PipedOutputStream out = new PipedOutputStream(pipe);
                long cap = i == filters.size() - 1 ? Long.MAX_VALUE : stageLimit(need);
                long bitmap = i == filters.size() - 1 ? limit : stageLimit(need);
                boolean jbig2 = COSName.JBIG2_DECODE.equals(kept.get(i));
                Filter filter = filters.get(i);
                InputStream from = in;
                int index = i;
                writers.add(Thread.ofVirtual().start(() -> {
                    try (from; OutputStream to = new Capped(out, cap)) {
                        try {
                            InputStream input = jbig2 ? checkedJbig2(from, stream, index, need, bitmap) : from;
                            filter.decode(input, to, stream, index);
                        } catch (Throwable e) {
                            if (!pipe.closed) {
                                failure.compareAndSet(null, e);
                            }
                        }
                    } catch (Throwable e) {
                        if (!pipe.closed) {
                            failure.compareAndSet(null, e);
                        }
                    }
                }));
                in = pipe;
            }
        } catch (IOException | RuntimeException e) {
            in.close();
            join(writers);
            throw e;
        }
        return new Limited(in, limit, failure, writers);
    }

    static COSDictionary parameters(COSStream stream, int index) {
        COSBase filter = stream.getDictionaryObject(COSName.F, COSName.FILTER);
        COSBase parms = stream.getDictionaryObject(COSName.DP, COSName.DECODE_PARMS);
        if (filter instanceof COSName && parms instanceof COSDictionary d) {
            return d;
        }
        if (filter instanceof COSArray && parms instanceof COSArray array && index < array.size()
                && array.getObject(index) instanceof COSDictionary d) {
            return d;
        }
        return new COSDictionary();
    }

    private static InputStream checkedJbig2(InputStream from, COSStream stream, int index, long need, long bitmap)
            throws IOException {
        int height = stream.getInt(COSName.HEIGHT, COSName.H, 0);
        if (parameters(stream, index).getDictionaryObject(COSName.JBIG2_GLOBALS) instanceof COSStream globals) {
            List<COSName> names = Source.filters(globals);
            if (names == null) {
                throw new IOException("The JBIG2 globals have a malformed filter list");
            }
            checkJbig2(read(open(globals, names, stageLimit(need)), stageLimit(need)), bitmap, height);
        }
        byte[] data = read(from, stageLimit(need));
        checkJbig2(data, bitmap, height);
        return new ByteArrayInputStream(data);
    }

    private static byte[] read(InputStream in, long cap) throws IOException {
        try (in) {
            byte[] data = in.readNBytes((int) Math.min(cap + 1, Integer.MAX_VALUE - 8));
            if (data.length > cap) {
                throw new TooLarge(cap);
            }
            return data;
        }
    }

    private static void checkJbig2(byte[] data, long bitmap, int height) throws TooLarge {
        long at = 0;
        while (at + 11 <= data.length) {
            long number = u32(data, at);
            int flags = data[(int) at + 4] & 0xFF;
            int type = flags & 0x3F;
            int count = (data[(int) at + 5] & 0xFF) >>> 5;
            at += 5;
            if (count == 7) {
                if (at + 4 > data.length) {
                    return;
                }
                count = (int) (u32(data, at) & 0x1FFFFFFF);
                at += 4 + (count + 8L) / 8;
            } else {
                at += 1;
            }
            at += (long) count * (number <= 256 ? 1 : number <= 65536 ? 2 : 4);
            at += (flags & 0x40) != 0 ? 4 : 1;
            if (at + 4 > data.length) {
                return;
            }
            long length = u32(data, at);
            at += 4;
            if ((type == 48 || REGIONS.contains(type)) && at + 8 <= data.length) {
                long w = u32(data, at);
                long h = u32(data, at + 4);
                if (type == 48 && h == 0xFFFFFFFFL) {
                    h = Math.max(0, height);
                }
                if ((w + 7) / 8 * h > bitmap) {
                    throw new TooLarge(bitmap);
                }
            }
            if (length == 0xFFFFFFFFL || type == 51) {
                return;
            }
            at += length;
        }
    }

    private static long u32(byte[] data, long at) {
        int i = (int) at;
        return ((data[i] & 0xFFL) << 24) | ((data[i + 1] & 0xFFL) << 16) | ((data[i + 2] & 0xFFL) << 8)
                | (data[i + 3] & 0xFFL);
    }

    private static long faxBytes(COSStream stream, int index) {
        COSDictionary dict = parameters(stream, index);
        int cols = dict.getInt(COSName.COLUMNS, 1728);
        int rows = dict.getInt(COSName.ROWS, 0);
        int height = stream.getInt(COSName.HEIGHT, COSName.H, 0);
        rows = rows > 0 && height > 0 ? height : Math.max(rows, height);
        return (cols + 7L) / 8 * Math.max(0, rows);
    }

    private static void join(List<Thread> writers) {
        for (int i = writers.size() - 1; i >= 0; i--) {
            try {
                writers.get(i).join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static final class Pipe extends PipedInputStream {
        volatile boolean closed;

        Pipe() {
            super(PIPE);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
            synchronized (this) {
                notifyAll();
            }
        }
    }

    private static final class Capped extends FilterOutputStream {
        private final long cap;
        private long written;

        Capped(OutputStream out, long cap) {
            super(out);
            this.cap = cap;
        }

        @Override
        public void write(int b) throws IOException {
            check(1);
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            check(len);
            out.write(b, off, len);
        }

        private void check(int more) throws IOException {
            written += more;
            if (written > cap) {
                throw new TooLarge(cap);
            }
        }
    }

    private static final class Limited extends FilterInputStream {
        private final long limit;
        private final AtomicReference<Throwable> failure;
        private final List<Thread> writers;
        private long served;

        Limited(InputStream in, long limit, AtomicReference<Throwable> failure, List<Thread> writers) {
            super(in);
            this.limit = limit;
            this.failure = failure;
            this.writers = writers;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n < 0) {
                failed();
                return n;
            }
            served += n;
            if (served > limit) {
                throw new TooLarge(limit);
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            byte[] skip = new byte[(int) Math.min(n, 8192)];
            long done = 0;
            while (done < n) {
                int r = read(skip, 0, (int) Math.min(skip.length, n - done));
                if (r < 0) {
                    break;
                }
                done += r;
            }
            return done;
        }

        @Override
        public void close() throws IOException {
            super.close();
            join(writers);
        }

        private void failed() throws IOException {
            Throwable e = failure.get();
            if (e instanceof IOException io) {
                throw io;
            }
            if (e != null) {
                throw new IOException("The image data could not be decoded: " + e.getMessage(), e);
            }
        }
    }
}
