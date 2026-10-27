package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Map;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class WebFontsBoundaryTest {

    @Test
    void oversizedProgramsAreRejectedBeforeParsing() {
        byte[] bytes = new byte[(64 << 20) + 1];
        assertThrows(Decoded.TooLarge.class,
                () -> WebFonts.toTrueType(bytes, WebFonts.FontKind.CFF, Map.of()));
    }

    @Test
    void compressedProgramsCannotExceedTheDecodedLimit() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDStream stream = new PDStream(document);
            try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
                byte[] block = new byte[8192];
                for (int i = 0; i <= (64 << 20) / block.length; i++) {
                    out.write(block);
                }
            }
            PDFontDescriptor descriptor = new PDFontDescriptor(new org.apache.pdfbox.cos.COSDictionary());
            descriptor.setFontFile3(stream);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA) {
                @Override
                public PDFontDescriptor getFontDescriptor() {
                    return descriptor;
                }
            };
            assertThrows(Decoded.TooLarge.class, () -> WebFonts.toTrueType(font));
        }
    }

    @Test
    void interruptedCallsStopBeforeParsingOrMutating() throws Exception {
        try (PDDocument document = new PDDocument()) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class,
                        () -> WebFonts.toTrueType(new byte[0], WebFonts.FontKind.CFF, Map.of()));
                assertThrows(InterruptedIOException.class,
                        () -> WebFonts.toTrueType(new PDType1Font(Standard14Fonts.FontName.HELVETICA)));
                assertThrows(InterruptedIOException.class, () -> FontEmbedder.embedMissing(document));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }
}
