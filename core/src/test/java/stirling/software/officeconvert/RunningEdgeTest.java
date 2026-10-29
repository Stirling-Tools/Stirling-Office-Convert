package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunningEdgeTest {

    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    private static final String LINE = "Body text that runs across the whole measure of the page to set its edge.";

    @TempDir Path dir;

    private static float width(String s, float size) throws IOException {
        return FONT.getStringWidth(s) / 1000f * size;
    }

    private Path pdf(String header, float headerRight) throws IOException {
        Path pdf = dir.resolve("two.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int p = 0; p < 2; p++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    text(cs, headerRight - width(header, 9), 750, header, 9);
                    float y = 700;
                    for (int i = 0; i < 10; i++) {
                        text(cs, 72, y, LINE, 11);
                        y -= 14;
                    }
                }
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static void text(PDPageContentStream cs, float x, float y, String s, float size) throws IOException {
        cs.beginText();
        cs.setFont(FONT, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    private String header(Path pdf) throws Exception {
        Path docx = dir.resolve("two.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        StringBuilder out = new StringBuilder();
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            for (ZipEntry e : zip.stream().toList()) {
                if (e.getName().startsWith("word/header")) {
                    out.append(new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return out.toString();
    }

    @Test
    void aRightAlignedHeaderShortOfTheBodyEdgeKeepsItsEdge() throws Exception {
        float edge = 72 + width(LINE, 11);
        String xml = header(pdf("Northwind Traders - Annual Report", edge - 5));
        assertTrue(xml.contains("<w:jc w:val=\"right\"/>"), xml);
        assertTrue(xml.matches("(?s).*<w:ind w:left=\"0\" w:right=\"1[23][0-9]\".*"), xml);
    }
}
