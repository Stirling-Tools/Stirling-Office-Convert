package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

class ImageBudgetUnpackedTest {

    private static PDImageXObject image(PDDocument doc, int width, int height, int bits, PDColorSpace space)
            throws IOException {
        PDImageXObject image = new PDImageXObject(doc);
        image.setWidth(width);
        image.setHeight(height);
        image.setBitsPerComponent(bits);
        image.setColorSpace(space);
        return image;
    }

    @Test
    void aOneBitImageIsChargedForTheRasterItExpandsTo() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject image = image(doc, 40_000, 40_000, 1, PDDeviceGray.INSTANCE);
            assertTrue(40_000L * 40_000 / 8 <= ImageBudget.MAX_DECODED_BYTES);
            assertEquals(40_000L * 40_000 * 5, ImageBudget.decodedBytes(image));
            assertFalse(ImageBudget.affordable(image));
        }
    }

    @Test
    void anEightBitGreyImageIsChargedForItsRgbRaster() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            assertFalse(ImageBudget.affordable(image(doc, 9_000, 9_000, 8, PDDeviceGray.INSTANCE)));
            assertTrue(ImageBudget.affordable(image(doc, 1_000, 1_000, 8, PDDeviceGray.INSTANCE)));
        }
    }

    @Test
    void aColourKeyMaskCountsItsKeyAndArgbRasters() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject small = image(doc, 10, 10, 8, PDDeviceRGB.INSTANCE);
            small.getCOSObject().setItem(COSName.MASK, key(6));
            assertEquals(100 * (3 + 9), ImageBudget.maskedRenderBytes(small));
            assertTrue(ImageBudget.affordable(small));

            PDImageXObject large = image(doc, 6_000, 6_000, 8, PDDeviceGray.INSTANCE);
            assertTrue(ImageBudget.affordable(image(doc, 6_000, 6_000, 8, PDDeviceGray.INSTANCE)));
            large.getCOSObject().setItem(COSName.MASK, key(2));
            assertFalse(ImageBudget.affordable(large));
        }
    }

    @Test
    void anUnreadableHeaderIsRefused() {
        PDImage image = (PDImage) Proxy.newProxyInstance(PDImage.class.getClassLoader(), new Class<?>[] {PDImage.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getSuffix" -> "jpg";
                    case "createInputStream" -> throw new IOException("unreadable");
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        assertEquals(Long.MAX_VALUE, ImageBudget.codedBytes(image));
    }

    private static COSArray key(int n) {
        COSArray a = new COSArray();
        for (int i = 0; i < n; i++) {
            a.add(COSInteger.get(i % 2 == 0 ? 0 : 10));
        }
        return a;
    }
}
