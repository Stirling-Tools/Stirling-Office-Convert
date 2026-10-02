package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnsupportedFormatsTest {

    @TempDir
    Path dir;

    private String refusal(String name, byte[] data) throws IOException {
        Path in = Files.write(dir.resolve(name), data);
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve(name + ".pdf")));
        return e.getMessage();
    }

    @Test
    void knownFormatsAreNamedByExtension() throws IOException {
        assertTrue(refusal("letter.wpd", new byte[] {1, 2, 3}).contains("WordPerfect"));
        assertTrue(refusal("flyer.pub", new byte[] {1, 2, 3}).contains("Publisher"));
        assertTrue(refusal("plan.vsd", new byte[] {1, 2, 3}).contains("Visio"));
        assertTrue(refusal("budget.wk1", new byte[] {1, 2, 3}).contains("Lotus"));
    }

    @Test
    void aMisnamedFileIsNamedByItsContent() throws IOException {
        assertTrue(refusal("letter.doc", new byte[] {(byte) 0xFF, 'W', 'P', 'C', 16, 0, 0, 0, 1, 10})
                .contains("WordPerfect"));
        ByteArrayOutputStream ole = new ByteArrayOutputStream();
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            fs.createDocument(new ByteArrayInputStream(new byte[64]), "Quill");
            fs.createDocument(new ByteArrayInputStream(new byte[64]), "Contents");
            fs.writeFilesystem(ole);
        }
        assertTrue(refusal("flyer.doc", ole.toByteArray()).contains("Publisher"));
        assertTrue(refusal("page.docx", "<!DOCTYPE html><html><body>Hi</body></html>".getBytes()).contains("HTML"));
    }
}
