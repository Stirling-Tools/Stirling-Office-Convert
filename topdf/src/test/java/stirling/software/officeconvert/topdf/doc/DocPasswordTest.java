package stirling.software.officeconvert.topdf.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hssf.record.crypto.Biff8EncryptionKey;
import org.apache.poi.hwpf.HWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.crypt.Passwords;

class DocPasswordTest {

    @TempDir
    Path dir;

    private static byte[] locked(String password) throws IOException {
        byte[] plain = new WordFixture().para("Confidential minutes").build();
        Biff8EncryptionKey.setCurrentUserPassword(password);
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(plain));
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.write(out);
            return out.toByteArray();
        } finally {
            Biff8EncryptionKey.setCurrentUserPassword(null);
        }
    }

    @Test
    void anEncryptedWordDocumentOpensWithItsPassword() throws IOException {
        Path in = Files.write(dir.resolve("locked.doc"), locked("minutes"));
        Path out = dir.resolve("locked.pdf");
        OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().password("minutes"));
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            String text = new PDFTextStripper().getText(d);
            assertTrue(text.contains("Confidential minutes"), text);
        }
        IOException none = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("none.pdf")));
        assertEquals(Passwords.PROTECTED, none.getMessage());
        IOException wrong = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("wrong.pdf"),
                OfficeToPdf.Options.defaults().password("guess")));
        assertEquals(Passwords.WRONG, wrong.getMessage());
    }
}
