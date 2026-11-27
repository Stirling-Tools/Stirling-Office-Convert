package stirling.software.officeconvert.topdf.psd;

import java.nio.ByteBuffer;

abstract class Adjustments {

    static final int MAX_BYTES = 1 << 16;

    static final int LUT = 1024;

    abstract void apply(float[] buf, int at, int n, float[] out);

    static Adjustments of(String key, byte[] data, int mode, int depth) {
        if (data == null) {
            return null;
        }
        try {
            return switch (key) {
                case "nvrt" -> new Luts(identity(mode, v -> 1 - v), null);
                case "post" -> posterize(data, mode);
                case "thrs" -> threshold(data, mode);
                case "levl" -> levels(data, mode);
                case "curv" -> curves(data, mode);
                case "hue2" -> hueSaturation(data, mode);
                case "expA" -> exposure(data, mode, depth);
                default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    interface Fn {
        float at(float v);
    }

    private static float[][] identity(int mode, Fn fn) {
        int n = channels(mode);
        float[][] luts = new float[n][];
        for (int i = 0; i < n; i++) {
            luts[i] = lut(fn);
        }
        return luts;
    }

    private static int channels(int mode) {
        return switch (mode) {
            case PsdDocument.RGB, PsdDocument.LAB -> 3;
            case PsdDocument.CMYK -> 4;
            default -> 1;
        };
    }

    static float[] lut(Fn fn) {
        float[] l = new float[LUT];
        for (int i = 0; i < LUT; i++) {
            l[i] = Colors.clamp(fn.at(i / (float) (LUT - 1)));
        }
        return l;
    }

    static float look(float[] lut, float v) {
        float p = Colors.clamp(v) * (LUT - 1);
        int i = (int) p;
        if (i >= LUT - 1) {
            return lut[LUT - 1];
        }
        float f = p - i;
        return lut[i] + (lut[i + 1] - lut[i]) * f;
    }

    private static Adjustments posterize(byte[] data, int mode) {
        int levels = Math.max(2, Math.min(255, ByteBuffer.wrap(data).getShort() & 0xFFFF));
        return new Luts(identity(mode, v -> (float) Math.floor(v * levels * 255f / 256f) / (levels - 1)), null);
    }

    private static Adjustments threshold(byte[] data, int mode) {
        if (mode != PsdDocument.RGB && mode != PsdDocument.GRAY && mode != PsdDocument.DUOTONE) {
            return null;
        }
        int level = ByteBuffer.wrap(data).getShort() & 0xFFFF;
        return new Adjustments() {
            @Override
            void apply(float[] buf, int at, int n, float[] out) {
                float lum = n >= 3 ? 0.3f * buf[at] + 0.59f * buf[at + 1] + 0.11f * buf[at + 2] : buf[at];
                float v = Math.round(lum * 255) >= level ? 1 : 0;
                for (int i = 0; i < n; i++) {
                    out[i] = v;
                }
            }
        };
    }

    private static Adjustments levels(byte[] data, int mode) {
        ByteBuffer b = ByteBuffer.wrap(data);
        if ((b.getShort() & 0xFFFF) != 2) {
            return null;
        }
        int n = channels(mode);
        float[] master = null;
        float[][] luts = new float[n][];
        for (int c = 0; c <= n && b.remaining() >= 10; c++) {
            float inFloor = (b.getShort() & 0xFFFF) / 255f;
            float inCeil = (b.getShort() & 0xFFFF) / 255f;
            float outFloor = (b.getShort() & 0xFFFF) / 255f;
            float outCeil = (b.getShort() & 0xFFFF) / 255f;
            float gamma = Math.max(0.01f, Math.min(9.99f, (b.getShort() & 0xFFFF) / 100f));
            float range = Math.abs(inCeil - inFloor) > 1e-6f ? inCeil - inFloor : 1;
            float[] l = lut(v -> {
                float t = Colors.clamp((v - inFloor) / range);
                t = (float) Math.pow(t, 1 / gamma);
                return outFloor + t * (outCeil - outFloor);
            });
            if (c == 0) {
                master = l;
            } else {
                luts[c - 1] = l;
            }
        }
        return new Luts(luts, mode == PsdDocument.GRAY || mode == PsdDocument.DUOTONE ? null : master);
    }

    private static Adjustments curves(byte[] data, int mode) {
        ByteBuffer b = ByteBuffer.wrap(data);
        int isMap = b.get() & 0xFF;
        int version = b.getShort() & 0xFFFF;
        long count = b.getInt() & 0xFFFFFFFFL;
        if (isMap != 0 || version != 1 && version != 4) {
            return null;
        }
        int curves = version == 1 ? Long.bitCount(count) : (int) Math.min(count, 64);
        int[] bitIds = new int[curves];
        for (int i = 0, k = 0; i < 32 && k < curves; i++) {
            if ((count >> i & 1) != 0) {
                bitIds[k++] = i;
            }
        }
        int n = channels(mode);
        float[][] luts = new float[n][];
        float[] master = null;
        float[][] read = new float[curves][];
        for (int i = 0; i < curves; i++) {
            read[i] = spline(b);
        }
        int[] ids = version == 1 ? bitIds : null;
        if (b.remaining() >= 10) {
            int sig = b.getInt();
            if (sig == 0x43727620) {
                b.getShort();
                int extra = (int) Math.min(b.getInt() & 0xFFFFFFFFL, 64);
                read = new float[extra][];
                ids = new int[extra];
                for (int i = 0; i < extra; i++) {
                    ids[i] = b.getShort() & 0xFFFF;
                    read[i] = spline(b);
                }
            }
        }
        if (ids == null) {
            ids = new int[read.length];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = i;
            }
        }
        for (int i = 0; i < read.length; i++) {
            if (read[i] == null) {
                continue;
            }
            if (ids[i] == 0) {
                master = read[i];
            } else if (ids[i] - 1 < n) {
                luts[ids[i] - 1] = read[i];
            }
        }
        boolean gray = mode == PsdDocument.GRAY || mode == PsdDocument.DUOTONE;
        return new Luts(luts, gray ? null : master);
    }

    private static float[] spline(ByteBuffer b) {
        int count = b.getShort() & 0xFFFF;
        if (count < 2 || count > 19) {
            throw new IllegalArgumentException("curve points");
        }
        double[] x = new double[count];
        double[] y = new double[count];
        for (int i = 0; i < count; i++) {
            y[i] = (b.getShort() & 0xFFFF) / 255.0;
            x[i] = (b.getShort() & 0xFFFF) / 255.0;
        }
        for (int i = 1; i < count; i++) {
            if (x[i] <= x[i - 1]) {
                throw new IllegalArgumentException("curve order");
            }
        }
        double[] m = natural(x, y);
        return lut(v -> {
            double t = Math.max(x[0], Math.min(x[count - 1], v));
            int k = 0;
            while (k < count - 2 && t > x[k + 1]) {
                k++;
            }
            double h = x[k + 1] - x[k];
            double a = (x[k + 1] - t) / h;
            double c = (t - x[k]) / h;
            return (float) (a * y[k] + c * y[k + 1] + ((a * a * a - a) * m[k] + (c * c * c - c) * m[k + 1]) * h * h / 6);
        });
    }

    private static double[] natural(double[] x, double[] y) {
        int n = x.length;
        double[] m = new double[n];
        if (n < 3) {
            return m;
        }
        double[] u = new double[n];
        for (int i = 1; i < n - 1; i++) {
            double sig = (x[i] - x[i - 1]) / (x[i + 1] - x[i - 1]);
            double p = sig * m[i - 1] + 2;
            m[i] = (sig - 1) / p;
            u[i] = (y[i + 1] - y[i]) / (x[i + 1] - x[i]) - (y[i] - y[i - 1]) / (x[i] - x[i - 1]);
            u[i] = (6 * u[i] / (x[i + 1] - x[i - 1]) - sig * u[i - 1]) / p;
        }
        m[n - 1] = 0;
        for (int k = n - 2; k >= 0; k--) {
            m[k] = m[k] * m[k + 1] + u[k];
        }
        return m;
    }

    private static Adjustments exposure(byte[] data, int mode, int depth) {
        if (mode != PsdDocument.RGB && mode != PsdDocument.GRAY && mode != PsdDocument.DUOTONE) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(data);
        b.getShort();
        float exposure = b.getFloat();
        float offset = b.getFloat();
        float gamma = Math.max(0.01f, Math.min(9.99f, b.getFloat()));
        if (!Float.isFinite(exposure) || !Float.isFinite(offset)) {
            return null;
        }
        double trc = depth == 32 ? 1 : mode == PsdDocument.RGB ? 2.2 : 1.75;
        double scale = Math.pow(2, Math.max(-20, Math.min(20, exposure)));
        float[] lut = lut(v -> {
            double linear = Math.pow(v, trc) * scale + offset;
            linear = Math.max(0, Math.min(1, linear));
            return (float) Math.pow(Math.pow(linear, 1 / gamma), 1 / trc);
        });
        float[][] luts = new float[channels(mode)][];
        java.util.Arrays.fill(luts, lut);
        return new Luts(luts, null);
    }

    private static Adjustments hueSaturation(byte[] data, int mode) {
        if (mode != PsdDocument.RGB) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(data);
        if ((b.getShort() & 0xFFFF) != 2) {
            return null;
        }
        boolean colorize = b.get() != 0;
        b.get();
        float cHue = b.getShort();
        float cSat = b.getShort();
        float cLight = b.getShort();
        float hue = b.getShort();
        float sat = b.getShort();
        float light = b.getShort();
        for (int r = 0; r < 6 && b.remaining() >= 14; r++) {
            b.getShort();
            b.getShort();
            b.getShort();
            b.getShort();
            if (b.getShort() != 0 || b.getShort() != 0 || b.getShort() != 0) {
                return null;
            }
        }
        return new Adjustments() {
            @Override
            void apply(float[] buf, int at, int n, float[] out) {
                float r = buf[at];
                float g = buf[at + 1];
                float bl = buf[at + 2];
                float max = Math.max(r, Math.max(g, bl));
                float min = Math.min(r, Math.min(g, bl));
                float l = (max + min) / 2;
                float s = max == min ? 0 : l <= 0.5f ? (max - min) / (max + min) : (max - min) / (2 - max - min);
                float h = 0;
                if (max != min) {
                    float d = max - min;
                    h = max == r ? (g - bl) / d + (g < bl ? 6 : 0) : max == g ? (bl - r) / d + 2 : (r - g) / d + 4;
                    h *= 60;
                }
                float lightness;
                if (colorize) {
                    h = cHue;
                    s = cSat / 100f;
                    lightness = cLight;
                } else {
                    h += hue;
                    s = Colors.clamp(s * (1 + sat / 100f));
                    lightness = light;
                }
                float[] rgb = hsl(((h % 360) + 360) % 360, Colors.clamp(s), l);
                for (int i = 0; i < 3; i++) {
                    float v = rgb[i];
                    v = lightness < 0 ? v * (1 + lightness / 100f) : v + (1 - v) * lightness / 100f;
                    out[i] = Colors.clamp(v);
                }
            }
        };
    }

    private static float[] hsl(float h, float s, float l) {
        float q = l < 0.5f ? l * (1 + s) : l + s - l * s;
        float p = 2 * l - q;
        return new float[] {channel(p, q, h / 360 + 1f / 3), channel(p, q, h / 360), channel(p, q, h / 360 - 1f / 3)};
    }

    private static float channel(float p, float q, float t) {
        if (t < 0) {
            t += 1;
        }
        if (t > 1) {
            t -= 1;
        }
        if (t < 1f / 6) {
            return p + (q - p) * 6 * t;
        }
        if (t < 0.5f) {
            return q;
        }
        if (t < 2f / 3) {
            return p + (q - p) * (2f / 3 - t) * 6;
        }
        return p;
    }

    static final class Luts extends Adjustments {

        private final float[][] luts;

        private final float[] master;

        Luts(float[][] luts, float[] master) {
            this.luts = luts;
            this.master = master;
        }

        @Override
        void apply(float[] buf, int at, int n, float[] out) {
            for (int i = 0; i < n; i++) {
                float v = buf[at + i];
                if (i < luts.length && luts[i] != null) {
                    v = look(luts[i], v);
                }
                if (master != null) {
                    v = look(master, v);
                }
                out[i] = v;
            }
        }
    }
}
