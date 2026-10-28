package stirling.software.officeconvert.lineart;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.zip.Deflater;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;

final class Codecs {

    record Encoded(LineArt.Codec codec, byte[] data, COSName filter, COSDictionary parameters) {}

    private Codecs() {}

    static Encoded encode(Bitmap bitmap, LineArt.Codec codec) throws IOException {
        LineArt.checkpoint();
        return switch (codec) {
            case JBIG2 -> jbig2(bitmap);
            case CCITT_G4 -> g4(bitmap);
            case FLATE -> flate(bitmap);
            case SMALLEST -> smallest(bitmap);
        };
    }

    private static Encoded smallest(Bitmap bitmap) {
        Encoded best = jbig2(bitmap);
        Encoded g4 = g4(bitmap, best.data().length);
        if (g4 != null) {
            best = g4;
        }
        Encoded flate = flate(bitmap, best.data().length);
        return flate != null ? flate : best;
    }

    static Encoded jbig2(Bitmap bitmap) {
        byte[] generic = Jbig2Generic.encode(bitmap);
        byte[] symbols = Jbig2Symbols.encode(bitmap);
        byte[] data = symbols != null && symbols.length < generic.length ? symbols : generic;
        return new Encoded(LineArt.Codec.JBIG2, data, COSName.JBIG2_DECODE, null);
    }

    static Encoded g4(Bitmap bitmap) {
        return g4(bitmap, Integer.MAX_VALUE);
    }

    private static Encoded g4(Bitmap bitmap, int shorterThan) {
        byte[] data = Group4.encode(bitmap, shorterThan);
        if (data == null) {
            return null;
        }
        COSDictionary parameters = new COSDictionary();
        parameters.setInt(COSName.K, -1);
        parameters.setInt(COSName.COLUMNS, bitmap.width());
        parameters.setInt(COSName.ROWS, bitmap.height());
        return new Encoded(LineArt.Codec.CCITT_G4, data, COSName.CCITTFAX_DECODE, parameters);
    }

    static Encoded flate(Bitmap bitmap) {
        return flate(bitmap, Integer.MAX_VALUE);
    }

    private static Encoded flate(Bitmap bitmap, int shorterThan) {
        boolean bounded = shorterThan != Integer.MAX_VALUE;
        long rough = bounded ? 3L * shorterThan : Long.MAX_VALUE;
        FutureTask<byte[]> upFuture = new FutureTask<>(() -> !bounded
                || deflate(bitmap, true, Deflater.BEST_SPEED, rough) != null
                ? deflate(bitmap, true, Deflater.BEST_COMPRESSION, shorterThan) : null);
        Thread.ofVirtual().start(upFuture);
        byte[] direct;
        byte[] up;
        try {
            direct = !bounded || deflate(bitmap, false, Deflater.BEST_SPEED, rough) != null
                    ? deflate(bitmap, false, Deflater.BEST_COMPRESSION, shorterThan) : null;
            up = upFuture.get();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new InterruptedIOException("Conversion interrupted"));
        } catch (ExecutionException failed) {
            throw new CompletionException(failed.getCause());
        } finally {
            upFuture.cancel(true);
        }
        if (up != null && (direct == null || up.length < direct.length)) {
            COSDictionary parameters = new COSDictionary();
            parameters.setInt(COSName.PREDICTOR, 15);
            parameters.setInt(COSName.COLORS, 1);
            parameters.setInt(COSName.BITS_PER_COMPONENT, 1);
            parameters.setInt(COSName.COLUMNS, bitmap.width());
            return new Encoded(LineArt.Codec.FLATE, up, COSName.FLATE_DECODE, parameters);
        }
        return direct == null ? null : new Encoded(LineArt.Codec.FLATE, direct, COSName.FLATE_DECODE, null);
    }

    private static byte[] deflate(Bitmap bitmap, boolean predicted, int level, long shorterThan) {
        byte[] raw = bitmap.packed();
        int stride = bitmap.stride();
        int h = bitmap.height();
        int tail = bitmap.width() & 7;
        byte keep = (byte) (tail == 0 ? 0xFF : 0xFF00 >>> tail & 0xFF);
        int rowBytes = predicted ? stride + 1 : stride;
        Deflater deflater = new Deflater(level);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 12);
            byte[] buffer = new byte[1 << 16];
            int rows = Math.max(1, (1 << 16) / rowBytes);
            byte[] chunk = new byte[rows * rowBytes];
            byte[] previous = new byte[stride];
            for (int y0 = 0; y0 < h; y0 += rows) {
                LineArt.checkpoint();
                int n = Math.min(rows, h - y0);
                for (int r = 0; r < n; r++) {
                    int src = (y0 + r) * stride;
                    int q = r * rowBytes;
                    if (predicted) {
                        chunk[q++] = 2;
                    }
                    for (int i = 0; i < stride; i++) {
                        byte v = (byte) ~raw[src + i];
                        if (i == stride - 1) {
                            v &= keep;
                        }
                        chunk[q + i] = predicted ? (byte) (v - previous[i]) : v;
                        previous[i] = v;
                    }
                }
                deflater.setInput(chunk, 0, n * rowBytes);
                boolean last = y0 + n >= h;
                if (last) {
                    deflater.finish();
                }
                while (last ? !deflater.finished() : !deflater.needsInput()) {
                    out.write(buffer, 0, deflater.deflate(buffer));
                    if (out.size() >= shorterThan) {
                        return null;
                    }
                }
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }
}
