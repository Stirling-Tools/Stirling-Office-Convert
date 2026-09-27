package stirling.software.officeconvert.extract;

import java.io.IOException;
import java.io.InterruptedIOException;

import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadView;

final class InterruptibleRead implements RandomAccessRead {

    private final RandomAccessRead in;

    InterruptibleRead(RandomAccessRead in) {
        this.in = in;
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
        return new RandomAccessReadView(new InterruptibleRead(in.createView(start, length)), 0, length, true);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
