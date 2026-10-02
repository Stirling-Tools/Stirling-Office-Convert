package stirling.software.officeconvert.topdf.flat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.testing.Fixtures;

class FlatOpcTest {

    @TempDir
    Path dir;

    private static String flat(byte[] zip, String contentType) throws IOException {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" standalone=\"yes\"?><?mso-application"
                + " progid=\"Word.Document\"?><pkg:package xmlns:pkg=\"http://schemas.microsoft.com/office/2006/xmlPackage\">");
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null;) {
                byte[] data = in.readAllBytes();
                String name = "/" + e.getName();
                if (name.equals("/[Content_Types].xml")) {
                    continue;
                }
                String type = name.endsWith(".rels") ? "application/vnd.openxmlformats-package.relationships+xml"
                        : name.equals("/word/document.xml") ? contentType : "application/xml";
                b.append("<pkg:part pkg:name=\"").append(name).append("\" pkg:contentType=\"").append(type).append("\">");
                if (name.endsWith(".xml") || name.endsWith(".rels")) {
                    String xml = new String(data, StandardCharsets.UTF_8).replaceFirst("^<\\?xml[^>]*\\?>", "");
                    b.append("<pkg:xmlData>").append(xml).append("</pkg:xmlData>");
                } else {
                    b.append("<pkg:binaryData>").append(Base64.getEncoder().encodeToString(data))
                            .append("</pkg:binaryData>");
                }
                b.append("</pkg:part>");
            }
        }
        return b.append("</pkg:package>").toString();
    }

    @Test
    void aWordXmlDocumentIsUnpackedAndConverted() throws IOException {
        String xml = flat(Fixtures.docx("Flat package text"),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml");
        Path in = Files.writeString(dir.resolve("report.xml"), xml);
        Path pdf = dir.resolve("report.pdf");
        OfficeToPdf.convert(in, pdf);
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            String t = new PDFTextStripper().getText(d);
            assertTrue(t.contains("Flat package text"), t);
        }
    }
}
