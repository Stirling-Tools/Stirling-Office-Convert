package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class SmokeStructureTest {

    private static String markdown(List<String> pages, PDRectangle size) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument d = new PDDocument()) {
            for (String content : pages) {
                PDPage page = new PDPage(size);
                PDResources r = new PDResources();
                r.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
                r.put(COSName.getPDFName("F2"), new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD));
                page.setResources(r);
                d.addPage(page);
                PDStream stream = new PDStream(d);
                try (OutputStream o = stream.createOutputStream()) {
                    o.write(content.getBytes(StandardCharsets.ISO_8859_1));
                }
                page.setContents(stream);
            }
            PdfToMarkdown.convert(d, out, PdfToDocx.Options.defaults());
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String markdown(String content) throws IOException {
        return markdown(List.of(content), PDRectangle.LETTER);
    }

    private static String at(String font, float size, float x, float y, String s) {
        return "BT /" + font + " " + size + " Tf " + x + " " + y + " Td (" + s + ") Tj ET\n";
    }

    private static String grid(String[][] rows, float left, float top, float pitch) {
        StringBuilder c = new StringBuilder();
        float step = 440f / rows[0].length;
        for (int r = 0; r < rows.length; r++) {
            for (int k = 0; k < rows[r].length; k++) {
                if (!rows[r][k].isEmpty()) {
                    c.append(at(r == 0 ? "F2" : "F1", 10, left + k * step, top - r * pitch, rows[r][k]));
                }
            }
        }
        return c.toString();
    }

    private static final String BODY = "This report summarises how the regional teams performed during the third quarter.";

    @Test
    void singleCharacterCellsStaySeparate() throws IOException {
        String[][] rows = {{"Line", "1", "2", "3", "4", "5", "6", "7", "8", "9"}, {"L1", "0", "1", "2", "0", "1", "2", "0", "1", "2"},
            {"L2", "0", "0", "0", "0", "0", "0", "0", "0", "0"}, {"L3", "1", "0", "2", "1", "0", "2", "1", "0", "2"}};
        String md = markdown(at("F1", 10, 60, 720, BODY) + grid(rows, 60, 690, 16));
        assertTrue(md.contains("| L2 | 0 | 0 | 0 |"), md);
    }

    @Test
    void aColumnWithOnlyItsHeaderKeepsTheHeader() throws IOException {
        String[][] rows = {{"Code", "Name", "Notes", "Qty"}, {"A1", "Paper", "", "12"}, {"A2", "Toner", "", "3"}, {"B1", "Labels", "", "800"},
            {"B2", "Boxes", "", "40"}, {"C1", "Tape", "", "9"}};
        String md = markdown(at("F1", 10, 60, 720, BODY) + grid(rows, 60, 690, 16));
        assertTrue(md.contains("| Notes | Qty |"), md);
    }

    @Test
    void rowsHoldingOnlyTheirLabelStayRows() throws IOException {
        String[][] rows = {{"Day", "S1", "S2", "S3", "S4"}, {"Day 1", "4", "", "", "7"}, {"Day 2", "", "8", "", ""}, {"Day 3", "", "", "", ""},
            {"Day 4", "", "", "2", ""}, {"Day 5", "1", "", "", ""}, {"Sign off", "", "", "", ""}};
        String md = markdown(at("F1", 10, 60, 720, BODY) + grid(rows, 60, 690, 16));
        assertTrue(md.contains("| Day 3 |"), md);
        assertFalse(md.contains("<br>"), md);
        assertTrue(md.contains("| Sign off |"), md);
    }

    @Test
    void slideTitlesRepeatedOnEverySlideAreKept() throws IOException {
        PDRectangle slide = new PDRectangle(720, 540);
        String c3 = "Quarterly Business Review";
        String[] titles = {c3, c3, c3, c3, c3, c3, c3, c3, c3, c3};
        List<String> pages = new java.util.ArrayList<>();
        for (int i = 0; i < titles.length; i++) {
            StringBuilder c = new StringBuilder(at("F2", 26, 54, 480, titles[i]));
            for (int k = 0; k < 4; k++) {
                c.append(at("F1", 18, 72, 400 - k * 40, "Point " + (k + 1) + " of slide " + (i + 1) + " about the regional teams"));
            }
            pages.add(c.toString());
        }
        String md = markdown(pages, slide);
        assertTrue(md.split(c3, -1).length - 1 == titles.length, md);
    }

    @Test
    void boldHeadingsJustAboveBodySizeKeepTheirLevel() throws IOException {
        StringBuilder c = new StringBuilder(at("F1", 24, 60, 740, "Delivery Windows"));
        float y = 690;
        String[][] heads = {{"F2", "14.3", "1 Model"}, {"F2", "12", "1.1 North"}, {"F2", "12", "1.2 South"}, {"F2", "14.3", "2 Results"},
            {"F2", "12", "2.1 Volumes"}};
        for (String[] h : heads) {
            c.append(at(h[0], Float.parseFloat(h[1]), 60, y, h[2]));
            y -= 22;
            for (int k = 0; k < 3; k++) {
                c.append(at("F1", 10.95f, 60, y, BODY));
                y -= 13;
            }
            y -= 10;
        }
        String md = markdown(c.toString());
        assertTrue(md.contains("\n# 1 Model"), md);
        assertTrue(md.contains("\n## 1.1 North"), md);
        assertTrue(md.contains("\n## 2.1 Volumes"), md);
    }
}
