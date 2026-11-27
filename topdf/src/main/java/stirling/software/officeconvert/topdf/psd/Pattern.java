package stirling.software.officeconvert.topdf.psd;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class Pattern {

    static final long MAX_PIXELS = 4L << 20;

    static final int MAX_PATTERNS = 2000;

    private static final int MAX_CHANNELS = 64;

    final int width;

    final int height;

    final byte[][] color;

    final byte[] alpha;

    private Pattern(int width, int height, byte[][] color, byte[] alpha) {
        this.width = width;
        this.height = height;
        this.color = color;
        this.alpha = alpha;
    }

    long bytes() {
        return (long) width * height * (color.length + (alpha == null ? 0 : 1));
    }

    static Pattern find(Source src, List<long[]> blocks, String id, PsdDocument doc) throws IOException {
        if (id == null || doc.depth == 32) {
            return null;
        }
        int seen = 0;
        for (long[] block : blocks) {
            Source.Cursor c = src.cursor(block[0], block[0] + block[1], 1 << 16);
            while (c.remaining() >= 12 && seen++ < MAX_PATTERNS) {
                long len = c.u32();
                long start = c.position();
                if (len < 8 || len > c.remaining()) {
                    break;
                }
                Pattern p;
                try {
                    p = read(src, src.cursor(start, start + len, 1 << 14), start + len, id, doc);
                } catch (IOException e) {
                    p = null;
                }
                if (p != null) {
                    return p;
                }
                c.seek(Math.min(c.end(), start + (len + 3 & ~3L)));
            }
        }
        return null;
    }

    private static Pattern read(Source src, Source.Cursor c, long end, String id, PsdDocument doc)
            throws IOException {
        if (c.u32() != 1) {
            return null;
        }
        int mode = (int) c.u32();
        c.skip(4);
        long chars = c.u32();
        if (chars > 4096 || chars * 2 > end - c.position()) {
            return null;
        }
        c.skip(chars * 2);
        int idLen = c.u8();
        byte[] idBytes = new byte[idLen];
        c.fully(idBytes);
        String found = new String(idBytes, StandardCharsets.ISO_8859_1).replace("\0", "");
        if (!found.equals(id)) {
            return null;
        }
        byte[] palette = null;
        if (mode == PsdDocument.INDEXED) {
            palette = new byte[768];
            c.fully(palette);
            c.skip(4);
        }
        int components = switch (mode) {
            case PsdDocument.GRAY, PsdDocument.DUOTONE, PsdDocument.INDEXED -> 1;
            case PsdDocument.RGB, PsdDocument.LAB -> 3;
            case PsdDocument.CMYK -> 4;
            default -> 0;
        };
        boolean fits = mode == doc.mode || mode == PsdDocument.INDEXED && doc.mode == PsdDocument.RGB
                || mode == PsdDocument.GRAY && doc.mode == PsdDocument.RGB
                || mode == PsdDocument.RGB && doc.mode == PsdDocument.GRAY;
        if (components == 0 || !fits) {
            return null;
        }
        if (c.u32() != 3) {
            return null;
        }
        c.skip(4);
        long top = c.u32();
        long left = c.u32();
        long bottom = c.u32();
        long right = c.u32();
        long w = right - left;
        long h = bottom - top;
        if (w <= 0 || h <= 0 || w > 30000 || h > 30000 || w * h > MAX_PIXELS) {
            return null;
        }
        int width = (int) w;
        int height = (int) h;
        long count = Math.min(c.u32(), MAX_CHANNELS) + 2;
        byte[][] planes = new byte[components + 1][];
        for (int i = 0; i < count && c.remaining() >= 4; i++) {
            if (c.u32() == 0) {
                continue;
            }
            long len = c.u32();
            if (len == 0) {
                continue;
            }
            long start = c.position();
            if (len > end - start) {
                return null;
            }
            if (i <= components && len >= 23) {
                planes[i] = channel(src, c, start + len, width, height);
            }
            c.seek(start + len);
        }
        for (int i = 0; i < components; i++) {
            if (planes[i] == null) {
                return null;
            }
        }
        byte[] alpha = planes[components];
        byte[][] color;
        if (palette != null) {
            color = new byte[3][width * height];
            for (int k = 0; k < width * height; k++) {
                int v = planes[0][k] & 0xFF;
                color[0][k] = palette[3 * v];
                color[1][k] = palette[3 * v + 1];
                color[2][k] = palette[3 * v + 2];
            }
        } else if (mode == PsdDocument.GRAY && doc.mode == PsdDocument.RGB) {
            color = new byte[][] {planes[0], planes[0], planes[0]};
        } else if (mode == PsdDocument.RGB && doc.mode == PsdDocument.GRAY) {
            byte[] g = new byte[width * height];
            for (int k = 0; k < g.length; k++) {
                g[k] = (byte) Math.round(0.3f * (planes[0][k] & 0xFF) + 0.59f * (planes[1][k] & 0xFF)
                        + 0.11f * (planes[2][k] & 0xFF));
            }
            color = new byte[][] {g};
        } else {
            color = java.util.Arrays.copyOf(planes, components);
        }
        return new Pattern(width, height, color, alpha);
    }

    private static byte[] channel(Source src, Source.Cursor c, long end, int width, int height) throws IOException {
        if (c.u32() != 8) {
            return null;
        }
        long top = c.u32();
        long left = c.u32();
        long bottom = c.u32();
        long right = c.u32();
        if (right - left != width || bottom - top != height) {
            return null;
        }
        c.skip(2);
        int compression = c.u8();
        long data = c.position();
        byte[] out = new byte[width * height];
        byte[] row = new byte[width];
        Rows rows;
        if (compression == 0) {
            rows = Rows.raw(src.cursor(data, end, 1 << 14), width);
        } else if (compression == 1) {
            long counts = (long) height * 2;
            if (counts > end - data) {
                return null;
            }
            rows = Rows.rle(src.cursor(data, data + counts, 1 << 14), src.cursor(data + counts, end, 1 << 14),
                    false, width);
        } else {
            return null;
        }
        try {
            for (int y = 0; y < height; y++) {
                rows.next(row);
                System.arraycopy(row, 0, out, y * width, width);
            }
        } finally {
            rows.close();
        }
        return out;
    }

    void row(int y, int width, double originX, double originY, double scale, float[] out, int stride, int n) {
        double s = scale > 0.01 ? scale : 1;
        boolean smooth = Math.abs(s - 1) > 1e-6;
        double fy = smooth ? (y + 0.5 - originY) / s - 0.5 : Math.floor(y - originY);
        int y0 = (int) Math.floor(fy);
        float ty = (float) (fy - y0);
        int r0 = Math.floorMod(y0, height) * this.width;
        int r1 = Math.floorMod(y0 + 1, height) * this.width;
        for (int x = 0; x < width; x++) {
            double fx = smooth ? (x + 0.5 - originX) / s - 0.5 : Math.floor(x - originX);
            int x0 = (int) Math.floor(fx);
            float tx = (float) (fx - x0);
            int c0 = Math.floorMod(x0, this.width);
            int c1 = Math.floorMod(x0 + 1, this.width);
            int at = x * stride;
            for (int i = 0; i < n && i < color.length; i++) {
                out[at + i] = sample(color[i], r0, r1, c0, c1, tx, ty);
            }
            out[at + n] = alpha == null ? 1 : sample(alpha, r0, r1, c0, c1, tx, ty);
        }
    }

    private static float sample(byte[] p, int r0, int r1, int c0, int c1, float tx, float ty) {
        float a = (p[r0 + c0] & 0xFF) + ((p[r0 + c1] & 0xFF) - (p[r0 + c0] & 0xFF)) * tx;
        float b = (p[r1 + c0] & 0xFF) + ((p[r1 + c1] & 0xFF) - (p[r1 + c0] & 0xFF)) * tx;
        return (a + (b - a) * ty) / 255f;
    }
}
