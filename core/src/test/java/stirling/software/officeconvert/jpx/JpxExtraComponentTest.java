package stirling.software.officeconvert.jpx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Base64;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.ImageBudget;

class JpxExtraComponentTest {

    private static final String TWO_COMPONENTS = "/0//UQAsAAAAAAAIAAAABAAAAAAAAAAAAAAACAAAAAQAAAAAAAAAAAACBwEBBwEB/1IADAAAAAEAAAQEAAH/XAAEQED"
            + "/ZAAlAAFDcmVhdGVkIGJ5IE9wZW5KUEVHIHZlcnNpb24gMi41LjT/kAAKAAAAAAA9AAH/k9+AuBFmkPNGGh5r5C8UFX/tuQsTZ22Xl5lK"
            + "34CQEozVB5RIvNX0L286pPpEuCJ//9k=";

    @Test
    void aTwoComponentColourSpaceGetsBothComponentsOfARawCodestream() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDImage image = ImageBudget.fitted(image(doc, deviceN()));

            var raster = image.getRawRaster();

            assertEquals(2, raster.getNumBands());
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 8; x++) {
                    assertEquals(x * 30, raster.getSample(x, y, 0), "first at " + x + "," + y);
                    assertEquals(y * 60, raster.getSample(x, y, 1), "second at " + x + "," + y);
                }
            }
            assertEquals(0xD2 << 16 | 0xB4 << 8, image.getImage().getRGB(7, 3) & 0xFFFFFF);
        }
    }

    @Test
    void aOneComponentColourSpaceStillReadsOnlyTheFirstComponent() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject plain = image(doc, COSName.DEVICEGRAY);

            assertEquals(plain, ImageBudget.fitted(plain));
            BufferedImage shown = plain.getImage();
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 8; x++) {
                    assertEquals(x * 30, shown.getRGB(x, y) & 0xFF, "grey at " + x + "," + y);
                }
            }
        }
    }

    private static PDImageXObject image(PDDocument doc, COSBase colourSpace) throws IOException {
        JpxImageIO.install();
        COSStream stream = doc.getDocument().createCOSStream();
        try (OutputStream out = stream.createRawOutputStream()) {
            out.write(Base64.getDecoder().decode(TWO_COMPONENTS));
        }
        stream.setItem(COSName.TYPE, COSName.XOBJECT);
        stream.setItem(COSName.SUBTYPE, COSName.IMAGE);
        stream.setItem(COSName.FILTER, COSName.JPX_DECODE);
        stream.setInt(COSName.WIDTH, 8);
        stream.setInt(COSName.HEIGHT, 4);
        stream.setInt(COSName.BITS_PER_COMPONENT, 8);
        stream.setItem(COSName.COLORSPACE, colourSpace);
        return new PDImageXObject(new PDStream(stream), new PDResources());
    }

    private static COSArray deviceN() throws IOException {
        COSArray names = new COSArray();
        names.add(COSName.getPDFName("A"));
        names.add(COSName.getPDFName("B"));
        COSStream tint = new COSStream();
        try (OutputStream out = tint.createOutputStream()) {
            out.write("{ 0 }".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        tint.setInt(COSName.FUNCTION_TYPE, 4);
        COSArray domain = new COSArray();
        domain.setFloatArray(new float[] {0, 1, 0, 1});
        tint.setItem(COSName.DOMAIN, domain);
        COSArray range = new COSArray();
        range.setFloatArray(new float[] {0, 1, 0, 1, 0, 1});
        tint.setItem(COSName.RANGE, range);
        COSArray space = new COSArray();
        space.add(COSName.DEVICEN);
        space.add(names);
        space.add(COSName.DEVICERGB);
        space.add(tint);
        space.add(new COSDictionary());
        return space;
    }
}
