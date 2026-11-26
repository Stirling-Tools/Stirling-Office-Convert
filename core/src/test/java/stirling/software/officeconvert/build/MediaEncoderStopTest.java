package stirling.software.officeconvert.build;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.List;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.Pictures;
import stirling.software.officeconvert.extract.PageGraphics.ImageDraw;
import stirling.software.officeconvert.extract.Warnings;

class MediaEncoderStopTest {

    private static ImageDraw broken(PDDocument doc) throws IOException {
        COSStream s = doc.getDocument().createCOSStream();
        s.setItem(COSName.SUBTYPE, COSName.IMAGE);
        s.setInt(COSName.WIDTH, 10);
        s.setInt(COSName.HEIGHT, 10);
        s.setInt(COSName.BITS_PER_COMPONENT, 8);
        s.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
        s.setItem(COSName.FILTER, COSName.DCT_DECODE);
        s.setItem(COSName.DECODE, COSArray.ofCOSIntegers(List.of(1, 0)));
        try (OutputStream out = s.createRawOutputStream()) {
            out.write(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xC0, 0, 11, 8, 0, 10, 0, 10, 1, 1, 0x11, 0,
                    (byte) 0xFF, (byte) 0xD9});
        }
        PDImageXObject image = new PDImageXObject(new PDStream(s), null);
        return new ImageDraw(0, 0, 100, 100, 0, 0, 100, 100, image, s, 0, false, false, false, -1, 1f);
    }

    @Test
    void aPictureThatCannotBeDecodedIsReportedAsLeftOut() throws IOException {
        try (PDDocument doc = new PDDocument(); Warnings.Sink sink = Warnings.open()) {
            assertNull(new MediaEncoder(doc, 150f, Pictures.COMPACT).image(broken(doc)));
            assertTrue(sink.partial());
            assertTrue(sink.messages().stream().anyMatch(m -> m.startsWith("Image left out")), sink.messages()::toString);
        }
    }

    @Test
    void anInterruptedDecodeStopsTheConversionInsteadOfDroppingThePicture() throws IOException {
        try (PDDocument doc = new PDDocument(); Warnings.Sink sink = Warnings.open()) {
            ImageDraw draw = broken(doc);
            MediaEncoder encoder = new MediaEncoder(doc, 150f, Pictures.COMPACT);
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, () -> encoder.image(draw));
            } finally {
                Thread.interrupted();
            }
            assertFalse(sink.partial());
        }
    }
}
