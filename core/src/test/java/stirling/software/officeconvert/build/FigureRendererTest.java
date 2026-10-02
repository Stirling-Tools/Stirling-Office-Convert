package stirling.software.officeconvert.build;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.GreyGroupPages;

class FigureRendererTest {

    @Test
    void aDarkGreyInAnIsolatedGreyGroupKeepsItsTone() throws Exception {
        try (PDDocument doc = GreyGroupPages.darkGreyInAnIsolatedGroup(COSName.DEVICEGRAY)) {
            int rgb = new FigureRenderer(doc, true).renderImage(0).getRGB(100, 100);
            assertEquals(GreyGroupPages.GREY, rgb & 0xFF, 1, Integer.toHexString(rgb));
        }
    }
}
