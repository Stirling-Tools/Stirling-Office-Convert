package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultiPageTableTest {

    @TempDir Path dir;

    @Test
    void aTableBrokenBetweenRowsStaysOneTableWithOneHeader() throws Exception {
        Path pdf = twoPageTable();
        Path docx = dir.resolve("t.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        String body = entry(docx, "word/document.xml");
        assertEquals(1, count(body, "<w:tbl>"), "one table");
        assertEquals(1, count(body, "<w:tblHeader/>"), "the repeated header is a header row");
        assertEquals(1, count(body, ">Name<"), "the header text is not copied into the body");
        assertEquals(0, count(body, "<w:pageBreakBefore/>"));
        Path odt = dir.resolve("t.odt");
        PdfToOdt.convert(pdf, odt, PdfToDocx.Options.defaults());
        String content = entry(odt, "content.xml");
        assertEquals(1, count(content, "<table:table "), "one ODT table");
        assertTrue(content.contains("<table:table-header-rows>"), "ODT header rows");
        Path rtf = dir.resolve("t.rtf");
        PdfToRtf.convert(pdf, rtf, PdfToDocx.Options.defaults());
        String text = Files.readString(rtf, StandardCharsets.US_ASCII);
        assertTrue(text.contains("\\trhdr"), "RTF header row");
        assertEquals(1, count(text, "Name}"), "the RTF header text appears once");
        assertEquals(0, count(text, "\\pagebb"));
    }

    private Path twoPageTable() throws IOException {
        Path pdf = dir.resolve("table.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            int row = 0;
            for (int pg = 0; pg < 2; pg++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                page.setResources(new PDResources());
                COSName f = page.getResources().add(font);
                StringBuilder cs = new StringBuilder();
                int rows = pg == 0 ? 33 : 10;
                if (pg == 0) {
                    cs.append("BT /").append(f.getName()).append(" 10 Tf 1 0 0 1 72 730 Tm (Inventory list) Tj ET\n");
                }
                float top = pg == 0 ? 700 : 720;
                cs.append("0.5 w\n");
                for (int r = 0; r <= rows; r++) {
                    cs.append("72 ").append(top - r * 18).append(" m 540 ").append(top - r * 18).append(" l S\n");
                }
                for (int x : new int[] {72, 200, 540}) {
                    cs.append(x).append(' ').append(top).append(" m ").append(x).append(' ').append(top - rows * 18).append(" l S\n");
                }
                cs.append("BT /").append(f.getName()).append(" 10 Tf\n");
                for (int r = 0; r < rows; r++) {
                    float y = top - r * 18 - 13;
                    String label = r == 0 ? "Name" : "Item " + (++row);
                    String value = r == 0 ? "Description" : "Value of row " + row;
                    cs.append("1 0 0 1 76 ").append(y).append(" Tm (").append(label).append(") Tj\n");
                    cs.append("1 0 0 1 204 ").append(y).append(" Tm (").append(value).append(") Tj\n");
                }
                cs.append("ET\n");
                PDStream s = new PDStream(doc);
                try (OutputStream os = s.createOutputStream(COSName.FLATE_DECODE)) {
                    os.write(cs.toString().getBytes(StandardCharsets.ISO_8859_1));
                }
                page.setContents(s);
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static int count(String s, String part) {
        int n = 0;
        for (int i = s.indexOf(part); i >= 0; i = s.indexOf(part, i + part.length())) {
            n++;
        }
        return n;
    }

    private static String entry(Path zip, String name) throws IOException {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            return new String(z.getInputStream(z.getEntry(name)).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
