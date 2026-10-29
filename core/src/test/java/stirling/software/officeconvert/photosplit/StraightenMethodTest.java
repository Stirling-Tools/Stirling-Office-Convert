package stirling.software.officeconvert.photosplit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;

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
    void aFaintScannerFrameDoesNotMergeSeparatePrints() {
        BufferedImage scan = new BufferedImage(900, 700, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(41);
        for (int y = 0; y < 700; y++) {
            for (int x = 0; x < 900; x++) {
                boolean frame = x >= 70 && x < 830 && y >= 70 && y < 630
                        && (x < 84 || x >= 816 || y < 84 || y >= 616);
                int level = (frame ? 246 : 250) + random.nextInt(5) - 2;
                scan.setRGB(x, y, new Color(level, level, level).getRGB());
            }
        }
        for (int y = 0; y < 250; y++) {
            for (int x = 0; x < 230; x++) {
                scan.setRGB(130 + x, 210 + y, new Color(30 + x / 3, 70 + y / 4, 180).getRGB());
                scan.setRGB(510 + x, 210 + y, new Color(180, 30 + x / 3, 70 + y / 4).getRGB());
            }
        }
        var photos = PhotoSplitter.split(scan, OPTIONS);
        assertEquals(2, photos.size());
        for (var photo : photos) {
            assertEquals(230, photo.image().getWidth(), 3);
            assertEquals(250, photo.image().getHeight(), 3);
        }
    }

    @Test
    void disconnectedFaintPaperOutlineEnclosesTheProductAndItsMargins() {
        BufferedImage image = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(251, 251, 249));
        graphics.fillRect(0, 0, 600, 600);
        graphics.setColor(new Color(246, 246, 243));
        graphics.fillRect(12, 12, 576, 576);
        graphics.setColor(new Color(60, 70, 100));
        graphics.fillRoundRect(170, 120, 260, 360, 30, 30);
        graphics.dispose();
        var scene = new SyntheticScan(1000, 900, 29).noise(3).shadow(0).photo(image, 500, 450, 2.6).render();
        var photos = PhotoSplitter.split(scene.scan(), PhotoSplitter.Options.defaults());
        assertEquals(1, photos.size());
        assertMatches(scene.photos().getFirst(), photos.getFirst());
    }

    @Test
    void faintPaperMarginsAroundAProductAreKept() {
        SyntheticScan.Scene scene = new SyntheticScan(1000, 900, 29).bed(Color.WHITE).noise(1).shadow(2)
                .photo(600, 600, 500, 450, 2.6, Style.PRODUCT, 22).render();
        List<PhotoSplitter.Photo> photos = PhotoSplitter.split(scene.scan(), OPTIONS);
        assertEquals(1, photos.size());
        assertMatches(scene.photos().getFirst(), photos.getFirst());
    }

    @Test
    void touchingWhiteBorderedPrintsOnAGreyBedAreSeparated() {
        SyntheticScan.Scene scene = new SyntheticScan(1275, 1000, 15).bed(new Color(232, 230, 224)).shadow(1)
                .photo(520, 400, 386, 400, 0, Style.GRADIENT, 9)
                .photo(540, 380, 922, 460, -1.6, Style.BLOBS, 9)
                .render();
        List<PhotoSplitter.Photo> photos = PhotoSplitter.split(scene.scan(), OPTIONS);
        assertEquals(2, photos.size());
        for (int i = 0; i < 2; i++) {
            SyntheticScan.Placed truth = scene.photos().get(i);
            BufferedImage image = photos.get(i).image();
            assertEquals(truth.content().getWidth(), image.getWidth(), 16);
            assertEquals(truth.content().getHeight(), image.getHeight(), 6);
            assertEquals(truth.angle(), photos.get(i).angle(), 0.6);
        }
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
    void photosSharingAWholeEdgeAreToldApartByTheStepBetweenThem() {
        SyntheticScan.Scene scene = new SyntheticScan(1300, 1000, 1)
                .photo(500, 380, 350, 400, 1, Style.GRADIENT, 0)
                .photo(500, 580, 850, 500, -2, Style.TEXTURE, 0)
                .render();
        List<PhotoSplitter.Photo> photos = PhotoSplitter.split(scene.scan(), OPTIONS);
        assertEquals(2, photos.size());
        assertMatches(scene.photos().get(0), photos.get(0));
    }

    @Test
    void aSinglePanoramaWithAStrongColourBoundaryStaysWholeByDefault() {
        BufferedImage panorama = new BufferedImage(600, 300, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 300; y++) {
            for (int x = 0; x < 600; x++) {
                panorama.setRGB(x, y, x < 300 ? 0xCC5533 : 0x3355CC);
            }
        }
        SyntheticScan.Scene scene = new SyntheticScan(1000, 700, 43).noise(0).shadow(0)
                .photo(panorama, 450, 350, 0).render();
        List<PhotoSplitter.Photo> photos = PhotoSplitter.split(scene.scan(), OPTIONS);
        assertEquals(1, photos.size());
        assertMatches(scene.photos().getFirst(), photos.getFirst());
    }

    @Test
    void aTypedPageBesideThePhotosIsNotAPhoto() {
        SyntheticScan.Scene scene = new SyntheticScan(1100, 1000, 3)
                .photo(420, 300, 280, 250, 2, Style.GRADIENT, 0)
                .document(420, 560, 800, 500, 1)
                .render();
        List<PhotoSplitter.Photo> photos = PhotoSplitter.split(scene.scan(), OPTIONS);
        assertEquals(1, photos.size());
        assertMatches(scene.photos().get(0), photos.get(0));
    }

    @Test
    void aTypedPageLyingSidewaysIsNotAPhoto() {
        SyntheticScan.Scene scene = new SyntheticScan(1100, 1000, 3)
                .photo(420, 300, 280, 250, 2, Style.GRADIENT, 0)
                .document(420, 560, 800, 580, 91)
                .render();
        List<PhotoSplitter.Photo> photos = PhotoSplitter.split(scene.scan(), OPTIONS);
        assertEquals(1, photos.size());
        assertMatches(scene.photos().get(0), photos.get(0));
    }

    @Test
    void aLineDiagramOnWhitePaperIsAPhoto() {
        BufferedImage diagram = new BufferedImage(560, 420, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = diagram.createGraphics();
        g.setColor(new Color(250, 250, 250));
        g.fillRect(0, 0, 560, 420);
        g.setStroke(new BasicStroke(3));
        g.setColor(new Color(40, 40, 40));
        g.drawRect(22, 22, 516, 376);
        for (int i = 0; i < 6; i++) {
            int x = 70 + i * 72;
            g.setColor(new Color(200, 220, 245));
            g.fillRect(x, 175, 55, 62);
            g.setColor(new Color(30, 50, 100));
            g.drawRect(x, 175, 55, 62);
            if (i < 5) {
                g.drawLine(x + 55, 206, x + 72, 206);
            }
        }
        g.dispose();
        SyntheticScan.Scene scene = new SyntheticScan(1200, 700, 6).bed(new Color(240, 240, 236))
                .photo(diagram, 340, 330, -6)
                .photo(420, 300, 900, 330, 4, Style.TEXTURE, 0)
                .render();
        List<PhotoSplitter.Photo> photos = PhotoSplitter.split(scene.scan(), OPTIONS);
        assertEquals(2, photos.size());
        assertMatches(scene.photos().get(0), photos.get(0));
        assertMatches(scene.photos().get(1), photos.get(1));
    }

    @Test
    void formControlsOnAPrintedPageAreNotPhotos() {
        BufferedImage page = new BufferedImage(2480, 3508, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = page.createGraphics();
        g.scale(2, 2);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1240, 1754);
        bevel(g, 250, 260, 280, 68, new Color(0xACA899), new Color(0x716F64), new Color(0xF1EFE2), Color.WHITE);
        bevel(g, 250, 520, 152, 57, new Color(0xF1EFE2), new Color(0xECE9D8), new Color(0xACA899), new Color(0xECE9D8));
        g.setColor(new Color(0x716F64));
        g.fillRect(250, 576, 152, 1);
        g.fillRect(401, 520, 1, 57);
        g.setColor(Color.BLACK);
        g.setFont(g.getFont().deriveFont(17f));
        g.drawString("Push Me", 286, 556);
        g.dispose();
        assertEquals(0, PhotoSplitter.split(page, OPTIONS).size());
    }

    private static void bevel(Graphics2D g, int x, int y, int w, int h, Color outer, Color inner, Color lit, Color face) {
        g.setColor(face);
        g.fillRect(x, y, w, h);
        g.setColor(lit);
        g.fillRect(x, y + h - 2, w, 2);
        g.fillRect(x + w - 2, y, 2, h);
        g.setColor(outer);
        g.fillRect(x, y, w - 2, 2);
        g.fillRect(x, y, 2, h - 2);
        g.setColor(inner);
        g.fillRect(x + 2, y + 2, w - 4, 1);
        g.fillRect(x + 2, y + 2, 1, h - 4);
    }

    @Test
    void aLargeScanIsSearchedSmallerAndCutAtFullSize() {
        SyntheticScan.Scene scene = new SyntheticScan(2480, 2600, 12).noise(2)
                .photo(1000, 700, 700, 600, 3, Style.BLOBS, 0)
                .photo(900, 1100, 1750, 1600, -4, Style.TEXTURE, 0)
                .render();
        List<PhotoSplitter.Photo> photos = PhotoSplitter.split(scene.scan(), OPTIONS);
        assertEquals(2, photos.size());
        assertMatches(scene.photos().get(0), photos.get(0));
        assertMatches(scene.photos().get(1), photos.get(1));
    }

    @Test
    void grayScansGiveGrayPhotos() {
        SyntheticScan.Scene scene = new SyntheticScan(900, 700, 4)
                .photo(400, 300, 260, 220, 3, Style.BLOBS, 0)
                .render();
        BufferedImage gray = new BufferedImage(900, 700, BufferedImage.TYPE_BYTE_GRAY);
        gray.getGraphics().drawImage(scene.scan(), 0, 0, null);
        List<PhotoSplitter.Photo> photos = PhotoSplitter.split(gray, OPTIONS);
        assertEquals(1, photos.size());
        assertEquals(BufferedImage.TYPE_BYTE_GRAY, photos.getFirst().image().getType());
        assertMatches(scene.photos().get(0), photos.getFirst());
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
