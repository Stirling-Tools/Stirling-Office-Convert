package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NamelessFontTest {

    private static final String LETTERS = "Iilnsu";

    @TempDir Path dir;

    @Test
    void plainStemsReadAsSans() throws Exception {
        Path pdf = makePdf();
        Path docx = dir.resolve("nameless.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        String xml;
        String styles;
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            xml = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
            styles = new String(zip.getInputStream(zip.getEntry("word/styles.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(xml.contains("sun"), "the text is read");
        assertFalse(xml.contains("Times New Roman"), "no serif stand-in in the text");
        assertTrue(styles.contains("Arial") || xml.contains("Arial"), "a sans stand-in");
    }

    private Path makePdf() throws IOException {
        Path pdf = dir.resolve("nameless.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDType3Font font = new PDType3Font(fontDict(doc));
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("F1"), font);
            page.setResources(resources);
            StringBuilder content = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                content.append("BT /F1 12 Tf 72 ").append(720 - i * 16)
                        .append(" Td (sun Iil nil sun lulls Iilnsu sun nil sun lulls sun nil Iil) Tj ET ");
            }
            PDStream stream = new PDStream(doc);
            try (OutputStream out = stream.createOutputStream()) {
                out.write(content.toString().getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(stream);
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static COSDictionary fontDict(PDDocument doc) throws IOException {
        COSDictionary font = new COSDictionary();
        font.setItem(COSName.TYPE, COSName.FONT);
        font.setItem(COSName.SUBTYPE, COSName.TYPE3);
        font.setItem(COSName.FONT_BBOX, numbers(0, -200, 800, 800));
        font.setItem(COSName.FONT_MATRIX, numbers(0.001f, 0, 0, 0.001f, 0, 0));
        COSDictionary procs = new COSDictionary();
        COSArray differences = new COSArray();
        int first = LETTERS.chars().min().orElseThrow();
        int last = LETTERS.chars().max().orElseThrow();
        COSArray widths = new COSArray();
        for (int c = first; c <= last; c++) {
            widths.add(COSInteger.get(LETTERS.indexOf(c) >= 0 ? 500 : 0));
        }
        for (char c : LETTERS.toCharArray()) {
            differences.add(COSInteger.get(c));
            differences.add(COSName.getPDFName(String.valueOf(c)));
            procs.setItem(String.valueOf(c), glyph(doc, c));
        }
        COSDictionary encoding = new COSDictionary();
        encoding.setItem(COSName.TYPE, COSName.ENCODING);
        encoding.setItem(COSName.DIFFERENCES, differences);
        font.setItem(COSName.ENCODING, encoding);
        font.setItem(COSName.CHAR_PROCS, procs);
        font.setInt(COSName.FIRST_CHAR, first);
        font.setInt(COSName.LAST_CHAR, last);
        font.setItem(COSName.WIDTHS, widths);
        font.setItem(COSName.RESOURCES, new COSDictionary());
        return font;
    }

    private static COSStream glyph(PDDocument doc, char c) throws IOException {
        String draw = c == 'l' || c == 'I' ? "200 0 80 700 re f" : "80 0 340 480 re f";
        COSStream s = doc.getDocument().createCOSStream();
        try (OutputStream out = s.createOutputStream()) {
            out.write(("500 0 0 -200 500 800 d1 " + draw).getBytes(StandardCharsets.US_ASCII));
        }
        return s;
    }

    private static COSArray numbers(float... v) {
        COSArray a = new COSArray();
        for (float f : v) {
            a.add(new COSFloat(f));
        }
        return a;
    }
}
