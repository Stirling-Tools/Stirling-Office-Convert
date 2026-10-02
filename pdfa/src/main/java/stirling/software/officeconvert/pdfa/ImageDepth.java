package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.io.OutputStream;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;

final class ImageDepth {

    private ImageDepth() {}

    static String fix(COSStream s, PdfALevel level) throws IOException {
        int bpc = s.getInt(COSName.BITS_PER_COMPONENT, -1);
        boolean mask = s.getBoolean(COSName.IMAGE_MASK, false);
        if (mask && bpc != 1 && bpc != -1) {
            return stencil(s, bpc)
                    ? "Stored image masks with more than one bit per sample, which PDF/A does not allow, as one bit masks"
                    : null;
        }
        if (level.part() == 1 && bpc == 16 && !mask) {
            return sixteen(s) ? "Stored 16-bit images, which PDF/A-1 does not allow, with 8 bits per sample" : null;
        }
        if (!mask && bpc > 0 && bpc != 1 && bpc != 2 && bpc != 4 && bpc != 8 && bpc != 16) {
            return rescale(s, bpc) ? "Stored images with " + bpc + " bits per sample, which PDF/A does not allow, "
                    + "with 8" : null;
        }
        return null;
    }

    private static byte[] samples(COSStream s) {
        COSBase f = s.getDictionaryObject(COSName.FILTER);
        String text = f == null ? "" : f.toString();
        if (text.contains("DCT") || text.contains("JPX") || text.contains("JBIG2")) {
            return null;
        }
        return StreamFixer.read(s);
    }

    private static boolean stencil(COSStream s, int bpc) throws IOException {
        int w = s.getInt(COSName.WIDTH);
        int h = s.getInt(COSName.HEIGHT);
        byte[] data = samples(s);
        if (data == null || w <= 0 || h <= 0 || bpc < 1 || bpc > 16 || (long) w * h > 256L << 20) {
            return false;
        }
        int inStride = (w * bpc + 7) / 8;
        int outStride = (w + 7) / 8;
        byte[] out = new byte[outStride * h];
        int half = 1 << (bpc - 1);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                long bit = (long) y * inStride * 8 + (long) x * bpc;
                if (sample(data, bit, bpc) >= half) {
                    out[y * outStride + x / 8] |= (byte) (0x80 >> (x % 8));
                }
            }
        }
        write(s, out, 1);
        return true;
    }

    private static boolean sixteen(COSStream s) throws IOException {
        byte[] data = samples(s);
        if (data == null) {
            return false;
        }
        byte[] out = new byte[data.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = data[2 * i];
        }
        write(s, out, 8);
        if (s.getDictionaryObject(COSName.MASK) instanceof COSArray key) {
            COSArray scaled = new COSArray();
            for (int i = 0; i < key.size(); i++) {
                scaled.add(key.getObject(i) instanceof COSNumber n ? COSInteger.get(n.intValue() >> 8) : key.get(i));
            }
            s.setItem(COSName.MASK, scaled);
        }
        return true;
    }

    private static boolean rescale(COSStream s, int bpc) throws IOException {
        int w = s.getInt(COSName.WIDTH);
        int h = s.getInt(COSName.HEIGHT);
        int comps = components(s);
        byte[] data = samples(s);
        if (data == null || comps <= 0 || w <= 0 || h <= 0 || bpc > 16 || (long) w * h * comps > 256L << 20) {
            return false;
        }
        int stride = (w * comps * bpc + 7) / 8;
        int max = (1 << bpc) - 1;
        byte[] out = new byte[w * h * comps];
        for (int y = 0; y < h; y++) {
            for (int i = 0; i < w * comps; i++) {
                long bit = (long) y * stride * 8 + (long) i * bpc;
                out[y * w * comps + i] = (byte) Math.round(sample(data, bit, bpc) * 255.0 / max);
            }
        }
        write(s, out, 8);
        if (s.getDictionaryObject(COSName.MASK) instanceof COSArray key) {
            COSArray scaled = new COSArray();
            for (int i = 0; i < key.size(); i++) {
                scaled.add(key.getObject(i) instanceof COSNumber n
                        ? COSInteger.get(Math.round(n.intValue() * 255.0 / max)) : key.get(i));
            }
            s.setItem(COSName.MASK, scaled);
        }
        return true;
    }

    private static int components(COSStream s) {
        COSBase cs = s.getDictionaryObject(COSName.COLORSPACE);
        if (cs instanceof COSName n) {
            return switch (n.getName()) {
                case "DeviceGray", "CalGray", "G" -> 1;
                case "DeviceRGB", "CalRGB", "RGB" -> 3;
                case "DeviceCMYK", "CMYK" -> 4;
                default -> -1;
            };
        }
        try {
            return PDColorSpace.create(cs).getNumberOfComponents();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    private static int sample(byte[] data, long bit, int bpc) {
        int v = 0;
        for (int i = 0; i < bpc; i++, bit++) {
            int at = (int) (bit >>> 3);
            int b = at < data.length ? (data[at] >> (7 - (int) (bit & 7))) & 1 : 0;
            v = v << 1 | b;
        }
        return v;
    }

    private static void write(COSStream s, byte[] data, int bpc) throws IOException {
        s.removeItem(COSName.FILTER);
        s.removeItem(COSName.DECODE_PARMS);
        try (OutputStream o = s.createOutputStream(COSName.FLATE_DECODE)) {
            o.write(data);
        }
        s.setInt(COSName.BITS_PER_COMPONENT, bpc);
    }
}
