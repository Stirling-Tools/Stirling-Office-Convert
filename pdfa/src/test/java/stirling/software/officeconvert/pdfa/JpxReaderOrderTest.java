package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import com.github.jaiimageio.jpeg2000.impl.J2KImageReaderSpi;

import stirling.software.officeconvert.jpx.JpxImageIO;
import stirling.software.officeconvert.jpx.JpxImageReaderSpi;

class JpxReaderOrderTest {

    @Test
    void convertingADocumentPutsTheBuiltInReaderBeforeJaiImageIo() throws IOException {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        JpxImageIO.install();
        ImageIO.scanForPlugins();
        ImageReaderSpi jai = registry.getServiceProviderByClass(J2KImageReaderSpi.class);
        assertNotNull(jai);
        registry.setOrdering(ImageReaderSpi.class, jai, registry.getServiceProviderByClass(JpxImageReaderSpi.class));
        assertSame(jai, ImageIO.getImageReadersByFormatName("jpeg2000").next().getOriginatingProvider());
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.LETTER));
            PdfToPdfA.convert(doc, new ByteArrayOutputStream(), PdfToPdfA.Options.defaults());
        }
        assertInstanceOf(JpxImageReaderSpi.class,
                ImageIO.getImageReadersByFormatName("jpeg2000").next().getOriginatingProvider());
    }
}
