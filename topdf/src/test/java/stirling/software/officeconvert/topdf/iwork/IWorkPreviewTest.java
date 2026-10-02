package stirling.software.officeconvert.topdf.iwork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.testing.Fixtures;

class IWorkPreviewTest {

    @TempDir
    Path dir;

    private static byte[] zip(Map<String, byte[]> parts) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : parts.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] preview(String... pages) throws IOException {
        try (PDDocument d = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : pages) {
                PDPage p = new PDPage(PDRectangle.A4);
                d.addPage(p);
                try (PDPageContentStream c = new PDPageContentStream(d, p)) {
                    c.beginText();
                    c.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    c.newLineAtOffset(72, 700);
                    c.showText(text);
                    c.endText();
                }
            }
            d.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void aPagesDocumentIsDrawnFromItsPdfPreviewAndSaysSo() throws IOException {
        byte[] pages = zip(Map.of("index.xml", "<sl:document/>".getBytes(), "QuickLook/Preview.pdf",
                preview("First page of the report", "Second page")));
        Path in = Files.write(dir.resolve("report.pages"), pages);
        Path out = dir.resolve("report.pdf");
        OfficeToPdf.Result r = OfficeToPdf.convert(in, out);
        assertEquals(2, r.pages());
        assertTrue(r.warnings().toString().contains("PDF preview"), r.warnings().toString());
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            String t = new PDFTextStripper().getText(d);
            assertTrue(t.contains("First page of the report") && t.contains("Second page"), t);
        }
    }

    @Test
    void aKeynoteWithOnlyAPreviewPictureIsMarkedIncomplete() throws IOException {
        byte[] key = zip(Map.of("Index/Document.iwa", new byte[] {1, 2, 3}, "preview.jpg",
                Fixtures.png(40, 30, Color.BLUE)));
        Path in = Files.write(dir.resolve("deck.key"), key);
        OfficeToPdf.Result r = OfficeToPdf.convert(in, dir.resolve("deck.pdf"));
        assertTrue(r.truncated());
        assertTrue(r.warnings().toString().contains("first page only"), r.warnings().toString());
    }

    @Test
    void anIWorkFileWithoutAPreviewIsRefusedPlainly() throws IOException {
        Path in = Files.write(dir.resolve("table.numbers"), zip(Map.of("index.xml", "<ls:document/>".getBytes())));
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("table.pdf")));
        assertTrue(e.getMessage().contains("holds no preview"), e.getMessage());
    }
}
