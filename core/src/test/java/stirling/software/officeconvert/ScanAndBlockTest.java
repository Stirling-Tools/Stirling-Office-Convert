package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
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
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScanAndBlockTest {

    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);

    private static final float SCALE = 150f / 72f;

    @TempDir Path dir;

    @Test
    void anIndentedJustifiedBlockFlowsWithoutLineBreaks() throws Exception {
        String words = "the quick brown fox jumps over the lazy dog while seven bold wizards quietly judge "
                + "every boxing match and the jovial crowd cheers for more ";
        String[] all = (words + words + words + words + words).trim().split(" ");
        Path pdf = dir.resolve("block.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = 720;
                for (int i = 0; i < 4; i++) {
                    justified(cs, 72, y, 468, false, ("A full width body line that sets the text column of the page "
                            + "for this test and runs from margin to margin").split(" "));
                    y -= 13;
                }
                y -= 20;
                int at = 0;
                for (int line = 0; line < 8; line++) {
                    int from = at;
                    float width = 0;
                    while (at < all.length && width + FONT.getStringWidth(all[at] + " ") / 100f <= 340) {
                        width += FONT.getStringWidth(all[at] + " ") / 100f;
                        at++;
                    }
                    justified(cs, 136, y, 340, line == 7, java.util.Arrays.copyOfRange(all, from, at));
                    y -= 12;
                }
            }
            doc.save(pdf.toFile());
        }
        String body = body(pdf);
        Matcher m = Pattern.compile("<w:p>(?:(?!</w:p>).)*quick brown(?:(?!</w:p>).)*</w:p>").matcher(body);
        assertTrue(m.find(), body);
        assertFalse(m.group().contains("<w:br/>"), m.group());
    }

    @Test
    void aRuledTableInAScanBecomesATable() throws Exception {
        float left = 72;
        float top = 150;
        float[] cols = {0, 60, 260, 340};
        float row = 20;
        BufferedImage img = blank();
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(3));
        for (int r = 0; r <= 6; r++) {
            g.drawLine(px(left), px(top + r * row), px(left + cols[3]), px(top + r * row));
        }
        for (float c : cols) {
            g.drawLine(px(left + c), px(top), px(left + c), px(top + 6 * row));
        }
        g.dispose();
        Path pdf = scan(img, cs -> {
            for (int r = 0; r < 6; r++) {
                float base = 792 - (top + r * row + 14);
                hidden(cs, left + 4, base, String.valueOf(r + 1));
                hidden(cs, left + cols[1] + 4, base, "Service item " + (r + 1));
                hidden(cs, left + cols[2] + 4, base, (r + 1) * 10 + ".00");
            }
            hidden(cs, 72, 792 - 400, "Total due after the table is listed here.");
        });
        String body = body(pdf);
        assertTrue(body.contains("<w:tbl>"), body);
        assertTrue(body.contains("Service item 3"), body);
        assertTrue(body.contains("w:val=\"single\""), body);
    }

    @Test
    void aLargeUnreadableMastheadInAScanStaysAPicture() throws Exception {
        BufferedImage img = blank();
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SERIF, Font.BOLD, 150));
        g.drawString("MASTHEAD", px(60), px(170));
        g.setFont(new Font(Font.SERIF, Font.PLAIN, 22));
        for (int i = 0; i < 12; i++) {
            g.drawString("Body text of the scanned page with a readable OCR layer line " + i, px(72), px(300 + i * 16));
        }
        g.dispose();
        Path pdf = scan(img, cs -> {
            hidden(cs, 70, 792 - 160, "FlTE");
            for (int i = 0; i < 12; i++) {
                hidden(cs, 72, 792 - (300 + i * 16), "Body text of the scanned page with a readable OCR layer line " + i);
            }
        });
        String body = body(pdf);
        assertTrue(body.contains("<w:drawing>"), body);
        assertFalse(body.contains("FlTE"), body);
        assertTrue(body.contains("readable OCR layer line 5"), body);
    }

    private interface Layer {
        void draw(PDPageContentStream cs) throws IOException;
    }

    private static BufferedImage blank() {
        BufferedImage img = new BufferedImage(px(612), px(792), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.dispose();
        return img;
    }

    private static int px(float pt) {
        return Math.round(pt * SCALE);
    }

    private Path scan(BufferedImage img, Layer ocr) throws IOException {
        Path pdf = dir.resolve("scan.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDImageXObject x = LosslessFactory.createFromImage(doc, img);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(x, 0, 0, 612, 792);
                ocr.draw(cs);
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static void hidden(PDPageContentStream cs, float x, float y, String s) throws IOException {
        cs.beginText();
        cs.setRenderingMode(RenderingMode.NEITHER);
        cs.setFont(FONT, 11);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    private static void plain(PDPageContentStream cs, float x, float y, String s) throws IOException {
        cs.beginText();
        cs.setFont(FONT, 10);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    private static void justified(PDPageContentStream cs, float x, float y, float width, boolean last, String[] words)
            throws IOException {
        float ink = 0;
        for (String w : words) {
            ink += FONT.getStringWidth(w) / 100f;
        }
        float gap = last || words.length < 2 ? FONT.getStringWidth(" ") / 100f : (width - ink) / (words.length - 1);
        float at = x;
        for (String w : words) {
            plain(cs, at, y, w);
            at += FONT.getStringWidth(w) / 100f + gap;
        }
    }

    private String body(Path pdf) throws Exception {
        Path docx = dir.resolve("out.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            return new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
