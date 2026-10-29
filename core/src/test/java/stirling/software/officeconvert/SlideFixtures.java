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

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType2;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDShadingPattern;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType3;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
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

    static Path shaded(Path dir, String name, PDShading shading) throws IOException {
        Path pdf = dir.resolve(name + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(WIDE);
            doc.addPage(page);
            page.setResources(new PDResources());
            PDShadingPattern pattern = new PDShadingPattern();
            pattern.setShading(shading);
            PDPattern space = new PDPattern(page.getResources());
            COSName patternName = page.getResources().add(pattern);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(new PDColor(patternName, space));
                cs.addRect(0, 0, 960, 540);
                cs.fill();
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    static Path strokes(Path dir) throws IOException {
        Path pdf = dir.resolve("strokes.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 3; i++) {
                doc.addPage(new PDPage(WIDE));
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, doc.getPage(0))) {
                cs.setStrokingColor(0.2f, 0.4f, 0.8f);
                cs.setLineWidth(20);
                cs.setLineCapStyle(1);
                cs.moveTo(100, 300);
                cs.lineTo(300, 300);
                cs.stroke();
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, doc.getPage(1))) {
                cs.transform(new Matrix(1, 0, 0, 4, 0, 0));
                cs.setStrokingColor(0.2f, 0.4f, 0.8f);
                cs.setLineWidth(1);
                cs.moveTo(100, 20);
                cs.lineTo(300, 70);
                cs.stroke();
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, doc.getPage(2))) {
                cs.addRect(100, 100, 400, 300);
                cs.clip();
                cs.setStrokingColor(0.2f, 0.4f, 0.8f);
                cs.setLineWidth(6);
                cs.moveTo(100, 100);
                cs.lineTo(500, 100);
                cs.stroke();
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    static PDShading axial(float x0, float y0, float x1, float y1, float[] from, float[] to) {
        PDShadingType2 shading = new PDShadingType2(new COSDictionary());
        shading.setShadingType(PDShading.SHADING_TYPE2);
        fill(shading, from, to);
        shading.setCoords(floats(x0, y0, x1, y1));
        return shading;
    }

    static PDShading radial(float x, float y, float r, float[] from, float[] to) {
        PDShadingType3 shading = new PDShadingType3(new COSDictionary());
        shading.setShadingType(PDShading.SHADING_TYPE3);
        fill(shading, from, to);
        shading.setCoords(floats(x, y, 0, x, y, r));
        return shading;
    }

    private static void fill(PDShadingType2 shading, float[] from, float[] to) {
        COSDictionary fn = new COSDictionary();
        fn.setInt(COSName.FUNCTION_TYPE, 2);
        fn.setItem(COSName.DOMAIN, floats(0, 1));
        fn.setItem(COSName.C0, floats(from));
        fn.setItem(COSName.C1, floats(to));
        fn.setInt(COSName.N, 1);
        shading.setColorSpace(PDDeviceRGB.INSTANCE);
        shading.setFunction(new PDFunctionType2(fn));
        COSArray extend = new COSArray();
        extend.add(COSBoolean.TRUE);
        extend.add(COSBoolean.TRUE);
        shading.setExtend(extend);
    }

    private static COSArray floats(float... values) {
        COSArray a = new COSArray();
        for (float v : values) {
            a.add(new COSFloat(v));
        }
        return a;
    }

    static Path ruledFacet(Path dir) throws IOException {
        Path pdf = dir.resolve("ruled.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(WIDE);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.saveGraphicsState();
                cs.addRect(0, 0, 960, 540);
                cs.clip();
                cs.setStrokingColor(0.75f, 0.75f, 0.75f);
                cs.setLineWidth(0.75f);
                cs.moveTo(737.87f, 540);
                cs.lineTo(833.87f, 0);
                cs.stroke();
                PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                gs.setNonStrokingAlphaConstant(0.6f);
                cs.setGraphicsStateParameters(gs);
                cs.setNonStrokingColor(0.25f, 0.5f, 0.75f);
                cs.moveTo(700, 540);
                cs.lineTo(960, 540);
                cs.lineTo(960, 0);
                cs.lineTo(820, 0);
                cs.closePath();
                cs.fill();
                cs.restoreGraphicsState();
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    static Path facets(Path dir) throws IOException {
        Path pdf = dir.resolve("facets.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(WIDE);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(1f, 1f, 1f);
                cs.addRect(0, 0, 960, 540);
                cs.fill();
                cs.saveGraphicsState();
                cs.addRect(0, 0, 960, 540);
                cs.clip();
                PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                gs.setNonStrokingAlphaConstant(0.6f);
                cs.setGraphicsStateParameters(gs);
                cs.setNonStrokingColor(0.25f, 0.5f, 0.75f);
                cs.moveTo(700, 545);
                cs.lineTo(960, 545);
                cs.lineTo(960, 0);
                cs.lineTo(820, 0);
                cs.closePath();
                cs.fill();
                cs.restoreGraphicsState();
                cs.saveGraphicsState();
                cs.moveTo(800, 270);
                cs.curveTo(800, 325, 845, 370, 900, 370);
                cs.curveTo(955, 370, 1000, 325, 1000, 270);
                cs.curveTo(1000, 215, 955, 170, 900, 170);
                cs.curveTo(845, 170, 800, 215, 800, 270);
                cs.clip();
                cs.setNonStrokingColor(0.8f, 0.2f, 0.1f);
                cs.moveTo(780, 150);
                cs.lineTo(940, 400);
                cs.lineTo(940, 150);
                cs.closePath();
                cs.fill();
                cs.restoreGraphicsState();
                cs.setNonStrokingColor(0f, 0f, 0f);
                text(cs, SANS, 40, 60, 440, "Facets");
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
