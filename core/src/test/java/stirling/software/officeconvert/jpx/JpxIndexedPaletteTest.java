package stirling.software.officeconvert.jpx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.ImageBudget;

class JpxIndexedPaletteTest {

    private static final int BLUE = 0x2050E0;

    @Test
    void anIndexedSpaceInThePdfOverridesThePaletteInTheJp2Data() throws IOException {
        JpxImageIO.install();
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject image = indexedJpx(doc, 6, 4);
            assertNotEquals(BLUE, image.getImage().getRGB(2, 1) & 0xFFFFFF);
            PDImage fitted = ImageBudget.fitted(image);
            BufferedImage shown = fitted.getImage();
            assertEquals(6, shown.getWidth());
            assertEquals(4, shown.getHeight());
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 6; x++) {
                    assertEquals(BLUE, shown.getRGB(x, y) & 0xFFFFFF, "pixel " + x + "," + y);
                }
            }
            assertEquals(BLUE, fitted.getImage(null, 2).getRGB(1, 1) & 0xFFFFFF);
        }
    }

    @Test
    void aJpxImageWithoutItsOwnPaletteIsLeftToTheDefaultDecoder() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            COSStream stream = doc.getDocument().createCOSStream();
            try (OutputStream out = stream.createRawOutputStream()) {
                out.write(jp2(SyntheticCodestream.samples(6, 4, 1, 8, 0, true, false, false, false, false), false));
            }
            describe(stream, 6, 4);
            PDImageXObject image = new PDImageXObject(new PDStream(stream), new PDResources());
            assertEquals(image, ImageBudget.fitted(image));
        }
    }

    private static PDImageXObject indexedJpx(PDDocument doc, int width, int height) throws IOException {
        COSStream stream = doc.getDocument().createCOSStream();
        try (OutputStream out = stream.createRawOutputStream()) {
            out.write(jp2(SyntheticCodestream.samples(width, height, 1, 8, 0, true, false, false, false, false), true));
        }
        describe(stream, width, height);
        return new PDImageXObject(new PDStream(stream), new PDResources());
    }

    private static void describe(COSStream stream, int width, int height) {
        stream.setItem(COSName.TYPE, COSName.XOBJECT);
        stream.setItem(COSName.SUBTYPE, COSName.IMAGE);
        stream.setItem(COSName.FILTER, COSName.JPX_DECODE);
        stream.setInt(COSName.WIDTH, width);
        stream.setInt(COSName.HEIGHT, height);
        stream.setInt(COSName.BITS_PER_COMPONENT, 8);
        byte[] lookup = new byte[256 * 3];
        for (int i = 120; i < 136; i++) {
            lookup[i * 3] = (byte) (BLUE >> 16);
            lookup[i * 3 + 1] = (byte) (BLUE >> 8);
            lookup[i * 3 + 2] = (byte) BLUE;
        }
        COSArray indexed = new COSArray();
        indexed.add(COSName.INDEXED);
        indexed.add(COSName.DEVICERGB);
        indexed.add(COSInteger.get(255));
        indexed.add(new COSString(lookup));
        stream.setItem(COSName.COLORSPACE, indexed);
    }

    private static byte[] jp2(byte[] codestream, boolean palette) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(box("ihdr", new byte[] {0, 0, 0, 4, 0, 0, 0, 6, 0, 1, 7, 7, 0, 0}));
        header.write(box("colr", new byte[] {1, 0, 0, 0, 0, 0, 16}));
        if (palette) {
            ByteArrayOutputStream pclr = new ByteArrayOutputStream();
            pclr.write(new byte[] {1, 0, 3, 7, 7, 7});
            pclr.write(new byte[256 * 3]);
            header.write(box("pclr", pclr.toByteArray()));
            header.write(box("cmap", new byte[] {0, 0, 1, 0, 0, 0, 1, 1, 0, 0, 1, 2}));
        }
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(box("jP  ", new byte[] {0x0D, 0x0A, (byte) 0x87, 0x0A}));
        file.write(box("ftyp", new byte[] {'j', 'p', '2', ' ', 0, 0, 0, 0, 'j', 'p', '2', ' '}));
        file.write(box("jp2h", header.toByteArray()));
        file.write(box("jp2c", codestream));
        return file.toByteArray();
    }

    private static byte[] box(String type, byte[] body) {
        int length = body.length + 8;
        byte[] out = new byte[length];
        out[0] = (byte) (length >>> 24);
        out[1] = (byte) (length >>> 16);
        out[2] = (byte) (length >>> 8);
        out[3] = (byte) length;
        for (int i = 0; i < 4; i++) {
            out[4 + i] = (byte) type.charAt(i);
        }
        System.arraycopy(body, 0, out, 8, body.length);
        return out;
    }
}
