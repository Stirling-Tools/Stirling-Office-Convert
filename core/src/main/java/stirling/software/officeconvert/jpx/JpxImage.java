package stirling.software.officeconvert.jpx;

import java.awt.image.BufferedImage;

public final class JpxImage {

    private final JpxRaster raster;

    private final Jp2Boxes boxes;

    private final int reduce;

    JpxImage(JpxRaster raster, Jp2Boxes boxes, int reduce) {
        this.raster = raster;
        this.boxes = boxes;
        this.reduce = reduce;
    }

    public JpxRaster raster() {
        return raster;
    }

    public int reduce() {
        return reduce;
    }

    public BufferedImage toBufferedImage() {
        return new ImageComposer(raster, boxes).compose();
    }
}
