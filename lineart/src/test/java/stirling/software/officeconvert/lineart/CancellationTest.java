package stirling.software.officeconvert.lineart;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.io.OutputStream;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.junit.jupiter.api.Test;

class CancellationTest {

    @Test
    void interruptedEmptyDocumentDoesNotReportSuccess() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, () -> LineArt.convert(doc, LineArt.Options.defaults()));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void interruptionInsideTheLastImageEscapesBestEffortRecovery() throws Exception {
        try (PDDocument doc = new PDDocument(); COSStream image = new COSStream() {
            @Override
            public long getLength() {
                Thread.currentThread().interrupt();
                return super.getLength();
            }
        }) {
            image.setItem(COSName.TYPE, COSName.XOBJECT);
            image.setItem(COSName.SUBTYPE, COSName.IMAGE);
            image.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
            image.setInt(COSName.BITS_PER_COMPONENT, 8);
            image.setInt(COSName.WIDTH, 1);
            image.setInt(COSName.HEIGHT, 1);
            try (OutputStream out = image.createOutputStream()) {
                out.write(new byte[] {0, 0, 0});
            }
            PDPage page = new PDPage();
            PDResources resources = new PDResources();
            COSDictionary images = new COSDictionary();
            images.setItem(COSName.getPDFName("Scan"), image);
            resources.getCOSObject().setItem(COSName.XOBJECT, images);
            page.setResources(resources);
            doc.addPage(page);
            try {
                assertThrows(InterruptedIOException.class, () -> LineArt.convert(doc, LineArt.Options.defaults()));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void cancelledBufferedImageAndEveryCodecStopWithoutClearingTheFlag() {
        BufferedImage scan = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        Thread.currentThread().interrupt();
        try {
            UncheckedIOException error = assertThrows(UncheckedIOException.class,
                    () -> LineArt.binarize(scan, LineArt.Options.defaults()));
            assertTrue(error.getCause() instanceof InterruptedIOException);
        } finally {
            Thread.interrupted();
        }
        Bitmap bitmap = LineArt.binarize(scan, LineArt.Options.defaults());
        for (LineArt.Codec codec : LineArt.Codec.values()) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(UncheckedIOException.class, () -> Codecs.encode(bitmap, codec));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void standaloneBinarizationHonoursTheConfiguredPixelLimit() {
        BufferedImage scan = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        assertThrows(IllegalArgumentException.class,
                () -> LineArt.binarize(scan, LineArt.Options.defaults().maxPixels(1000)));
    }

    @Test
    void inlineContentCancellationIsNotReportedAsAnUnreadableStream() throws Exception {
        try (PDDocument doc = new PDDocument(); COSStream content = new COSStream() {
            @Override
            public org.apache.pdfbox.cos.COSInputStream createInputStream() throws java.io.IOException {
                throw new InterruptedIOException("Stopped during inline image read");
            }
        }) {
            PDPage page = new PDPage();
            page.getCOSObject().setItem(COSName.CONTENTS, content);
            doc.addPage(page);
            assertThrows(InterruptedIOException.class, () -> LineArt.convert(doc, LineArt.Options.defaults()));
        }
    }

}
