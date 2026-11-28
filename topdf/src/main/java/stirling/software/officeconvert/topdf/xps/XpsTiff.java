package stirling.software.officeconvert.topdf.xps;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import stirling.software.officeconvert.topdf.io.PictureDecoder;

final class XpsTiff {

    private XpsTiff() {}

    static PDImageXObject decode(PDDocument doc, byte[] d) throws IOException {
        if (d.length < 8 || !(d[0] == 'I' && d[1] == 'I' || d[0] == 'M' && d[1] == 'M')) {
            return null;
        }
        boolean le = d[0] == 'I';
        if (u16(d, 2, le) != 42) {
            return null;
        }
        long ifd = u32(d, 4, le);
        if (ifd < 8 || ifd + 2 > d.length) {
            return null;
        }
        int n = u16(d, (int) ifd, le);
        if (ifd + 2 + 12L * n + 4 > d.length) {
            return null;
        }
        long width = 0;
        long height = 0;
        int compression = 1;
        int photometric = -1;
        int spp = 1;
        int predictor = 1;
        long offset = -1;
        long count = -1;
        long rows = Long.MAX_VALUE;
        boolean bits = false;
        for (int i = 0; i < n; i++) {
            int e = (int) ifd + 2 + i * 12;
            int tag = u16(d, e, le);
            int type = u16(d, e + 2, le);
            long cnt = u32(d, e + 4, le);
            long v = type == 3 ? u16(d, e + 8, le) : type == 4 ? u32(d, e + 8, le) : -1;
            switch (tag) {
                case 256 -> width = v;
                case 257 -> height = v;
                case 258 -> {
                    if (!eights(d, e, type, cnt, le)) {
                        return null;
                    }
                    bits = true;
                }
                case 259 -> compression = (int) v;
                case 262 -> photometric = (int) v;
                case 273 -> {
                    if (cnt != 1) {
                        return null;
                    }
                    offset = v;
                }
                case 277 -> spp = (int) v;
                case 278 -> rows = v;
                case 279 -> {
                    if (cnt != 1) {
                        return null;
                    }
                    count = v;
                }
                case 317 -> predictor = (int) v;
                case 254, 282, 283, 296, 305, 306, 315, 316, 269, 270, 271, 272, 285, 297 -> {
                }
                case 266, 274, 284, 339 -> {
                    if (v != 1) {
                        return null;
                    }
                }
                default -> {
                    return null;
                }
            }
        }
        boolean gray = photometric == 1 && spp == 1;
        boolean rgb = photometric == 2 && spp == 3;
        if (!bits || !gray && !rgb || compression != 1 && compression != 5 || predictor != 1 && predictor != 2) {
            return null;
        }
        if (width <= 0 || height <= 0 || width > 1 << 20 || height > 1 << 20 || rows < height) {
            return null;
        }
        long pixels = width * height;
        if (pixels > PictureDecoder.DECODE_PIXELS || offset < 0 || count <= 0 || offset + count > d.length) {
            return null;
        }
        int w = (int) width;
        int h = (int) height;
        int stride = w * spp;
        int need = stride * h;
        byte[] raw;
        if (compression == 1) {
            if (count < need) {
                return null;
            }
            raw = new byte[need];
            System.arraycopy(d, (int) offset, raw, 0, need);
        } else {
            if ((d[(int) offset] & 0xFF) != 0x80) {
                return null;
            }
            raw = lzw(d, (int) offset, (int) (offset + count), need);
        }
        if (predictor == 2) {
            for (int y = 0; y < h; y++) {
                int row = y * stride;
                for (int i = row + spp; i < row + stride; i++) {
                    raw[i] += raw[i - spp];
                }
            }
        }
        COSStream stream = doc.getDocument().createCOSStream();
        stream.setItem(COSName.TYPE, COSName.XOBJECT);
        stream.setItem(COSName.SUBTYPE, COSName.IMAGE);
        stream.setInt(COSName.WIDTH, w);
        stream.setInt(COSName.HEIGHT, h);
        stream.setInt(COSName.BITS_PER_COMPONENT, 8);
        stream.setItem(COSName.COLORSPACE, gray ? COSName.DEVICEGRAY : COSName.DEVICERGB);
        stream.setItem(COSName.FILTER, COSName.FLATE_DECODE);
        COSDictionary parms = new COSDictionary();
        parms.setInt(COSName.PREDICTOR, 15);
        parms.setInt(COSName.COLORS, spp);
        parms.setInt(COSName.BITS_PER_COMPONENT, 8);
        parms.setInt(COSName.COLUMNS, w);
        stream.setItem(COSName.DECODE_PARMS, parms);
        Deflater deflater = new Deflater(5);
        try (OutputStream out = new DeflaterOutputStream(stream.createRawOutputStream(), deflater, 1 << 16)) {
            byte[] line = new byte[stride + 1];
            byte[] trial = new byte[stride + 1];
            for (int y = 0; y < h; y++) {
                if ((y & 63) == 0 && Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("The conversion was stopped");
                }
                filter(raw, y * stride, y == 0 ? -1 : (y - 1) * stride, stride, spp, line, trial);
                out.write(line);
            }
        } finally {
            deflater.end();
        }
        PDImageXObject image = new PDImageXObject(new org.apache.pdfbox.pdmodel.common.PDStream(stream), null);
        image.setColorSpace(gray ? PDDeviceGray.INSTANCE : PDDeviceRGB.INSTANCE);
        return image;
    }

    private static void filter(byte[] raw, int at, int above, int stride, int bpp, byte[] line, byte[] trial) {
        long best = Long.MAX_VALUE;
        for (int type = 0; type <= 2; type++) {
            if (type == 2 && above < 0) {
                break;
            }
            long sum = 0;
            trial[0] = (byte) type;
            for (int i = 0; i < stride; i++) {
                int cur = raw[at + i] & 0xFF;
                int ref = switch (type) {
                    case 1 -> i >= bpp ? raw[at + i - bpp] & 0xFF : 0;
                    case 2 -> raw[above + i] & 0xFF;
                    default -> 0;
                };
                byte b = (byte) (cur - ref);
                trial[i + 1] = b;
                sum += Math.abs(b);
            }
            if (sum < best) {
                best = sum;
                System.arraycopy(trial, 0, line, 0, stride + 1);
            }
        }
    }

    static byte[] lzw(byte[] d, int start, int end, int need) {
        byte[] out = new byte[need + 4097];
        short[] prefix = new short[4096];
        byte[] last = new byte[4096];
        byte[] first = new byte[4096];
        int[] length = new int[4096];
        for (int i = 0; i < 256; i++) {
            last[i] = (byte) i;
            first[i] = (byte) i;
            length[i] = 1;
        }
        int pos = 0;
        long bits = 0;
        int have = 0;
        int p = start;
        int width = 9;
        int next = 258;
        int prev = -1;
        while (pos < need) {
            while (have < width && p < end) {
                bits = bits << 8 | d[p++] & 0xFF;
                have += 8;
            }
            if (have < width) {
                break;
            }
            int code = (int) (bits >>> (have - width)) & ((1 << width) - 1);
            have -= width;
            if (code == 256) {
                next = 258;
                width = 9;
                prev = -1;
                continue;
            }
            if (code == 257) {
                break;
            }
            if (prev < 0) {
                if (code > 255) {
                    break;
                }
                out[pos++] = (byte) code;
                prev = code;
                continue;
            }
            int emit;
            byte head;
            if (code < next) {
                emit = code;
                head = first[code];
            } else if (code == next && next < 4096) {
                emit = -1;
                head = first[prev];
            } else {
                break;
            }
            if (next < 4096) {
                prefix[next] = (short) prev;
                last[next] = head;
                first[next] = first[prev];
                length[next] = length[prev] + 1;
                next++;
            }
            int c = emit < 0 ? next - 1 : emit;
            int len = length[c];
            int e = pos + len - 1;
            for (int q = e; q >= pos; q--) {
                out[q] = last[c];
                c = prefix[c];
            }
            pos += len;
            prev = emit < 0 ? next - 1 : emit;
            if (next >= (1 << width) - 1 && width < 12) {
                width++;
            }
        }
        return out;
    }

    private static boolean eights(byte[] d, int e, int type, long cnt, boolean le) {
        if (type != 3 || cnt < 1 || cnt > 4) {
            return false;
        }
        if (cnt <= 2) {
            for (int i = 0; i < cnt; i++) {
                if (u16(d, e + 8 + 2 * i, le) != 8) {
                    return false;
                }
            }
            return true;
        }
        long off = u32(d, e + 8, le);
        if (off < 0 || off + 2 * cnt > d.length) {
            return false;
        }
        for (int i = 0; i < cnt; i++) {
            if (u16(d, (int) off + 2 * i, le) != 8) {
                return false;
            }
        }
        return true;
    }

    private static int u16(byte[] d, int p, boolean le) {
        return le ? (d[p] & 0xFF) | (d[p + 1] & 0xFF) << 8 : (d[p] & 0xFF) << 8 | d[p + 1] & 0xFF;
    }

    private static long u32(byte[] d, int p, boolean le) {
        return le ? (d[p] & 0xFFL) | (d[p + 1] & 0xFFL) << 8 | (d[p + 2] & 0xFFL) << 16 | (d[p + 3] & 0xFFL) << 24
                : (d[p] & 0xFFL) << 24 | (d[p + 1] & 0xFFL) << 16 | (d[p + 2] & 0xFFL) << 8 | d[p + 3] & 0xFFL;
    }
}
