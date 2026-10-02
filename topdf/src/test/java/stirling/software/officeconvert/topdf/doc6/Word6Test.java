package stirling.software.officeconvert.topdf.doc6;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;

class Word6Test {

    @TempDir
    Path dir;

    private static HWPFDocument upgraded(byte[] word6) throws IOException {
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(word6))) {
            return new HWPFDocument(Word6Upgrade.upgrade(fs.getRoot()).fs().getRoot());
        }
    }

    @Test
    void textRunsParagraphsAndStylesBecomeAWord97Document() throws IOException {
        HWPFDocument doc = upgraded(new Word6Fixture().build());
        Range r = doc.getRange();
        assertEquals("Hello Word 6\r", r.getParagraph(0).text());
        assertEquals("Second paragraph\r", r.getParagraph(1).text());
        assertEquals(1, r.getParagraph(0).getJustification());
        assertTrue(r.getParagraph(0).getCharacterRun(0).isBold());
        assertEquals("Hello", r.getParagraph(0).getCharacterRun(0).text());
        assertTrue(!r.getParagraph(1).getCharacterRun(0).isBold());
        assertEquals("Normal", doc.getStyleSheet().getStyleDescription(0).getName());
        assertEquals(24, r.getParagraph(1).getCharacterRun(0).getFontSize());
        assertEquals("Times New Roman", r.getParagraph(0).getCharacterRun(0).getFontName());
    }

    @Test
    void textInTheDocumentsCodePageIsDecoded() throws IOException {
        HWPFDocument doc = upgraded(new Word6Fixture().russian().text("Привет", "мир").build());
        assertEquals("Привет\r", doc.getRange().getParagraph(0).text());
    }

    @Test
    void aWord95DocumentConvertsWithItsSectionLayout() throws IOException {
        Path in = Files.write(dir.resolve("memo.doc"), new Word6Fixture().landscape().build());
        Path out = dir.resolve("memo.pdf");
        OfficeToPdf.convert(in, out);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            String t = new PDFTextStripper().getText(d);
            assertTrue(t.contains("Hello Word 6") && t.contains("Second paragraph"), t);
            assertTrue(d.getPage(0).getMediaBox().getWidth() > d.getPage(0).getMediaBox().getHeight());
        }
    }
}
