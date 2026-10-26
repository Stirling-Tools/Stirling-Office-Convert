package stirling.software.officeconvert.lineart;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.Deflater;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.CCITTFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

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

    private static Encoded smallest(Bitmap bitmap) throws IOException {
        Encoded best = jbig2(bitmap);
        for (Encoded e : new Encoded[] {g4(bitmap), flate(bitmap)}) {
            if (e.data().length < best.data().length) {
                best = e;
            }
        }
        return best;
    }

    static Encoded jbig2(Bitmap bitmap) {
        return new Encoded(LineArt.Codec.JBIG2, Jbig2Generic.encode(bitmap), COSName.JBIG2_DECODE, null, 0);
    }

    static Encoded g4(Bitmap bitmap) throws IOException {
        try (PDDocument scratch = new PDDocument()) {
            PDImageXObject image = CCITTFactory.createFromImage(scratch, bitmap.toImage());
            COSStream stream = image.getCOSObject();
            byte[] data;
            try (InputStream in = stream.createRawInputStream()) {
                data = in.readAllBytes();
            }
            COSDictionary parameters = new COSDictionary();
            COSBase given = stream.getDictionaryObject(COSName.DECODE_PARMS);
            if (given instanceof COSDictionary d) {
                for (COSName key : d.keySet()) {
                    COSBase v = d.getDictionaryObject(key);
                    if (v instanceof COSBoolean || v instanceof COSNumber) {
                        parameters.setItem(key, v);
                    }
                }
            }
            boolean blackIs1 = parameters.getBoolean(COSName.BLACK_IS_1, false);
            return new Encoded(LineArt.Codec.CCITT_G4, data, COSName.CCITTFAX_DECODE, parameters,
                    blackIs1 ? 1 : 0);
        }
    }

    static Encoded flate(Bitmap bitmap) {
        byte[] raw = bitmap.packed();
        int stride = bitmap.stride();
        int h = bitmap.height();
        byte[] plain = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) {
            plain[i] = (byte) ~raw[i];
        }
        clearPadding(plain, bitmap.width(), stride, h);
        byte[] direct = deflate(plain);
        byte[] predicted = new byte[(stride + 1) * h];
        for (int y = 0; y < h; y++) {
            predicted[y * (stride + 1)] = 2;
            for (int i = 0; i < stride; i++) {
                int above = y == 0 ? 0 : plain[(y - 1) * stride + i];
                predicted[y * (stride + 1) + 1 + i] = (byte) (plain[y * stride + i] - above);
            }
        }
        byte[] up = deflate(predicted);
        if (up.length < direct.length) {
            COSDictionary parameters = new COSDictionary();
            parameters.setInt(COSName.PREDICTOR, 15);
            parameters.setInt(COSName.COLORS, 1);
            parameters.setInt(COSName.BITS_PER_COMPONENT, 1);
            parameters.setInt(COSName.COLUMNS, bitmap.width());
            return new Encoded(LineArt.Codec.FLATE, up, COSName.FLATE_DECODE, parameters, 0);
        }
        return new Encoded(LineArt.Codec.FLATE, direct, COSName.FLATE_DECODE, null, 0);
    }

    private static void clearPadding(byte[] rows, int width, int stride, int h) {
        int tail = width & 7;
        if (tail == 0) {
            return;
        }
        int keep = 0xFF00 >>> tail & 0xFF;
        for (int y = 0; y < h; y++) {
            rows[y * stride + stride - 1] &= (byte) keep;
        }
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 4));
            byte[] buffer = new byte[1 << 16];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }
}
