package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
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
            image.getCOSObject().setItem(COSName.MASK, stencil(doc, 46000, 46000).getCOSObject());
            assertFalse(ImageBudget.affordable(image));
        }
    }

    @Test
    void aMaskThatIsNotAStencilIsIgnored() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject image = gray(doc, 10, 10);
            image.getCOSObject().setItem(COSName.MASK, gray(doc, 46000, 46000).getCOSObject());
            assertTrue(ImageBudget.affordable(image));
        }
    }

    @Test
    void aSoftMaskOverridesALargeStencilMask() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject image = gray(doc, 10, 10);
            image.getCOSObject().setItem(COSName.SMASK, gray(doc, 10, 10).getCOSObject());
            image.getCOSObject().setItem(COSName.MASK, stencil(doc, 46000, 46000).getCOSObject());
            assertTrue(ImageBudget.affordable(image));
        }
    }

    @Test
    void aJpxSoftMaskCountsItsEncodedSize() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject image = gray(doc, 10, 10);
            image.getCOSObject().setItem(COSName.SMASK, jpx(doc, 10, 10, 46000, 46000));
            assertFalse(ImageBudget.affordable(image));
        }
    }

    @Test
    void aJpxImageCountsItsEncodedSizeAgainstItsMask() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            COSStream image = jpx(doc, 10, 10, 64000, 1);
            image.setItem(COSName.SMASK, gray(doc, 10, 64000).getCOSObject());
            assertFalse(ImageBudget.affordable(new PDImageXObject(new PDStream(image), null)));
        }
    }

    @Test
    void aSmallJpxSoftMaskStaysAffordable() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject image = gray(doc, 10, 10);
            image.getCOSObject().setItem(COSName.SMASK, jpx(doc, 10, 10, 20, 20));
            assertTrue(ImageBudget.affordable(image));
        }
    }

    private static PDImageXObject stencil(PDDocument doc, int width, int height) throws IOException {
        PDImageXObject image = new PDImageXObject(doc);
        image.setWidth(width);
        image.setHeight(height);
        image.setBitsPerComponent(1);
        image.setStencil(true);
        return image;
    }

    private static COSStream jpx(PDDocument doc, int width, int height, int encodedWidth, int encodedHeight)
            throws IOException {
        COSStream stream = doc.getDocument().createCOSStream();
        stream.setItem(COSName.TYPE, COSName.XOBJECT);
        stream.setItem(COSName.SUBTYPE, COSName.IMAGE);
        stream.setInt(COSName.WIDTH, width);
        stream.setInt(COSName.HEIGHT, height);
        stream.setInt(COSName.BITS_PER_COMPONENT, 8);
        stream.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
        stream.setItem(COSName.FILTER, COSName.JPX_DECODE);
        ByteBuffer siz = ByteBuffer.allocate(42);
        siz.putShort((short) 0xFF4F).putShort((short) 0xFF51).putShort((short) 41).putShort((short) 0);
        siz.putInt(encodedWidth).putInt(encodedHeight).putInt(0).putInt(0);
        siz.putInt(encodedWidth).putInt(encodedHeight).putInt(0).putInt(0).putShort((short) 1);
        try (OutputStream out = stream.createRawOutputStream()) {
            out.write(siz.array());
        }
        return stream;
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
