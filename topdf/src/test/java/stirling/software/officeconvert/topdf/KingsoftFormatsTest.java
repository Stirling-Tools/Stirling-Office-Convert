package stirling.software.officeconvert.topdf;

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
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KingsoftFormatsTest {

    @TempDir
    Path dir;

    private String text(String name, byte[] data) throws IOException {
        Path in = Files.write(dir.resolve(name), data);
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.convert(in, out);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private String refusal(String name, byte[] data) {
        Path in;
        try {
            in = Files.write(dir.resolve(name), data);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve(name + ".pdf"))).getMessage();
    }

    @Test
    void wpsWriterFilesSavedAsWordDocumentsConvert() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Kingsoft writer text");
            doc.write(bytes);
        }
        assertTrue(text("report.wps", bytes.toByteArray()).contains("Kingsoft writer text"));
        assertTrue(text("report.wpt", bytes.toByteArray()).contains("Kingsoft writer text"));
    }

    @Test
    void etSpreadsheetsConvertFromBothExcelFormats() throws IOException {
        ByteArrayOutputStream ooxml = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            wb.createSheet("S").createRow(0).createCell(0).setCellValue("Spreadsheet cell");
            wb.write(ooxml);
        }
        ByteArrayOutputStream binary = new ByteArrayOutputStream();
        try (HSSFWorkbook wb = new HSSFWorkbook()) {
            wb.createSheet("S").createRow(0).createCell(0).setCellValue("Binary cell");
            wb.write(binary);
        }
        assertTrue(text("book.et", ooxml.toByteArray()).contains("Spreadsheet cell"));
        assertTrue(text("book.ett", binary.toByteArray()).contains("Binary cell"));
    }

    @Test
    void dpsPresentationsConvertFromBothPowerPointFormats() throws IOException {
        ByteArrayOutputStream ooxml = new ByteArrayOutputStream();
        try (XMLSlideShow show = new XMLSlideShow()) {
            var box = show.createSlide().createTextBox();
            box.setText("Slide words");
            box.setAnchor(new java.awt.Rectangle(50, 50, 300, 50));
            show.write(ooxml);
        }
        ByteArrayOutputStream binary = new ByteArrayOutputStream();
        try (HSLFSlideShow show = new HSLFSlideShow()) {
            HSLFTextBox box = show.createSlide().createTextBox();
            box.setText("Old slide words");
            box.setAnchor(new java.awt.Rectangle(50, 50, 300, 50));
            show.write(binary);
        }
        assertTrue(text("deck.dps", ooxml.toByteArray()).contains("Slide words"));
        assertTrue(text("deck.dpt", binary.toByteArray()).contains("Old slide words"));
    }

    @Test
    void worksDocumentsNamedWpsAreStillRecognised() throws IOException {
        ByteArrayOutputStream ole = new ByteArrayOutputStream();
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            fs.createDocument(new ByteArrayInputStream(new byte[64]), "MatOST");
            fs.writeFilesystem(ole);
        }
        assertTrue(refusal("letter.wps", ole.toByteArray()).contains("Microsoft Works"));
    }

    @Test
    void olderKingsoftFilesAreRefusedPlainly() {
        String m = refusal("old.wps", "WPS binary document".getBytes());
        assertTrue(m.contains("older WPS Office"), m);
        assertTrue(refusal("old.et", new byte[] {9, 8, 7, 6, 5}).contains("save it as .docx, .xlsx or .pptx"));
    }

    @Test
    void proprietaryChineseFormatsAreRefusedWithTheirReason() {
        assertTrue(refusal("book.ceb", new byte[] {1, 2, 3}).contains("Founder Apabi"));
        assertTrue(refusal("paper.caj", new byte[] {1, 2, 3}).contains("CNKI"));
        assertTrue(refusal("letter.s72", new byte[] {1, 2, 3}).contains("Sursen"));
        assertTrue(refusal("page.pdg", new byte[] {1, 2, 3}).contains("SuperStar"));
        assertEquals(null, UnsupportedFormats.byExtension("wps"));
    }
}
