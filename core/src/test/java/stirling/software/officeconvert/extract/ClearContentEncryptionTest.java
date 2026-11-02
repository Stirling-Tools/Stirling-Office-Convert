package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.testing.DamagedPdf;

class ClearContentEncryptionTest {

    @TempDir Path dir;

    private Path write(DamagedPdf pdf) throws IOException {
        return pdf.write(Files.createTempFile(dir, "encrypted-", ".pdf"));
    }

    private static String text(PDDocument doc) throws IOException {
        return new PDFTextStripper().getText(doc).strip();
    }

    private static DamagedPdf encrypted(String streams, String strings) {
        String key = "(" + "\\000".repeat(32) + ")";
        return DamagedPdf.hello()
                .object(5, "<< /Pages 1 0 R /Type /Catalog /Names << /EmbeddedFiles << /Names [] >> >> >>")
                .object(7, "<< /Filter /Standard /V 4 /R 4 /Length 128 /P -1028 /O " + key + " /U " + key
                        + " /CF << /StdCF << /AuthEvent /EFOpen /CFM /AESV2 /Length 16 >> >> /EFF /StdCF"
                        + streams + strings + " /EncryptMetadata false >>")
                .trailer("/Root 5 0 R /Encrypt 7 0 R /ID [<00112233445566778899AABBCCDDEEFF>"
                        + " <00112233445566778899AABBCCDDEEFF>]");
    }

    @Test
    void identityCryptFiltersOpenWithoutThePasswordThatOnlyGuardsAttachments() throws IOException {
        try (PDDocument doc = PdfFiles.open(write(encrypted(" /StmF /Identity", " /StrF /Identity")), null)) {
            assertFalse(doc.isEncrypted());
            assertEquals("Hello PDF-world!", text(doc));
            assertNull(doc.getDocumentCatalog().getNames().getEmbeddedFiles());
            Path saved = dir.resolve("saved.pdf");
            doc.save(saved.toFile());
            try (PDDocument again = PdfFiles.open(saved, null)) {
                assertFalse(again.isEncrypted());
                assertEquals("Hello PDF-world!", text(again));
            }
        }
        try (PDDocument doc = PdfFiles.open(write(encrypted("", "")), null)) {
            assertEquals("Hello PDF-world!", text(doc));
        }
    }

    @Test
    void encryptedContentStillNeedsItsPassword() throws IOException {
        Path in = write(encrypted(" /StmF /StdCF", " /StrF /Identity"));
        assertThrows(InvalidPasswordException.class, () -> PdfFiles.open(in, null));
        Path attachmentsOnly = write(encrypted(" /StmF /Identity", " /StrF /Identity"));
        assertThrows(InvalidPasswordException.class, () -> PdfFiles.openAsStored(attachmentsOnly, null));
    }
}
