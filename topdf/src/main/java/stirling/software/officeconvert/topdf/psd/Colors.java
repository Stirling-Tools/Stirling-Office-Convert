package stirling.software.officeconvert.topdf.psd;

final class Colors {

    private Colors() {}

    static float[] toRgb(int space, int[] v) {
        return switch (space) {
            case 0 -> new float[] {v[0] / 65535f, v[1] / 65535f, v[2] / 65535f};
            case 1 -> hsb(v[0] / 65535f * 360f, v[1] / 65535f, v[2] / 65535f);
            case 2 -> {
                float c = 1 - v[0] / 65535f;
                float m = 1 - v[1] / 65535f;
                float y = 1 - v[2] / 65535f;
                float k = 1 - v[3] / 65535f;
                yield new float[] {(1 - c) * (1 - k), (1 - m) * (1 - k), (1 - y) * (1 - k)};
            }
            case 7 -> lab(v[0] / 100f, (short) v[1] / 100f, (short) v[2] / 100f);
            case 8 -> {
                float g = 1 - Math.min(10000, v[0]) / 10000f;
                yield new float[] {g, g, g};
            }
            default -> new float[] {0, 0, 0};
        };
    }

    private static float[] hsb(float h, float s, float b) {
        float c = b * s;
        float x = c * (1 - Math.abs((h / 60f) % 2 - 1));
        float m = b - c;
        int i = (int) (h / 60f) % 6;
        float[] r = switch (i) {
            case 0 -> new float[] {c, x, 0};
            case 1 -> new float[] {x, c, 0};
            case 2 -> new float[] {0, c, x};
            case 3 -> new float[] {0, x, c};
            case 4 -> new float[] {x, 0, c};
            default -> new float[] {c, 0, x};
        };
        return new float[] {r[0] + m, r[1] + m, r[2] + m};
    }

    static float[] lab(float l, float a, float b) {
        double fy = (l + 16) / 116.0;
        double fx = fy + a / 500.0;
        double fz = fy - b / 200.0;
        double x = 0.9642 * finv(fx);
        double y = finv(fy);
        double z = 0.8249 * finv(fz);
        double r = 3.1338561 * x - 1.6168667 * y - 0.4906146 * z;
        double g = -0.9787684 * x + 1.9161415 * y + 0.0334540 * z;
        double bl = 0.0719453 * x - 0.2289914 * y + 1.4052427 * z;
        return new float[] {encode(r), encode(g), encode(bl)};
    }

    private static double finv(double t) {
        return t > 6.0 / 29 ? t * t * t : 3 * (6.0 / 29) * (6.0 / 29) * (t - 4.0 / 29);
    }

    static float encode(double linear) {
        double v = Math.max(0, Math.min(1, linear));
        return (float) (v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055);
    }

    static float clamp(float v) {
        return v > 0 ? v < 1 ? v : 1 : 0;
    }
}
