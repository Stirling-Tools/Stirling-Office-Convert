package stirling.software.officeconvert.jpx;

public final class JpxRaster {

    private static final long MAX_SAMPLES = Integer.MAX_VALUE - 8;

    private final int[] widths;

    private final int[] heights;

    private final int[] depths;

    private final boolean[] signed;

    private final int[] x0;

    private final int[] y0;

    private final int[] dx;

    private final int[] dy;

    private final byte[][] bytes;

    private final short[][] shorts;

    private final int imageX0;

    private final int imageY0;

    private final int width;

    private final int height;

    JpxRaster(Siz siz, int reduce, long maxSamples, int bands) throws JpxException {
        int n = siz.components();
        widths = new int[n];
        heights = new int[n];
        depths = new int[n];
        signed = new boolean[n];
        x0 = new int[n];
        y0 = new int[n];
        dx = siz.dx().clone();
        dy = siz.dy().clone();
        bytes = new byte[n][];
        shorts = new short[n][];
        long total = 0;
        for (int c = 0; c < n; c++) {
            x0[c] = (int) Siz.ceilShift(Siz.ceilDiv(siz.x0(), dx[c]), reduce);
            y0[c] = (int) Siz.ceilShift(Siz.ceilDiv(siz.y0(), dy[c]), reduce);
            widths[c] = (int) Siz.ceilShift(Siz.ceilDiv(siz.width(), dx[c]), reduce) - x0[c];
            heights[c] = (int) Siz.ceilShift(Siz.ceilDiv(siz.height(), dy[c]), reduce) - y0[c];
            depths[c] = Math.min(16, siz.depth()[c]);
            signed[c] = siz.signed()[c];
            total += (long) widths[c] * heights[c];
        }
        ImageGrid grid = ImageGrid.of(siz, reduce);
        imageX0 = grid.x0();
        imageY0 = grid.y0();
        width = grid.width();
        height = grid.height();
        if (width <= 0 || height <= 0) {
            throw new JpxException("JPEG 2000 image is empty");
        }
        long samples = Math.max(total, (long) width * height * Math.max(1, bands));
        long limit = Math.min(maxSamples, MAX_SAMPLES);
        if (samples > limit) {
            throw new JpxException("JPEG 2000 image has " + samples + " samples, more than " + limit);
        }
        for (int c = 0; c < n; c++) {
            if (depths[c] <= 8) {
                bytes[c] = new byte[widths[c] * heights[c]];
            } else {
                shorts[c] = new short[widths[c] * heights[c]];
            }
        }
        if (siz.uniform()) {
            for (int c = 0; c < n; c++) {
                dx[c] = 1;
                dy[c] = 1;
            }
        }
    }

    public int components() {
        return widths.length;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int width(int c) {
        return widths[c];
    }

    public int height(int c) {
        return heights[c];
    }

    public int depth(int c) {
        return depths[c];
    }

    public boolean signed(int c) {
        return signed[c];
    }

    public int sample(int c, int x, int y) {
        int i = y * widths[c] + x;
        return bytes[c] != null ? bytes[c][i] & 0xFF : shorts[c][i] & 0xFFFF;
    }

    public int imageSample(int c, int x, int y) {
        if (widths[c] == 0 || heights[c] == 0) {
            return 0;
        }
        if (dx[c] == 1 && dy[c] == 1) {
            return sample(c, x, y);
        }
        int sx = Math.clamp(Math.floorDiv(imageX0 + x, dx[c]) - x0[c], 0, widths[c] - 1);
        int sy = Math.clamp(Math.floorDiv(imageY0 + y, dy[c]) - y0[c], 0, heights[c] - 1);
        return sample(c, sx, sy);
    }

    boolean subsampled(int c) {
        return dx[c] > 1 || dy[c] > 1;
    }

    int originX(int c) {
        return x0[c];
    }

    int originY(int c) {
        return y0[c];
    }

    void set(int c, int i, int v) {
        if (bytes[c] != null) {
            bytes[c][i] = (byte) v;
        } else {
            shorts[c][i] = (short) v;
        }
    }
}
