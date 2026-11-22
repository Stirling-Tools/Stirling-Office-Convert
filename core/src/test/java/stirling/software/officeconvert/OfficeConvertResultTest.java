package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficeConvertResultTest {

    @TempDir Path dir;

    @Test
    void aCleanConversionReportsItsPagesAndNothingLeftOut() throws IOException {
        Path pdf = dir.resolve("clean.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Quarterly figures");
                cs.endText();
            }
            doc.save(pdf.toFile());
        }
        assertEquals(new OfficeConvert.Result(1, List.of(), false),
                OfficeConvert.convertWithResult(pdf, dir.resolve("clean.docx")));
    }

    @Test
    void aPictureThatCannotBeDecodedMakesTheResultPartial() throws IOException {
        Path pdf = dir.resolve("broken-picture.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            COSStream s = doc.getDocument().createCOSStream();
            s.setItem(COSName.SUBTYPE, COSName.IMAGE);
            s.setInt(COSName.WIDTH, 10);
            s.setInt(COSName.HEIGHT, 10);
            s.setInt(COSName.BITS_PER_COMPONENT, 8);
            s.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
            s.setItem(COSName.FILTER, COSName.DCT_DECODE);
            s.setItem(COSName.DECODE, COSArray.ofCOSIntegers(List.of(1, 0)));
            try (OutputStream raw = s.createRawOutputStream()) {
                raw.write(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xC0, 0, 11, 8, 0, 10, 0, 10, 1, 1, 0x11,
                    0, (byte) 0xFF, (byte) 0xD9});
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(new PDImageXObject(new PDStream(s), null), 72, 400, 200, 200);
            }
            doc.save(pdf.toFile());
        }
        OfficeConvert.Result broken = OfficeConvert.convertWithResult(pdf, dir.resolve("broken.docx"));
        assertTrue(broken.partial());
        assertEquals(1, broken.pages());
        assertTrue(broken.warnings().stream().anyMatch(w -> w.startsWith("Image left out")), broken::toString);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            OfficeConvert.Result streamed = OfficeConvert.convertWithResult(doc, new ByteArrayOutputStream(),
                    OfficeConvert.Format.ODT, OfficeConvert.Settings.defaults());
            assertTrue(streamed.partial(), streamed::toString);
        }
    }
}
