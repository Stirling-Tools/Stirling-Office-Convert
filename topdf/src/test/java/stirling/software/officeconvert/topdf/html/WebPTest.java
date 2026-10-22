package stirling.software.officeconvert.topdf.html;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class WebPTest {

    @Test
    void aStillPictureMustFitItsCanvas() {
        byte[] small = riff(vp8x(0, 1, 1), chunk("VP8 ", vp8(1, 1)));
        assertArrayEquals(new int[] {1, 1}, WebP.size(small));
        assertTrue(WebP.fits(small, WebP.size(small)));
        byte[] lossy = riff(vp8x(0, 1, 1), chunk("VP8 ", vp8(12_000, 12_000)));
        assertFalse(WebP.fits(lossy, WebP.size(lossy)));
        assertNull(WebP.redraw(lossy));
        byte[] lossless = riff(vp8x(0, 1, 1), chunk("VP8L", vp8l(12_000, 12_000)));
        assertFalse(WebP.fits(lossless, WebP.size(lossless)));
    }

    @Test
    void everyAnimationFrameMustFitItsCanvas() {
        byte[] fine = riff(vp8x(2, 100, 100), chunk("ANIM", new byte[6]), chunk("ANMF",
                anmf(0, 0, 50, 50, "VP8L", vp8l(50, 50))));
        assertTrue(WebP.fits(fine, WebP.size(fine)));
        byte[] bigBitstream = riff(vp8x(2, 1, 1), chunk("ANIM", new byte[6]), chunk("ANMF", anmf(0, 0, 1, 1, "VP8 ",
                vp8(12_000, 12_000))));
        assertFalse(WebP.fits(bigBitstream, WebP.size(bigBitstream)));
        byte[] bigFrame = riff(vp8x(2, 10, 10), chunk("ANIM", new byte[6]), chunk("ANMF", anmf(4, 0, 10, 10, "VP8L",
                vp8l(10, 10))));
        assertFalse(WebP.fits(bigFrame, WebP.size(bigFrame)));
    }

    private static byte[] riff(byte[]... chunks) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
        for (byte[] c : chunks) {
            body.writeBytes(c);
        }
        byte[] b = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(le(b.length, 4));
        out.writeBytes(b);
        return out.toByteArray();
    }

    private static byte[] chunk(String id, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(id.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(le(data.length, 4));
        out.writeBytes(data);
        if ((data.length & 1) != 0) {
            out.write(0);
        }
        return out.toByteArray();
    }

    private static byte[] vp8x(int flags, int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) flags, 0, 0, 0});
        out.writeBytes(le(width - 1, 3));
        out.writeBytes(le(height - 1, 3));
        return chunk("VP8X", out.toByteArray());
    }

    private static byte[] vp8(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {0, 0, 0, (byte) 0x9D, 0x01, 0x2A});
        out.writeBytes(le(width, 2));
        out.writeBytes(le(height, 2));
        out.writeBytes(new byte[16]);
        return out.toByteArray();
    }

    private static byte[] vp8l(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x2F);
        out.writeBytes(le((width - 1) | (height - 1) << 14, 4));
        out.writeBytes(new byte[16]);
        return out.toByteArray();
    }

    private static byte[] anmf(int x, int y, int width, int height, String id, byte[] bitstream) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(le(x / 2, 3));
        out.writeBytes(le(y / 2, 3));
        out.writeBytes(le(width - 1, 3));
        out.writeBytes(le(height - 1, 3));
        out.writeBytes(le(100, 3));
        out.write(0);
        out.writeBytes(chunk(id, bitstream));
        return out.toByteArray();
    }

    private static byte[] le(int v, int bytes) {
        byte[] b = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            b[i] = (byte) (v >>> 8 * i);
        }
        return b;
    }
}
