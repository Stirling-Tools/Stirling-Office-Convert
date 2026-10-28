package stirling.software.officeconvert.photosplit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

class PaleFrameSeedTest {

    @Test
    void faintPaperWithADarkInsetKeepsItsWholeBoundaryWithoutGhostCrops() {
        BufferedImage paper = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
        var graphics = paper.createGraphics();
        graphics.setColor(new Color(251, 251, 249));
        graphics.fillRect(0, 0, 600, 600);
        graphics.setColor(new Color(246, 246, 243));
        graphics.fillRect(6, 6, 588, 588);
        graphics.setColor(new Color(60, 70, 100));
        graphics.fillRoundRect(170, 120, 260, 360, 30, 30);
        graphics.dispose();
        var scene = new SyntheticScan(1000, 900, 29).noise(3).shadow(1)
                .photo(paper, 500, 450, 2.6).render();
        var photos = PhotoSplitter.split(scene.scan(), PhotoSplitter.Options.defaults());
        assertEquals(1, photos.size());
        var actual = photos.getFirst();
        assertTrue(actual.image().getWidth() >= 595);
        assertTrue(actual.image().getHeight() >= 595);
        for (var corner : scene.photos().getFirst().corners()) {
            assertTrue(actual.corners().stream().anyMatch(found -> found.distance(corner) <= 3),
                    "The complete paper boundary must be retained");
        }
    }

}
