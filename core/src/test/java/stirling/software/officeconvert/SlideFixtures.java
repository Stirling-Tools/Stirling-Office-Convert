package stirling.software.officeconvert;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.util.Matrix;

final class SlideFixtures {

    static final PDType1Font SANS = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    static final PDRectangle WIDE = new PDRectangle(960, 540);

    private SlideFixtures() {}

    static Path deck(Path dir, int variant) throws IOException {
        Path pdf = dir.resolve("deck" + variant + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage title = new PDPage(WIDE);
            doc.addPage(title);
            try (PDPageContentStream cs = new PDPageContentStream(doc, title)) {
                cs.setNonStrokingColor(new Color(0x1F, 0x38, 0x64));
                cs.addRect(0, 0, 960, 540);
                cs.fill();
                cs.setNonStrokingColor(Color.WHITE);
                text(cs, BOLD, 44, 250, 300, "Quarterly Review " + variant);
                text(cs, SANS, 24, 330, 250, "Finance and Operations");
            }
            PDPage bullets = new PDPage(WIDE);
            doc.addPage(bullets);
            try (PDPageContentStream cs = new PDPageContentStream(doc, bullets)) {
                text(cs, BOLD, 36, 60, 470, "Highlights");
                String[] items = {"Revenue grew in every region", "Two new markets opened this year",
                    "Churn fell below four percent", "Details at https://example.com/report"};
                float y = 400;
                for (String item : items) {
                    text(cs, SANS, 24, 80, y, "•");
                    text(cs, SANS, 24, 110, y, item + (variant > 0 ? " (" + variant + ")" : ""));
                    y -= 40;
                }
            }
            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(110, 275, 400, 30));
            PDActionURI uri = new PDActionURI();
            uri.setURI("https://example.com/report");
            link.setAction(uri);
            bullets.getAnnotations().add(link);
            PDPage table = new PDPage(WIDE);
            doc.addPage(table);
            try (PDPageContentStream cs = new PDPageContentStream(doc, table)) {
                text(cs, BOLD, 36, 60, 470, "Results by region");
                grid(cs, 60, 420);
                BufferedImage img = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
                for (int x = 0; x < 120; x++) {
                    for (int yy = 0; yy < 80; yy++) {
                        img.setRGB(x, yy, new Color(x * 2, yy * 3, 120 + variant).getRGB());
                    }
                }
                PDImageXObject image = LosslessFactory.createFromImage(doc, img);
                cs.drawImage(image, 640, 250, 240, 160);
                cs.beginText();
                cs.setFont(SANS, 14);
                cs.setTextMatrix(Matrix.getRotateInstance(Math.PI / 2, 920, 150));
                cs.showText("Draft " + variant);
                cs.endText();
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static void grid(PDPageContentStream cs, float x, float top) throws IOException {
        String[][] cells = {{"Region", "Revenue", "Growth"}, {"North", "1,200", "8%"}, {"South", "950", "5%"}};
        float[] cols = {x, x + 180, x + 330, x + 480};
        float row = 30;
        for (int r = 0; r < cells.length; r++) {
            for (int c = 0; c < 3; c++) {
                text(cs, r == 0 ? BOLD : SANS, 16, cols[c] + 6, top - row * r - 21, cells[r][c]);
            }
        }
        cs.setLineWidth(1f);
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

    static void text(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String s)
            throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    static Map<String, byte[]> parts(byte[] zip) throws IOException {
        Map<String, byte[]> parts = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                parts.put(e.getName(), in.readAllBytes());
            }
        }
        return parts;
    }

    static String text(Map<String, byte[]> parts, String name) {
        byte[] b = parts.get(name);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    static byte[] bytes(ByteArrayOutputStream out) {
        return out.toByteArray();
    }
}
