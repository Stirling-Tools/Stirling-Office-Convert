package stirling.software.officeconvert.photosplit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.photosplit.SyntheticScan.Style;

class StraightenMethodTest {

    private static final PhotoSplitter.Options OPTIONS = PhotoSplitter.Options.defaults();

    @Test
    void tiltedPhotosAreCutOutStraightInReadingOrder() {
        SyntheticScan.Scene scene = new SyntheticScan(1000, 1100, 11)
                .photo(360, 240, 260, 220, 6, Style.GRADIENT, 0)
                .photo(300, 380, 740, 300, -8, Style.TEXTURE, 0)
                .photo(520, 300, 480, 800, 3, Style.BLOBS, 0)
                .render();
        List<PhotoSplitter.Photo> photos = PhotoSplitter.split(scene.scan(), OPTIONS);
        assertEquals(3, photos.size());
        for (int i = 0; i < 3; i++) {
            assertMatches(scene.photos().get(i), photos.get(i));
        }
    }

    @Test
    void photosAFewPixelsApartStaySeparate() {
        SyntheticScan.Scene scene = new SyntheticScan(1000, 700, 4).shadow(1)
                .photo(400, 300, 260, 300, 0, Style.GRADIENT, 0)
                .photo(400, 300, 668, 300, 0, Style.TEXTURE, 0)
                .render();
        List<PhotoSplitter.Photo> photos = PhotoSplitter.split(scene.scan(), OPTIONS);
        assertEquals(2, photos.size());
        assertMatches(scene.photos().get(0), photos.get(0));
        assertMatches(scene.photos().get(1), photos.get(1));
    }

    @Test
    void aShadowFilledGapStillSeparatesThePhotos() {
        SyntheticScan.Scene scene = new SyntheticScan(1100, 700, 6)
                .photo(450, 320, 290, 320, 0, Style.BLOBS, 0)
                .photo(450, 320, 746, 320, 0, Style.GRADIENT, 0)
                .render();
        List<PhotoSplitter.Photo> photos = PhotoSplitter.split(scene.scan(), OPTIONS);
        assertEquals(2, photos.size());
        assertMatches(scene.photos().get(0), photos.get(0));
        assertMatches(scene.photos().get(1), photos.get(1));
    }

    @Test
    void whiteBorderedPrintsKeepTheirBorder() {
        SyntheticScan.Scene scene = new SyntheticScan(1000, 900, 8).shadow(2)
                .photo(380, 280, 260, 220, 0, Style.GRADIENT, 22)
                .photo(360, 300, 720, 600, 4, Style.TEXTURE, 22)
                .render();
        List<PhotoSplitter.Photo> photos = PhotoSplitter.split(scene.scan(), OPTIONS);
        assertEquals(2, photos.size());
        assertMatches(scene.photos().get(0), photos.get(0));
        assertMatches(scene.photos().get(1), photos.get(1));
    }

    @Test
    void overlappingPhotosAreSeparated() {
        SyntheticScan.Scene scene = new SyntheticScan(1100, 900, 9)
                .photo(420, 300, 280, 260, 0, Style.BLOBS, 0)
                .photo(420, 300, 660, 520, 0, Style.TEXTURE, 0)
                .render();
        List<PhotoSplitter.Photo> photos = PhotoSplitter.split(scene.scan(), OPTIONS);
        assertEquals(2, photos.size());
    }

    @Test
    void tintedShadedBedAndPhotoAcrossTheScanEdge() {
        SyntheticScan.Scene scene = new SyntheticScan(1000, 900, 2).bed(new Color(214, 224, 240)).shading(0.15)
                .photo(400, 300, 300, 260, 2, Style.GRADIENT, 0)
                .photo(360, 300, 900, 650, -4, Style.TEXTURE, 0)
                .render();
        List<PhotoSplitter.Photo> photos = PhotoSplitter.split(scene.scan(), OPTIONS);
        assertEquals(2, photos.size());
        assertMatches(scene.photos().get(0), photos.get(0));
    }

    @Test
    void scanCornersCoveredByPhotosStillFindTheBed() {
        SyntheticScan.Scene scene = new SyntheticScan(900, 900, 5)
                .photo(400, 400, 200, 200, 0, Style.GRADIENT, 0)
                .photo(400, 400, 700, 200, 0, Style.TEXTURE, 0)
                .photo(400, 400, 200, 700, 0, Style.BLOBS, 0)
                .photo(300, 300, 650, 650, 1, Style.TEXTURE, 0)
                .render();
        assertEquals(4, PhotoSplitter.split(scene.scan(), OPTIONS).size());
    }

    @Test
    void anEmptyBedHasNoPhotos() {
        SyntheticScan.Scene scene = new SyntheticScan(800, 600, 1).noise(6).specks(80).render();
        assertTrue(PhotoSplitter.split(scene.scan(), OPTIONS).isEmpty());
    }

    private static void assertMatches(SyntheticScan.Placed truth, PhotoSplitter.Photo photo) {
        BufferedImage image = photo.image();
        assertEquals(truth.content().getWidth(), image.getWidth(), 6);
        assertEquals(truth.content().getHeight(), image.getHeight(), 6);
        assertEquals(truth.angle(), photo.angle(), 0.6);
        List<Point2D.Double> want = truth.corners();
        List<Point2D.Double> got = photo.corners();
        for (int i = 0; i < 4; i++) {
            assertTrue(want.get(i).distance(got.get(i)) < 6, "corner " + i + " " + got.get(i) + " expected " + want.get(i));
        }
    }
}
