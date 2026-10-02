package stirling.software.officeconvert.topdf.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ZipBytes {

    public interface Body {
        void write(OutputStream out) throws IOException;
    }

    private record Part(String name, Body body) {}

    private final List<Part> parts = new ArrayList<>();

    public ZipBytes add(String name, byte[] data) {
        parts.add(new Part(name, out -> out.write(data)));
        return this;
    }

    public ZipBytes add(String name, String text) {
        return add(name, text.getBytes(StandardCharsets.UTF_8));
    }

    public ZipBytes repeat(String name, String head, byte[] chunk, long times, String tail) {
        parts.add(new Part(name, out -> {
            out.write(head.getBytes(StandardCharsets.UTF_8));
            for (long i = 0; i < times; i++) {
                out.write(chunk);
            }
            out.write(tail.getBytes(StandardCharsets.UTF_8));
        }));
        return this;
    }

    public byte[] bytes() {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Part p : parts) {
                zip.putNextEntry(new ZipEntry(p.name()));
                p.body().write(zip);
                zip.closeEntry();
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] declareSize(byte[] zip, String name, int size) {
        byte[] out = zip.clone();
        ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        byte[] wanted = name.getBytes(StandardCharsets.UTF_8);
        boolean found = false;
        for (int i = 0; i + 46 <= out.length; i++) {
            if (b.getInt(i) != 0x02014b50) {
                continue;
            }
            int nameLength = b.getShort(i + 28) & 0xffff;
            if (nameLength == wanted.length && java.util.Arrays.equals(out, i + 46, i + 46 + nameLength, wanted, 0,
                    wanted.length)) {
                b.putInt(i + 24, size);
                found = true;
            }
        }
        if (!found) {
            throw new IllegalArgumentException("No entry " + name);
        }
        return out;
    }
}
