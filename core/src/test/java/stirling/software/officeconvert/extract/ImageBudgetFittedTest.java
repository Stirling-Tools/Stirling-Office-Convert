package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

class ImageBudgetFittedTest {

    private static PDImageXObject jpeg(PDDocument doc, int width, int height) throws IOException {
        COSStream s = doc.getDocument().createCOSStream();
        s.setItem(COSName.SUBTYPE, COSName.IMAGE);
        s.setInt(COSName.WIDTH, width);
        s.setInt(COSName.HEIGHT, height);
        s.setInt(COSName.BITS_PER_COMPONENT, 8);
        s.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
        s.setItem(COSName.FILTER, COSName.DCT_DECODE);
        try (OutputStream out = s.createRawOutputStream()) {
            out.write(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xC0, 0, 17, 8, (byte) (height >> 8),
                (byte) height, (byte) (width >> 8), (byte) width, 3});
            for (int i = 0; i < 3; i++) {
                out.write(new byte[] {(byte) (i + 1), 0x11, 0});
            }
            out.write(new byte[] {(byte) 0xFF, (byte) 0xD9});
        }
        return new PDImageXObject(new PDStream(s), null);
    }

    private static PDImageXObject flate(PDDocument doc, int width, int height, PDColorSpace space) throws IOException {
        PDImageXObject image = new PDImageXObject(doc);
        image.setWidth(width);
        image.setHeight(height);
        image.setBitsPerComponent(8);
        image.setColorSpace(space);
        image.getCOSObject().setItem(COSName.FILTER, COSName.FLATE_DECODE);
        return image;
    }

    @Test
    void aLargeScannedJpegIsDrawnAtACoarserStep() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject scan = jpeg(doc, 7016, 9921);
            assertTrue(ImageBudget.step(scan) > 1);
            assertNotNull(ImageBudget.fitted(scan));
        }
    }

    @Test
    void aFlateImageIsChargedForItsWholeDecodedStream() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject image = flate(doc, 4_000, 4_000, PDDeviceGray.INSTANCE);
            long pixels = 4_000L * 4_000;
            assertEquals(pixels * 5 + pixels, ImageBudget.decodedBytes(image));
            assertEquals(pixels / 4 * 5 + pixels, ImageBudget.decodedBytes(image, 2));
        }
    }

    @Test
    void aSeparationImageIsChargedForItsAlternateRaster() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            COSDictionary tint = new COSDictionary();
            tint.setInt(COSName.FUNCTION_TYPE, 2);
            tint.setItem(COSName.DOMAIN, numbers(0, 1));
            tint.setItem(COSName.C0, numbers(0, 0, 0, 0));
            tint.setItem(COSName.C1, numbers(1, 1, 1, 1));
            tint.setInt(COSName.N, 1);
            COSArray separation = new COSArray();
            separation.add(COSName.SEPARATION);
            separation.add(COSName.getPDFName("Spot"));
            separation.add(COSName.DEVICECMYK);
            separation.add(tint);
            PDImageXObject image = new PDImageXObject(doc);
            image.setWidth(100);
            image.setHeight(100);
            image.setBitsPerComponent(8);
            image.setColorSpace(PDColorSpace.create(separation));
            assertEquals(100L * 100 * (1 + 4 + 4), ImageBudget.decodedBytes(image));
        }
    }

    @Test
    void aSoftMaskIsChargedTogetherWithTheImageItMasks() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject image = flate(doc, 6_000, 6_000, PDDeviceRGB.INSTANCE);
            PDImageXObject mask = flate(doc, 6_000, 6_000, PDDeviceGray.INSTANCE);
            image.getCOSObject().setItem(COSName.SMASK, mask.getCOSObject());
            int step = ImageBudget.step(image);
            assertEquals(3, step);
            assertTrue(ImageBudget.decodedBytes(image, step) + ImageBudget.maskedRenderBytes(image, step)
                    <= ImageBudget.MAX_DECODED_BYTES);
            assertTrue(ImageBudget.decodedBytes(image, 2) + ImageBudget.maskedRenderBytes(image, 2)
                    > ImageBudget.MAX_DECODED_BYTES);
        }
    }

    @Test
    void aPictureDrawnSubsampledIsChargedAtItsStep() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            COSStream image = flate(doc, 16_000, 16_000, PDDeviceGray.INSTANCE).getCOSObject();
            long charged = PdfFootprint.decoded(image);
            assertTrue(charged >= 16_000L * 16_000, "the decoded stream is held whole, got " + charged);
            assertTrue(charged <= ImageBudget.MAX_DECODED_BYTES);
        }
    }

    private static COSArray numbers(int... values) {
        COSArray a = new COSArray();
        for (int v : values) {
            a.add(COSInteger.get(v));
        }
        return a;
    }
}
