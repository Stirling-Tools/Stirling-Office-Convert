package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlankPagesTest {

    @TempDir Path dir;

    private Path pdf(int pages, int withText) throws IOException {
        Path out = dir.resolve("blank-" + pages + "-" + withText + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                if (i == withText) {
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.beginText();
                        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        cs.newLineAtOffset(72, 700);
                        cs.showText("The only text in the document.");
                        cs.endText();
                    }
                }
            }
            doc.save(out.toFile());
        }
        return out;
    }

    private static String document(Path docx) throws IOException {
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            return new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int count(String xml, String regex) {
        Matcher m = Pattern.compile(regex).matcher(xml);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    @Test
    void documentOfBlankPagesKeepsEveryPage() throws IOException {
        Path docx = dir.resolve("blank.docx");
        PdfToDocx.convert(pdf(12, -1), docx, PdfToDocx.Options.defaults());

        String xml = document(docx);
        assertEquals(11, count(xml, "<w:pageBreakBefore/>"));
        assertEquals(12, count(xml, "w:name=\"_Pg\\d+\""));
        assertTrue(xml.contains("w:w=\"11906\" w:h=\"16838\""), "A4 page size kept");
    }

    @Test
    void textAfterBlankPagesStaysOnItsOwnPage() throws IOException {
        Path docx = dir.resolve("middle.docx");
        PdfToDocx.convert(pdf(5, 3), docx, PdfToDocx.Options.defaults());

        String xml = document(docx);
        int text = xml.indexOf("The only text in the document.");
        int page4 = xml.indexOf("w:name=\"_Pg4\"");
        int page5 = xml.indexOf("w:name=\"_Pg5\"");
        assertTrue(page4 >= 0 && page4 < text && text < page5, "text sits on page 4, after its bookmark");
        assertEquals(5, count(xml, "w:name=\"_Pg\\d+\""));
    }
}
