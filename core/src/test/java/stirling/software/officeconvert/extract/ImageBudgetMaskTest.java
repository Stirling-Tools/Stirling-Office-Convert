package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

class ImageBudgetMaskTest {

    private static PDImageXObject gray(PDDocument doc, int width, int height) throws IOException {
        PDImageXObject image = new PDImageXObject(doc);
        image.setWidth(width);
        image.setHeight(height);
        image.setBitsPerComponent(8);
        image.setColorSpace(PDDeviceGray.INSTANCE);
        return image;
    }

    @Test
    void softMaskDimensionsCountTowardTheBudget() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject image = gray(doc, 10, 10);
            image.getCOSObject().setItem(COSName.SMASK, gray(doc, 46000, 46000).getCOSObject());
            assertTrue(ImageBudget.decodedBytes(image) <= ImageBudget.MAX_DECODED_BYTES);
            assertFalse(ImageBudget.affordable(image));
        }
    }

    @Test
    void maskDimensionsCountTowardTheBudget() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject image = gray(doc, 10, 10);
            image.getCOSObject().setItem(COSName.MASK, gray(doc, 46000, 46000).getCOSObject());
            assertFalse(ImageBudget.affordable(image));
        }
    }

    @Test
    void anImageWithinTheBudgetStaysAffordable() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject image = gray(doc, 10, 10);
            image.getCOSObject().setItem(COSName.SMASK, gray(doc, 10, 10).getCOSObject());
            assertTrue(ImageBudget.affordable(image));
        }
    }

    @Test
    void aLargeStencilCountsFourBytesPerPixel() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject image = new PDImageXObject(doc);
            image.setWidth(46000);
            image.setHeight(46000);
            image.setBitsPerComponent(1);
            image.getCOSObject().setBoolean(COSName.IMAGE_MASK, true);
            assertFalse(ImageBudget.affordable(image));
        }
    }
}
