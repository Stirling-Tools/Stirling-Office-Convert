package stirling.software.officeconvert.jpx;

abstract class Channel {

    abstract int depth();

    abstract void row(int y, int[] out);

    static Channel component(JpxRaster raster, int c) {
        return new Channel() {
            @Override
            int depth() {
                return raster.depth(c);
            }

            @Override
            void row(int y, int[] out) {
                int w = raster.width();
                for (int x = 0; x < w; x++) {
                    out[x] = raster.imageSample(c, x, y);
                }
            }
        };
    }

    static Channel palette(Channel index, Palette palette, int column) {
        int[] values = palette.values()[column];
        int last = palette.entries() - 1;
        int depth = palette.depth()[column];
        return new Channel() {
            @Override
            int depth() {
                return depth;
            }

            @Override
            void row(int y, int[] out) {
                index.row(y, out);
                for (int x = 0; x < out.length; x++) {
                    out[x] = values[Math.min(out[x], last)];
                }
            }
        };
    }
}
