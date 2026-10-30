package stirling.software.officeconvert.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.OutputStream;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.Pictures;
import stirling.software.officeconvert.extract.PageGraphics.ImageDraw;

class MediaEncoderJpegTest {

    @Test
    void aLargeJpegGoesThroughWithoutBeingDecoded() throws IOException {
        int width = 7016;
        int height = 9921;
        try (PDDocument doc = new PDDocument()) {
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
            PDImageXObject scan = new PDImageXObject(new PDStream(s), null);
            ImageDraw draw = new ImageDraw(0, 0, 595, 842, 0, 0, 595, 842, scan, s, 0, false, false, false, -1, 1f);
            MediaEncoder.Encoded encoded = new MediaEncoder(doc, 150f, Pictures.LOSSLESS).image(draw);
            assertNotNull(encoded);
            assertEquals("jpeg", encoded.ext());
            assertEquals(width, encoded.width());
        }
    }
}
