package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import java.util.zip.Deflater;

import org.junit.jupiter.api.Test;

class Kf8FontTest {

    private static byte[] record(byte[] font, boolean compress, byte[] key) {
        byte[] data = font.clone();
        if (compress) {
            Deflater d = new Deflater();
            d.setInput(font);
            d.finish();
            ByteArrayOutputStream z = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!d.finished()) {
                z.write(buf, 0, d.deflate(buf));
            }
            d.end();
            data = z.toByteArray();
        }
        if (key != null) {
            for (int i = 0; i < Math.min(1040, data.length); i++) {
                data[i] ^= key[i % key.length];
            }
        }
        int keyStart = 24;
        int start = keyStart + (key == null ? 0 : key.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'F', 'O', 'N', 'T'});
        u32(out, font.length);
        u32(out, (compress ? 1 : 0) | (key != null ? 2 : 0));
        u32(out, start);
        u32(out, key == null ? 0 : key.length);
        u32(out, keyStart);
        if (key != null) {
            out.writeBytes(key);
        }
        out.writeBytes(data);
        return out.toByteArray();
    }

    private static void u32(ByteArrayOutputStream out, long v) {
        out.write((int) (v >>> 24) & 0xFF);
        out.write((int) (v >>> 16) & 0xFF);
        out.write((int) (v >>> 8) & 0xFF);
        out.write((int) v & 0xFF);
    }

    @Test
    void fontRecordsAreUnmaskedAndInflated() throws IOException {
        byte[] font = new byte[5000];
        new Random(4).nextBytes(font);
        byte[] key = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        assertArrayEquals(font, Kf8Book.font(record(font, true, key)));
        assertArrayEquals(font, Kf8Book.font(record(font, false, key)));
        assertArrayEquals(font, Kf8Book.font(record(font, true, null)));
        assertArrayEquals(font, Kf8Book.font(record(font, false, null)));
    }

    @Test
    void lyingFontRecordsAreDropped() throws IOException {
        byte[] font = new byte[3000];
        byte[] r = record(font, true, null);
        r[7] = 0x10;
        assertNull(Kf8Book.font(r));
        byte[] huge = record(font, true, null);
        huge[4] = 0x7F;
        assertNull(Kf8Book.font(huge));
        assertNull(Kf8Book.font(new byte[] {'F', 'O', 'N', 'T'}));
    }
}
