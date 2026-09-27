package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RobustnessTest {

    @TempDir Path dir;

    private Path pdf(PDRectangle size, String content) throws IOException {
        Path out = dir.resolve("in.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(size);
            PDResources res = new PDResources();
            res.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            COSDictionary broken = new COSDictionary();
            broken.setItem(COSName.TYPE, COSName.FONT);
            broken.setItem(COSName.SUBTYPE, COSName.TYPE0);
            broken.setName(COSName.BASE_FONT, "Broken");
            broken.setItem(COSName.ENCODING, COSName.IDENTITY_H);
            res.getCOSObject().getCOSDictionary(COSName.FONT).setItem(COSName.getPDFName("F2"), broken);
            page.setResources(res);
            PDStream stream = new PDStream(doc);
            try (OutputStream os = stream.createOutputStream()) {
                os.write(content.getBytes(StandardCharsets.ISO_8859_1));
            }
            page.setContents(stream);
            doc.addPage(page);
            doc.save(out.toFile());
        }
        return out;
    }

    private String convert(Path pdf) throws IOException {
        Path docx = dir.resolve("out.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            return new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void brokenFontLosesOnlyItsOwnText() throws IOException {
        String xml = convert(pdf(PDRectangle.A4, "BT /F1 12 Tf 72 700 Td (Hello) Tj ET\n"
                + "BT /F2 12 Tf 72 680 Td <0001> Tj ET\n"
                + "BT /F1 12 Tf 72 660 Td (World) Tj ET\n"));
        assertTrue(xml.contains("Hello") && xml.contains("World"), "text around the broken font is kept");
    }

    @Test
    void truncatedStreamKeepsWhatCameBefore() throws IOException {
        String xml = convert(pdf(PDRectangle.A4, "BT /F1 12 Tf 72 700 Td (Before the break) Tj ET\nBI /W 1 /H 1 Infinity"));
        assertTrue(xml.contains("Before the break"));
    }

    @Test
    void pageBeyondWordsLimitIsShrunkToFit() throws IOException {
        String xml = convert(pdf(PDRectangle.A0, "BT /F1 40 Tf 100 3000 Td (Poster title) Tj ET"));
        Matcher m = Pattern.compile("<w:pgSz w:w=\"(\\d+)\" w:h=\"(\\d+)\"").matcher(xml);
        assertTrue(m.find());
        assertEquals(31680, Integer.parseInt(m.group(2)), "height capped at 22 inches");
        assertTrue(Integer.parseInt(m.group(1)) < 31680);
        assertTrue(xml.contains("Poster title"));
    }

    @Test
    void optionsHideThePasswordAndRejectNonsense() {
        PdfToDocx.Options o = new PdfToDocx.Options(0, 0, true, 150f, "secret");
        assertFalse(o.toString().contains("secret"));
        assertThrows(IllegalArgumentException.class, () -> new PdfToDocx.Options(-1, 0, true, 150f, null));
        assertThrows(IllegalArgumentException.class, () -> new PdfToDocx.Options(0, 0, true, 5000f, null));
    }
}
