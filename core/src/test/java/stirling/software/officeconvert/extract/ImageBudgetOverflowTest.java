package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

class ImageBudgetOverflowTest {

    @Test
    void hugeRasterDimensionsCannotWrapTheDecodedByteCount() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject image = new PDImageXObject(doc);
            image.setWidth(Integer.MAX_VALUE);
            image.setHeight(Integer.MAX_VALUE);
            image.setBitsPerComponent(8);
            image.setColorSpace(PDDeviceRGB.INSTANCE);
            assertTrue(ImageBudget.decodedBytes(image) > ImageBudget.MAX_DECODED_BYTES);
            assertFalse(ImageBudget.affordable(image));
        }
    }

    @Test
    void invalidRasterDimensionsAreRefusedBeforeDecoding() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject image = new PDImageXObject(doc);
            image.setWidth(-1);
            image.setHeight(10);
            image.setBitsPerComponent(8);
            image.setColorSpace(PDDeviceRGB.INSTANCE);
            assertFalse(ImageBudget.affordable(image));
        }
    }

    @Test
    void jpxDimensionsAndComponentsCannotWrapTheSampleCount() {
        byte[] header = jpx(0xFFFFFFFF, 0xFFFFFFFF, 65535);
        assertEquals(Long.MAX_VALUE, ImageBudget.jpxBytes(header));
        assertEquals(300, ImageBudget.jpxBytes(jpx(10, 10, 3)));
    }

    private static byte[] jpx(int width, int height, int components) {
        ByteBuffer header = ByteBuffer.allocate(42);
        header.putInt(0xFF4FFF51).putShort((short) 41).putShort((short) 0);
        header.putInt(width).putInt(height).putInt(0).putInt(0);
        header.putInt(width).putInt(height).putInt(0).putInt(0);
        header.putShort((short) components);
        return header.array();
    }
}
