package stirling.software.officeconvert.photosplit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class ScriptMethod {

    private static final int DILATE_RADIUS = 4;
    private static final int CANNY_LOW = 50;
    private static final int CANNY_HIGH = 150;
    private static final int HOUGH_VOTES = 200;

    private ScriptMethod() {}

    static List<PhotoSplitter.Photo> split(ScanImage scan, PhotoSplitter.Options options) {
        int background = background(scan);
        int border = options.borderSize();
        ScanImage image = border > 0 ? scan.pad(border, background) : scan;
        byte[] mask = mask(image, background, options.tolerance());
        Morphology.dilate(mask, image.width, image.height, DILATE_RADIUS);
        List<PhotoSplitter.Photo> photos = new ArrayList<>();
        for (OuterContours.Contour c : OuterContours.find(mask, image.width, image.height)) {
            if ((long) c.width() * c.height() < options.minArea()
                    || OuterContours.area(mask, image.width, image.height, c) < options.minContourArea()) {
                continue;
            }
            ScanImage crop = image.crop(c.x(), c.y(), c.width(), c.height());
            double angle = angle(crop, options.angleThreshold());
            if (angle != 0) {
                crop = CubicWarp.rotate(crop, angle);
            }
            int x = c.x() - border;
            int y = c.y() - border;
            if (border > 0 && crop.height > 2 * border && crop.width > 2 * border) {
                crop = crop.crop(border, border, crop.width - 2 * border, crop.height - 2 * border);
                x += border;
                y += border;
            }
            photos.add(PhotoSplitter.Photo.of(crop.toImage(), x, y, crop.width, crop.height, angle));
        }
        return photos;
    }

    static int background(ScanImage scan) {
        int w = scan.width;
        int h = scan.height;
        int[] samples = {
            scan.rgb[0], scan.rgb[w - 1], scan.rgb[(h - 1) * w + w - 1], scan.rgb[(h - 1) * w], scan.rgb[(h / 2) * w + w / 2]
        };
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int[] c = new int[samples.length];
            for (int i = 0; i < samples.length; i++) {
                c[i] = samples[i] >> shift & 0xFF;
            }
            Arrays.sort(c);
            out |= c[2] << shift;
        }
        return out;
    }

    private static byte[] mask(ScanImage image, int background, int tolerance) {
        int[] lo = new int[3];
        int[] hi = new int[3];
        for (int k = 0; k < 3; k++) {
            int c = background >> (16 - 8 * k) & 0xFF;
            lo[k] = Math.clamp(c - (long) tolerance, 0, 255);
            hi[k] = Math.clamp(c + (long) tolerance, 0, 255);
        }
        int[] px = image.rgb;
        byte[] mask = new byte[px.length];
        for (int i = 0; i < px.length; i++) {
            int r = px[i] >> 16 & 0xFF;
            int g = px[i] >> 8 & 0xFF;
            int b = px[i] & 0xFF;
            boolean inside = r >= lo[0] && r <= hi[0] && g >= lo[1] && g <= hi[1] && b >= lo[2] && b <= hi[2];
            mask[i] = inside ? 0 : (byte) 1;
        }
        return mask;
    }

    private static double angle(ScanImage crop, int threshold) {
        byte[] edges = Canny.edges(crop.gray(), crop.width, crop.height, CANNY_LOW, CANNY_HIGH);
        float[] thetas = HoughLines.thetas(edges, crop.width, crop.height, HOUGH_VOTES);
        if (thetas.length == 0) {
            return 0;
        }
        float[] angles = new float[thetas.length];
        for (int i = 0; i < thetas.length; i++) {
            angles[i] = thetas[i] * 180f / (float) Math.PI - 90f;
        }
        Arrays.sort(angles);
        int n = angles.length;
        float median = n % 2 == 1 ? angles[n / 2] : (angles[n / 2 - 1] + angles[n / 2]) / 2f;
        return Math.abs(median) < threshold ? 0 : median;
    }
}
