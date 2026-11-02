package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.testing.DamagedPdf;

class TextOutsideTextObjectsTest {

    @TempDir Path dir;

    private String text(String content) throws IOException {
        Path in = DamagedPdf.hello().content(content).write(dir.resolve("in.pdf"));
        return OfficeConvert.text(in).strip();
    }

    @Test
    void textShownWithoutBeginTextIsKept() throws IOException {
        assertEquals("Hello PDF-world!", text(DamagedPdf.HELLO.replace("BT\n", "")));
    }

    @Test
    void textShownWithoutAnyTextObjectIsKept() throws IOException {
        assertEquals("Hello PDF-world!", text(DamagedPdf.HELLO.replace("BT\n", "").replace("ET\n", "")));
    }

    @Test
    void positioningBeforeTheFirstTextObjectStartsOne() throws IOException {
        assertEquals("Hello PDF-world!", text("/F0 24 Tf 50 700 Td (Hello PDF-world!) Tj"));
    }

    @Test
    void streamBytesThatAreNotContentShowNoText() throws IOException {
        assertEquals("", text("/F0 24 Tf 50 700 Td (Hello PDF-world!) Tj j3.bute27fc (r#gh) ' 9x Tq"));
    }

    @Test
    void textObjectsInAGarbledStreamStillShow() throws IOException {
        assertEquals("Hello PDF-world!", text(DamagedPdf.HELLO + " j3.bute27fc (r#gh) ' 9x Tq"));
    }

    @Test
    void aZeroFontSizeKeepsTheLettersInOrder() throws IOException {
        assertEquals("Hello PDF-world!", text(DamagedPdf.HELLO.replace("36.", ".")));
    }

    @Test
    void wellFormedTextIsUnchanged() throws IOException {
        assertEquals("Hello PDF-world!", text(DamagedPdf.HELLO));
    }

    @Test
    void theWordDocumentKeepsTextShownOutsideATextObject() throws IOException {
        Path in = DamagedPdf.hello().content(DamagedPdf.HELLO.replace("BT\n", "").replace("ET\n", ""))
                .write(dir.resolve("loose.pdf"));
        Path docx = dir.resolve("loose.docx");
        PdfToDocx.convert(in, docx, PdfToDocx.Options.defaults());
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            String xml = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(xml.contains("Hello PDF-world!"), xml);
        }
    }
}
