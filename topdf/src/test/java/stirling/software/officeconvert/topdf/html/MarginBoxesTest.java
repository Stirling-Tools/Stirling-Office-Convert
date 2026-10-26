package stirling.software.officeconvert.topdf.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.topdf.OfficeToPdf;

class MarginBoxesTest {

    @TempDir
    Path dir;

    private PDDocument convert(String html) throws IOException {
        Path in = Files.writeString(dir.resolve("page.html"), html, StandardCharsets.UTF_8);
        Path out = dir.resolve("page.pdf");
        OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        return Loader.loadPDF(out.toFile());
    }

    private static String body(int paragraphs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= paragraphs; i++) {
            sb.append("<p>Body paragraph ").append(i).append(" with enough words to fill part of a line.</p>");
        }
        return sb.toString();
    }

    @Test
    void runningHeaderAndPageCounterOnEveryPage() throws IOException {
        String html = "<html><head><style>@page { margin: 72pt; "
                + "@top-left { content: \"Northwind\" } @top-right { content: \"Report 7\" } "
                + "@bottom-center { content: \"Page \" counter(page) \" of \" counter(pages); font-size: 8pt } }"
                + "</style></head><body>" + body(150) + "</body></html>";
        try (PDDocument doc = convert(html)) {
            int pages = doc.getNumberOfPages();
            assertTrue(pages >= 3, "pages " + pages);
            for (int i = 1; i <= pages; i++) {
                PDFTextStripper s = new PDFTextStripper();
                s.setStartPage(i);
                s.setEndPage(i);
                String text = s.getText(doc);
                assertTrue(text.contains("Northwind"), text);
                assertTrue(text.contains("Report 7"), text);
                assertTrue(text.contains("Page " + i + " of " + pages), text);
            }
            float[] footer = position(doc, "Page 1");
            assertTrue(footer[1] > doc.getPage(0).getMediaBox().getHeight() - 72, "footer y " + footer[1]);
            assertEquals(8f, footer[2], 0.5f);
            float[] header = position(doc, "Northwind");
            assertTrue(header[1] < 72, "header y " + header[1]);
        }
    }

    @Test
    void namedPagesDoNotLeakBoxesAndFirstPageBoxesStayOnTheFirstPage() throws IOException {
        String html = "<html><head><style>@page :first { @top-center { content: \"First only\" } } "
                + "@page wide { @top-center { content: \"Wide only\" } }</style></head><body>" + body(150)
                + "</body></html>";
        try (PDDocument doc = convert(html)) {
            assertTrue(doc.getNumberOfPages() >= 2);
            assertTrue(page(doc, 1).contains("First only"), page(doc, 1));
            assertFalse(page(doc, 2).contains("First only"), page(doc, 2));
            assertFalse(new PDFTextStripper().getText(doc).contains("Wide only"));
        }
    }

    @Test
    void leftAndRightPagesGetTheirOwnBoxes() throws IOException {
        String html = "<html><head><style>@page { margin: 72pt; @bottom-center { content: counter(page) } } "
                + "@page :left { @top-left { content: \"Even side\" } } @page :right { @top-right { content: "
                + "\"Odd side\" } }</style></head><body>" + body(260) + "</body></html>";
        try (PDDocument doc = convert(html)) {
            assertTrue(doc.getNumberOfPages() >= 3);
            for (int i = 1; i <= 3; i++) {
                String text = page(doc, i);
                assertEquals(i % 2 == 1, text.contains("Odd side"), text);
                assertEquals(i % 2 == 0, text.contains("Even side"), text);
                assertTrue(text.contains(Integer.toString(i)), text);
            }
        }
    }

    @Test
    void aFirstPageThatClearsItsBoxesHasNoHeaderOrFooter() throws IOException {
        String html = "<html><head><style>@page { margin: 72pt; @top-center { content: \"Running head\" } "
                + "@bottom-right { content: \"Page \" counter(page) } } "
                + "@page :first { @top-center { content: none } @bottom-right { content: none } }</style></head><body>"
                + body(150) + "</body></html>";
        try (PDDocument doc = convert(html)) {
            assertTrue(doc.getNumberOfPages() >= 2);
            for (int i = 1; i <= 2; i++) {
                PDFTextStripper s = new PDFTextStripper();
                s.setStartPage(i);
                s.setEndPage(i);
                String text = s.getText(doc);
                assertEquals(i > 1, text.contains("Running head"), text);
                assertEquals(i > 1, text.contains("Page " + i), text);
            }
        }
    }

    private static String page(PDDocument doc, int i) throws IOException {
        PDFTextStripper s = new PDFTextStripper();
        s.setStartPage(i);
        s.setEndPage(i);
        return s.getText(doc);
    }

    @Test
    void runningStringsFollowTheChapterOnEachPage() throws IOException {
        String html = "<html><head><style>@page { margin: 72pt; @top-left { content: \"Head: \" string(chapter) } "
                + "@top-right { content: \"Last: \" string(chapter, last) } } h2 { string-set: chapter content(); "
                + "page-break-before: always } .short { string-set: chapter \"Gamma \" attr(data-n) }</style></head>"
                + "<body><p>Intro</p><h2>Alpha</h2>" + body(3) + "<h2>Beta</h2>" + body(120)
                + "<h2 class=short data-n=3>ignored text</h2>" + body(2) + "</body></html>";
        try (PDDocument doc = convert(html)) {
            int pages = doc.getNumberOfPages();
            assertTrue(pages >= 5, "pages " + pages);
            assertFalse(page(doc, 1).contains("Alpha"), page(doc, 1));
            assertTrue(page(doc, 2).contains("Head: Alpha"), page(doc, 2));
            assertTrue(page(doc, 3).contains("Head: Beta"), page(doc, 3));
            assertTrue(page(doc, 4).contains("Head: Beta"), page(doc, 4));
            assertTrue(page(doc, pages).contains("Head: Gamma 3"), page(doc, pages));
            assertTrue(page(doc, pages).contains("Last: Gamma 3"), page(doc, pages));
        }
    }

    @Test
    void laterRulesAndImportantCascadeIntoTheSameBox() throws IOException {
        String html = "<html><head><style>@page { margin: 72pt; @top-left { content: \"Left head\" } "
                + "@top-right { content: \"Kept\" !important } } "
                + "@page { @top-left { font-size: 8pt } @top-right { content: \"Lost\" } }"
                + "</style></head><body>" + body(2) + "</body></html>";
        try (PDDocument doc = convert(html)) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Left head"), text);
            assertTrue(text.contains("Kept"), text);
            assertFalse(text.contains("Lost"), text);
            assertEquals(8f, position(doc, "Left head")[2], 0.5f);
        }
    }

    @Test
    void semicolonInsideContentStringStaysInTheHeader() throws IOException {
        String html = "<html><head><style>@page { @top-center { content: \"Draft; internal\"; color: gray } }"
                + "</style></head><body>" + body(2) + "</body></html>";
        try (PDDocument doc = convert(html)) {
            assertTrue(new PDFTextStripper().getText(doc).contains("Draft; internal"));
        }
    }

    @Test
    void pageWithoutBoxesHasNoHeaderOrFooter() throws IOException {
        try (PDDocument doc = convert("<html><body>" + body(2) + "</body></html>")) {
            String text = new PDFTextStripper().getText(doc).strip();
            assertTrue(text.startsWith("Body paragraph 1"), text);
            assertTrue(text.endsWith("part of a line."), text);
        }
    }

    @Test
    void contentReadsStringsAndCounters() {
        List<Inline> in = MarginBoxes.content("\"Page \\\"\" counter(page) ' of ' counter(pages, decimal) attr(x)", null);
        assertEquals(4, in.size());
        assertEquals("Page \"", ((Inline.Text) in.get(0)).text());
        assertFalse(assertInstanceOf(Inline.PageNumber.class, in.get(1)).total());
        assertEquals(" of ", ((Inline.Text) in.get(2)).text());
        assertTrue(assertInstanceOf(Inline.PageNumber.class, in.get(3)).total());
        assertTrue(MarginBoxes.content("none", null).isEmpty());
        assertEquals("Report \u2014 Draft", ((Inline.Text) MarginBoxes.content("\"Report \\2014  Draft\"", null).get(0)).text());
        assertEquals("a b\u00a0c", ((Inline.Text) MarginBoxes.content("'a\\A b\\a0 c'", null).get(0)).text());
    }

    private static float[] position(PDDocument doc, String needle) throws IOException {
        float[] found = {-1, -1, -1};
        PDFTextStripper s = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> positions) {
                if (found[0] < 0 && text.contains(needle)) {
                    TextPosition p = positions.get(0);
                    found[0] = p.getXDirAdj();
                    found[1] = p.getYDirAdj();
                    found[2] = p.getFontSizeInPt();
                }
            }
        };
        s.setStartPage(1);
        s.setEndPage(1);
        s.getText(doc);
        return found;
    }
}
