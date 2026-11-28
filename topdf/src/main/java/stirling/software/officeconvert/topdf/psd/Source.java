package stirling.software.officeconvert.topdf.psd;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class Source implements Closeable {

    private final FileChannel channel;

    final long size;

    Source(Path file) throws IOException {
        this.channel = FileChannel.open(file, StandardOpenOption.READ);
        this.size = channel.size();
    }

    int read(long position, byte[] into, int off, int len) throws IOException {
        if (position >= size || len == 0) {
            return 0;
        }
        ByteBuffer b = ByteBuffer.wrap(into, off, (int) Math.min(len, size - position));
        int total = 0;
        while (b.hasRemaining()) {
            int n = channel.read(b, position + total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    Cursor cursor(long position, long end, int buffer) {
        return new Cursor(this, position, Math.min(end, size), buffer);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    static final class Cursor {

        private final Source source;

        private final byte[] buf;

        private long bufStart;

        private int bufLen;

        private int at;

        private final long end;

        Cursor(Source source, long position, long end, int buffer) {
            this.source = source;
            this.buf = new byte[buffer];
            this.bufStart = position;
            this.end = Math.max(position, end);
        }

        long position() {
            return bufStart + at;
        }

        long end() {
            return end;
        }

        long remaining() {
            return end - position();
        }

        void seek(long position) {
            if (position >= bufStart && position <= bufStart + bufLen) {
                at = (int) (position - bufStart);
                return;
            }
            bufStart = position;
            bufLen = 0;
            at = 0;
        }

        void skip(long n) throws IOException {
            if (n < 0 || n > remaining()) {
                throw new EOFException("The PSD file ends inside a section");
            }
            seek(position() + n);
        }

        private boolean fill() throws IOException {
            bufStart += at;
            at = 0;
            long left = end - bufStart;
            if (left <= 0) {
                bufLen = 0;
                return false;
            }
            bufLen = source.read(bufStart, buf, 0, (int) Math.min(buf.length, left));
            return bufLen > 0;
        }

        int u8() throws IOException {
            if (at >= bufLen && !fill()) {
                throw new EOFException("The PSD file ends inside a section");
            }
            return buf[at++] & 0xFF;
        }

        int read(byte[] into, int off, int len) throws IOException {
            int done = 0;
            while (done < len) {
                if (at >= bufLen && !fill()) {
                    break;
                }
                int n = Math.min(len - done, bufLen - at);
                System.arraycopy(buf, at, into, off + done, n);
                at += n;
                done += n;
            }
            return done;
        }

        void fully(byte[] into) throws IOException {
            if (read(into, 0, into.length) != into.length) {
                throw new EOFException("The PSD file ends inside a section");
            }
        }

        int u16() throws IOException {
            return u8() << 8 | u8();
        }

        short i16() throws IOException {
            return (short) u16();
        }

        long u32() throws IOException {
            return (long) u16() << 16 | u16();
        }

        int i32() throws IOException {
            return (int) u32();
        }

        long u64() throws IOException {
            long v = u32() << 32 | u32();
            if (v < 0) {
                throw new IOException("A length in the PSD file is out of range");
            }
            return v;
        }

        long length(boolean big) throws IOException {
            return big ? u64() : u32();
        }

        int tag() throws IOException {
            return i32();
        }
    }
}
