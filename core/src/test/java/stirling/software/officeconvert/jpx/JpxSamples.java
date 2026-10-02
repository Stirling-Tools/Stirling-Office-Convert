package stirling.software.officeconvert.jpx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

final class JpxSamples {

    static final int WIDTH = 61;
    static final int HEIGHT = 47;

    private JpxSamples() {}

    static int value(int x, int y, int c) {
        if (x >= 20 && x < 40 && y >= 10 && y < 30) {
            return (x * 73 ^ y * 151 ^ c * 37) * 0x9E3779B1 >>> 24;
        }
        return x * 5 + y * 3 + c * 40 + ((x * x + y * y * 3 + c * 7) >> 3) & 255;
    }

    static int value16(int x, int y) {
        return value(x, y, 0) * 257 ^ x * 31 & 0xFF;
    }

    static int value12(int x, int y) {
        return value(x, y, 0) << 4 | x + y & 15;
    }

    static byte[] resource(String name) {
        try (InputStream in = JpxSamples.class.getResourceAsStream("/jpx/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static long crc(JpxRaster r) {
        CRC32 crc = new CRC32();
        for (int c = 0; c < r.components(); c++) {
            for (int y = 0; y < r.height(c); y++) {
                for (int x = 0; x < r.width(c); x++) {
                    int v = r.sample(c, x, y);
                    crc.update(v >> 8);
                    crc.update(v);
                }
            }
        }
        return crc.getValue();
    }

    static byte[] box(String type, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            body.writeBytes(p);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int len = body.size() + 8;
        out.writeBytes(new byte[] {(byte) (len >> 24), (byte) (len >> 16), (byte) (len >> 8), (byte) len});
        out.writeBytes(type.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    static byte[] jp2(byte[] codestream, int components, int depth, byte[]... headerBoxes) {
        byte[] signature = {0, 0, 0, 12, 'j', 'P', ' ', ' ', 0x0D, 0x0A, (byte) 0x87, 0x0A};
        byte[] ftyp = box("ftyp", "jp2 ".getBytes(StandardCharsets.US_ASCII), new byte[4],
                "jp2 ".getBytes(StandardCharsets.US_ASCII));
        byte[] ihdr = box("ihdr", new byte[] {0, 0, 0, (byte) HEIGHT, 0, 0, 0, (byte) WIDTH, 0, (byte) components,
                (byte) (depth - 1), 7, 0, 0});
        byte[][] header = new byte[headerBoxes.length + 1][];
        header[0] = ihdr;
        System.arraycopy(headerBoxes, 0, header, 1, headerBoxes.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(signature);
        out.writeBytes(ftyp);
        out.writeBytes(box("jp2h", header));
        out.writeBytes(box("jp2c", codestream));
        return out.toByteArray();
    }

    static byte[] enumerated(int space) {
        return box("colr", new byte[] {1, 0, 0, 0, 0, 0, (byte) space});
    }
}
