package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import stirling.software.officeconvert.jpx.JpxImageIO;
import stirling.software.officeconvert.jpx.JpxImageReader;
import stirling.software.officeconvert.jpx.JpxImageReaderSpi;

class JpxEntryPointsTest {

    static final class HostSpi extends ImageReaderSpi {

        HostSpi() {
            super("Host", "1", new String[] {"jpeg2000"}, null, null, JpxImageReader.class.getName(),
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
            return "Host";
        }
    }

    @ParameterizedTest
    @EnumSource(value = OfficeConvert.Format.class, names = {"DOCX", "ODT", "TXT", "PPTX", "XLSX"})
    void everyDocumentEntryPointPutsTheBuiltInReaderFirst(OfficeConvert.Format format) throws IOException {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        JpxImageIO.install();
        HostSpi host = new HostSpi();
        registry.registerServiceProvider(host, ImageReaderSpi.class);
        try {
            ImageIO.scanForPlugins();
            registry.setOrdering(ImageReaderSpi.class, host,
                    registry.getServiceProviderByClass(JpxImageReaderSpi.class));
            assertSame(host, first().getOriginatingProvider());
            try (PDDocument doc = new PDDocument()) {
                doc.addPage(new PDPage(PDRectangle.LETTER));
                OfficeConvert.convert(doc, new ByteArrayOutputStream(), format, OfficeConvert.Settings.defaults());
            }
            assertInstanceOf(JpxImageReaderSpi.class, first().getOriginatingProvider());
        } finally {
            registry.deregisterServiceProvider(host, ImageReaderSpi.class);
            JpxImageIO.install();
        }
    }

    private static ImageReader first() {
        return ImageIO.getImageReadersByFormatName("jpeg2000").next();
    }
}
