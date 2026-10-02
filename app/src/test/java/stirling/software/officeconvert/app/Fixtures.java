package stirling.software.officeconvert.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

final class Fixtures {

    static final String DOCX_MAIN = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";

    private Fixtures() {}

    static byte[] docx(String text, int paragraphs) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < paragraphs; i++) {
                doc.createParagraph().createRun().setText(text + " " + (i + 1));
            }
            doc.write(out);
            return out.toByteArray();
        }
    }

    static byte[] longDocx(int paragraphs) throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < paragraphs; i++) {
            body.append("<w:p><w:r><w:t>Paragraph ").append(i).append(" of a long document that runs past the limit</w:t></w:r></w:p>");
        }
        return zip("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"" + DOCX_MAIN + "\"/></Types>",
                "_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/"
                + "officeDocument\" Target=\"word/document.xml\"/></Relationships>",
                "word/document.xml", "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body>" + body + "</w:body></w:document>");
    }

    static byte[] pptx(String text) throws IOException {
        try (XMLSlideShow ppt = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSLFTextBox box = ppt.createSlide().createTextBox();
            box.setAnchor(new java.awt.geom.Rectangle2D.Double(60, 60, 500, 80));
            box.setText(text);
            ppt.write(out);
            return out.toByteArray();
        }
    }

    static byte[] xlsx(String text) throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = book.createSheet("Data");
            for (int r = 0; r < 5; r++) {
                sheet.createRow(r).createCell(0).setCellValue(text + " " + r);
                sheet.getRow(r).createCell(1).setCellValue(r * 1.5);
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    static byte[] xls(String text) throws IOException {
        try (HSSFWorkbook book = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            HSSFSheet sheet = book.createSheet("Data");
            for (int r = 0; r < 5; r++) {
                sheet.createRow(r).createCell(0).setCellValue(text + " " + r);
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    static byte[] ppt(String text) throws IOException {
        try (HSLFSlideShow ppt = new HSLFSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            HSLFTextBox box = ppt.createSlide().createTextBox();
            box.setAnchor(new java.awt.geom.Rectangle2D.Double(60, 60, 500, 80));
            box.setText(text);
            ppt.write(out);
            return out.toByteArray();
        }
    }

    static byte[] pdf(String text) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                cs.newLineAtOffset(72, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    static byte[] retype(byte[] zip, String from, String to) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip));
                ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                byte[] data = in.readAllBytes();
                if (e.getName().equals("[Content_Types].xml")) {
                    data = new String(data, StandardCharsets.UTF_8).replace(from, to).getBytes(StandardCharsets.UTF_8);
                }
                out.putNextEntry(new ZipEntry(e.getName()));
                out.write(data);
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    static byte[] zip(String... namesAndTexts) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (int i = 0; i < namesAndTexts.length; i += 2) {
                out.putNextEntry(new ZipEntry(namesAndTexts[i]));
                out.write(namesAndTexts[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
