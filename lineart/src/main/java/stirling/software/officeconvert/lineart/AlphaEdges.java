package stirling.software.officeconvert.lineart;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;

final class AlphaEdges {

    private AlphaEdges() {}

    static Bitmap binarize(BufferedImage image, LineArt.Options options) throws IOException {
        int w = image.getWidth();
        int h = image.getHeight();
        int r = options.edgeLevel();
        int k = 2 * r + 1;
        float[][] grey = new float[k][];
        int[][] pixels = new int[k][w];
        int[] cached = new int[k];
        float[] line = new float[w];
        Bitmap out = Bitmap.blank(w, h);
        Binarizer.thresholdEdges(band -> {
            Arrays.fill(cached, -1);
            for (int y = 0; y < h; y++) {
                LineArt.checkpoint();
                for (int dy = -r; dy <= r; dy++) {
                    int yy = Math.clamp(y + dy, 0, h - 1);
                    int slot = yy % k;
                    if (cached[slot] != yy) {
                        grey[slot] = Binarizer.grey(image.getSubimage(0, yy, w, 1), grey[slot]);
                        image.getRGB(0, yy, w, 1, pixels[slot], 0, w);
                        cached[slot] = yy;
                    }
                }
                for (int x = 0; x < w; x++) {
                    if ((x & 4095) == 0) {
                        LineArt.checkpoint();
                    }
                    double value = 0;
                    double weight = 0;
                    for (int dy = -r; dy <= r; dy++) {
                        int slot = Math.clamp(y + dy, 0, h - 1) % k;
                        for (int dx = -r; dx <= r; dx++) {
                            int xx = Math.clamp(x + dx, 0, w - 1);
                            double kernel = dx == 0 && dy == 0 ? k * k - 1 : -1;
                            double alpha = (1.0 / 65535) * ((pixels[slot][xx] >>> 24) * 257);
                            double weighted = alpha * kernel;
                            value += weighted * grey[slot][xx];
                            weight += weighted;
                        }
                    }
                    double reciprocal = Math.abs(weight) < 1e-12 ? 1e12 : 1 / weight;
                    float edge = (float) (value * reciprocal);
                    line[x] = 65535f - Math.clamp(edge, 0f, 65535f);
                }
                band.accept(y, line, 1);
            }
        }, w, h, out);
        return out;
    }
}
