package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
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

class OutlinedTextTest {

    private static final PDType1Font SERIF = new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
    private static final PDType1Font MONO = new PDType1Font(Standard14Fonts.FontName.COURIER);
    private static final float SIZE = 12f;

    @TempDir Path dir;

    @Test
    void outlinedWordsAndPillsFlowAsRuns() throws Exception {
        String xml = convert();
        assertFalse(xml.contains("<w:framePr"));
        assertFalse(xml.contains("<w:ptab"));
        Matcher anchors = Pattern.compile("<wp:anchor.*?</wp:anchor>", Pattern.DOTALL).matcher(xml);
        while (anchors.find()) {
            assertFalse(anchors.group().contains("<pic:pic"), "outlined words must not float");
        }
        String tags = paragraph(xml, "Source tags:");
        assertEquals(2, count(tags, "<wp:inline"));
        assertFalse(tags.contains("<w:tab/>"));
        String pill = paragraph(xml, "calc();");
        Matcher run = Pattern.compile("<w:r><w:rPr>(?:(?!</w:rPr>).)*<w:bdr [^>]*/>(?:(?!</w:rPr>).)*</w:rPr><w:t[^>]*>calc\\(\\);</w:t>")
                .matcher(pill);
        assertTrue(run.find(), "pill text carries a run border");
        assertTrue(pill.contains("before the call"));
        assertTrue(pill.contains("and after it."));
        Matcher box = Pattern.compile("<wps:spPr>.*?prst=\"roundRect\".*?</wps:spPr>", Pattern.DOTALL).matcher(xml);
        assertTrue(box.find(), "code block keeps its rounded outline");
        assertTrue(box.group().contains("<a:noFill/>"), "code block must not be filled solid");
        assertTrue(paragraph(xml, "int x = 1;").contains("Courier New"));
    }

    private String convert() throws IOException {
        Path docx = dir.resolve("outlined.docx");
        PdfToDocx.convert(makePdf(), docx, PdfToDocx.Options.defaults());
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            return new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Path makePdf() throws IOException {
        Path pdf = dir.resolve("outlined.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = 700;
                float x = text(cs, SERIF, "Source tags:", 72, y);
                x = outline(cs, "Moodle Notes", x + 3, y);
                text(cs, SERIF, "= practice tests and course notes for the exam.", x + 3, y);
                for (int i = 1; i <= 4; i++) {
                    text(cs, SERIF, "Plain body text keeps the page looking like prose, line " + i + ".", 72, y - 18 * i);
                }
                y -= 100;
                x = text(cs, SERIF, "Declare it before the call", 72, y);
                float start = x + 4;
                float end = text(cs, MONO, "calc();", start + 3, y) + 3;
                ring(cs, start, y - 4, end, y + 10, 3, 0.6f);
                text(cs, SERIF, "and after it.", end + 4, y);
                ring(cs, 66, 470, 400, 540, 6, 0.6f);
                text(cs, MONO, "int x = 1;", 80, 515);
                text(cs, MONO, "return x;", 80, 495);
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static float text(PDPageContentStream cs, PDType1Font font, String s, float x, float y) throws IOException {
        cs.setNonStrokingColor(0f);
        cs.beginText();
        cs.setFont(font, SIZE);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
        return x + font.getStringWidth(s) / 1000f * SIZE;
    }

    private static float outline(PDPageContentStream cs, String s, float x, float y) throws IOException {
        Font bold = new Font(Font.SERIF, Font.BOLD, 1).deriveFont(SIZE);
        GlyphVector gv = bold.createGlyphVector(new FontRenderContext(null, true, true), s);
        AffineTransform at = new AffineTransform(1, 0, 0, -1, x, y);
        cs.setNonStrokingColor(0f);
        float[] c = new float[6];
        for (PathIterator it = gv.getOutline().getPathIterator(at); !it.isDone(); it.next()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> cs.moveTo(c[0], c[1]);
                case PathIterator.SEG_LINETO -> cs.lineTo(c[0], c[1]);
                case PathIterator.SEG_CUBICTO -> cs.curveTo(c[0], c[1], c[2], c[3], c[4], c[5]);
                case PathIterator.SEG_QUADTO -> cs.curveTo(c[0], c[1], c[2], c[3], c[2], c[3]);
                default -> cs.closePath();
            }
        }
        cs.fill();
        return x + (float) gv.getLogicalBounds().getWidth();
    }

    private static void ring(PDPageContentStream cs, float x0, float y0, float x1, float y1, float r, float w) throws IOException {
        cs.setNonStrokingColor(0f);
        rounded(cs, x0, y0, x1, y1, r, false);
        rounded(cs, x0 + w, y0 + w, x1 - w, y1 - w, r - w, true);
        cs.moveTo(x0, y0);
        cs.fill();
    }

    private static void rounded(PDPageContentStream cs, float x0, float y0, float x1, float y1, float r, boolean reverse)
            throws IOException {
        float k = 0.55f * r;
        if (!reverse) {
            cs.moveTo(x0 + r, y0);
            cs.lineTo(x1 - r, y0);
            cs.curveTo(x1 - r + k, y0, x1, y0 + r - k, x1, y0 + r);
            cs.lineTo(x1, y1 - r);
            cs.curveTo(x1, y1 - r + k, x1 - r + k, y1, x1 - r, y1);
            cs.lineTo(x0 + r, y1);
            cs.curveTo(x0 + r - k, y1, x0, y1 - r + k, x0, y1 - r);
            cs.lineTo(x0, y0 + r);
            cs.curveTo(x0, y0 + r - k, x0 + r - k, y0, x0 + r, y0);
        } else {
            cs.moveTo(x0 + r, y0);
            cs.curveTo(x0 + r - k, y0, x0, y0 + r - k, x0, y0 + r);
            cs.lineTo(x0, y1 - r);
            cs.curveTo(x0, y1 - r + k, x0 + r - k, y1, x0 + r, y1);
            cs.lineTo(x1 - r, y1);
            cs.curveTo(x1 - r + k, y1, x1, y1 - r + k, x1, y1 - r);
            cs.lineTo(x1, y0 + r);
            cs.curveTo(x1, y0 + r - k, x1 - r + k, y0, x1 - r, y0);
            cs.lineTo(x0 + r, y0);
        }
        cs.closePath();
    }

    private static String paragraph(String xml, String text) {
        Matcher p = Pattern.compile("<w:p>.*?</w:p>", Pattern.DOTALL).matcher(xml);
        while (p.find()) {
            if (p.group().contains(text)) {
                return p.group();
            }
        }
        throw new AssertionError("no paragraph holds " + text);
    }

    private static int count(String s, String part) {
        int n = 0;
        for (int i = s.indexOf(part); i >= 0; i = s.indexOf(part, i + 1)) {
            n++;
        }
        return n;
    }
}
