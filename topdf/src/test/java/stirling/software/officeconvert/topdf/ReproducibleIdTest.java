package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.topdf.testing.Fixtures;

class ReproducibleIdTest {

    private static final OfficeToPdf.Options FIXED = OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2))
            .clock(Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC));

    @Test
    void differentUntitledDocumentsUnderOneFixedClockGetDifferentIds() throws Exception {
        byte[] first = convert(Fixtures.docx("First document"));
        byte[] second = convert(Fixtures.docx("Second document"));
        assertFalse(Arrays.equals(id(first), id(second)));
    }

    @Test
    void theSameDocumentUnderOneFixedClockGivesTheSameBytes() throws Exception {
        byte[] docx = Fixtures.docx("Same document");
        assertArrayEquals(convert(docx), convert(docx));
    }

    private static byte[] convert(byte[] docx) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OfficeToPdf.convert(new ByteArrayInputStream(docx), OfficeToPdf.Format.DOCX, out, FIXED);
        return out.toByteArray();
    }

    private static byte[] id(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            COSArray ids = doc.getDocument().getTrailer().getCOSArray(COSName.ID);
            return ((COSString) ids.getObject(0)).getBytes();
        }
    }
}
