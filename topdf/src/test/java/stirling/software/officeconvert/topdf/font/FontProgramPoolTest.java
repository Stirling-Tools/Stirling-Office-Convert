package stirling.software.officeconvert.topdf.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.PdfOutput;
import stirling.software.officeconvert.topdf.pdf.TextStyle;
import stirling.software.officeconvert.topdf.testing.TestFonts;

class FontProgramPoolTest {

    @TempDir
    Path dir;

    @Test
    void aParsedSystemFontIsLentToOneUserAtATimeAndKept() throws Exception {
        Path fonts = Files.createDirectories(dir.resolve("pool"));
        Files.write(fonts.resolve("Pool.ttf"), TestFonts.renamed("Pool Face"));
        FontEntry entry = FontLibrary.of(List.of(fonts)).find("Pool Face", false, false).program().entry();
        FontProgram.Opened first = FontProgram.open(entry);
        FontProgram.Opened during = FontProgram.open(entry);
        assertNotSame(first.font(), during.font());
        first.close();
        during.close();
        try (FontProgram.Opened again = FontProgram.open(entry)) {
            assertSame(first.font(), again.font());
        }
    }

    @Test
    void theBundledFallbackFontIsKeptLikeASystemFont() throws Exception {
        FontEntry entry = FontLibrary.of(List.of(Files.createDirectories(dir.resolve("empty")))).find("Calibri", false,
                false).program().entry();
        assertTrue(FontLibrary.bundled(entry));
        FontProgram.Opened first = FontProgram.open(entry);
        first.close();
        try (FontProgram.Opened again = FontProgram.open(entry)) {
            assertSame(first.font(), again.font());
        }
    }

    @Test
    void fontsFromTheDocumentAreNeverKept() throws Exception {
        FontLibrary lib = FontLibrary.of(List.of(Files.createDirectories(dir.resolve("none"))))
                .withFonts(List.of(TestFonts.renamed("Doc Face")));
        FontEntry entry = lib.find("Doc Face", false, false).program().entry();
        FontProgram.Opened first = FontProgram.open(entry);
        assertNull(first.pooled());
        first.close();
        try (FontProgram.Opened again = FontProgram.open(entry)) {
            assertNotSame(first.font(), again.font());
        }
    }

    @Test
    void documentsMadeOneAfterAnotherWithAKeptFontAreBothRight() throws Exception {
        Path fonts = Files.createDirectories(dir.resolve("docs"));
        Files.write(fonts.resolve("Kept.ttf"), TestFonts.renamed("Kept Face"));
        FontLibrary lib = FontLibrary.of(List.of(fonts));
        FontFace face = lib.find("Kept Face", false, false);
        for (String text : List.of("First document", "Second one, other glyphs: xyzQ")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (PdfOutput out = new PdfOutput(lib)) {
                try (PdfCanvas c = out.newPage(300, 200)) {
                    c.text(text, 20, 50, TextStyle.of(face, 12));
                }
                out.save(bytes);
            }
            try (PDDocument pdf = Loader.loadPDF(bytes.toByteArray())) {
                assertEquals(text, new PDFTextStripper().getText(pdf).strip());
                assertTrue(pdf.getPage(0).getResources().getFontNames().iterator().hasNext());
            }
        }
    }
}
