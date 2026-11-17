package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class SmokeLayoutTest {

    private static PDDocument page(String content) throws IOException {
        PDDocument d = new PDDocument();
        PDPage page = new PDPage();
        PDResources r = new PDResources();
        r.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
        r.put(COSName.getPDFName("F2"), new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD));
        page.setResources(r);
        d.addPage(page);
        PDStream stream = new PDStream(d);
        try (OutputStream out = stream.createOutputStream()) {
            out.write(content.getBytes(StandardCharsets.ISO_8859_1));
        }
        page.setContents(stream);
        return d;
    }

    private static String text(String content) throws IOException {
        try (PDDocument d = page(content)) {
            return PdfToText.text(d, PdfToDocx.Options.defaults());
        }
    }

    private static String docx(String content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument d = page(content)) {
            PdfToDocx.convert(d, out, PdfToDocx.Options.defaults());
        }
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.getName().equals("word/document.xml")) {
                    return new String(((InputStream) zip).readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IOException("no document part");
    }

    private static String at(float x, float y, String s) {
        return "BT /F1 11 Tf " + x + " " + y + " Td (" + s + ") Tj ET\n";
    }

    @Test
    void aDropCapReadsAsTheFirstLetterOfItsParagraph() throws IOException {
        String c = "BT /F2 44 Tf 72 646 Td (T) Tj ET\n" + at(104, 672, "his report summarises how the regional teams performed")
                + at(104, 659, "during the third quarter and it covers delivery volumes") + at(104, 646, "customer satisfaction and the main risks")
                + at(72, 633, "for the coming months and the actions agreed at the review.");
        String out = text(c);
        assertTrue(out.strip().startsWith("This report summarises"), out);
        assertTrue(out.indexOf("during the third") < out.indexOf("customer satisfaction"), out);
    }

    @Test
    void headersLeftOfRightAlignedNumbersStartTheirOwnColumn() throws IOException {
        StringBuilder c = new StringBuilder();
        String[][] rows = {{"North", "41,250", "92.1%"}, {"South", "58,940", "96.3%"}, {"East", "47,615", "95.8%"},
            {"West", "39,870", "94.0%"}, {"Total", "187,675", "94.8%"}};
        c.append("BT /F2 11 Tf 72 700 Td (Region) Tj ET\nBT /F2 11 Tf 180 700 Td (Orders) Tj ET\nBT /F2 11 Tf 266 700 Td (On time) Tj ET\n");
        float y = 682;
        for (String[] r : rows) {
            c.append(at(72, y, r[0])).append(right(260, y, r[1])).append(right(360, y, r[2]));
            y -= 18;
        }
        String out = text(c.toString());
        assertTrue(out.contains("Orders\tOn time"), out);
    }

    private static String right(float rightEdge, float y, String s) throws IOException {
        float w = new PDType1Font(Standard14Fonts.FontName.HELVETICA).getStringWidth(s) / 1000f * 11;
        return at(rightEdge - w, y, s);
    }

    @Test
    void shortColumnHeadersStaySeparateWords() throws IOException {
        StringBuilder c = new StringBuilder();
        String[] heads = {"H1", "H2", "H1", "H2", "%"};
        for (int i = 0; i < heads.length; i++) {
            c.append(at(188 + i * 48, 600, heads[i]));
        }
        assertTrue(text(c.toString()).contains("H1	H2	H1	H2	%"));
    }

    @Test
    void lightTextOnADarkBadgeKeepsItsBadgeColour() throws IOException {
        String c = "BT /F2 18 Tf 72 700 Td (Quarterly report) Tj ET\n0.18 0.49 0.2 rg 230 696 36 14 re f\n"
                + "1 1 1 rg BT /F1 9 Tf 236 700 Td (Final) Tj ET\n0 0 0 rg" + at(72, 670, "Body text follows the heading on its own line.");
        String xml = docx(c);
        assertTrue(xml.contains("w:fill=\"2E7D32\"") || xml.contains("w:fill=\"2E7D33\""), "badge shading kept");
    }

    @Test
    void zebraRowFillsStayInTheTableNotAsFloatingShapes() throws IOException {
        StringBuilder c = new StringBuilder();
        String[][] rows = {{"Region", "Orders", "Score"}, {"North", "41,250", "4.4"}, {"South", "58,940", "4.7"}, {"East", "47,615", "4.6"},
            {"West", "39,870", "4.5"}, {"Total", "187,675", "4.6"}};
        float y = 700;
        for (int i = 0; i < rows.length; i++) {
            if (i % 2 == 1) {
                c.append("0.93 0.95 0.97 rg 66 ").append(y - 5).append(" 300 18 re f 0 0 0 rg\n");
            }
            c.append(at(72, y, rows[i][0])).append(right(250, y, rows[i][1])).append(right(350, y, rows[i][2]));
            y -= 18;
        }
        String xml = docx(c.toString());
        assertTrue(xml.contains("<w:tbl>"), "a table");
        assertEquals(0, xml.split("<w:drawing>", -1).length - 1, "no leftover row shapes");
    }

    @Test
    void aQrCodeIsAPictureNotATable() throws IOException {
        StringBuilder c = new StringBuilder();
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < 4; i++) {
            float lx = 40 + (i % 2) * 270;
            float ly = 732 - (i / 2) * 210;
            c.append("0.6 g ").append(lx).append(' ').append(ly - 180).append(" 250 180 re S 0 g\n");
            c.append("BT /F2 13 Tf ").append(lx + 12).append(' ').append(ly - 24).append(" Td (Parcel ").append(i + 1).append(" of 4) Tj ET\n");
            c.append(at(lx + 12, ly - 44, "Ms Jane Porter")).append(at(lx + 12, ly - 57, "88 Mill Lane"));
            for (int y = 0; y < 25; y++) {
                for (int x = 0, start = -1; x <= 25; x++) {
                    boolean dark = x < 25 && qrModule(x, y, r);
                    if (dark && start < 0) {
                        start = x;
                    } else if (!dark && start >= 0) {
                        c.append(lx + 180 + start * 2.45f).append(' ').append(ly - 120 + y * 2.45f).append(' ').append((x - start) * 2.45f)
                                .append(" 2.45 re f\n");
                        start = -1;
                    }
                }
            }
            float x = lx + 8;
            for (int k = 0; k < 40; k++) {
                float w = 1 + r.nextInt(3);
                c.append(x).append(' ').append(ly - 160).append(' ').append(w).append(" 34 re f\n");
                x += w + 1 + r.nextInt(3);
            }
        }
        String xml = docx(c.toString());
        assertEquals(0, xml.split("<w:tbl>", -1).length - 1, "no table from the QR modules");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<wp:extent cx=\"([0-9]+)\" cy=\"([0-9]+)\"").matcher(xml);
        int codes = 0;
        while (m.find()) {
            long w = Long.parseLong(m.group(1)) / 12700;
            long h = Long.parseLong(m.group(2)) / 12700;
            assertTrue(Math.max(w, h) >= 60 || Math.min(w, h) >= 30, "no loose bars or modules: " + m.group());
            codes += h >= 30 && h <= 80 ? 1 : 0;
        }
        assertTrue(codes >= 8, "codes drawn as pictures");
    }

    // Finder squares in three corners and dotted timing lines, like a real QR; the rest is random data.
    private static boolean qrModule(int x, int y, java.util.Random r) {
        for (int[] o : new int[][] {{0, 0}, {18, 0}, {0, 18}}) {
            int dx = x - o[0];
            int dy = y - o[1];
            if (dx >= 0 && dx < 7 && dy >= 0 && dy < 7) {
                int ring = Math.min(Math.min(dx, dy), Math.min(6 - dx, 6 - dy));
                return ring != 1;
            }
        }
        if (x == 6 || y == 6) {
            return (x + y) % 2 == 0;
        }
        return r.nextBoolean();
    }

    @Test
    void aSectionNumberAtThePageTopIsNotAPageNumber() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument d = new PDDocument()) {
            for (int i = 1; i <= 5; i++) {
                PDPage page = new PDPage();
                PDResources r = new PDResources();
                r.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
                r.put(COSName.getPDFName("F2"), new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD));
                page.setResources(r);
                d.addPage(page);
                StringBuilder c = new StringBuilder();
                if (i == 1) {
                    c.append("BT /F2 12 Tf 72 740 Td (1 Overview) Tj ET\n").append(at(320, 741, "Volumes grew in every region except"));
                }
                for (int k = 0; k < 20; k++) {
                    c.append(at(72, 700 - k * 14, "This report summarises how the regional teams performed during the quarter"));
                }
                c.append(at(300, 40, String.valueOf(i)));
                PDStream stream = new PDStream(d);
                try (OutputStream o = stream.createOutputStream()) {
                    o.write(c.toString().getBytes(StandardCharsets.ISO_8859_1));
                }
                page.setContents(stream);
            }
            PdfToDocx.convert(d, out, PdfToDocx.Options.defaults());
        }
        String xml;
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(out.toByteArray()))) {
            String found = null;
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.getName().equals("word/document.xml")) {
                    found = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            xml = found;
        }
        int box = xml.indexOf("<w:txbxContent>");
        while (box >= 0) {
            int end = xml.indexOf("</w:txbxContent>", box);
            assertTrue(!xml.substring(box, end).contains("Overview"), "heading kept in the flow");
            box = xml.indexOf("<w:txbxContent>", end);
        }
    }
}
