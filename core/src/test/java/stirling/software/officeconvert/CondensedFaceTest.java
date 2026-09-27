package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CondensedFaceTest {

    private static final float CONDENSED = 0.85f;

    @TempDir Path dir;

    @Test
    void narrowArialIsScaledToThePage() throws Exception {
        Path pdf = dir.resolve("condensed.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("F1"), new PDTrueTypeFont(narrowArial()));
            page.setResources(resources);
            StringBuilder content = new StringBuilder();
            String line = "Numerous studies have confirmed that low acidity and a healthy flora is typical";
            for (int i = 0; i < 20; i++) {
                content.append("BT /F1 10 Tf 72 ").append(760 - i * 12).append(" Td (").append(line).append(") Tj ET ");
            }
            PDStream stream = new PDStream(doc);
            try (OutputStream out = stream.createOutputStream()) {
                out.write(content.toString().getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(stream);
            doc.save(pdf.toFile());
        }
        Path docx = dir.resolve("condensed.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        String xml;
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            xml = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(xml.contains("Numerous studies"), "the text is read");
        Matcher m = Pattern.compile("<w:w w:val=\"(\\d+)\"/>").matcher(xml);
        assertTrue(m.find(), "Word's Arial is given a character scale");
        int scale = Integer.parseInt(m.group(1));
        assertTrue(Math.abs(scale - 100 * CONDENSED) <= 2, "scaled to the page's widths: " + scale);
    }

    private static COSDictionary narrowArial() throws IOException {
        PDType1Font helvetica = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        COSArray widths = new COSArray();
        for (int c = 32; c <= 126; c++) {
            widths.add(COSInteger.get(Math.round(helvetica.getWidth(c) * CONDENSED)));
        }
        COSDictionary descriptor = new COSDictionary();
        descriptor.setItem(COSName.TYPE, COSName.FONT_DESC);
        descriptor.setName(COSName.FONT_NAME, "Arial");
        descriptor.setInt(COSName.FLAGS, 32);
        descriptor.setItem(COSName.FONT_BBOX, numbers(-665, -325, 2000, 1040));
        descriptor.setInt(COSName.ITALIC_ANGLE, 0);
        descriptor.setInt(COSName.ASCENT, 905);
        descriptor.setInt(COSName.DESCENT, -212);
        descriptor.setInt(COSName.CAP_HEIGHT, 716);
        descriptor.setInt(COSName.STEM_V, 80);
        COSDictionary font = new COSDictionary();
        font.setItem(COSName.TYPE, COSName.FONT);
        font.setItem(COSName.SUBTYPE, COSName.TRUE_TYPE);
        font.setName(COSName.BASE_FONT, "Arial");
        font.setInt(COSName.FIRST_CHAR, 32);
        font.setInt(COSName.LAST_CHAR, 126);
        font.setItem(COSName.WIDTHS, widths);
        font.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING);
        font.setItem(COSName.FONT_DESC, descriptor);
        return font;
    }

    private static COSArray numbers(float... v) {
        COSArray a = new COSArray();
        for (float f : v) {
            a.add(new COSFloat(f));
        }
        return a;
    }
}
