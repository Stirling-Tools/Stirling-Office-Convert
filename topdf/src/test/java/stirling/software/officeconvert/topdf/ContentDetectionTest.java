package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.Fixtures;

class ContentDetectionTest {

    private static final String SYLK = "ID;PWXL\r\nC;Y1;X1;K\"cell\"\r\nE\r\n";

    @TempDir
    Path dir;

    private String text(Path pdf) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(d);
        }
    }

    private String convert(String name, byte[] data) throws IOException {
        Path in = Files.write(dir.resolve(name), data);
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.convert(in, out);
        return text(out);
    }

    @Test
    void textThatLooksLikeSylkStaysTextForEveryTextExtensionAndTextRequest() throws IOException {
        for (String name : new String[] {"notes.log", "notes.asc", "notes.text", "notes.txt"}) {
            String t = convert(name, SYLK.getBytes(StandardCharsets.US_ASCII));
            assertTrue(t.contains("ID;PWXL"), name + ": " + t);
        }
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        OfficeToPdf.convert(new ByteArrayInputStream(SYLK.getBytes(StandardCharsets.US_ASCII)),
                OfficeToPdf.Format.TEXT, pdf, OfficeToPdf.Options.defaults());
        Path out = Files.write(dir.resolve("stream.pdf"), pdf.toByteArray());
        assertTrue(text(out).contains("ID;PWXL"), text(out));
    }

    @Test
    void aWordDocumentUnderAnotherFormatsNameStillConverts() throws IOException {
        byte[] docx = Fixtures.docx("Renamed but readable");
        for (String name : new String[] {"report.rtf", "report.wk1", "report.dbf"}) {
            String t = convert(name, docx);
            assertTrue(t.contains("Renamed but readable"), name + ": " + t);
        }
    }

    @Test
    void textStartingWithTheWordTwoSignatureIsText() throws IOException {
        byte[] data = new byte[200];
        data[0] = (byte) 0xDB;
        data[1] = (byte) 0xA5;
        byte[] words = " plain notes follow here and here and here and there".repeat(3)
                .getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(words, 0, data, 2, Math.min(words.length, data.length - 2));
        String t = convert("notes.txt", data);
        assertTrue(t.contains("plain notes"), t);
    }
}
