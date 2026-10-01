package stirling.software.officeconvert.pdfa;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;

final class TintedData {

    private static final int SAMPLES_1D = 512;

    private static final int SAMPLES_2D = 64;

    private TintedData() {}

    static boolean image(COSStream s, Tint t) throws IOException {
        int w = s.getInt(COSName.WIDTH);
        int h = s.getInt(COSName.HEIGHT);
        int bpc = s.getInt(COSName.BITS_PER_COMPONENT);
        if (w <= 0 || h <= 0 || (long) w * h > 64L << 20) {
            return false;
        }
        byte[] out;
        if (rawSamples(s) && (bpc == 1 || bpc == 2 || bpc == 4 || bpc == 8 || bpc == 16)) {
            out = samples(s, t, w, h, bpc);
        } else {
            return rgb(s, w, h);
        }
        if (out == null) {
            return false;
        }
        write(s, out, t.alternate(), t.ranges());
        return true;
    }

    private static boolean rawSamples(COSStream s) {
        COSBase f = s.getDictionaryObject(COSName.FILTER);
        String text = f == null ? "" : f.toString();
        return !text.contains("DCT") && !text.contains("JPX") && !text.contains("JBIG2");
    }

    private static byte[] samples(COSStream s, Tint t, int w, int h, int bpc) throws IOException {
        int n = t.inputs();
        int m = t.outputs();
        float[] decode = new float[2 * n];
        COSArray d = ContentGraph.array(s.getDictionaryObject(COSName.DECODE));
        for (int i = 0; i < n; i++) {
            decode[2 * i] = d != null && d.size() == 2 * n && d.getObject(2 * i) instanceof COSNumber v ? v.floatValue() : 0;
            decode[2 * i + 1] = d != null && d.size() == 2 * n && d.getObject(2 * i + 1) instanceof COSNumber v
                    ? v.floatValue() : 1;
        }
        long stride = ((long) w * n * bpc + 7) / 8;
        byte[] data;
        try (InputStream in = s.createInputStream()) {
            data = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, stride * h));
        }
        byte[] out = new byte[w * h * m];
        float max = (1 << bpc) - 1;
        Map<String, byte[]> cache = new HashMap<>();
        int[] raw = new int[n];
        float[] in = new float[n];
        for (int y = 0; y < h; y++) {
            long bit = y * stride * 8;
            for (int x = 0; x < w; x++) {
                for (int c = 0; c < n; c++, bit += bpc) {
                    raw[c] = read(data, bit, bpc);
                }
                String key = Arrays.toString(raw);
                byte[] px = cache.get(key);
                if (px == null) {
                    for (int c = 0; c < n; c++) {
                        in[c] = decode[2 * c] + raw[c] / max * (decode[2 * c + 1] - decode[2 * c]);
                    }
                    px = bytes(t.eval(in), t.ranges());
                    if (cache.size() < 1 << 16) {
                        cache.put(key, px);
                    }
                }
                System.arraycopy(px, 0, out, (y * w + x) * m, m);
            }
        }
        return out;
    }

    private static int read(byte[] data, long bit, int bpc) {
        int at = (int) (bit >>> 3);
        if (at >= data.length) {
            return 0;
        }
        if (bpc == 8) {
            return data[at] & 0xFF;
        }
        if (bpc == 16) {
            return (data[at] & 0xFF) << 8 | (at + 1 < data.length ? data[at + 1] & 0xFF : 0);
        }
        int shift = 8 - bpc - (int) (bit & 7);
        return (data[at] >> shift) & ((1 << bpc) - 1);
    }

    private static byte[] bytes(float[] v, float[] ranges) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            float lo = ranges[2 * i];
            float hi = ranges[2 * i + 1];
            b[i] = (byte) Math.round((v[i] - lo) / (hi - lo) * 255);
        }
        return b;
    }

    private static boolean rgb(COSStream s, int w, int h) throws IOException {
        BufferedImage im;
        try {
            im = new PDImageXObject(new PDStream(s), null).getImage();
        } catch (IOException | RuntimeException e) {
            return false;
        }
        if (im == null) {
            return false;
        }
        byte[] out = new byte[w * h * 3];
        for (int y = 0; y < h && y < im.getHeight(); y++) {
            for (int x = 0; x < w && x < im.getWidth(); x++) {
                int p = im.getRGB(x, y);
                int i = (y * w + x) * 3;
                out[i] = (byte) (p >> 16);
                out[i + 1] = (byte) (p >> 8);
                out[i + 2] = (byte) p;
            }
        }
        write(s, out, COSName.DEVICERGB, new float[] {0, 1, 0, 1, 0, 1});
        return true;
    }

    private static void write(COSStream s, byte[] data, COSBase colourSpace, float[] ranges) throws IOException {
        for (COSName k : new COSName[] {COSName.FILTER, COSName.DECODE_PARMS, COSName.DECODE, COSName.MASK}) {
            if (k != COSName.MASK || s.getDictionaryObject(k) instanceof COSArray) {
                s.removeItem(k);
            }
        }
        try (OutputStream o = s.createOutputStream(COSName.FLATE_DECODE)) {
            o.write(data);
        }
        s.setItem(COSName.COLORSPACE, colourSpace);
        s.setInt(COSName.BITS_PER_COMPONENT, 8);
        if (!unit(ranges)) {
            s.setItem(COSName.DECODE, floats(ranges));
        }
    }

    static COSString lookup(COSBase table, int hival, Tint t) throws IOException {
        byte[] b = table instanceof COSString str ? str.getBytes()
                : table instanceof COSStream st ? StreamFixer.read(st) : null;
        if (b == null) {
            return null;
        }
        int n = t.inputs();
        int m = t.outputs();
        byte[] out = new byte[(hival + 1) * m];
        float[] in = new float[n];
        for (int i = 0; i <= hival; i++) {
            for (int c = 0; c < n; c++) {
                int at = i * n + c;
                in[c] = at < b.length ? (b[at] & 0xFF) / 255f : 0;
            }
            System.arraycopy(bytes(t.eval(in), t.ranges()), 0, out, i * m, m);
        }
        return new COSString(out);
    }

    static boolean shading(PDDocument doc, COSDictionary d, Tint t) throws IOException {
        PDShading sh = PDShading.create(d);
        int type = sh.getShadingType();
        if (d.getDictionaryObject(COSName.FUNCTION) == null) {
            return false;
        }
        int inputs = type == 1 ? 2 : 1;
        float[] domain = new float[2 * inputs];
        COSArray dom = ContentGraph.array(d.getDictionaryObject(type == 1 || type == 2 || type == 3 ? COSName.DOMAIN : COSName.DECODE));
        for (int i = 0; i < 2 * inputs; i++) {
            domain[i] = i % 2;
        }
        if (type >= 4) {
            COSArray dec = dom;
            if (dec != null && dec.size() >= 6 && dec.getObject(4) instanceof COSNumber a && dec.getObject(5) instanceof COSNumber b) {
                domain[0] = a.floatValue();
                domain[1] = b.floatValue();
            }
        } else if (dom != null && dom.size() >= 2 * inputs) {
            for (int i = 0; i < 2 * inputs; i++) {
                if (dom.getObject(i) instanceof COSNumber v) {
                    domain[i] = v.floatValue();
                }
            }
        }
        int[] size = inputs == 1 ? new int[] {SAMPLES_1D} : new int[] {SAMPLES_2D, SAMPLES_2D};
        int m = t.outputs();
        int count = size.length == 1 ? size[0] : size[0] * size[1];
        byte[] samples = new byte[count * m * 2];
        float[] r = t.ranges();
        for (int k = 0; k < count; k++) {
            float[] x = new float[inputs];
            x[0] = domain[0] + (domain[1] - domain[0]) * (k % size[0]) / (size[0] - 1);
            if (inputs == 2) {
                x[1] = domain[2] + (domain[3] - domain[2]) * (k / size[0]) / (size[1] - 1);
            }
            float[] v = t.eval(sh.evalFunction(x));
            for (int c = 0; c < m; c++) {
                int q = Math.round((v[c] - r[2 * c]) / (r[2 * c + 1] - r[2 * c]) * 65535);
                samples[(k * m + c) * 2] = (byte) (q >> 8);
                samples[(k * m + c) * 2 + 1] = (byte) q;
            }
        }
        COSStream f = doc.getDocument().createCOSStream();
        f.setInt(COSName.FUNCTION_TYPE, 0);
        f.setItem(COSName.DOMAIN, floats(domain));
        f.setItem(COSName.RANGE, floats(r));
        COSArray sz = new COSArray();
        for (int s : size) {
            sz.add(COSInteger.get(s));
        }
        f.setItem(COSName.SIZE, sz);
        f.setInt(COSName.BITS_PER_SAMPLE, 16);
        try (OutputStream o = f.createOutputStream(COSName.FLATE_DECODE)) {
            o.write(samples);
        }
        d.setItem(COSName.FUNCTION, f);
        d.setItem(COSName.COLORSPACE, t.alternate());
        d.removeItem(COSName.BACKGROUND);
        return true;
    }

    private static boolean unit(float[] r) {
        for (int i = 0; i < r.length; i++) {
            if (r[i] != i % 2) {
                return false;
            }
        }
        return true;
    }

    private static COSArray floats(float[] v) {
        COSArray a = new COSArray();
        for (float f : v) {
            a.add(f == Math.rint(f) ? COSInteger.get((long) f) : new COSFloat(f));
        }
        return a;
    }
}
