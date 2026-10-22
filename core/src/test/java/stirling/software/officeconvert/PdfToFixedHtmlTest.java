package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.html.FixedHtml;

class PdfToFixedHtmlTest {

    @TempDir static Path dir;
    static String html;
    static String live;

    @BeforeAll
    static void convert() throws IOException {
        Path pdf = dir.resolve("two.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 1; i <= 2; i++) {
                PDPage page = new PDPage(i == 1 ? PDRectangle.A4 : new PDRectangle(PDRectangle.A4.getHeight(),
                        PDRectangle.A4.getWidth()));
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.setNonStrokingColor(0.1f, 0.3f, 0.6f);
                    cs.addRect(72, 120, 200, 40);
                    cs.fill();
                    cs.beginText();
                    cs.setFont(font, 18);
                    cs.newLineAtOffset(72, page.getMediaBox().getHeight() - 100);
                    cs.showText("Page " + i + " heading <&>");
                    cs.endText();
                }
            }
            PDAnnotationLink web = new PDAnnotationLink();
            web.setRectangle(new PDRectangle(72, 700, 200, 20));
            PDActionURI uri = new PDActionURI();
            uri.setURI("https://example.com/x");
            web.setAction(uri);
            PDAnnotationLink script = new PDAnnotationLink();
            script.setRectangle(new PDRectangle(72, 600, 200, 20));
            PDActionJavaScript js = new PDActionJavaScript("app.alert(1)");
            script.setAction(js);
            doc.getPage(0).getAnnotations().add(web);
            doc.getPage(0).getAnnotations().add(script);
            doc.getDocumentInformation().setTitle("Two pages");
            doc.save(pdf.toFile());
        }
        Path out = dir.resolve("two.html");
        PdfToHtml.convertFixed(pdf, out, FixedHtml.Options.defaults().text(FixedHtml.Text.PICTURE));
        html = Files.readString(out, StandardCharsets.UTF_8);
        Path liveOut = dir.resolve("live.html");
        PdfToHtml.convertFixed(pdf, liveOut, FixedHtml.Options.defaults());
        live = Files.readString(liveOut, StandardCharsets.UTF_8);
    }

    @Test
    void eachPageIsAPictureOfItsOwnSize() {
        assertEquals(2, count("<section class=\"pg picture\""));
        assertEquals(2, count("<img alt=\"\" src=\"data:image/"));
        assertTrue(html.contains("width:595.3pt;height:841.9pt"), html.substring(0, 2000));
        assertTrue(html.contains("width:841.9pt;height:595.3pt"));
        assertTrue(html.contains("<title>Two pages</title>"));
    }

    @Test
    void textLayerIsEscapedAndPositioned() {
        assertTrue(html.contains(">Page 1 heading &lt;&amp;&gt;</text>"));
        assertTrue(html.contains(">Page 2 heading &lt;&amp;&gt;</text>"));
        Matcher m = Pattern.compile("<text x=\"72\" y=\"([0-9.]+)\" font-size=\"18\" textLength=\"([0-9.]+)\"").matcher(html);
        assertTrue(m.find(), html.substring(html.indexOf("<svg"), html.indexOf("<svg") + 400));
        float baseline = Float.parseFloat(m.group(1));
        assertEquals(100f, baseline, 0.5f);
        assertTrue(Float.parseFloat(m.group(2)) > 150);
    }

    @Test
    void onlySafeLinksBecomeAnchors() {
        assertTrue(html.contains("<a href=\"https://example.com/x\""));
        assertEquals(1, count("<a href="));
        assertFalse(html.toLowerCase().contains("javascript"));
        assertFalse(html.contains("<script"));
    }

    @Test
    void liveTextIsRealTextOverGraphicsOnly() {
        assertEquals(2, count(live, "<section class=\"pg\""));
        assertTrue(live.contains("font-family:'Helvetica',sans-serif"), live.substring(0, 3000));
        assertTrue(live.contains(">Page 1 heading &lt;&amp;&gt;</text>"));
        assertTrue(live.contains("lengthAdjust=\"spacingAndGlyphs\""));
        assertTrue(live.length() < html.length(), live.length() + " vs " + html.length());
        assertTrue(count(live, "<img alt=\"\"") >= 1);
    }

    @Test
    void invisibleOcrTextStaysSearchable() throws IOException {
        Path pdf = dir.resolve("scan.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(0.5f, 0.5f, 0.5f);
                cs.addRect(50, 50, 400, 600);
                cs.fill();
                cs.beginText();
                cs.setRenderingMode(org.apache.pdfbox.pdmodel.graphics.state.RenderingMode.NEITHER);
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 500);
                cs.showText("Recognised scan words");
                cs.endText();
            }
            doc.save(pdf.toFile());
        }
        Path out = dir.resolve("scan.html");
        PdfToHtml.convertFixed(pdf, out, FixedHtml.Options.defaults());
        String scan = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(scan.contains("fill:transparent;font-family:'Courier New',monospace"), scan.substring(scan.length() - 600));
        assertTrue(scan.contains(">Recognised scan words</text>"));
    }

    @Test
    void familiesKeepTheNameAndAGenericFallback() {
        assertEquals("'Times New Roman',serif", FixedHtml.family("TimesNewRomanPSMT"));
        assertEquals("'Courier New',monospace", FixedHtml.family("CourierNewPS-BoldMT"));
        assertEquals("'Calibri',sans-serif", FixedHtml.family("Calibri-Light"));
    }

    @Test
    void suppliedFontsAreEmbeddedOncePerFont() throws IOException {
        byte[] ttf;
        try (var in = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            ttf = in.readAllBytes();
        }
        int[] calls = {0};
        Path out = dir.resolve("fonts.html");
        PdfToHtml.convertFixed(dir.resolve("two.pdf"), out, FixedHtml.Options.defaults().fonts(font -> {
            calls[0]++;
            return ttf;
        }));
        String withFonts = Files.readString(out, StandardCharsets.UTF_8);
        assertEquals(1, calls[0]);
        assertEquals(1, count(withFonts, "@font-face{font-family:'pdf-f1'"));
        assertTrue(withFonts.contains("font-family:'pdf-f1','Helvetica',sans-serif"));
        assertTrue(withFonts.indexOf("@font-face") < withFonts.indexOf("<section"));

        Path big = dir.resolve("big.html");
        PdfToHtml.convertFixed(dir.resolve("two.pdf"), big, FixedHtml.Options.defaults()
                .fonts(font -> new byte[5 << 20]));
        assertFalse(Files.readString(big, StandardCharsets.UTF_8).contains("@font-face"));

        Path failing = dir.resolve("failing.html");
        PdfToHtml.convertFixed(dir.resolve("two.pdf"), failing, FixedHtml.Options.defaults().fonts(font -> {
            throw new IOException("unsupported");
        }));
        assertTrue(Files.readString(failing, StandardCharsets.UTF_8).contains(">Page 1 heading"));
    }

    @Test
    void thinPagesStayWithinThePixelBudget() throws IOException {
        Path pdf = dir.resolve("thin.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (PDRectangle box : new PDRectangle[] {new PDRectangle(25_000_000f, 0.01f),
                    new PDRectangle(0.01f, 25_000_000f)}) {
                PDPage page = new PDPage(box);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.addRect(0, 0, box.getWidth(), box.getHeight());
                    cs.fill();
                }
            }
            doc.save(pdf.toFile());
        }
        Path out = dir.resolve("thin.html");
        PdfToHtml.convertFixed(pdf, out, FixedHtml.Options.defaults().text(FixedHtml.Text.PICTURE));
        Matcher m = Pattern.compile("base64,([^\"]+)\"").matcher(Files.readString(out, StandardCharsets.UTF_8));
        int pictures = 0;
        while (m.find()) {
            pictures++;
            try (ImageInputStream in = ImageIO.createImageInputStream(
                    new ByteArrayInputStream(Base64.getDecoder().decode(m.group(1))))) {
                ImageReader reader = ImageIO.getImageReaders(in).next();
                reader.setInput(in);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                reader.dispose();
                assertTrue(pixels <= 40_000_000L, Long.toString(pixels));
            }
        }
        assertEquals(2, pictures);
    }

    @Test
    void rotatedPagesAndTextSitWhereThePageShowsThem() throws IOException {
        Path pdf = dir.resolve("rotated.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int rotation : new int[] {0, 90}) {
                PDPage page = new PDPage(PDRectangle.A4);
                page.setCropBox(new PDRectangle(50, 40, 500, 700));
                page.setRotation(rotation);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 20);
                    cs.setTextMatrix(Matrix.getRotateInstance(Math.PI / 2, 100, 300));
                    cs.showText("Up");
                    cs.setTextMatrix(Matrix.getTranslateInstance(150, 500));
                    cs.showText("Flat");
                    cs.endText();
                }
            }
            doc.save(pdf.toFile());
        }
        Path out = dir.resolve("rotated.html");
        PdfToHtml.convertFixed(pdf, out, FixedHtml.Options.defaults());
        String rotated = Files.readString(out, StandardCharsets.UTF_8);
        String upright = rotated.substring(0, rotated.indexOf("id=\"page-2\""));
        String turned = rotated.substring(rotated.indexOf("id=\"page-2\""));
        assertTrue(upright.contains("<text x=\"100\" y=\"240\""), upright);
        assertTrue(upright.contains("<text x=\"50\" y=\"440\"") && upright.contains("rotate(-90 50 440)"), upright);
        assertTrue(turned.contains("<text x=\"460\" y=\"100\"") && turned.contains("rotate(90 460 100)"), turned);
        assertTrue(turned.contains("<text x=\"260\" y=\"50\""), turned);
    }

    @Test
    void glyphsWithoutUnicodeStayInThePicture() throws IOException {
        Path pdf = dir.resolve("cid.pdf");
        try (PDDocument doc = new PDDocument();
                var ttf = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDType0Font font = PDType0Font.load(doc, ttf);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 20);
                cs.newLineAtOffset(100, 700);
                cs.showText("Glyphs without unicode");
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 20);
                cs.showText("Mapped");
                cs.endText();
            }
            doc.save(pdf.toFile());
        }
        Path stripped = dir.resolve("cid-stripped.pdf");
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            for (COSName name : doc.getPage(0).getResources().getFontNames()) {
                doc.getPage(0).getResources().getFont(name).getCOSObject().removeItem(COSName.TO_UNICODE);
            }
            doc.save(stripped.toFile());
        }
        Path out = dir.resolve("cid.html");
        PdfToHtml.convertFixed(stripped, out, FixedHtml.Options.defaults());
        String cid = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(cid.contains("<img alt=\"\" src=\"data:image/"));
        assertTrue(cid.contains("font-family:'Helvetica',sans-serif\">Mapped</text>"), cid.substring(cid.indexOf("<svg")));
    }

    @Test
    void unmappedGlyphsInAWordSplitFromTheLiveText() throws IOException {
        Path pdf = dir.resolve("mixed.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            COSDictionary encoding = new COSDictionary();
            COSArray differences = new COSArray();
            differences.add(COSInteger.get(65));
            differences.add(COSName.getPDFName("gxx1"));
            encoding.setItem(COSName.DIFFERENCES, differences);
            COSDictionary font = new COSDictionary();
            font.setItem(COSName.TYPE, COSName.FONT);
            font.setItem(COSName.SUBTYPE, COSName.TYPE1);
            font.setName(COSName.BASE_FONT, "Helvetica");
            font.setItem(COSName.ENCODING, encoding);
            COSDictionary fonts = new COSDictionary();
            fonts.setItem(COSName.getPDFName("F1"), font);
            PDResources resources = new PDResources();
            resources.getCOSObject().setItem(COSName.FONT, fonts);
            page.setResources(resources);
            page.setContents(new PDStream(doc, new ByteArrayInputStream(
                    "BT /F1 20 Tf 100 700 Td (ABC) Tj ET".getBytes(StandardCharsets.US_ASCII))));
            doc.save(pdf.toFile());
        }
        Path out = dir.resolve("mixed.html");
        PdfToHtml.convertFixed(pdf, out, FixedHtml.Options.defaults());
        String mixed = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(mixed.contains("fill:transparent;font-family:'Courier New',monospace\">A</text>"), mixed);
        assertTrue(mixed.contains("font-family:'Helvetica',sans-serif\">BC</text>"), mixed);
    }

    private static int count(String needle) {
        return count(html, needle);
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
