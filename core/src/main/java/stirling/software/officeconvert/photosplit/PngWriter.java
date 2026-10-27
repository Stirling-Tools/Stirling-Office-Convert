package stirling.software.officeconvert.photosplit;

import java.awt.image.BufferedImage;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

final class PngWriter {

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final int CHUNK = 1 << 16;
    private static final int SUB = 1;

    private PngWriter() {}

    static void write(BufferedImage image, OutputStream out) throws IOException {
        Picture picture = Source.of(image);
        int w = picture.width();
        int h = picture.height();
        boolean gray = picture.gray();
        int channels = gray ? 1 : 3;
        DataOutputStream data = new DataOutputStream(out);
        data.write(SIGNATURE);
        byte[] header = new byte[13];
        int[] fields = {w, h};
        for (int k = 0; k < 2; k++) {
            header[4 * k] = (byte) (fields[k] >>> 24);
            header[4 * k + 1] = (byte) (fields[k] >>> 16);
            header[4 * k + 2] = (byte) (fields[k] >>> 8);
            header[4 * k + 3] = (byte) fields[k];
        }
        header[8] = 8;
        header[9] = (byte) (gray ? 0 : 2);
        chunk(data, "IHDR", header, header.length);
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        deflater.setStrategy(Deflater.HUFFMAN_ONLY);
        try (DeflaterOutputStream z = new DeflaterOutputStream(new Chunks(data), deflater, CHUNK)) {
            byte[] row = new byte[1 + w * channels];
            row[0] = SUB;
            for (int y = 0; y < h; y++) {
                PhotoSplitter.checkpoint();
                int previous = 0;
                for (int x = 0; x < w; x++) {
                    int p = picture.rgb(x, y);
                    if (gray) {
                        int v = p & 0xFF;
                        row[1 + x] = (byte) (v - (previous & 0xFF));
                        previous = v;
                    } else {
                        int i = 1 + 3 * x;
                        row[i] = (byte) ((p >> 16) - (previous >> 16));
                        row[i + 1] = (byte) ((p >> 8) - (previous >> 8));
                        row[i + 2] = (byte) (p - previous);
                        previous = p;
                    }
                }
                z.write(row);
            }
        } finally {
            deflater.end();
        }
        chunk(data, "IEND", new byte[0], 0);
        data.flush();
    }

    private static void chunk(DataOutputStream out, String type, byte[] body, int length) throws IOException {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(body, 0, length);
        out.writeInt(length);
        out.write(name);
        out.write(body, 0, length);
        out.writeInt((int) crc.getValue());
    }

    private static final class Chunks extends OutputStream {

        private final DataOutputStream out;
        private final byte[] buffer = new byte[CHUNK];
        private int size;

        Chunks(DataOutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            if (size == buffer.length) {
                flushChunk();
            }
            buffer[size++] = (byte) b;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                if (size == buffer.length) {
                    flushChunk();
                }
                int n = Math.min(len, buffer.length - size);
                System.arraycopy(b, off, buffer, size, n);
                size += n;
                off += n;
                len -= n;
            }
        }

        @Override
        public void close() throws IOException {
            flushChunk();
        }

        private void flushChunk() throws IOException {
            if (size > 0) {
                chunk(out, "IDAT", buffer, size);
                size = 0;
            }
        }
    }
}
