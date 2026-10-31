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

    public boolean palette() {
        return boxes.palette != null;
    }

    public int[][] channelDefinitions() {
        if (boxes.definitions == null) {
            return null;
        }
        int[][] copy = new int[boxes.definitions.length][];
        for (int i = 0; i < copy.length; i++) {
            copy[i] = boxes.definitions[i].clone();
        }
        return copy;
    }

    public BufferedImage toBufferedImage() {
        return new ImageComposer(raster, boxes).compose();
    }
}
