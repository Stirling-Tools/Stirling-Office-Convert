package stirling.software.officeconvert.photosplit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

class FalseFragmentsTest {

    @Test
    void disconnectedNoisyPaperFragmentsDoNotBecomeOverlappingPhotoCrops() {
        assertInsetOnly(3, 22, 0);
    }

    @Test
    void anUnsupportedBrokenShadowDoesNotOverlapTheSupportedPhoto() {
        assertInsetOnly(5, 12, 1);
    }

    @Test
    void aCoherentSparsePaleFrameIsRetainedAroundItsContent() {
        int size = 500;
        byte[] mask = new byte[size * size];
        for (int y = 50; y < 450; y++) {
            for (int x = 50; x < 450; x++) {
                if (x < 53 || x >= 447 || y < 53 || y >= 447) {
                    mask[y * size + x] = 1;
                }
            }
        }
        Region frame = Region.label(mask, size, size).getFirst();
        var candidate = FalseFragments.candidate(frame.rect(), frame, new byte[mask.length], size,
                PhotoSplitter.Options.defaults(), false);
        assertFalse(candidate.fragment());
        var content = new FalseFragments.Candidate(new RotatedRect(250, 250, 180, 200, -17), true, false);
        assertEquals(List.of(candidate.rect(), content.rect()), FalseFragments.reject(List.of(candidate, content)));
    }

    @Test
    void anUnresolvedSparseCandidateWithoutASupportedPhotoIsRetained() {
        var candidate = new FalseFragments.Candidate(new RotatedRect(250, 250, 400, 200, 35), false, true);
        assertEquals(List.of(candidate.rect()), FalseFragments.reject(List.of(candidate)));
    }

    private static void assertInsetOnly(int noise, int padding, int shadow) {
        BufferedImage paper = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
        var graphics = paper.createGraphics();
        graphics.setColor(new Color(251, 251, 249));
        graphics.fillRect(0, 0, 600, 600);
        graphics.setColor(new Color(246, 246, 243));
        graphics.fillRect(padding, padding, 600 - 2 * padding, 600 - 2 * padding);
        graphics.setColor(new Color(60, 70, 100));
        graphics.fillRoundRect(170, 120, 260, 360, 30, 30);
        graphics.dispose();
        var scene = new SyntheticScan(1000, 900, 29).noise(noise).shadow(shadow)
                .photo(paper, 500, 450, 2.6).render();
        var photos = PhotoSplitter.split(scene.scan(), PhotoSplitter.Options.defaults());
        assertEquals(1, photos.size());
        var actual = photos.getFirst();
        assertEquals(260, actual.image().getWidth(), 3);
        assertEquals(360, actual.image().getHeight(), 3);
        for (var expected : new RotatedRect(500, 450, 260, 360, 2.6).corners()) {
            org.junit.jupiter.api.Assertions.assertTrue(actual.corners().stream()
                    .anyMatch(found -> found.distance(expected) <= 3));
        }
    }
}
