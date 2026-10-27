package stirling.software.officeconvert.photosplit;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class StraightenMethod {

    private static final int SMOOTH_RADIUS = 1;
    private static final int WIDE_RADIUS = 4;
    private static final int CLOSE_RADIUS = 1;
    private static final int OPEN_RADIUS = 3;
    private static final double LEVEL_ANGLE = 0.25;
    private static final double SOLID = 0.9;
    private static final double MAX_STRIP_ASPECT = 6;
    private static final int NESTED_POINTS = 4;
    private static final double ANALYSIS_PIXELS = 4_500_000;

    private StraightenMethod() {}

    static List<PhotoSplitter.Photo> split(Source source, PhotoSplitter.Options options) {
        List<PhotoSplitter.Photo> photos = new ArrayList<>();
        for (RotatedRect rect : find(source, options)) {
            PhotoSplitter.checkpoint();
            photos.add(photo(source, rect));
        }
        return photos;
    }

    static PhotoSplitter.Photo photo(Source source, RotatedRect rect) {
        return new PhotoSplitter.Photo(Crop.of(source, rect, LEVEL_ANGLE), rect.corners(), rect.angle());
    }

    static List<RotatedRect> find(Source source, PhotoSplitter.Options options) {
        int factor = (int) Math.max(1, Math.ceil(Math.sqrt((double) source.width() * source.height() / ANALYSIS_PIXELS)));
        ScanImage scan = source.shrink(factor);
        int area = factor * factor;
        PhotoSplitter.Options scaled = options.minArea(options.minArea() / area).minContourArea(options.minContourArea() / area);
        double inside = 6.0 * factor + 4;
        double outside = 2.0 * factor + 2;
        List<RotatedRect> found = new ArrayList<>();
        for (RotatedRect rect : candidates(scan, scaled)) {
            RotatedRect full = rect.scaled(factor);
            if (factor > 1) {
                full = EdgeFit.refine(source, full, inside, outside, 2);
            }
            if (full.area() >= options.minArea()) {
                found.add(full);
            }
        }
        List<RotatedRect> out = new ArrayList<>();
        for (RotatedRect rect : readingOrder(outermost(found))) {
            out.add(rect.inset(options.borderSize()));
        }
        return out;
    }

    static List<RotatedRect> candidates(ScanImage scan, PhotoSplitter.Options options) {
        int w = scan.width;
        int h = scan.height;
        PhotoSplitter.checkpoint();
        int[] smooth = Filters.boxBlur(scan.rgb, w, h, SMOOTH_RADIUS);
        PhotoSplitter.checkpoint();
        Bed bed = Bed.fit(scan, smooth, options.tolerance());
        int strong = bed.threshold(options.tolerance());
        byte[] photo = new byte[w * h];
        byte[] mask = bed.foreground(smooth, w, h, strong, photo);
        smooth = null;
        int[] wide = Filters.boxBlur(scan.rgb, w, h, WIDE_RADIUS);
        byte[] faint = bed.foreground(wide, w, h, bed.faintThreshold(strong, SMOOTH_RADIUS, WIDE_RADIUS), null);
        wide = null;
        Filters.grow(mask, faint, w, h);
        faint = null;
        Morphology.dilate(mask, w, h, CLOSE_RADIUS);
        Morphology.erode(mask, w, h, CLOSE_RADIUS);
        Filters.fillHoles(mask, w, h);
        Morphology.erode(mask, w, h, OPEN_RADIUS);
        Morphology.dilate(mask, w, h, OPEN_RADIUS);
        List<RotatedRect> found = new ArrayList<>();
        for (Region region : Region.label(mask, w, h)) {
            PhotoSplitter.checkpoint();
            for (Region part : Seams.split(Pieces.separate(region, options), photo, scan, options)) {
                RotatedRect rect = part.rect();
                if (rect.area() < options.minArea() || part.count < options.minContourArea() || strip(part, rect, w, h)) {
                    continue;
                }
                RotatedRect fitted = EdgeFit.refine(scan, rect);
                if (fitted.area() >= options.minArea() && !Documents.isText(scan, fitted)) {
                    found.add(fitted);
                }
            }
        }
        return found;
    }

    private static boolean strip(Region part, RotatedRect rect, int w, int h) {
        boolean edge = part.minX == 0 || part.minY == 0 || part.maxX == w - 1 || part.maxY == h - 1;
        double longSide = Math.max(rect.width(), rect.height());
        double shortSide = Math.max(1, Math.min(rect.width(), rect.height()));
        return edge && longSide / shortSide > MAX_STRIP_ASPECT;
    }

    static List<RotatedRect> outermost(List<RotatedRect> rects) {
        List<RotatedRect> bySize = new ArrayList<>(rects);
        bySize.sort(Comparator.comparingDouble(RotatedRect::area).reversed());
        List<RotatedRect> kept = new ArrayList<>();
        for (RotatedRect r : bySize) {
            if (kept.stream().noneMatch(big -> inside(r, big))) {
                kept.add(r);
            }
        }
        return kept;
    }

    private static boolean inside(RotatedRect small, RotatedRect big) {
        int in = big.contains(small.cx(), small.cy()) ? 1 : 0;
        for (Point2D.Double p : small.corners()) {
            in += big.contains(p.x, p.y) ? 1 : 0;
        }
        return in >= NESTED_POINTS;
    }

    static List<RotatedRect> readingOrder(List<RotatedRect> rects) {
        List<RotatedRect> byTop = new ArrayList<>(rects);
        byTop.sort(Comparator.comparingDouble(RotatedRect::cy));
        List<RotatedRect> out = new ArrayList<>();
        List<RotatedRect> row = new ArrayList<>();
        double rowBottom = Double.NEGATIVE_INFINITY;
        for (RotatedRect r : byTop) {
            double half = halfHeight(r);
            if (!row.isEmpty() && r.cy() > rowBottom) {
                row.sort(Comparator.comparingDouble(RotatedRect::cx));
                out.addAll(row);
                row.clear();
                rowBottom = Double.NEGATIVE_INFINITY;
            }
            row.add(r);
            rowBottom = Math.max(rowBottom, r.cy() + half * 0.5);
        }
        row.sort(Comparator.comparingDouble(RotatedRect::cx));
        out.addAll(row);
        return out;
    }

    private static double halfHeight(RotatedRect r) {
        double a = Math.toRadians(r.angle());
        return (Math.abs(r.width() * Math.sin(a)) + Math.abs(r.height() * Math.cos(a))) / 2;
    }

    static boolean solid(Region region, RotatedRect rect) {
        return region.count >= SOLID * rect.area();
    }
}
