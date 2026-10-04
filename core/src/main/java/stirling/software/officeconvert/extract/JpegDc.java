package stirling.software.officeconvert.extract;

import java.io.IOException;
import java.io.InputStream;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

final class JpegDc {

    private static final int INK = 232;
    private static final int MAX_DC = 2048;

    private JpegDc() {}

    static boolean dark(PDImageXObject x) {
        COSStream s = x.getCOSObject();
        boolean dct = s.getFilters() instanceof COSName f
                && (COSName.DCT_DECODE.equals(f) || COSName.DCT_DECODE_ABBREVIATION.equals(f));
        if (!dct || x.getBitsPerComponent() != 8 || x.isStencil() || x.getDecode() != null && x.getDecode().size() > 0
                || s.containsKey(COSName.SMASK) || s.containsKey(COSName.MASK)) {
            return false;
        }
        try (InputStream in = s.createRawInputStream()) {
            return x.getColorSpace() instanceof PDDeviceRGB && dark(in.readAllBytes(), x.getWidth(), x.getHeight());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static boolean dark(byte[] jpeg, int width, int height) {
        try {
            return new Scan(jpeg, width, height).dark();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static final class Unknown extends RuntimeException {
        Unknown() {
            super(null, null, false, false);
        }
    }

    private static final class Huffman {
        final int[] maxCode = new int[17];
        final int[] valPtr = new int[17];
        final int[] minCode = new int[17];
        final int[] values;
        final boolean dcSafe;

        Huffman(int[] counts, int[] values) {
            this.values = values;
            int code = 0;
            int k = 0;
            for (int l = 1; l <= 16; l++) {
                valPtr[l] = k;
                minCode[l] = code;
                code += counts[l - 1];
                k += counts[l - 1];
                maxCode[l] = counts[l - 1] == 0 ? -1 : code - 1;
                if (code >= 1 << l) {
                    throw new Unknown();
                }
                code <<= 1;
            }
            boolean safe = true;
            for (int v : values) {
                safe &= v <= 15;
            }
            dcSafe = safe;
        }
    }

    private static final class Scan {
        private final byte[] d;
        private final int width;
        private final int height;
        private int pos;
        private int bitBuf;
        private int bitCount;
        private boolean frame;
        private boolean progressive;
        private final int[] ids = new int[3];
        private final int[] h = new int[3];
        private final int[] v = new int[3];
        private final int[] tq = new int[3];
        private int hMax;
        private int vMax;
        private final int[] q0 = {-1, -1, -1, -1};
        private final Huffman[] dcTables = new Huffman[4];
        private final Huffman[] acTables = new Huffman[4];
        private int restart;
        private int quant = -1;
        private boolean seen;
        private int firstAl;
        private boolean found;

        Scan(byte[] d, int width, int height) {
            this.d = d;
            this.width = width;
            this.height = height;
        }

        boolean dark() {
            if (d.length > 0 && d[0] == 0x0A) {
                pos = 1;
            }
            if (u8() != 0xFF || u8() != 0xD8) {
                return false;
            }
            while (true) {
                int m = nextMarker();
                if (m < 0 || m == 0xD9) {
                    return found;
                }
                if (m == 0xD8) {
                    throw new Unknown();
                }
                if (m >= 0xD0 && m <= 0xD7 || m == 0x01) {
                    continue;
                }
                int len = u16();
                int end = pos + len - 2;
                if (len < 2 || end > d.length) {
                    throw new Unknown();
                }
                switch (m) {
                    case 0xC0, 0xC1, 0xC2 -> frame(m, end);
                    case 0xC4 -> huffman(end);
                    case 0xDB -> quantisation(end);
                    case 0xDD -> {
                        if (len != 4) {
                            throw new Unknown();
                        }
                        restart = u16();
                    }
                    case 0xDA -> {
                        scan(end);
                        continue;
                    }
                    default -> {
                        if (m >= 0xC3 && m <= 0xCF) {
                            throw new Unknown();
                        }
                    }
                }
                pos = end;
            }
        }

        private void frame(int m, int end) {
            if (frame || end - pos != 15 || u8() != 8) {
                throw new Unknown();
            }
            frame = true;
            progressive = m == 0xC2;
            int y = u16();
            int x = u16();
            if (u8() != 3 || x != width || y != height) {
                throw new Unknown();
            }
            for (int c = 0; c < 3; c++) {
                ids[c] = u8();
                int hv = u8();
                h[c] = hv >> 4;
                v[c] = hv & 15;
                tq[c] = u8();
                if (h[c] < 1 || h[c] > 4 || v[c] < 1 || v[c] > 4 || tq[c] > 3) {
                    throw new Unknown();
                }
                hMax = Math.max(hMax, h[c]);
                vMax = Math.max(vMax, v[c]);
            }
            if (ids[0] == ids[1] || ids[0] == ids[2] || ids[1] == ids[2] || h[0] != hMax || v[0] != vMax) {
                throw new Unknown();
            }
        }

        private void huffman(int end) {
            while (pos < end) {
                int t = u8();
                int cls = t >> 4;
                int id = t & 15;
                if (cls > 1 || id > 3) {
                    throw new Unknown();
                }
                int[] counts = new int[16];
                int total = 0;
                for (int i = 0; i < 16; i++) {
                    counts[i] = u8();
                    total += counts[i];
                }
                if (total > 256 || pos + total > end) {
                    throw new Unknown();
                }
                int[] values = new int[total];
                for (int i = 0; i < total; i++) {
                    values[i] = u8();
                }
                (cls == 0 ? dcTables : acTables)[id] = new Huffman(counts, values);
            }
            if (pos != end) {
                throw new Unknown();
            }
        }

        private void quantisation(int end) {
            while (pos < end) {
                int t = u8();
                int precision = t >> 4;
                int id = t & 15;
                if (precision > 1 || id > 3) {
                    throw new Unknown();
                }
                q0[id] = precision == 0 ? u8() : u16();
                pos += precision == 0 ? 63 : 126;
            }
            if (pos != end) {
                throw new Unknown();
            }
        }

        private void scan(int end) {
            int ns = u8();
            if (!frame || ns < 1 || ns > 3 || end - pos != 2 * ns + 3) {
                throw new Unknown();
            }
            int[] comps = new int[ns];
            Huffman[] dc = new Huffman[ns];
            Huffman[] ac = new Huffman[ns];
            boolean has0 = false;
            for (int i = 0; i < ns; i++) {
                int id = u8();
                int c = id == ids[0] ? 0 : id == ids[1] ? 1 : id == ids[2] ? 2 : -1;
                int t = u8();
                if (c < 0 || (t >> 4) > 3 || (t & 15) > 3) {
                    throw new Unknown();
                }
                comps[i] = c;
                dc[i] = dcTables[t >> 4];
                ac[i] = acTables[t & 15];
                has0 |= c == 0;
            }
            int ss = u8();
            int se = u8();
            int ahal = u8();
            int ah = ahal >> 4;
            int al = ahal & 15;
            if (has0 && quant < 0) {
                quant = q0[tq[0]];
                if (quant < 0) {
                    throw new Unknown();
                }
            }
            boolean decode = false;
            if (!progressive) {
                if (ss != 0 || se != 63 || ahal != 0) {
                    throw new Unknown();
                }
                if (has0) {
                    if (seen) {
                        throw new Unknown();
                    }
                    seen = true;
                    decode = true;
                }
            } else if (ss == 0) {
                if (se != 0 || al > 13 || ah != 0 && al != ah - 1) {
                    throw new Unknown();
                }
                if (has0 && ah == 0) {
                    if (seen) {
                        throw new Unknown();
                    }
                    seen = true;
                    firstAl = al;
                    decode = true;
                } else if (has0 && seen && al >= firstAl) {
                    throw new Unknown();
                }
            } else if (ns != 1 || se < ss || se > 63 || al > 13 || ah != 0 && al != ah - 1) {
                throw new Unknown();
            }
            if (decode && !found) {
                found = decodeDc(comps, dc, ac, progressive ? al : 0);
            }
            skipEntropy();
        }

        private boolean decodeDc(int[] comps, Huffman[] dc, Huffman[] ac, int al) {
            for (int i = 0; i < comps.length; i++) {
                if (dc[i] == null || !dc[i].dcSafe || !progressive && ac[i] == null) {
                    throw new Unknown();
                }
            }
            bitBuf = 0;
            bitCount = 0;
            int[] pred = new int[3];
            int fullX = width / 8;
            int fullY = height / 8;
            int mcusX;
            int mcusY;
            if (comps.length == 1) {
                int c = comps[0];
                mcusX = ceil(width * h[c], 8 * hMax);
                mcusY = ceil(height * v[c], 8 * vMax);
            } else {
                mcusX = ceil(width, 8 * hMax);
                mcusY = ceil(height, 8 * vMax);
            }
            int next = 0;
            long total = (long) mcusX * mcusY;
            for (long mcu = 0; mcu < total; mcu++) {
                if (restart > 0 && mcu > 0 && mcu % restart == 0) {
                    bitBuf = 0;
                    bitCount = 0;
                    if (nextMarker() != 0xD0 + next) {
                        throw new Unknown();
                    }
                    next = (next + 1) & 7;
                    pred[0] = pred[1] = pred[2] = 0;
                }
                int mx = (int) (mcu % mcusX);
                int my = (int) (mcu / mcusX);
                for (int i = 0; i < comps.length; i++) {
                    int c = comps[i];
                    int bw = comps.length == 1 ? 1 : h[c];
                    int bh = comps.length == 1 ? 1 : v[c];
                    for (int by = 0; by < bh; by++) {
                        for (int bx = 0; bx < bw; bx++) {
                            int s = decode(dc[i]);
                            pred[c] += s == 0 ? 0 : extend(bits(s), s);
                            if (!progressive) {
                                skipAc(ac[i]);
                            }
                            if (c != 0) {
                                continue;
                            }
                            int x = mx * bw + bx;
                            int y = my * bh + by;
                            if (x < fullX && y < fullY && ink(pred[0], al)) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        }

        private boolean ink(int dc, int al) {
            long low = (long) dc << al;
            long high = low + (1L << al) - 1;
            if (low < Short.MIN_VALUE || high > Short.MAX_VALUE) {
                throw new Unknown();
            }
            long top = high * quant;
            if (Math.abs(low * quant) > MAX_DC || Math.abs(top) > MAX_DC) {
                throw new Unknown();
            }
            return 1024 + top <= INK * 8L;
        }

        private void skipAc(Huffman t) {
            for (int k = 1; k < 64; k++) {
                int rs = decode(t);
                int r = rs >> 4;
                int s = rs & 15;
                if (s != 0) {
                    k += r;
                    bits(s);
                } else {
                    if (r != 15) {
                        break;
                    }
                    k += 15;
                }
            }
        }

        private int decode(Huffman t) {
            int code = bit();
            int l = 1;
            while (code > t.maxCode[l]) {
                if (++l > 16) {
                    throw new Unknown();
                }
                code = code << 1 | bit();
            }
            int k = t.valPtr[l] + code - t.minCode[l];
            if (k < 0 || k >= t.values.length) {
                throw new Unknown();
            }
            return t.values[k];
        }

        private static int extend(int value, int s) {
            return value < 1 << (s - 1) ? value + (-1 << s) + 1 : value;
        }

        private int bits(int n) {
            int value = 0;
            for (int i = 0; i < n; i++) {
                value = value << 1 | bit();
            }
            return value;
        }

        private int bit() {
            if (bitCount == 0) {
                int b = u8();
                if (b == 0xFF) {
                    int c;
                    do {
                        c = u8();
                    } while (c == 0xFF);
                    if (c != 0) {
                        throw new Unknown();
                    }
                }
                bitBuf = b;
                bitCount = 8;
            }
            return bitBuf >> --bitCount & 1;
        }

        private void skipEntropy() {
            while (pos < d.length) {
                if ((d[pos] & 0xFF) != 0xFF) {
                    pos++;
                    continue;
                }
                int k = pos + 1;
                while (k < d.length && (d[k] & 0xFF) == 0xFF) {
                    k++;
                }
                if (k >= d.length) {
                    pos = d.length;
                    return;
                }
                int c = d[k] & 0xFF;
                if (c == 0 || c >= 0xD0 && c <= 0xD7) {
                    pos = k + 1;
                } else {
                    pos = k - 1;
                    return;
                }
            }
        }

        private int nextMarker() {
            while (true) {
                if (pos >= d.length) {
                    return -1;
                }
                int c = d[pos++] & 0xFF;
                if (c != 0xFF) {
                    continue;
                }
                while (pos < d.length && (d[pos] & 0xFF) == 0xFF) {
                    pos++;
                }
                if (pos >= d.length) {
                    return -1;
                }
                c = d[pos++] & 0xFF;
                if (c != 0) {
                    return c;
                }
            }
        }

        private int u8() {
            if (pos >= d.length) {
                throw new Unknown();
            }
            return d[pos++] & 0xFF;
        }

        private int u16() {
            return u8() << 8 | u8();
        }

        private static int ceil(long a, long b) {
            return (int) ((a + b - 1) / b);
        }
    }
}
