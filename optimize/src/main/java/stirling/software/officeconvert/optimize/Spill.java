package stirling.software.officeconvert.optimize;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class Spill implements Closeable {

    static final long MEMORY_BYTES = 16L << 20;

    private final List<byte[]> held = new ArrayList<>();

    private long[] offsets = new long[64];

    private int[] lengths = new int[64];

    private long inMemory;

    private Path file;

    private FileChannel channel;

    private long fileSize;

    int put(byte[] data) throws IOException {
        int index = held.size();
        if (index == offsets.length) {
            offsets = Arrays.copyOf(offsets, index * 2);
            lengths = Arrays.copyOf(lengths, index * 2);
        }
        lengths[index] = data.length;
        if (inMemory + data.length <= MEMORY_BYTES) {
            held.add(data);
            inMemory += data.length;
            return index;
        }
        open();
        held.add(null);
        offsets[index] = fileSize;
        ByteBuffer b = ByteBuffer.wrap(data);
        while (b.hasRemaining()) {
            fileSize += channel.write(b, fileSize);
        }
        return index;
    }

    int put(InputStream in, MessageDigest digest) throws IOException {
        int index = held.size();
        if (index == offsets.length) {
            offsets = Arrays.copyOf(offsets, index * 2);
            lengths = Arrays.copyOf(lengths, index * 2);
        }
        open();
        held.add(null);
        offsets[index] = fileSize;
        byte[] buf = new byte[1 << 16];
        long total = 0;
        for (int n; (n = in.read(buf)) > 0; ) {
            if (total + n > Integer.MAX_VALUE - 16) {
                throw new IOException("A stream is larger than 2 GB");
            }
            digest.update(buf, 0, n);
            ByteBuffer b = ByteBuffer.wrap(buf, 0, n);
            while (b.hasRemaining()) {
                fileSize += channel.write(b, fileSize);
            }
            total += n;
        }
        lengths[index] = (int) total;
        return index;
    }

    private void open() throws IOException {
        if (channel == null) {
            file = Files.createTempFile("pdf-optimize-", ".spill");
            channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE,
                    StandardOpenOption.DELETE_ON_CLOSE);
        }
    }

    int length(int index) {
        return lengths[index];
    }

    void copy(int index, OutputStream out) throws IOException {
        byte[] data = held.get(index);
        if (data != null) {
            out.write(data);
            return;
        }
        long position = offsets[index];
        long end = position + lengths[index];
        ByteBuffer b = ByteBuffer.allocate(Math.min(1 << 16, Math.max(1, lengths[index])));
        while (position < end) {
            b.clear();
            b.limit((int) Math.min(b.capacity(), end - position));
            int n = channel.read(b, position);
            if (n < 0) {
                throw new IOException("The optimizer's scratch file ended early");
            }
            out.write(b.array(), 0, n);
            position += n;
        }
    }

    @Override
    public void close() throws IOException {
        held.clear();
        if (channel != null) {
            channel.close();
            Files.deleteIfExists(file);
        }
    }
}
