package stirling.software.officeconvert.slides;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.geom.AffineTransform;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.GreyGroupPages;
import stirling.software.officeconvert.layout.Box;

class ArtRendererTest {

    @Test
    void aDarkGreyInAnIsolatedGreyGroupKeepsItsTone() throws Exception {
        try (PDDocument doc = GreyGroupPages.darkGreyInAnIsolatedGroup(COSName.DEVICEGRAY)) {
            AffineTransform toDisplay = new AffineTransform(1, 0, 0, -1, 0, 200);
            int rgb = new ArtRenderer(doc).render(doc.getPage(0), 0, toDisplay, new Box(0, 0, 200, 200), 1f, o -> true)
                    .getRGB(100, 100);
            assertEquals(GreyGroupPages.GREY, rgb & 0xFF, 1, Integer.toHexString(rgb));
        }
    }
}
