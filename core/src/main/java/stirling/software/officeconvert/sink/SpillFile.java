package stirling.software.officeconvert.sink;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class SpillFile implements Closeable {

    public record Block(long position, int length) {}

    private final String prefix;
    private Path file;
    private FileChannel channel;
    private long size;

    public SpillFile(String prefix) {
        this.prefix = prefix;
    }

    public Block append(byte[] bytes) throws IOException {
        if (channel == null) {
            file = Files.createTempFile(prefix, ".bin");
            channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
        }
        long at = size;
        ByteBuffer b = ByteBuffer.wrap(bytes);
        while (b.hasRemaining()) {
            size += channel.write(b, size);
        }
        return new Block(at, bytes.length);
    }

    public byte[] read(Block block) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(block.length());
        while (b.hasRemaining()) {
            if (channel.read(b, block.position() + b.position()) < 0) {
                throw new EOFException("Spilled block runs past the end of " + file);
            }
        }
        return b.array();
    }

    Path file() {
        return file;
    }

    @Override
    public void close() throws IOException {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } finally {
            Files.deleteIfExists(file);
            channel = null;
            file = null;
            size = 0;
        }
    }
}
