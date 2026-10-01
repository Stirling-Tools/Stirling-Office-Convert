package stirling.software.officeconvert.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OfficeFilesTest {

    @TempDir
    Path dir;

    private Path write(byte[] bytes) throws IOException {
        return Files.write(dir.resolve("upload"), bytes);
    }

    @Test
    void recognisesTheThreeFamiliesFromTheirContent() throws IOException {
        assertEquals("docx", OfficeFiles.extension(write(Fixtures.docx("Hello", 1))));
        assertEquals("pptx", OfficeFiles.extension(write(Fixtures.pptx("Hello"))));
        assertEquals("xlsx", OfficeFiles.extension(write(Fixtures.xlsx("Hello"))));
    }

    @Test
    void recognisesLegacyExcelAndPowerPointFromTheirContent() throws IOException {
        assertEquals("xls", OfficeFiles.extension(write(Fixtures.xls("Hello"))));
        assertEquals("ppt", OfficeFiles.extension(write(Fixtures.ppt("Hello"))));
        assertEquals("xlsx", OfficeFiles.family("xls"));
        assertEquals("pptx", OfficeFiles.family("pps"));
        byte[] ole = new byte[2048];
        System.arraycopy(new byte[] {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0}, 0, ole, 0, 4);
        IOException e = assertThrows(OfficeFiles.Unsupported.class, () -> OfficeFiles.extension(write(ole)));
        assertTrue(e.getMessage().contains("97-2003"));
    }

    @Test
    void recognisesRtfFromItsHeader() throws IOException {
        assertEquals("rtf", OfficeFiles.extension(write("{\\rtf1\\ansi Hello\\par}".getBytes(
                StandardCharsets.US_ASCII))));
        assertEquals("docx", OfficeFiles.family("rtf"));
    }

    @ParameterizedTest
    @CsvSource({
        "application/vnd.ms-word.document.macroEnabled.main+xml, docm",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.template.main+xml, dotx",
        "application/vnd.ms-word.template.macroEnabledTemplate.main+xml, dotm"})
    void tellsMacroEnabledAndTemplateVariantsApart(String type, String ext) throws IOException {
        byte[] docx = Fixtures.docx("Hello", 1);
        assertEquals(ext, OfficeFiles.extension(write(Fixtures.retype(docx, Fixtures.DOCX_MAIN, type))));
        assertEquals("docx", OfficeFiles.family(ext));
    }

    @Test
    void fallsBackToTheMainPartWithoutContentTypes() throws IOException {
        assertEquals("xlsx", OfficeFiles.extension(write(Fixtures.zip("xl/workbook.xml", "<workbook/>"))));
    }

    @Test
    void refusesOpenDocumentAndOtherZips() throws IOException {
        IOException odf = assertThrows(IOException.class, () -> OfficeFiles.extension(
                write(Fixtures.zip("mimetype", "application/vnd.oasis.opendocument.text", "content.xml", "<x/>"))));
        assertTrue(odf.getMessage().startsWith("OpenDocument files"));
        IOException other = assertThrows(IOException.class,
                () -> OfficeFiles.extension(write(Fixtures.zip("readme.txt", "hello"))));
        assertTrue(other.getMessage().contains("holds no Word, PowerPoint or Excel"));
    }

    @Test
    void aDamagedZipSaysSo() throws IOException {
        byte[] junk = new byte[4096];
        junk[0] = 'P';
        junk[1] = 'K';
        junk[2] = 3;
        junk[3] = 4;
        IOException e = assertThrows(IOException.class, () -> OfficeFiles.extension(write(junk)));
        assertTrue(e.getMessage().startsWith("This document is damaged"));
    }

    @Test
    void familiesCoverEveryExtension() {
        for (String ext : new String[] {"docx", "docm", "dotx", "dotm", "doc", "dot"}) {
            assertEquals("docx", OfficeFiles.family(ext));
        }
        for (String ext : new String[] {"pptx", "pptm", "ppsx", "ppsm", "potx", "potm"}) {
            assertEquals("pptx", OfficeFiles.family(ext));
        }
        for (String ext : new String[] {"xlsx", "xlsm", "xltx", "xltm"}) {
            assertEquals("xlsx", OfficeFiles.family(ext));
        }
        assertThrows(IllegalArgumentException.class, () -> OfficeFiles.family("rtf"));
    }
}
