package stirling.software.officeconvert.jpx;

final class Colour {

    private Colour() {}

    static void inverseRct(int[] y, int[] cb, int[] cr) {
        for (int i = 0; i < y.length; i++) {
            int g = y[i] - ((cb[i] + cr[i]) >> 2);
            int r = cr[i] + g;
            int b = cb[i] + g;
            y[i] = r;
            cb[i] = g;
            cr[i] = b;
        }
    }

    static void inverseIct(float[] y, float[] cb, float[] cr) {
        for (int i = 0; i < y.length; i++) {
            float yy = y[i];
            float u = cb[i];
            float v = cr[i];
            y[i] = yy + v * 1.402f;
            cb[i] = yy - u * 0.34413f - v * 0.71414f;
            cr[i] = yy + u * 1.772f;
        }
    }
}
