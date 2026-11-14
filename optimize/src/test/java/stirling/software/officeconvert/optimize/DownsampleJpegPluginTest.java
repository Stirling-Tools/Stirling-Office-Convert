package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;

import javax.imageio.IIOImage;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageWriterSpi;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownsampleJpegPluginTest {

    @TempDir
    Path dir;

    @Test
    void anotherJpegPluginRegisteredFirstDoesNotStopPhotosBeingReencoded() throws Exception {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        BrokenJpegWriterSpi broken = new BrokenJpegWriterSpi();
        registry.registerServiceProvider(broken, ImageWriterSpi.class);
        registry.getServiceProviders(ImageWriterSpi.class, true).forEachRemaining(spi -> {
            if (spi != broken) {
                registry.setOrdering(ImageWriterSpi.class, broken, spi);
            }
        });
        try {
            Path in = dir.resolve("in.pdf");
            try (PDDocument doc = new PDDocument()) {
                PDImageXObject image = LosslessFactory.createFromImage(doc, photo(1200, 1200));
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.drawImage(image, 50, 50, 144, 144);
                }
                doc.save(in.toFile());
            }
            Path out = dir.resolve("out.pdf");
            PdfOptimizer.Result result = PdfOptimizer.optimize(in, out,
                    PdfOptimizer.Options.defaults().images(new PdfOptimizer.Images(150, 0.7f)));

            assertEquals(java.util.List.of(), result.warnings());
            try (PDDocument doc = Loader.loadPDF(out.toFile())) {
                PDPage page = doc.getPage(0);
                COSName name = page.getResources().getXObjectNames().iterator().next();
                COSStream stream = ((PDImageXObject) page.getResources().getXObject(name)).getCOSObject();
                COSBase filter = stream.getDictionaryObject(COSName.FILTER);
                boolean dct = COSName.DCT_DECODE.equals(filter)
                        || filter instanceof COSArray array && array.toList().contains(COSName.DCT_DECODE);
                assertTrue(dct, "filter " + filter);
            }
        } finally {
            registry.deregisterServiceProvider(broken, ImageWriterSpi.class);
        }
    }

    private static BufferedImage photo(int width, int height) {
        Random random = new Random(7);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = Math.max(0, Math.min(255, x * 255 / width + random.nextInt(9) - 4));
                int g = Math.max(0, Math.min(255, y * 255 / height + random.nextInt(9) - 4));
                image.setRGB(x, y, r << 16 | g << 8 | 96);
            }
        }
        return image;
    }

    private static final class BrokenJpegWriterSpi extends ImageWriterSpi {

        BrokenJpegWriterSpi() {
            super("test", "1", new String[] {"jpeg", "JPEG"}, new String[] {"jpg"}, new String[] {"image/jpeg"},
                    BrokenJpegWriter.class.getName(), new Class<?>[] {javax.imageio.stream.ImageOutputStream.class},
                    null, false, null, null, null, null, false, null, null, null, null);
        }

        @Override
        public boolean canEncodeImage(ImageTypeSpecifier type) {
            return true;
        }

        @Override
        public ImageWriter createWriterInstance(Object extension) {
            return new BrokenJpegWriter(this);
        }

        @Override
        public String getDescription(Locale locale) {
            return "a JPEG writer that always fails";
        }
    }

    private static final class BrokenJpegWriter extends ImageWriter {

        BrokenJpegWriter(ImageWriterSpi spi) {
            super(spi);
        }

        @Override
        public IIOMetadata getDefaultStreamMetadata(javax.imageio.ImageWriteParam param) {
            return null;
        }

        @Override
        public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier type, javax.imageio.ImageWriteParam param) {
            return null;
        }

        @Override
        public IIOMetadata convertStreamMetadata(IIOMetadata in, javax.imageio.ImageWriteParam param) {
            return null;
        }

        @Override
        public IIOMetadata convertImageMetadata(IIOMetadata in, ImageTypeSpecifier type,
                javax.imageio.ImageWriteParam param) {
            return null;
        }

        @Override
        public void write(IIOMetadata streamMetadata, IIOImage image, javax.imageio.ImageWriteParam param)
                throws IOException {
            throw new IOException("this writer always fails");
        }
    }
}
