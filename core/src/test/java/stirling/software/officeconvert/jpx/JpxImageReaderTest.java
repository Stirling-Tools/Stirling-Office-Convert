package stirling.software.officeconvert.jpx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

class JpxImageReaderTest {

    static final class OtherSpi extends ImageReaderSpi {

        OtherSpi() {
            super("Other", "1", new String[] {"jpeg2000"}, null, null, JpxImageReader.class.getName(),
                    new Class<?>[] {ImageInputStream.class}, null, false, null, null, null, null, false, null, null,
                    null, null);
        }

        @Override
        public boolean canDecodeInput(Object source) {
            return false;
        }

        @Override
        public ImageReader createReaderInstance(Object extension) {
            return new JpxImageReader(this);
        }

        @Override
        public String getDescription(Locale locale) {
            return "Other";
        }
    }

    private static ImageReader first() {
        return ImageIO.getImageReadersByFormatName("jpeg2000").next();
    }

    private static BufferedImage read(byte[] data, ImageReadParam param) throws IOException {
        ImageReader reader = first();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            reader.setInput(in);
            return reader.read(0, param);
        } finally {
            reader.dispose();
        }
    }

    @Test
    void ourReaderComesFirstUnlessThePropertyAsksOtherwise() {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        OtherSpi other = new OtherSpi();
        registry.registerServiceProvider(other, ImageReaderSpi.class);
        try {
            JpxImageIO.install();
            assertInstanceOf(JpxImageReaderSpi.class, first().getOriginatingProvider());
            System.setProperty(JpxImageIO.READER_PROPERTY, "imageio");
            JpxImageIO.install();
            assertSame(other, first().getOriginatingProvider());
        } finally {
            System.clearProperty(JpxImageIO.READER_PROPERTY);
            registry.deregisterServiceProvider(other, ImageReaderSpi.class);
            JpxImageIO.install();
        }
        assertInstanceOf(JpxImageReaderSpi.class, first().getOriginatingProvider());
    }

    @Test
    void imageIoReadsJp2Files() throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(JpxSamples.resource("rgb.jp2")));
        assertNotNull(img);
        assertEquals(JpxSamples.WIDTH, img.getWidth());
        assertEquals(JpxSamples.value(9, 8, 1), img.getRaster().getSample(9, 8, 1));
    }

    @Test
    void subsamplingDecodesALowerResolution() throws IOException {
        byte[] data = JpxSamples.resource("rgb-lossless.j2k");
        BufferedImage half = JpxDecoder.decode(data, JpxOptions.defaults().withReduce(1)).toBufferedImage();
        ImageReadParam p = new ImageReadParam();
        p.setSourceSubsampling(2, 2, 0, 0);
        BufferedImage two = read(data, p);
        assertEquals(31, two.getWidth());
        assertEquals(24, two.getHeight());
        assertEquals(half.getRGB(10, 10), two.getRGB(10, 10));
        p.setSourceSubsampling(3, 3, 0, 0);
        BufferedImage three = read(data, p);
        assertEquals(21, three.getWidth());
        assertEquals(16, three.getHeight());
        assertEquals(half.getRGB(15, 15), three.getRGB(10, 10));
    }

    @Test
    void sourceRegionsCropTheImage() throws IOException {
        ImageReadParam p = new ImageReadParam();
        p.setSourceRegion(new Rectangle(5, 6, 20, 10));
        BufferedImage img = read(JpxSamples.resource("rgb-tiles-offset.j2k"), p);
        assertEquals(20, img.getWidth());
        assertEquals(10, img.getHeight());
        assertEquals(JpxSamples.value(5 + 7, 6 + 4, 2), img.getRaster().getSample(7, 4, 2));
    }

    private static PDImageXObject pdfImage(PDDocument doc, byte[] jpx, boolean smaskInData) throws IOException {
        COSStream s = doc.getDocument().createCOSStream();
        try (OutputStream o = s.createRawOutputStream()) {
            o.write(jpx);
        }
        s.setItem(COSName.TYPE, COSName.XOBJECT);
        s.setItem(COSName.SUBTYPE, COSName.IMAGE);
        s.setItem(COSName.FILTER, COSName.JPX_DECODE);
        s.setInt(COSName.WIDTH, JpxSamples.WIDTH);
        s.setInt(COSName.HEIGHT, JpxSamples.HEIGHT);
        if (smaskInData) {
            s.setInt(COSName.SMASK_IN_DATA, 1);
        }
        return new PDImageXObject(new PDStream(s), null);
    }

    @Test
    void pdfboxDecodesJpxImagesThroughTheReader() throws IOException {
        JpxImageIO.install();
        try (PDDocument doc = new PDDocument()) {
            BufferedImage img = pdfImage(doc, JpxSamples.resource("rgb.jp2"), false).getImage();
            assertEquals(JpxSamples.WIDTH, img.getWidth());
            int rgb = img.getRGB(30, 20);
            assertEquals(JpxSamples.value(30, 20, 0), rgb >> 16 & 0xFF);
            assertEquals(JpxSamples.value(30, 20, 2), rgb & 0xFF);
            BufferedImage small = pdfImage(doc, JpxSamples.resource("rgb.jp2"), false).getImage(null, 2);
            assertEquals(31, small.getWidth());
            BufferedImage grey = pdfImage(doc, JpxSamples.resource("gray16.j2k"), false).getImage();
            assertEquals(JpxSamples.value16(5, 5) >> 8, grey.getRaster().getSample(5, 5, 0), 1);
        }
    }

    @Test
    void pdfboxUsesTheAlphaChannelWhenSmaskInDataIsSet() throws IOException {
        JpxImageIO.install();
        try (PDDocument doc = new PDDocument()) {
            BufferedImage img = pdfImage(doc, JpxSamples.resource("rgba.jp2"), true).getImage();
            assertTrue(img.getColorModel().hasAlpha());
            assertEquals(JpxSamples.value(30, 20, 3), img.getRGB(30, 20) >>> 24);
        }
    }

    @Test
    void pdfboxReadsOnlyTheColoursWhenSmaskInDataIsNotSet() throws IOException {
        JpxImageIO.install();
        try (PDDocument doc = new PDDocument()) {
            BufferedImage img = pdfImage(doc, JpxSamples.resource("rgba.jp2"), false).getImage();
            for (int y = 0; y < JpxSamples.HEIGHT; y += 5) {
                for (int x = 0; x < JpxSamples.WIDTH; x += 5) {
                    int rgb = img.getRGB(x, y);
                    assertEquals(0xFF, rgb >>> 24);
                    assertEquals(JpxSamples.value(x, y, 0), rgb >> 16 & 0xFF, "red at " + x + "," + y);
                    assertEquals(JpxSamples.value(x, y, 1), rgb >> 8 & 0xFF, "green at " + x + "," + y);
                    assertEquals(JpxSamples.value(x, y, 2), rgb & 0xFF, "blue at " + x + "," + y);
                }
            }
        }
    }
}
