package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
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
        assertTrue(html.contains(">Page 1 heading &lt;&amp;&gt;</span>"));
        assertTrue(html.contains(">Page 2 heading &lt;&amp;&gt;</span>"));
        Matcher m = Pattern.compile("<span style=\"left:72pt;top:([0-9.]+)pt").matcher(html);
        assertTrue(m.find());
        float top = Float.parseFloat(m.group(1));
        assertTrue(top > 70 && top < 100, "top " + top);
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
        assertTrue(live.contains(">Page 1 heading &lt;&amp;&gt;</span>"));
        assertFalse(live.contains("scaleX"));
        assertTrue(live.length() < html.length(), live.length() + " vs " + html.length());
        assertTrue(count(live, "<img alt=\"\"") >= 1);
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
