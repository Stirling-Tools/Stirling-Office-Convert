package stirling.software.officeconvert.topdf.pptx;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.function.IntUnaryOperator;

final class Recolor {

    private Recolor() {}

    static BufferedImage duotone(BufferedImage src, Color dark, Color light) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            PixelRows.read(src, 0, y, w, row);
            for (int x = 0; x < w; x++) {
                int p = row[x];
                int a = p >>> 24;
                float lum = (0.299f * (p >> 16 & 0xFF) + 0.587f * (p >> 8 & 0xFF) + 0.114f * (p & 0xFF)) / 255f;
                int r = mix(dark.getRed(), light.getRed(), lum);
                int g = mix(dark.getGreen(), light.getGreen(), lum);
                int b = mix(dark.getBlue(), light.getBlue(), lum);
                row[x] = a << 24 | r << 16 | g << 8 | b;
            }
            PixelRows.write(out, y, w, row);
        }
        return out;
    }

    // Brightness and contrast (a:lum, fractions of 1) as LibreOffice applies them; washout is +0.7 and -0.7
    static BufferedImage lum(BufferedImage src, float bright, float contrast) {
        float c = Math.max(-1, Math.min(1, contrast)) * 100;
        float m = c >= 0 ? 128 / Math.max(1, 128 - 1.27f * c) : (128 + 1.27f * c) / 128;
        float off = Math.max(-1, Math.min(1, bright)) * 255 + 128 - m * 128;
        int[] map = new int[256];
        for (int i = 0; i < 256; i++) {
            map[i] = Math.max(0, Math.min(255, Math.round(m * i + off)));
        }
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            PixelRows.read(src, 0, y, w, row);
            for (int x = 0; x < w; x++) {
                int p = row[x];
                row[x] = p & 0xFF000000 | map[p >> 16 & 0xFF] << 16 | map[p >> 8 & 0xFF] << 8 | map[p & 0xFF];
            }
            PixelRows.write(out, y, w, row);
        }
        return out;
    }

    // Set Transparent Color: pixels within the tolerance of one colour take another, usually see-through
    static BufferedImage change(BufferedImage src, Color from, Color to, int tolerance) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        int fr = from.getRed();
        int fg = from.getGreen();
        int fb = from.getBlue();
        int target = to.getAlpha() << 24 | to.getRGB() & 0xFFFFFF;
        for (int y = 0; y < h; y++) {
            PixelRows.read(src, 0, y, w, row);
            for (int x = 0; x < w; x++) {
                int p = row[x];
                if (p >>> 24 != 0 && Math.abs((p >> 16 & 0xFF) - fr) <= tolerance
                        && Math.abs((p >> 8 & 0xFF) - fg) <= tolerance && Math.abs((p & 0xFF) - fb) <= tolerance) {
                    row[x] = target;
                }
            }
            PixelRows.write(out, y, w, row);
        }
        return out;
    }

    // Soft edges: opacity rises from nothing at the visible edge to full a radius inside it (radii as fractions)
    static BufferedImage soften(BufferedImage src, float[] crop, float rx, float ry) {
        int w = src.getWidth();
        int h = src.getHeight();
        float x0 = Math.max(0, crop[0]) * w;
        float x1 = (1 - Math.max(0, crop[2])) * w;
        float y0 = Math.max(0, crop[1]) * h;
        float y1 = (1 - Math.max(0, crop[3])) * h;
        float radX = Math.max(1e-3f, rx * (x1 - x0));
        float radY = Math.max(1e-3f, ry * (y1 - y0));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            PixelRows.read(src, 0, y, w, row);
            float ay = ramp(Math.min(y + 0.5f - y0, y1 - y - 0.5f) / radY);
            for (int x = 0; x < w; x++) {
                float a = ay * ramp(Math.min(x + 0.5f - x0, x1 - x - 0.5f) / radX);
                int p = row[x];
                row[x] = Math.round((p >>> 24) * a) << 24 | p & 0xFFFFFF;
            }
            PixelRows.write(out, y, w, row);
        }
        return out;
    }

    private static float ramp(float t) {
        if (t <= 0) {
            return 0;
        }
        return t >= 1 ? 1 : t * t * (3 - 2 * t);
    }

    static BufferedImage gray(BufferedImage src) {
        return map(src, p -> {
            int l = luma(p);
            return p & 0xFF000000 | l << 16 | l << 8 | l;
        });
    }

    // Black and white: luminance at or above the threshold (a fraction of 1) turns white
    static BufferedImage biLevel(BufferedImage src, float threshold) {
        int t = Math.round(Math.max(0, Math.min(1, threshold)) * 255);
        return map(src, p -> p & 0xFF000000 | (luma(p) >= t ? 0xFFFFFF : 0));
    }

    private static int luma(int p) {
        return Math.round(0.299f * (p >> 16 & 0xFF) + 0.587f * (p >> 8 & 0xFF) + 0.114f * (p & 0xFF));
    }

    private static BufferedImage map(BufferedImage src, IntUnaryOperator f) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            PixelRows.read(src, 0, y, w, row);
            for (int x = 0; x < w; x++) {
                row[x] = f.applyAsInt(row[x]);
            }
            PixelRows.write(out, y, w, row);
        }
        return out;
    }

    private static int mix(int a, int b, float t) {
        return Math.max(0, Math.min(255, Math.round(a + (b - a) * t)));
    }

    // The picture's visible part upside down, its bottom rows first, fading from one opacity to another
    static BufferedImage reflect(BufferedImage src, float left, float top, float right, float bottom, float stA,
            float stPos, float endA, float endPos) {
        int w = src.getWidth();
        int h = src.getHeight();
        int x0 = clamp(Math.round(left * w), 0, w - 1);
        int x1 = clamp(Math.round((1 - right) * w), x0 + 1, w);
        int y0 = clamp(Math.round(top * h), 0, h - 1);
        int y1 = clamp(Math.round((1 - bottom) * h), y0 + 1, h);
        int cw = x1 - x0;
        int rows = Math.max(1, Math.round((y1 - y0) * Math.max(0.01f, Math.min(1, endPos))));
        BufferedImage out = new BufferedImage(cw, rows, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[cw];
        float span = Math.max(1e-3f, endPos - stPos);
        for (int y = 0; y < rows; y++) {
            float pos = (y + 0.5f) / (y1 - y0);
            float t = Math.max(0, Math.min(1, (pos - stPos) / span));
            float alpha = pos < stPos ? stA : stA + (endA - stA) * t;
            PixelRows.read(src, x0, y1 - 1 - y, cw, row);
            for (int x = 0; x < cw; x++) {
                int a = Math.round((row[x] >>> 24) * Math.max(0, Math.min(1, alpha)));
                row[x] = a << 24 | row[x] & 0xFFFFFF;
            }
            PixelRows.write(out, y, cw, row);
        }
        return out;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
