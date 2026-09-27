package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertTrue;

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

class DoubleBorderTest {

    private static final Pattern SHAPE = Pattern.compile(
            "<wp:extent cx=\"(\\d+)\" cy=\"(\\d+)\"/>(?:(?!</wp:anchor>).)*?<wps:spPr>(?:(?!</wps:spPr>).)*?<a:srgbClr val=\"000000\"",
            Pattern.DOTALL);

    @TempDir Path dir;

    @Test
    void doubleFramedBarKeepsItsGrey() throws Exception {
        Path pdf = dir.resolve("bar.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(0.894f);
                cs.addRect(75, 625.24f, 481.85f, 22.65f);
                cs.fill();
                cs.setNonStrokingColor(0f);
                float[] in = {0, 0.24f, 0.48f, 0.72f};
                for (float d : in) {
                    cs.addRect(74.64f + d, 624.88f + d, 482.57f - 2 * d, 23.37f - 2 * d);
                }
                cs.fillEvenOdd();
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 10);
                cs.newLineAtOffset(220, 632);
                cs.showText("1. PRODUCT IDENTIFICATION");
                cs.endText();
            }
            doc.save(pdf.toFile());
        }
        Path docx = dir.resolve("bar.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        String xml;
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            xml = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(xml.contains("E4E4E4"), "the bar is drawn grey");
        Matcher m = SHAPE.matcher(xml);
        while (m.find()) {
            long thin = Math.min(Long.parseLong(m.group(1)), Long.parseLong(m.group(2)));
            assertTrue(thin <= 2 * 12700, "black is only the thin frame, not the bar: " + m.group(1) + "x" + m.group(2));
        }
    }
}
