package stirling.software.officeconvert.jpx;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

final class Subsample {

    private Subsample() {}

    static BufferedImage pick(BufferedImage src, Rectangle region, int sx, int sy, int reduce) {
        int w = (region.width + sx - 1) / sx;
        int h = (region.height + sy - 1) / sy;
        WritableRaster out = src.getRaster().createCompatibleWritableRaster(w, h);
        Raster in = src.getRaster();
        int maxX = src.getWidth() - 1;
        int maxY = src.getHeight() - 1;
        Object pixel = null;
        for (int y = 0; y < h; y++) {
            int ry = Math.min(maxY, (region.y + y * sy) >> reduce);
            for (int x = 0; x < w; x++) {
                int rx = Math.min(maxX, (region.x + x * sx) >> reduce);
                pixel = in.getDataElements(rx, ry, pixel);
                out.setDataElements(x, y, pixel);
            }
        }
        return new BufferedImage(src.getColorModel(), out, src.isAlphaPremultiplied(), null);
    }
}
