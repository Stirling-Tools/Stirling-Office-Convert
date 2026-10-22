package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.markdown.MarkdownWriter;

class PdfToMarkdownTest {

    private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    @TempDir static Path dir;
    static Path pdf;
    static String markdown;

    @BeforeAll
    static void makePdf() throws IOException {
        pdf = dir.resolve("report.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            BufferedImage img = new BufferedImage(90, 60, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(Color.GREEN);
            g.fillRect(0, 0, 90, 60);
            g.dispose();
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, BOLD, 22, 72, 700, "Quarterly Report");
                text(cs, REGULAR, 11, 72, 664, "Revenue grew 5*3 in [every] region_wide & margins held <steady>.");
                text(cs, REGULAR, 11, 72, 640, "See the dashboard for details.");
                text(cs, REGULAR, 11, 72, 610, "•");
                text(cs, REGULAR, 11, 90, 610, "First action item");
                text(cs, REGULAR, 11, 72, 594, "•");
                text(cs, REGULAR, 11, 90, 594, "Second action item");
                String[][] cells = {{"Region", "Sales"}, {"North", "1,200"}, {"South|East", "950"}};
                float[] cols = {72, 222, 372};
                float top = 560;
                for (int r = 0; r < cells.length; r++) {
                    for (int c = 0; c < 2; c++) {
                        text(cs, r == 0 ? BOLD : REGULAR, 11, cols[c] + 5, top - 20 * r - 14, cells[r][c]);
                    }
                }
                cs.setLineWidth(0.75f);
                for (int r = 0; r <= cells.length; r++) {
                    cs.moveTo(cols[0], top - 20 * r);
                    cs.lineTo(cols[2], top - 20 * r);
                }
                for (float x : cols) {
                    cs.moveTo(x, top);
                    cs.lineTo(x, top - 20 * cells.length);
                }
                cs.stroke();
                cs.drawImage(LosslessFactory.createFromImage(doc, img), 72, 380, 135, 90);
            }
            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(72, 636, 160, 14));
            PDActionURI uri = new PDActionURI();
            uri.setURI("https://example.com/dash");
            link.setAction(uri);
            page.getAnnotations().add(link);
            doc.save(pdf.toFile());
        }
        Path md = dir.resolve("report.md");
        OfficeConvert.convert(pdf, md);
        markdown = Files.readString(md, StandardCharsets.UTF_8);
    }

    private static void text(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String s)
            throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    @Test
    void titleIsAHeading() {
        assertTrue(markdown.startsWith("# Quarterly Report\n"), markdown);
    }

    @Test
    void markdownCharactersInTextAreEscaped() {
        assertTrue(markdown.contains("Revenue grew 5\\*3 in \\[every\\] region\\_wide &amp; margins held &lt;steady>."),
                markdown);
    }

    @Test
    void linksKeepTheirTarget() {
        assertTrue(markdown.contains("](https://example.com/dash)"), markdown);
    }

    @Test
    void bulletsBecomeListItems() {
        assertTrue(markdown.contains("- First action item\n- Second action item"), markdown);
    }

    @Test
    void ruledTableBecomesAPipeTable() {
        assertTrue(markdown.contains("| --- | --- |"), markdown);
        assertTrue(markdown.contains("| North | 1,200 |"), markdown);
        assertTrue(markdown.contains("| South\\|East | 950 |"), markdown);
    }

    @Test
    void singleFileCarriesPicturesInline() {
        assertTrue(markdown.contains("![](data:image/png;base64,"), markdown);
    }

    @Test
    void packageKeepsPicturesAsFiles() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PdfToMarkdown.convertPackage(doc, out, PdfToDocx.Options.defaults());
        }
        Map<String, byte[]> parts = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                parts.put(e.getName(), zip.readAllBytes());
            }
        }
        String md = new String(parts.get(MarkdownWriter.PACKAGE_DOCUMENT), StandardCharsets.UTF_8);
        assertTrue(md.contains("![](images/picture1.png)"), md);
        assertTrue(parts.containsKey("images/picture1.png"), parts.keySet().toString());
        assertFalse(md.contains("data:image"), md);
        assertEquals(2, parts.size(), parts.keySet().toString());
    }
}
