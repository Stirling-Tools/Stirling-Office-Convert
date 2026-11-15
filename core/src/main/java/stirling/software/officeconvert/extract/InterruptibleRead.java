package stirling.software.officeconvert.extract;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadView;

final class InterruptibleRead implements RandomAccessRead {

    private final RandomAccessRead in;

    private final Path deleteOnClose;

    InterruptibleRead(RandomAccessRead in) {
        this(in, null);
    }

    InterruptibleRead(RandomAccessRead in, Path deleteOnClose) {
        this.in = in;
        this.deleteOnClose = deleteOnClose;
    }

    private static void check() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Conversion interrupted");
        }
    }

    @Override
    public int read() throws IOException {
        check();
        return in.read();
    }

    @Override
    public int read(byte[] b, int offset, int length) throws IOException {
        check();
        return in.read(b, offset, length);
    }

    @Override
    public long getPosition() throws IOException {
        return in.getPosition();
    }

    @Override
    public void seek(long position) throws IOException {
        check();
        in.seek(position);
    }

    @Override
    public long length() throws IOException {
        return in.length();
    }

    @Override
    public boolean isClosed() {
        return in.isClosed();
    }

    @Override
    public int peek() throws IOException {
        check();
        return in.peek();
    }

    @Override
    public void rewind(int bytes) throws IOException {
        in.rewind(bytes);
    }

    @Override
    public boolean isEOF() throws IOException {
        return in.isEOF();
    }

    @Override
    public int available() throws IOException {
        return in.available();
    }

    @Override
    public void skip(int length) throws IOException {
        check();
        in.skip(length);
    }

    @Override
    public RandomAccessReadView createView(long start, long length) throws IOException {
        return new View(in.createView(start, length), length);
    }

    @Override
    public void close() throws IOException {
        try {
            in.close();
        } finally {
            if (deleteOnClose != null) {
                Files.deleteIfExists(deleteOnClose);
            }
        }
    }

    private static final class View extends RandomAccessReadView {
        private final RandomAccessReadView view;
        private byte[] buffer;
        private long bufferStart;
        private int bufferLength;
        private long position;

        View(RandomAccessReadView view, long length) {
            super(view, 0, length, true);
            this.view = view;
        }

        @Override
        public int read() throws IOException {
            readable();
            if (!buffered()) {
                return -1;
            }
            return buffer[(int) (position++ - bufferStart)] & 0xFF;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            readable();
            if (position >= view.length()) {
                return -1;
            }
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            if (length >= 8192) {
                view.seek(position);
                int count = view.read(bytes, offset, length);
                if (count > 0) {
                    position += count;
                }
                bufferLength = 0;
                return count;
            }
            if (!buffered()) {
                return -1;
            }
            int count = Math.min(length, (int) (bufferStart + bufferLength - position));
            System.arraycopy(buffer, (int) (position - bufferStart), bytes, offset, count);
            position += count;
            return count;
        }

        @Override
        public long getPosition() throws IOException {
            view.getPosition();
            return position;
        }

        @Override
        public void seek(long position) throws IOException {
            readable();
            if (position < 0) {
                throw new IOException("Invalid position " + position);
            }
            // Refills and direct reads synchronise the native cursor; logical seeks need no file access.
            this.position = position;
        }

        @Override
        public long length() throws IOException {
            return view.length();
        }

        @Override
        public boolean isClosed() {
            return view.isClosed();
        }

        @Override
        public int peek() throws IOException {
            int value = read();
            if (value >= 0) {
                position--;
            }
            return value;
        }

        @Override
        public void rewind(int bytes) throws IOException {
            seek(position - bytes);
        }

        @Override
        public boolean isEOF() throws IOException {
            return position >= view.length();
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(Integer.MAX_VALUE, view.length() - position);
        }

        @Override
        public void skip(int length) throws IOException {
            seek(position + length);
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                buffer = null;
                bufferLength = 0;
            }
        }

        private void readable() throws IOException {
            check();
            if (view.isClosed()) {
                throw new IOException("Reader is closed");
            }
        }

        private boolean buffered() throws IOException {
            if (position >= bufferStart && position < bufferStart + bufferLength) {
                return true;
            }
            if (position >= view.length()) {
                return false;
            }
            if (buffer == null) {
                buffer = new byte[(int) Math.min(8192, view.length())];
            }
            view.seek(position);
            bufferStart = position;
            bufferLength = Math.max(0, view.read(buffer, 0, buffer.length));
            return bufferLength > 0;
        }
    }
}
