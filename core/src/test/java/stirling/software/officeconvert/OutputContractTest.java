package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutputContractTest {

    @TempDir Path dir;

    private static final class Tracked extends ByteArrayOutputStream {
        boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void callerStreamStaysOpen() throws IOException {
        Tracked out = new Tracked();
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            PdfToDocx.convert(doc, out, PdfToDocx.Options.defaults());
        }
        assertFalse(out.closed, "caller's stream closed");
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            boolean body = false;
            for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                body |= e.getName().equals("word/document.xml");
            }
            assertTrue(body, "complete package written");
        }
    }

    @Test
    void failedConversionLeavesNoFile() throws IOException {
        Path pdf = dir.resolve("broken.pdf");
        Files.writeString(pdf, "not a pdf");
        Path docx = dir.resolve("broken.docx");
        assertThrows(IOException.class, () -> PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults()));
        try (var files = Files.list(dir)) {
            assertTrue(files.allMatch(pdf::equals), "only the input remains");
        }
    }
}
