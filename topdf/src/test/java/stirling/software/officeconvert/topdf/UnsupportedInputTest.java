package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import stirling.software.officeconvert.UnsupportedFormatException;

class UnsupportedInputTest {

    private static final String TEXT = "Just some plain text.\nSecond line.\n";

    @TempDir
    Path dir;

    @Test
    void anUnknownExtensionIsRefusedAsUnsupported() throws IOException {
        Path in = Files.writeString(dir.resolve("notes.foo"), TEXT);
        Path out = dir.resolve("notes.pdf");
        UnsupportedFormatException e = assertThrows(UnsupportedFormatException.class, () -> OfficeToPdf.convert(in, out));
        assertTrue(e.getMessage().startsWith("Not an Office document: notes.foo"), e.getMessage());
        assertFalse(Files.exists(out));
        assertThrows(UnsupportedFormatException.class, () -> OfficeToPdf.memoryEstimate(in));
    }

    @Test
    void aKnownUnsupportedExtensionIsRefusedWithItsReason() throws IOException {
        Path in = Files.writeString(dir.resolve("letter.wpd"), TEXT);
        UnsupportedFormatException e = assertThrows(UnsupportedFormatException.class,
                () -> OfficeToPdf.convert(in, dir.resolve("letter.pdf")));
        assertTrue(e.getMessage().startsWith("WordPerfect documents are not supported"), e.getMessage());
    }

    @Test
    void plainTextNamedRtfIsRefusedAsUnsupported() throws IOException {
        Path in = Files.writeString(dir.resolve("memo.rtf"), TEXT);
        UnsupportedFormatException e = assertThrows(UnsupportedFormatException.class,
                () -> OfficeToPdf.convert(in, dir.resolve("memo.pdf")));
        assertTrue(e.getMessage().startsWith("The file is not an RTF document"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"fods", "dbf", "xml", "vsdx", "docx", "odt", "xls", "doc", "pptx"})
    void plainTextUnderAnOfficeNameIsRefusedAsUnsupported(String ext) throws IOException {
        Path in = Files.writeString(dir.resolve("plain." + ext), TEXT);
        assertThrows(UnsupportedFormatException.class, () -> OfficeToPdf.convert(in, dir.resolve(ext + ".pdf")), ext);
    }

    @Test
    void aStreamOfTheWrongContentIsRefusedAsUnsupported() {
        ByteArrayInputStream in = new ByteArrayInputStream(TEXT.getBytes(StandardCharsets.UTF_8));
        assertThrows(UnsupportedFormatException.class,
                () -> OfficeToPdf.convert(in, OfficeToPdf.Format.DOCX, new ByteArrayOutputStream(),
                        OfficeToPdf.Options.defaults()));
    }

    @Test
    void aPdfNamedDocxIsRefusedAsUnsupported() throws IOException {
        Path in = Files.writeString(dir.resolve("report.docx"), "%PDF-1.7\n%%EOF\n", StandardCharsets.ISO_8859_1);
        assertThrows(UnsupportedFormatException.class, () -> OfficeToPdf.convert(in, dir.resolve("report.pdf")));
    }

    @Test
    void aDamagedZipOfARecognisedKindIsNotCalledUnsupported() throws IOException {
        byte[] damaged = new byte[64];
        damaged[0] = 'P';
        damaged[1] = 'K';
        damaged[2] = 3;
        damaged[3] = 4;
        Path in = Files.write(dir.resolve("broken.docx"), damaged);
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("broken.pdf")));
        assertFalse(e instanceof UnsupportedFormatException, e.toString());
    }
}
