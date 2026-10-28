package stirling.software.officeconvert.photosplit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.photosplit.SyntheticScan.Style;

class ScriptMethodTest {

    @Test
    void separatedPhotosComeBackBottomFirstWithTheDilationMargin() {
        SyntheticScan.Scene scene = new SyntheticScan(900, 1100, 7)
                .photo(320, 220, 250, 200, 0, Style.GRADIENT, 0)
                .photo(300, 260, 600, 750, 0, Style.TEXTURE, 0)
                .render();
        List<PhotoSplitter.Photo> photos = ScriptMethod.split(ScanImage.of(scene.scan()), PhotoSplitter.Options.defaults());
        assertEquals(2, photos.size());
        assertBoxAround(photos.get(0), 600, 750, 300, 260);
        assertBoxAround(photos.get(1), 250, 200, 320, 220);
    }

    @Test
    void borderSizeTrimsEveryCrop() {
        SyntheticScan.Scene scene = new SyntheticScan(700, 600, 3).photo(300, 200, 350, 300, 0, Style.BLOBS, 0).render();
        PhotoSplitter.Photo plain = ScriptMethod.split(ScanImage.of(scene.scan()), PhotoSplitter.Options.defaults()).getFirst();
        PhotoSplitter.Photo trimmed = ScriptMethod.split(ScanImage.of(scene.scan()), PhotoSplitter.Options.defaults().borderSize(3))
                .getFirst();
        assertEquals(plain.image().getWidth() - 6, trimmed.image().getWidth());
        assertEquals(plain.image().getHeight() - 6, trimmed.image().getHeight());
    }

    @Test
    void backgroundIsThePerChannelMedianOfCornersAndCentre() {
        int[] px = new int[25];
        java.util.Arrays.fill(px, 0x101010);
        px[0] = 0xF0E0D0;
        px[4] = 0xF2E2D2;
        px[24] = 0xF4E4D4;
        px[20] = 0x000000;
        px[12] = 0xFFFFFF;
        assertEquals(0xF2E2D2, ScriptMethod.background(new ScanImage(5, 5, px)));
    }

    @Test
    void outerContourAreaMatchesTheTracedPolygon() {
        int w = 40;
        int h = 30;
        byte[] mask = new byte[w * h];
        fill(mask, w, 2, 3, 10, 6);
        fill(mask, w, 20, 0, 1, 1);
        fill(mask, w, 25, 10, 12, 15);
        fill(mask, w, 28, 13, 6, 9, (byte) 0);
        fill(mask, w, 30, 16, 2, 2);
        List<OuterContours.Contour> found = OuterContours.find(mask, w, h);
        assertEquals(3, found.size());
        assertEquals(new OuterContours.Contour(25, 10, 12, 15, 25, 10), found.get(0));
        assertEquals(new OuterContours.Contour(2, 3, 10, 6, 2, 3), found.get(1));
        assertEquals(new OuterContours.Contour(20, 0, 1, 1, 20, 0), found.get(2));
        assertEquals(11 * 14, OuterContours.area(mask, w, h, found.get(0)));
        assertEquals(9 * 5, OuterContours.area(mask, w, h, found.get(1)));
        assertEquals(0, OuterContours.area(mask, w, h, found.get(2)));
    }

    @Test
    void contourAreaOfADiagonalLineIsZero() {
        int w = 20;
        byte[] mask = new byte[w * w];
        for (int i = 2; i < 18; i++) {
            mask[i * w + i] = 1;
        }
        List<OuterContours.Contour> found = OuterContours.find(mask, w, w);
        assertEquals(1, found.size());
        assertEquals(0, OuterContours.area(mask, w, w, found.getFirst()));
    }

    @Test
    void quarterTurnsAndNoTurnMovePixelsExactly() {
        SplittableRandom random = new SplittableRandom(5);
        int n = 9;
        int[] px = new int[n * n];
        for (int i = 0; i < px.length; i++) {
            px[i] = random.nextInt(0x1000000);
        }
        ScanImage square = new ScanImage(n, n, px);
        int[] turned = CubicWarp.rotate(square, 90).rgb;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                assertEquals(px[x * n + (n - 1 - y)], turned[y * n + x]);
            }
        }
        assertArrayEquals(px, CubicWarp.rotate(square, 0).rgb);
    }

    @Test
    void cannyFindsTheOutlineOfAFilledSquare() {
        int n = 40;
        int[] gray = new int[n * n];
        for (int y = 10; y < 30; y++) {
            for (int x = 10; x < 30; x++) {
                gray[y * n + x] = 200;
            }
        }
        byte[] edges = Canny.edges(gray, n, n, 50, 150);
        int count = 0;
        for (byte e : edges) {
            count += e;
        }
        assertTrue(edges[20 * n + 10] == 1 || edges[20 * n + 9] == 1);
        assertEquals(0, edges[20 * n + 20]);
        assertTrue(count >= 4 * 18 && count <= 4 * 22, "edge pixels " + count);
    }

    @Test
    void houghFindsAHorizontalAndAVerticalLine() {
        int n = 300;
        byte[] edges = new byte[n * n];
        for (int i = 20; i < 280; i++) {
            edges[100 * n + i] = 1;
            edges[i * n + 50] = 1;
        }
        float[] thetas = HoughLines.thetas(edges, n, n, 200);
        assertEquals(2, thetas.length);
        assertEquals(0f, thetas[0], 1e-6f);
        assertEquals(Math.PI / 2, thetas[1], 1e-6);
    }

    private static void assertBoxAround(PhotoSplitter.Photo photo, double cx, double cy, int w, int h) {
        BufferedImage image = photo.image();
        double x = photo.corners().getFirst().x;
        double y = photo.corners().getFirst().y;
        assertTrue(Math.abs(x + image.getWidth() / 2.0 - cx) < 6, "centre x " + (x + image.getWidth() / 2.0));
        assertTrue(Math.abs(y + image.getHeight() / 2.0 - cy) < 6, "centre y " + (y + image.getHeight() / 2.0));
        assertTrue(image.getWidth() >= w && image.getWidth() <= w + 16, "width " + image.getWidth());
        assertTrue(image.getHeight() >= h && image.getHeight() <= h + 16, "height " + image.getHeight());
    }

    private static void fill(byte[] mask, int w, int x, int y, int fw, int fh) {
        fill(mask, w, x, y, fw, fh, (byte) 1);
    }

    private static void fill(byte[] mask, int w, int x, int y, int fw, int fh, byte v) {
        for (int r = y; r < y + fh; r++) {
            for (int c = x; c < x + fw; c++) {
                mask[r * w + c] = v;
            }
        }
    }
}
