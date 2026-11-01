package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

class FreeBottomMarginTest {

    private static final Pattern BOTTOM = Pattern.compile("<w:pgMar [^>]*w:bottom=\"(\\d+)\"");

    @TempDir Path dir;

    @Test
    void pagesThatEndInAHardBreakGiveTheirLastLineTheRoomBelowIt() throws Exception {
        Path pdf = pages(false);
        Path docx = dir.resolve("p.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        String body = entry(docx, "word/document.xml");
        assertEquals(1, count(body, "<w:pageBreakBefore/>"), "the second page starts with a hard break");
        assertEquals(80, bottom(body), "4 pt bottom margin");
        Path rtf = dir.resolve("p.rtf");
        PdfToRtf.convert(pdf, rtf, PdfToDocx.Options.defaults());
        assertTrue(Files.readString(rtf, StandardCharsets.US_ASCII).contains("\\margb80"), "RTF bottom margin");
    }

    @Test
    void aTableThatFlowsOntoTheNextPageKeepsTheBottomMarginItBreaksAt() throws Exception {
        Path docx = dir.resolve("t.docx");
        PdfToDocx.convert(pages(true), docx, PdfToDocx.Options.defaults());
        String body = entry(docx, "word/document.xml");
        assertEquals(1, count(body, "<w:tbl>"), "one joined table");
        assertTrue(bottom(body) > 1000, "the margin the table breaks at stays: " + bottom(body));
    }

    private Path pages(boolean table) throws IOException {
        Path pdf = dir.resolve(table ? "table.pdf" : "text.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            int row = 0;
            for (int pg = 0; pg < 2; pg++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                page.setResources(new PDResources());
                COSName f = page.getResources().add(font);
                StringBuilder cs = new StringBuilder();
                int rows = 20;
                float top = 720;
                if (table) {
                    cs.append("0.5 w\n");
                    for (int r = 0; r <= rows; r++) {
                        cs.append("72 ").append(top - r * 18).append(" m 540 ").append(top - r * 18).append(" l S\n");
                    }
                    for (int x : new int[] {72, 200, 540}) {
                        cs.append(x).append(' ').append(top).append(" m ").append(x).append(' ')
                                .append(top - rows * 18).append(" l S\n");
                    }
                }
                cs.append("BT /").append(f.getName()).append(" 10 Tf\n");
                for (int r = 0; r < rows; r++) {
                    float y = top - r * 18 - 13;
                    row++;
                    if (table) {
                        String label = r == 0 ? "Name" : "Item " + row;
                        cs.append("1 0 0 1 76 ").append(y).append(" Tm (").append(label).append(") Tj\n");
                        cs.append("1 0 0 1 204 ").append(y).append(" Tm (Value of row ").append(row).append(") Tj\n");
                    } else {
                        cs.append("1 0 0 1 72 ").append(y).append(" Tm (Line ").append(row)
                                .append(" of a short note that ends here.) Tj\n");
                    }
                }
                cs.append("ET\n");
                PDStream stream = new PDStream(doc);
                try (OutputStream out = stream.createOutputStream()) {
                    out.write(cs.toString().getBytes(StandardCharsets.US_ASCII));
                }
                page.setContents(stream);
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static int bottom(String body) {
        Matcher m = BOTTOM.matcher(body);
        assertTrue(m.find(), "a page margin");
        return Integer.parseInt(m.group(1));
    }

    private static String entry(Path zip, String name) throws IOException {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            return new String(z.getInputStream(z.getEntry(name)).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
