package stirling.software.officeconvert;

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

class BorderlessTableTest {

    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final String[][] ROWS = {
        {"Plan", "Price", "Users", "Storage"},
        {"Starter", "9.00", "1", "10 GB"},
        {"Team", "29.00", "10", "100 GB"},
        {"Business", "59.00", "50", "1 TB"},
        {"Enterprise", "99.00", "500", "10 TB"},
    };

    @TempDir Path dir;

    @Test
    void rowTextKeepsItsGapAbove() throws Exception {
        Path pdf = makePdf();
        Path docx = dir.resolve("table.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        String xml;
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            xml = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(xml.contains("<w:tbl>"), "the grid is a table");
        Matcher m = Pattern.compile("<w:tc>.*?</w:tc>").matcher(xml);
        int checked = 0;
        while (m.find()) {
            String cell = m.group();
            if (!cell.contains(">Team<") && !cell.contains(">Business<")) {
                continue;
            }
            Matcher before = Pattern.compile("w:before=\"(\\d+)\"").matcher(cell);
            int twips = before.find() ? Integer.parseInt(before.group(1)) : 0;
            assertTrue(twips >= 80, "space above a row's text in twips: " + twips);
            checked++;
        }
        assertTrue(checked == 2, "both body cells were found");
    }

    private Path makePdf() throws IOException {
        Path pdf = dir.resolve("table.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, 72, 760, 11, "The plans below differ in price, the number of users and the storage they include.");
                for (int r = 0; r < ROWS.length; r++) {
                    for (int c = 0; c < ROWS[r].length; c++) {
                        text(cs, 72 + c * 110, 720 - r * 26, 10, ROWS[r][c]);
                    }
                }
                text(cs, 72, 560, 11, "Prices are per month and include every feature of the plan below them.");
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
