package stirling.software.officeconvert.photosplit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.photosplit.SyntheticScan.Style;

class OverlapRefinementTest {

    @Test
    void staggeredAbuttingPrintsRetainTheirCornersWithoutExcessOverlap() {
        assertAbutting(560);
    }

    @Test
    void widerAbuttingPrintsRetainBothPrintsAndASteeplyRotatedNeighbor() {
        assertAbutting(700);
    }

    @Test
    void partiallyOccludedEdgesStillRetainBothRotatedPrints() {
        SyntheticScan.Scene scene = new SyntheticScan(2400, 2000, 2)
                .photo(1200, 800, 700, 600, 1, Style.GRADIENT, 0)
                .photo(1000, 1400, 1800, 1100, -3, Style.TEXTURE, 0)
                .photo(260, 160, 350, 1700, -42, Style.BLOBS, 0).render();
        assertPrints(scene, 10);
    }

    @Test
    void unresolvedPhysicalOverlapDoesNotDeleteOrArbitrarilyTrimPrints() {
        BufferedImage image = new BufferedImage(1000, 800, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.GRAY);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        List<RotatedRect> original = List.of(new RotatedRect(350, 300, 500, 400, -4),
                new RotatedRect(650, 450, 500, 400, 3));
        assertTrue(overlap(original.getFirst().corners(), original.getLast().corners()) > 0.05);
        assertEquals(original, OverlapFit.refine(Source.of(image), original, PhotoSplitter.Options.defaults()));
    }

    @Test
    void separateRotatedBoundsAreUnchangedWithoutAnOverlapConflict() {
        BufferedImage image = new BufferedImage(1000, 800, BufferedImage.TYPE_INT_RGB);
        List<RotatedRect> original = List.of(new RotatedRect(200, 200, 200, 150, -42),
                new RotatedRect(700, 500, 250, 200, 17));
        assertEquals(original, OverlapFit.refine(Source.of(image), original, PhotoSplitter.Options.defaults()));
    }

    @Test
    void interruptionArrivingDuringAWideEdgeSearchIsObserved() {
        Picture scan = new Picture() {
            private int reads;

            @Override
            public int width() {
                return 1200;
            }

            @Override
            public int height() {
                return 900;
            }

            @Override
            public boolean gray() {
                return true;
            }

            @Override
            public int rgb(int x, int y) {
                if (++reads == 100) {
                    Thread.currentThread().interrupt();
                }
                return 0x808080;
            }
        };
        try {
            assertThrows(CancellationException.class,
                    () -> EdgeFit.refine(scan, new RotatedRect(600, 450, 700, 500, 0), 128, 4, 2));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void explicitEdgeEvidenceDistinguishesAValidatedPrintFromAnUnresolvedBoundsGuess() {
        BufferedImage image = new BufferedImage(600, 500, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(246, 246, 243));
        graphics.fillRect(0, 0, 600, 500);
        graphics.dispose();
        RotatedRect initial = new RotatedRect(300, 250, 308, 228, 0);
        var unresolved = EdgeFit.checked(Source.of(image), initial);
        assertFalse(unresolved.supported());
        assertEquals(initial, unresolved.rect());
        graphics = image.createGraphics();
        graphics.setColor(new Color(50, 100, 170));
        graphics.fillRect(150, 140, 300, 220);
        graphics.dispose();
        var validated = EdgeFit.checked(Source.of(image), initial);
        assertTrue(validated.supported());
        assertEquals(300, validated.rect().width(), 1);
        assertEquals(220, validated.rect().height(), 1);
    }

    private static void assertAbutting(int width) {
        int height = width * 5 / 7;
        int centerY = height / 2 + 70;
        SyntheticScan.Scene scene = new SyntheticScan(width * 2 + 200, height * 3 + 150, 41)
                .photo(width, height, width / 2 + 100, centerY, 0, Style.GRADIENT, 0)
                .photo(width, height, width * 3 / 2 + 100, centerY + height * 3 / 5, 0, Style.TEXTURE, 0)
                .photo(260, 160, 250, height * 2 + 150, -42, Style.BLOBS, 0).render();
        assertPrints(scene, 2);
    }

    private static void assertPrints(SyntheticScan.Scene scene, double tolerance) {
        List<PhotoSplitter.Photo> photos = PhotoSplitter.split(scene.scan(), PhotoSplitter.Options.defaults());
        assertEquals(scene.photos().size(), photos.size());
        for (int i = 0; i < photos.size(); i++) {
            for (int j = i + 1; j < photos.size(); j++) {
                double fraction = overlap(photos.get(i).corners(), photos.get(j).corners());
                assertTrue(fraction <= 0.05, "Separate prints overlap by " + fraction);
            }
        }
        List<PhotoSplitter.Photo> unmatched = new ArrayList<>(photos);
        for (SyntheticScan.Placed print : scene.photos()) {
            PhotoSplitter.Photo best = null;
            double error = Double.POSITIVE_INFINITY;
            for (PhotoSplitter.Photo photo : unmatched) {
                double candidate = 0;
                for (Point2D.Double expected : print.corners()) {
                    double nearest = Double.POSITIVE_INFINITY;
                    for (Point2D.Double actual : photo.corners()) {
                        nearest = Math.min(nearest, expected.distance(actual));
                    }
                    candidate = Math.max(candidate, nearest);
                }
                if (candidate < error) {
                    best = photo;
                    error = candidate;
                }
            }
            assertTrue(error < tolerance, "Print corners differ by " + error + " pixels");
            unmatched.remove(best);
        }
    }

    private static Area polygon(List<Point2D.Double> corners) {
        Path2D.Double path = new Path2D.Double();
        path.moveTo(corners.getFirst().x, corners.getFirst().y);
        for (int i = 1; i < corners.size(); i++) {
            path.lineTo(corners.get(i).x, corners.get(i).y);
        }
        path.closePath();
        return new Area(path);
    }

    private static double overlap(List<Point2D.Double> first, List<Point2D.Double> second) {
        Area intersection = polygon(first);
        Area other = polygon(second);
        double smaller = Math.min(area(intersection), area(other));
        intersection.intersect(other);
        return area(intersection) / smaller;
    }

    private static double area(Area polygon) {
        double[] point = new double[6];
        double firstX = 0, firstY = 0, previousX = 0, previousY = 0, sum = 0;
        for (PathIterator iterator = polygon.getPathIterator(null); !iterator.isDone(); iterator.next()) {
            int segment = iterator.currentSegment(point);
            if (segment == PathIterator.SEG_MOVETO) {
                firstX = previousX = point[0];
                firstY = previousY = point[1];
            } else if (segment == PathIterator.SEG_LINETO) {
                sum += previousX * point[1] - previousY * point[0];
                previousX = point[0];
                previousY = point[1];
            } else if (segment == PathIterator.SEG_CLOSE) {
                sum += previousX * firstY - previousY * firstX;
            }
        }
        return Math.abs(sum) / 2;
    }
}
