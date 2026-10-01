package stirling.software.officeconvert.topdf.doc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;

class DocTest {

    @TempDir
    Path dir;

    static String part(byte[] doc, String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(doc))) {
            DocPackage.write(fs.getRoot(), out);
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null;) {
                if (e.getName().equals(name)) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    static String body(byte[] doc) throws IOException {
        return part(doc, "word/document.xml");
    }

    String pdfText(byte[] doc, String name) throws IOException {
        Path in = dir.resolve(name);
        Files.write(in, doc);
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.convert(in, out);
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            return new PDFTextStripper().getText(pdf);
        }
    }

    @Test
    void convertsTextAndRunFormatting() throws IOException {
        byte[] doc = new WordFixture()
                .para(List.of(WordFixture.run("Plain "), WordFixture.run("bold", Sprms.bold(), Sprms.size(28))), 0,
                        Sprms.jc(1))
                .para("Second paragraph").build();
        String xml = body(doc);
        assertTrue(xml.contains("<w:t xml:space=\"preserve\">Plain </w:t>"), xml);
        assertTrue(xml.contains("<w:b/>"), xml);
        assertTrue(xml.contains("<w:sz w:val=\"28\"/>"), xml);
        assertTrue(xml.contains("<w:jc w:val=\"center\"/>"), xml);
        String text = pdfText(doc, "basic.doc");
        assertTrue(text.contains("Plain bold"), text);
        assertTrue(text.contains("Second paragraph"), text);
    }
}
