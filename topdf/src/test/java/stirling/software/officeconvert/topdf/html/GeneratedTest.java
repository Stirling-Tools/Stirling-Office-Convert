package stirling.software.officeconvert.topdf.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;

class GeneratedTest {

    @TempDir
    Path dir;

    private record Pdf(int pages, List<String> pageTexts) {
        String text() {
            return String.join("\n", pageTexts);
        }
    }

    private Pdf convert(String name, String html) throws IOException {
        Path in = dir.resolve(name + ".html");
        Files.writeString(in, html, StandardCharsets.UTF_8);
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults());
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            PDFTextStripper strip = new PDFTextStripper();
            List<String> texts = new java.util.ArrayList<>();
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                strip.setStartPage(i);
                strip.setEndPage(i);
                texts.add(strip.getText(doc));
            }
            return new Pdf(doc.getNumberOfPages(), texts);
        }
    }

    @Test
    void beforeAndAfterContentIsDrawn() throws IOException {
        Pdf pdf = convert("gen", "<html><head><style>.req::after{content:' *';color:#c00}"
                + "blockquote::before{content:open-quote}blockquote::after{content:close-quote}"
                + "a.ext:after{content:\" (\" attr(href) \")\"}.icon::before{content:'\\2192  '}"
                + ".none::before{content:none}.gone::after{content:'X';display:none}</style></head><body>"
                + "<p><span class=req>Name</span></p><blockquote>Quoted words</blockquote>"
                + "<p><a class=ext href=\"https://example.com/x\">Site</a></p><p class=icon>Next</p>"
                + "<p class=none>Plain</p><p class=gone>Kept</p></body></html>");
        String text = pdf.text();
        assertTrue(text.contains("Name *"), text);
        assertTrue(text.contains("“Quoted words”"), text);
        assertTrue(text.contains("Site (https://example.com/x)"), text);
        assertTrue(text.contains("→ Next"), text);
        assertFalse(text.contains("KeptX"), text);
    }

    @Test
    void countersNumberHeadings() throws IOException {
        Pdf pdf = convert("counters", "<html><head><style>body{counter-reset:chap}h1{counter-increment:chap;"
                + "counter-reset:sec}h2{counter-increment:sec}h1::before{content:counter(chap, upper-roman) '. '}"
                + "h2::before{content:counter(chap) '.' counter(sec, lower-alpha) ' '}</style></head><body>"
                + "<h1>Intro</h1><h2>Scope</h2><h2>Terms</h2><h1>Usage</h1><h2>Setup</h2></body></html>");
        String text = pdf.text();
        assertTrue(text.contains("I. Intro"), text);
        assertTrue(text.contains("1.a Scope"), text);
        assertTrue(text.contains("1.b Terms"), text);
        assertTrue(text.contains("II. Usage"), text);
        assertTrue(text.contains("2.a Setup"), text);
    }

    @Test
    void nestedCountersAreScopedToTheirLists() throws IOException {
        Pdf pdf = convert("scoped", "<html><head><style>ol{counter-reset:item;list-style:none}li{counter-increment:item}"
                + "li::before{content:counters(item, '.') ') '}</style></head><body><ol><li>Apple</li><li>Banana<ol>"
                + "<li>Bread</li><li>Butter<ol><li>Salted</li></ol></li></ol></li><li>Cherry</li></ol>"
                + "<ol><li>Again</li></ol></body></html>");
        String text = pdf.text();
        for (String want : new String[] {"1) Apple", "2) Banana", "2.1) Bread", "2.2) Butter", "2.2.1) Salted",
                "3) Cherry", "1) Again"}) {
            assertTrue(text.contains(want), want + " in " + text);
        }
    }

    @Test
    void englishTextWithHyphensAutoIsHyphenated() throws IOException {
        String words = "International organisations communicate responsibilities internationally, "
                .repeat(12);
        String css = "<style>p{width:150pt;hyphens:auto}</style>";
        String hyphenated = convert("hy", "<html lang=en><head>" + css + "</head><body><p>" + words
                + "</p></body></html>").text();
        String other = convert("hy-de", "<html lang=de><head>" + css + "</head><body><p>" + words
                + "</p></body></html>").text();
        String manual = convert("hy-off", "<html lang=en><body><p style=\"width:150pt\">" + words
                + "</p></body></html>").text();
        assertTrue(hyphenated.matches("(?s).*[a-z]-\\R.*"), hyphenated);
        assertFalse(other.matches("(?s).*[a-z]-\\R.*"), other);
        assertFalse(manual.matches("(?s).*[a-z]-\\R.*"), manual);
    }

    @Test
    void generatedBoxesDoNotChangeWhichChildIsFirstOrLast() throws IOException {
        Pdf pdf = convert("structural", "<html><head><style>ul::before{content:'Start'}ul::after{content:'End'}"
                + "li:first-child::before{content:'[first] '}li:last-child::before{content:'[last] '}</style></head>"
                + "<body><ul><li>One</li><li>Two</li></ul></body></html>");
        String text = pdf.text();
        assertTrue(text.contains("[first] One"), text);
        assertTrue(text.contains("[last] Two"), text);
    }

    @Test
    void aTableOfContentsGetsLeadersAndThePagesOfItsTargets() throws IOException {
        StringBuilder html = new StringBuilder("<html><head><style>h2{page-break-before:always}"
                + "nav a::after{content:leader('.') target-counter(attr(href), page)}</style></head><body>"
                + "<h1>Guide</h1><nav><ol>");
        for (int c = 1; c <= 3; c++) {
            html.append("<li><a href=\"#c").append(c).append("\">Chapter ").append(c).append("</a></li>");
        }
        html.append("</ol></nav>");
        for (int c = 1; c <= 3; c++) {
            html.append("<h2 id=\"c").append(c).append("\">Chapter ").append(c).append(" body</h2>");
            for (int p = 0; p < (c == 1 ? 60 : 3); p++) {
                html.append("<p>Paragraph ").append(p).append(" of chapter ").append(c).append(".</p>");
            }
        }
        Pdf pdf = convert("toc", html.append("</body></html>").toString());
        int second = -1;
        for (int i = 0; i < pdf.pages(); i++) {
            if (pdf.pageTexts().get(i).contains("Chapter 2 body")) {
                second = i + 1;
            }
        }
        assertTrue(second > 3, "chapter 1 should run over two pages");
        String toc = pdf.pageTexts().get(0);
        assertTrue(toc.matches("(?s).*Chapter 1\\s*\\.{5,}\\s*2\\b.*"), toc);
        assertTrue(toc.matches("(?s).*Chapter 2\\s*\\.{5,}\\s*" + second + "\\b.*"), toc);
        assertEquals(second + 1, pageOf(pdf, "Chapter 3 body"));
    }

    private static int pageOf(Pdf pdf, String text) {
        for (int i = 0; i < pdf.pages(); i++) {
            if (pdf.pageTexts().get(i).contains(text)) {
                return i + 1;
            }
        }
        return -1;
    }
}
