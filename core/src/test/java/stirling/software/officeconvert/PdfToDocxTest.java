package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfToDocxTest {

    private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    private static final String[] BODY = {
        "Revenue grew in every region this quarter, led by strong demand for the new",
        "product line and steady renewals across our existing customer base. Costs",
        "stayed flat, so operating margin improved by three points over last year.",
    };

    @TempDir static Path dir;
    static Path pdf;

    @BeforeAll
    static void makePdf() throws IOException {
        pdf = dir.resolve("report.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage first = new PDPage(PDRectangle.LETTER);
            doc.addPage(first);
            try (PDPageContentStream cs = new PDPageContentStream(doc, first)) {
                text(cs, BOLD, 20, 72, 700, "Quarterly Report");
                float y = 660;
                for (String line : BODY) {
                    text(cs, REGULAR, 11, 72, y, line);
                    y -= 14;
                }
                y -= 14;
                for (String item : new String[] {"First point", "Second point", "Third point"}) {
                    text(cs, REGULAR, 11, 90, y, "•");
                    text(cs, REGULAR, 11, 108, y, item);
                    y -= 14;
                }
                table(cs, 72, y - 20);
            }
            PDPage second = new PDPage(PDRectangle.LETTER);
            doc.addPage(second);
            try (PDPageContentStream cs = new PDPageContentStream(doc, second)) {
                text(cs, REGULAR, 11, 72, 700, "Second page text stands alone here.");
            }
            doc.save(pdf.toFile());
        }
    }

    private static void text(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String s)
            throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    private static void table(PDPageContentStream cs, float x, float top) throws IOException {
        String[][] cells = {{"Region", "Sales", "Growth"}, {"North", "1,200", "8%"}, {"South", "950", "5%"}};
        float[] cols = {x, x + 150, x + 270, x + 390};
        float row = 20;
        for (int r = 0; r < cells.length; r++) {
            for (int c = 0; c < 3; c++) {
                text(cs, r == 0 ? BOLD : REGULAR, 11, cols[c] + 5, top - row * r - 14, cells[r][c]);
            }
        }
        cs.setLineWidth(0.75f);
        for (int r = 0; r <= cells.length; r++) {
            cs.moveTo(cols[0], top - row * r);
            cs.lineTo(cols[3], top - row * r);
        }
        for (float cx : cols) {
            cs.moveTo(cx, top);
            cs.lineTo(cx, top - row * cells.length);
        }
        cs.stroke();
    }

    private static Map<String, String> convert(PdfToDocx.Options options, String name) throws Exception {
        Path docx = dir.resolve(name);
        PdfToDocx.convert(pdf, docx, options);
        Map<String, String> parts = new HashMap<>();
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            for (ZipEntry e : java.util.Collections.list(zip.entries())) {
                byte[] bytes = zip.getInputStream(e).readAllBytes();
                parts.put(e.getName(), new String(bytes, StandardCharsets.UTF_8));
                if (e.getName().endsWith(".xml") || e.getName().endsWith(".rels")) {
                    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
                }
            }
        }
        return parts;
    }

    @Test
    void writesAWellFormedPackage() throws Exception {
        Map<String, String> parts = convert(PdfToDocx.Options.defaults(), "all.docx");
        assertNotNull(parts.get("[Content_Types].xml"));
        assertNotNull(parts.get("_rels/.rels"));
        assertNotNull(parts.get("word/styles.xml"));
        assertNotNull(parts.get("word/_rels/document.xml.rels"));
    }

    @Test
    void keepsTextHeadingsListsAndTables() throws Exception {
        Map<String, String> parts = convert(PdfToDocx.Options.defaults(), "structure.docx");
        String body = parts.get("word/document.xml");
        String plain = body.replaceAll("<[^>]+>", "");

        assertTrue(plain.contains("Quarterly Report"));
        assertTrue(plain.contains("Second page text stands alone here."));
        for (String line : BODY) {
            assertTrue(plain.replace(" ", "").contains(line.replace(" ", "")), line);
        }
        assertTrue(body.matches("(?s).*<w:pStyle w:val=\"(Heading1|Title)\"/>.*Quarterly Report.*"));

        assertTrue(body.contains("<w:numPr>"), "bullets should become a Word list");
        assertNotNull(parts.get("word/numbering.xml"));
        assertFalse(plain.contains("•"), "list bullets should come from numbering, not text");

        assertTrue(body.contains("<w:tbl>"), "ruled grid should become a table");
        String table = body.substring(body.indexOf("<w:tbl>"), body.indexOf("</w:tbl>"));
        assertEquals(3, table.split("<w:tr[ >]", -1).length - 1);
        for (String cell : new String[] {"Region", "North", "1,200", "5%"}) {
            assertTrue(table.contains(cell), cell);
        }
    }

    @Test
    void honoursThePageRange() throws Exception {
        Map<String, String> parts = convert(new PdfToDocx.Options(2, 2, true, 150, null), "page2.docx");
        String plain = parts.get("word/document.xml").replaceAll("<[^>]+>", "");
        assertTrue(plain.contains("Second page text stands alone here."));
        assertFalse(plain.contains("Quarterly Report"));
    }
}
