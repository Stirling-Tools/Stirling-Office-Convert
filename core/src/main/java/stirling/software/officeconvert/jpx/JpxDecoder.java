package stirling.software.officeconvert.jpx;

import java.awt.Rectangle;
import java.io.IOException;

public final class JpxDecoder {

    private JpxDecoder() {}

    public static JpxImage decode(byte[] data) throws IOException {
        return decode(data, JpxOptions.defaults());
    }

    public static JpxImage decode(byte[] data, JpxOptions options) throws IOException {
        Jp2Boxes boxes = Jp2Boxes.read(data);
        Codestream cs = Codestream.parse(data, boxes.codestreamStart, boxes.codestreamEnd);
        int reduce = Math.min(options.reduce(), minimumLevels(cs));
        if (boxes.palette != null) {
            reduce = 0;
        }
        JpxRaster raster = new JpxRaster(cs.siz, reduce, options.maxSamples());
        TileDecoder decoder = new TileDecoder(cs, raster, reduce, options.maxSamples());
        for (int t = 0; t < cs.tiles.length; t++) {
            if (wanted(cs.siz, t, options.region())) {
                decoder.decode(t);
            }
        }
        return new JpxImage(raster, boxes, reduce);
    }

    public static int[] size(byte[] data) throws IOException {
        Jp2Boxes boxes = Jp2Boxes.read(data);
        Siz siz = Codestream.header(data, boxes.codestreamStart, boxes.codestreamEnd);
        boolean uniform = uniform(siz);
        int dx = uniform ? siz.dx()[0] : 1;
        int dy = uniform ? siz.dy()[0] : 1;
        long w = Siz.ceilDiv(siz.width(), dx) - Siz.ceilDiv(siz.x0(), dx);
        long h = Siz.ceilDiv(siz.height(), dy) - Siz.ceilDiv(siz.y0(), dy);
        return new int[] {(int) w, (int) h, siz.components()};
    }

    private static int minimumLevels(Codestream cs) {
        int min = levels(cs.main);
        for (TileStream t : cs.tiles) {
            if (t.present()) {
                min = Math.min(min, levels(t.markers));
            }
        }
        return min;
    }

    private static int levels(MarkerSet m) {
        int min = m.cod == null ? Integer.MAX_VALUE : m.cod.style().levels();
        for (ComponentStyle s : m.coc.values()) {
            min = Math.min(min, s.levels());
        }
        return min;
    }

    private static boolean wanted(Siz siz, int t, Rectangle region) {
        if (region == null) {
            return true;
        }
        long[] r = siz.tileRect(t);
        int sx = 1;
        int sy = 1;
        if (uniform(siz)) {
            sx = siz.dx()[0];
            sy = siz.dy()[0];
        }
        long x0 = siz.x0() + (long) region.x * sx;
        long y0 = siz.y0() + (long) region.y * sy;
        long x1 = x0 + (long) region.width * sx;
        long y1 = y0 + (long) region.height * sy;
        return r[0] < x1 && r[2] > x0 && r[1] < y1 && r[3] > y0;
    }

    private static boolean uniform(Siz siz) {
        for (int c = 1; c < siz.components(); c++) {
            if (siz.dx()[c] != siz.dx()[0] || siz.dy()[c] != siz.dy()[0]) {
                return false;
            }
        }
        return true;
    }
}
