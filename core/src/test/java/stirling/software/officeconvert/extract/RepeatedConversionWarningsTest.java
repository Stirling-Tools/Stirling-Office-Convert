package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DeflaterOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.OfficeConvert;
import stirling.software.officeconvert.testing.RunawayFonts;

class RepeatedConversionWarningsTest {

    @Test
    void aFontLeftOutByAnEarlierConversionIsReportedAgain() throws IOException {
        try (PDDocument doc = RunawayFonts.simpleFontPdf(COSName.FONT_FILE3, RunawayFonts.cff(9, 100),
                COSName.getPDFName("Type1C"), true)) {
            for (int run = 0; run < 2; run++) {
                OfficeConvert.Result result = OfficeConvert.convertWithResult(doc, new ByteArrayOutputStream(),
                        OfficeConvert.Format.DOCX, OfficeConvert.Settings.defaults());
                assertTrue(result.partial(), result::toString);
                assertTrue(result.warnings().stream().anyMatch(w -> w.contains("embedded font program")),
                        result::toString);
            }
        }
    }

    @Test
    void aStreamEmptiedByAnEarlierCheckIsReportedAgain() throws IOException {
        try (PDDocument doc = Loader.loadPDF(bomb())) {
            for (int run = 0; run < 2; run++) {
                try (Warnings.Sink sink = Warnings.open()) {
                    assertEquals(1, StreamGuard.check(doc));
                    assertTrue(sink.partial());
                }
            }
        }
    }

    @Test
    void aPictureFoundTooLargeBeforeIsReportedOnEveryCall() throws IOException {
        try (PDDocument doc = Loader.loadPDF(bomb())) {
            COSStream picture = picture(doc);
            for (int run = 0; run < 2; run++) {
                try (Warnings.Sink sink = Warnings.open()) {
                    assertFalse(StreamGuard.fits(picture));
                    assertTrue(sink.partial());
                }
            }
        }
    }

    private static COSStream picture(PDDocument doc) {
        return (COSStream) doc.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.XOBJECT)
                .getDictionaryObject(COSName.getPDFName("Im1"));
    }

    private static byte[] bomb() throws IOException {
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(packed)) {
            byte[] zeros = new byte[1 << 20];
            for (long n = 0; n <= StreamGuard.MAX_DECODED; n += zeros.length) {
                deflate.write(zeros);
            }
        }
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            COSStream image = doc.getDocument().createCOSStream();
            image.setItem(COSName.SUBTYPE, COSName.IMAGE);
            image.setItem(COSName.FILTER, COSName.FLATE_DECODE);
            try (var out = image.createRawOutputStream()) {
                packed.writeTo(out);
            }
            COSDictionary xobjects = new COSDictionary();
            xobjects.setItem(COSName.getPDFName("Im1"), image);
            PDResources resources = new PDResources();
            resources.getCOSObject().setItem(COSName.XOBJECT, xobjects);
            page.setResources(resources);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
