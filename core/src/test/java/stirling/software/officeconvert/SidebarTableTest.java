package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SidebarTableTest {

    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    @TempDir Path dir;

    @Test
    void tableBesideSidebarFloats() throws Exception {
        Path pdf = makePdf();
        Path docx = dir.resolve("cv.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        String xml;
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            xml = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(xml.contains("<w:txbxContent>"), "the sidebar is a text box");
        assertTrue(xml.contains("<w:tbl>"), "the grid is a table");
        assertTrue(xml.contains("<w:tblpPr "), "the table is set at its page place");
    }

    private Path makePdf() throws IOException {
        Path pdf = dir.resolve("cv.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            float h = PDRectangle.A4.getHeight();
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(0.15f, 0.1f, 0.1f);
                cs.addRect(0, 0, 200, h);
                cs.fill();
                cs.setNonStrokingColor(1f);
                String[] side = {"Alex Example", "Engineer", "Contact", "Hanoi, Vietnam", "Skills", "AutoCAD", "Public speaking"};
                for (int i = 0; i < side.length; i++) {
                    text(cs, 20, h - 60 - i * 40, 11, side[i]);
                }
                cs.setNonStrokingColor(0f);
                text(cs, 220, h - 50, 14, "Education");
                String[][] rows = {{"Degree", "School", "Years"}, {"Engineer", "Hanoi University", "2015 - 2020"},
                    {"Master", "Hanoi University", "2020 - 2022"}, {"Bachelor", "Economics University", "2021 - 2024"}};
                cs.setStrokingColor(0.5f);
                cs.setLineWidth(0.5f);
                float top = h - 70;
                for (int r = 0; r <= rows.length; r++) {
                    cs.moveTo(220, top - r * 22);
                    cs.lineTo(570, top - r * 22);
                }
                float[] xs = {220, 320, 470, 570};
                for (float x : xs) {
                    cs.moveTo(x, top);
                    cs.lineTo(x, top - rows.length * 22);
                }
                cs.stroke();
                for (int r = 0; r < rows.length; r++) {
                    for (int c = 0; c < 3; c++) {
                        text(cs, xs[c] + 4, top - r * 22 - 15, 10, rows[r][c]);
                    }
                }
                text(cs, 220, top - rows.length * 22 - 30, 14, "Work experience");
                String[] work = {"Intern at LG Academy, designing air conditioning systems for offices.",
                    "Technical staff at a heat engineering company, maintaining cooling plants.",
                    "Research on solar air conditioning with the university's energy lab."};
                for (int i = 0; i < work.length; i++) {
                    text(cs, 220, top - rows.length * 22 - 55 - i * 16, 10, work[i]);
                }
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static void text(PDPageContentStream cs, float x, float y, float size, String s) throws IOException {
        cs.beginText();
        cs.setFont(FONT, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }
}
