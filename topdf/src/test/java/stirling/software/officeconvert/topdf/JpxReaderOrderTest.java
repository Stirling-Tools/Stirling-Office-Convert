package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.jpx.JpxImageIO;
import stirling.software.officeconvert.jpx.JpxImageReader;
import stirling.software.officeconvert.jpx.JpxImageReaderSpi;
import stirling.software.officeconvert.topdf.testing.Fixtures;

class JpxReaderOrderTest {

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

    @TempDir
    Path dir;

    @Test
    void bothConvertEntryPointsPutTheBuiltInReaderFirst() throws IOException {
        Path in = Files.write(dir.resolve("a.docx"), Fixtures.docx("Body"));
        IIORegistry registry = IIORegistry.getDefaultInstance();
        HostSpi host = new HostSpi();
        registry.registerServiceProvider(host, ImageReaderSpi.class);
        try {
            preferHost(registry, host);
            OfficeToPdf.convert(in, dir.resolve("a.pdf"));
            assertInstanceOf(JpxImageReaderSpi.class, first().getOriginatingProvider());
            preferHost(registry, host);
            OfficeToPdf.convert(new ByteArrayInputStream(Fixtures.docx("Body")), OfficeToPdf.Format.DOCX,
                    new ByteArrayOutputStream(), OfficeToPdf.Options.defaults());
            assertInstanceOf(JpxImageReaderSpi.class, first().getOriginatingProvider());
        } finally {
            registry.deregisterServiceProvider(host, ImageReaderSpi.class);
            JpxImageIO.install();
        }
    }

    private static void preferHost(IIORegistry registry, HostSpi host) {
        JpxImageIO.install();
        ImageReaderSpi ours = registry.getServiceProviderByClass(JpxImageReaderSpi.class);
        registry.unsetOrdering(ImageReaderSpi.class, ours, host);
        registry.setOrdering(ImageReaderSpi.class, host, ours);
        assertSame(host, first().getOriginatingProvider());
    }

    private static ImageReader first() {
        return ImageIO.getImageReadersByFormatName("jpeg2000").next();
    }
}
