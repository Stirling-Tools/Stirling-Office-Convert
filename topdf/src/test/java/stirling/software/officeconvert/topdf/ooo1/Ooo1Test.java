package stirling.software.officeconvert.topdf.ooo1;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;

class Ooo1Test {

    @TempDir
    Path dir;

    private static final String NS = "xmlns:office=\"http://openoffice.org/2000/office\" xmlns:style="
            + "\"http://openoffice.org/2000/style\" xmlns:text=\"http://openoffice.org/2000/text\" xmlns:table="
            + "\"http://openoffice.org/2000/table\" xmlns:fo=\"http://www.w3.org/1999/XSL/Format\"";

    private static final String DOCTYPE = "<!DOCTYPE office:document-content PUBLIC \"-//OpenOffice.org//DTD"
            + " OfficeDocument 1.0//EN\" \"office.dtd\">";

    private static byte[] zip(String mimetype, String content, String styles) throws IOException {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("mimetype", mimetype);
        parts.put("content.xml", content);
        parts.put("styles.xml", styles);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> e : parts.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static String styles() {
        return "<?xml version=\"1.0\"?>" + DOCTYPE.replace("document-content", "document-styles")
                + "<office:document-styles " + NS + "><office:automatic-styles><style:page-master style:name=\"pm1\">"
                + "<style:properties fo:page-width=\"29.7cm\" fo:page-height=\"21cm\" fo:margin-left=\"2cm\""
                + " fo:margin-right=\"2cm\" fo:margin-top=\"2cm\" fo:margin-bottom=\"2cm\"/></style:page-master>"
                + "</office:automatic-styles><office:master-styles><style:master-page style:name=\"Standard\""
                + " style:page-master-name=\"pm1\"/></office:master-styles></office:document-styles>";
    }

    private static String writer() {
        return "<?xml version=\"1.0\"?>" + DOCTYPE + "<office:document-content " + NS + " office:class=\"text\">"
                + "<office:automatic-styles><style:style style:name=\"P1\" style:family=\"paragraph\">"
                + "<style:properties fo:font-weight=\"bold\" fo:font-size=\"16pt\"/></style:style>"
                + "</office:automatic-styles><office:body><text:h text:level=\"1\">Old heading</text:h>"
                + "<text:p text:style-name=\"P1\">Bold paragraph</text:p><text:ordered-list><text:list-item><text:p>"
                + "Listed item</text:p></text:list-item></text:ordered-list></office:body></office:document-content>";
    }

    @Test
    void anOpenOfficeOrgTextDocumentIsReadAsOpenDocument() throws IOException {
        Path in = Files.write(dir.resolve("letter.sxw"), zip("application/vnd.sun.xml.writer", writer(), styles()));
        Path out = dir.resolve("letter.pdf");
        OfficeToPdf.convert(in, out);
        StringBuilder bold = new StringBuilder();
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertTrue(d.getPage(0).getMediaBox().getWidth() > d.getPage(0).getMediaBox().getHeight());
            PDFTextStripper s = new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition t) {
                    if (t.getFont().getName().toLowerCase().contains("bold")) {
                        bold.append(t.getUnicode());
                    }
                    super.processTextPosition(t);
                }
            };
            String t = s.getText(d);
            assertTrue(t.contains("Old heading") && t.contains("Bold paragraph") && t.contains("Listed item"), t);
        }
        assertTrue(bold.toString().contains("Boldparagraph") || bold.toString().contains("Bold paragraph"),
                bold.toString());
    }

    @Test
    void spreadsheetCellValuesMoveToTheOfficeNamespace() throws IOException {
        String content = "<?xml version=\"1.0\"?>" + DOCTYPE + "<office:document-content " + NS
                + " office:class=\"spreadsheet\"><office:body><table:table table:name=\"Sheet1\"><table:table-row>"
                + "<table:table-cell table:value-type=\"float\" table:value=\"42.5\"><text:p>42.5</text:p>"
                + "</table:table-cell></table:table-row></table:table></office:body></office:document-content>";
        Path in = Files.write(dir.resolve("book.sxc"), zip("application/vnd.sun.xml.calc", content, styles()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Ooo1Package.write(in, Ooo1Package.Kind.SPREADSHEET, out);
        String odf = null;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null;) {
                if (e.getName().equals("content.xml")) {
                    odf = new String(z.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertTrue(odf.contains("office:value-type=\"float\"") && odf.contains("<office:spreadsheet>")
                && odf.contains("urn:oasis:names:tc:opendocument:xmlns:table:1.0"), odf);
        Path pdf = dir.resolve("book.pdf");
        OfficeToPdf.convert(in, pdf);
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            assertTrue(new PDFTextStripper().getText(d).contains("42.5"));
        }
    }
}
