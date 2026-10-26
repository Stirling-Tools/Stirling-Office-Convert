package stirling.software.officeconvert.lineart;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;

final class Codecs {

    record Encoded(LineArt.Codec codec, byte[] data, COSName filter, COSDictionary parameters, int inkSample) {}

    private Codecs() {}

    static Encoded encode(Bitmap bitmap, LineArt.Codec codec) throws IOException {
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
        return new Encoded(LineArt.Codec.JBIG2, data, COSName.JBIG2_DECODE, null, 0);
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
        return new Encoded(LineArt.Codec.CCITT_G4, data, COSName.CCITTFAX_DECODE, parameters, 0);
    }

    static Encoded flate(Bitmap bitmap) {
        return flate(bitmap, Integer.MAX_VALUE);
    }

    private static Encoded flate(Bitmap bitmap, int shorterThan) {
        byte[] raw = bitmap.packed();
        int stride = bitmap.stride();
        int h = bitmap.height();
        int tail = bitmap.width() & 7;
        byte keep = (byte) (tail == 0 ? 0xFF : 0xFF00 >>> tail & 0xFF);
        Deflater direct = new Deflater(Deflater.BEST_COMPRESSION);
        Deflater up = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            ByteArrayOutputStream directOut = new ByteArrayOutputStream(1 << 12);
            ByteArrayOutputStream upOut = new ByteArrayOutputStream(1 << 12);
            byte[] buffer = new byte[1 << 16];
            int rows = Math.max(1, (1 << 16) / (stride + 1));
            byte[] plain = new byte[rows * stride];
            byte[] predicted = new byte[rows * (stride + 1)];
            byte[] previous = new byte[stride];
            boolean directAlive = true;
            boolean upAlive = true;
            for (int y0 = 0; y0 < h && (directAlive || upAlive); y0 += rows) {
                int n = Math.min(rows, h - y0);
                for (int r = 0; r < n; r++) {
                    int src = (y0 + r) * stride;
                    int p = r * stride;
                    int q = r * (stride + 1);
                    predicted[q] = 2;
                    for (int i = 0; i < stride; i++) {
                        byte v = (byte) ~raw[src + i];
                        if (i == stride - 1) {
                            v &= keep;
                        }
                        plain[p + i] = v;
                        predicted[q + 1 + i] = (byte) (v - previous[i]);
                        previous[i] = v;
                    }
                }
                boolean last = y0 + n >= h;
                if (directAlive) {
                    directAlive = feed(direct, plain, n * stride, last, buffer, directOut, shorterThan);
                }
                if (upAlive) {
                    upAlive = feed(up, predicted, n * (stride + 1), last, buffer, upOut, shorterThan);
                }
            }
            if (!directAlive && !upAlive) {
                return null;
            }
            if (upAlive && (!directAlive || upOut.size() < directOut.size())) {
                COSDictionary parameters = new COSDictionary();
                parameters.setInt(COSName.PREDICTOR, 15);
                parameters.setInt(COSName.COLORS, 1);
                parameters.setInt(COSName.BITS_PER_COMPONENT, 1);
                parameters.setInt(COSName.COLUMNS, bitmap.width());
                return new Encoded(LineArt.Codec.FLATE, upOut.toByteArray(), COSName.FLATE_DECODE, parameters, 0);
            }
            return new Encoded(LineArt.Codec.FLATE, directOut.toByteArray(), COSName.FLATE_DECODE, null, 0);
        } finally {
            direct.end();
            up.end();
        }
    }

    private static boolean feed(Deflater deflater, byte[] data, int length, boolean last, byte[] buffer,
            ByteArrayOutputStream out, int shorterThan) {
        deflater.setInput(data, 0, length);
        if (last) {
            deflater.finish();
        }
        while (last ? !deflater.finished() : !deflater.needsInput()) {
            out.write(buffer, 0, deflater.deflate(buffer));
            if (out.size() >= shorterThan) {
                return false;
            }
        }
        return true;
    }
}
