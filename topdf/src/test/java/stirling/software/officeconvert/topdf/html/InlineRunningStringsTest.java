package stirling.software.officeconvert.topdf.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.topdf.OfficeToPdf;

class InlineRunningStringsTest {

    private static void collects(String style) {
        Document document = Jsoup.parse("<h2>Chapter inline</h2>");
        document.selectFirst("h2").attr("style", style);
        Cascade cascade = new Cascade(document, 600);
        assertTrue(cascade.setsStrings());
        Generated.Result result = Generated.apply(document, cascade, Map.of());
        assertEquals(1, result.strings().size());
        String[] value = result.strings().values().iterator().next();
        assertEquals("chapter", value[1]);
        assertEquals("Chapter inline", value[2]);
    }

    @Test
    void lowercaseInlineDeclarationActivatesRunningStrings() {
        collects("string-set: chapter content()");
    }

    @Test
    void uppercaseInlineDeclarationActivatesRunningStrings() {
        collects("STRING-SET: chapter content()");
    }

    @Test
    void importantInlineDeclarationActivatesRunningStrings() {
        collects("string-set: chapter content() !important");
    }

    @Test
    void aQuotedPropertyNameDoesNotActivateRunningStrings() {
        Document document = Jsoup.parse("<h2>Chapter inline</h2>");
        document.selectFirst("h2").attr("style", "content: 'string-set: chapter content()'");
        Cascade cascade = new Cascade(document, 600);
        assertFalse(cascade.setsStrings());
        assertTrue(Generated.apply(document, cascade, Map.of()).strings().isEmpty());
    }

    @Test
    void stylesheetDeclarationsStillActivateRunningStrings() {
        Document document = Jsoup.parse("<style>h2 { string-set: chapter content() }</style><h2>Chapter sheet</h2>");
        Cascade cascade = new Cascade(document, 600);
        assertTrue(cascade.setsStrings());
        Generated.Result result = Generated.apply(document, cascade, Map.of());
        assertEquals(1, result.strings().size());
        assertEquals("Chapter sheet", result.strings().values().iterator().next()[2]);
    }

    @Test
    void publicConversionDrawsTheInlineRunningStringOnItsPage() throws Exception {
        String html = "<style>@page { margin:72pt; @top-left { content: 'Header: ' string(chapter) } }</style>"
                + "<p>Intro</p><h2 style='string-set: chapter content(); page-break-before:always'>Chapter inline</h2>"
                + "<p>Body</p>";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OfficeToPdf.convert(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)), OfficeToPdf.Format.HTML,
                out, OfficeToPdf.Options.defaults());
        try (var document = Loader.loadPDF(out.toByteArray())) {
            assertEquals(2, document.getNumberOfPages());
            PDFTextStripper text = new PDFTextStripper();
            text.setStartPage(2);
            assertTrue(text.getText(document).contains("Header: Chapter inline"));
        }
    }
}
