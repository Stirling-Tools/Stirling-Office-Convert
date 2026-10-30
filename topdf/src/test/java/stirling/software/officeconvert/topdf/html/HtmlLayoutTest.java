package stirling.software.officeconvert.topdf.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;

class HtmlLayoutTest {

    @TempDir
    Path dir;

    record Word(String text, int page, float left, float right, float top) {}

    record Laid(int pages, String text, List<Word> words, float pageWidth, BufferedImage first) {

        Word word(String text) {
            return words.stream().filter(w -> w.text().equals(text)).findFirst()
                    .orElseThrow(() -> new AssertionError(text + " not found in " + words));
        }
    }

    Laid lay(String html) throws IOException {
        return lay(html, OfficeToPdf.Options.defaults());
    }

    Laid lay(String html, OfficeToPdf.Options options) throws IOException {
        Path in = Files.writeString(dir.resolve("page.html"), html);
        Path out = dir.resolve("page.pdf");
        OfficeToPdf.convert(in, out, options.timeout(Duration.ofMinutes(2)));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            List<Word> words = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) throws IOException {
                    List<TextPosition> run = new ArrayList<>();
                    for (TextPosition p : positions) {
                        boolean apart = !run.isEmpty() && Math.abs(run.getLast().getXDirAdj()
                                + run.getLast().getWidthDirAdj() - p.getXDirAdj()) > 2;
                        if (p.getUnicode().isBlank() || apart) {
                            add(run);
                            run.clear();
                        }
                        if (!p.getUnicode().isBlank()) {
                            run.add(p);
                        }
                    }
                    add(run);
                    super.writeString(text, positions);
                }

                private void add(List<TextPosition> run) {
                    if (run.isEmpty()) {
                        return;
                    }
                    StringBuilder sb = new StringBuilder();
                    run.forEach(p -> sb.append(p.getUnicode()));
                    TextPosition last = run.getLast();
                    words.add(new Word(sb.toString(), getCurrentPageNo(), run.getFirst().getXDirAdj(),
                            last.getXDirAdj() + last.getWidthDirAdj(), run.getFirst().getYDirAdj()));
                }
            };
            String text = stripper.getText(doc);
            return new Laid(doc.getNumberOfPages(), text, words, doc.getPage(0).getMediaBox().getWidth(),
                    new PDFRenderer(doc).renderImage(0, 1f));
        }
    }

    @Test
    void flexRowItemsKeepTheirPlacesOnTheLine() throws IOException {
        Laid l = lay("""
                <html><head><style>
                body{margin:0} .line{display:flex}.d{flex:1}.q{width:60px;text-align:center}.a{width:110px;text-align:right}
                .top{display:flex;justify-content:space-between}
                </style></head><body>
                <div class=top><b>Acme</b><span>Invoice</span></div>
                <div class=line><span class=d>Website redesign</span><span class=q>7</span><span class=a>$4,800.00</span></div>
                </body></html>""");
        assertFalse(l.text().contains("redesign7"), l.text());
        Word name = l.word("Website");
        Word quantity = l.word("7");
        Word amount = l.word("$4,800.00");
        Word invoice = l.word("Invoice");
        float right = l.word("Acme").left() + (l.pageWidth() - 2 * l.word("Acme").left());
        assertEquals(name.top(), amount.top(), 1f);
        assertEquals(right, amount.right(), 4f);
        assertEquals(right, invoice.right(), 4f);
        float quantityMiddle = (quantity.left() + quantity.right()) / 2;
        assertEquals(right - 82.5f - 22.5f, quantityMiddle, 4f);
    }

    @Test
    void flexItemsOfSeveralLinesStayInTheirColumns() throws IOException {
        Laid l = lay("""
                <html><head><style>.sigs{display:flex;justify-content:space-between}</style></head><body>
                <div class=sigs><div>Lee Wong<br>Director</div><div>Sara Klein<br>Dean</div></div>
                </body></html>""");
        assertEquals(l.word("Lee").top(), l.word("Sara").top(), 1f);
        assertEquals(l.word("Director").top(), l.word("Dean").top(), 1f);
        assertTrue(l.word("Director").top() > l.word("Lee").top() + 5);
        assertTrue(l.word("Dean").left() > l.pageWidth() / 2, l.words().toString());
        assertTrue(l.word("Director").left() < l.pageWidth() / 3, l.words().toString());
    }

    @Test
    void gridAndFlexItemsKeepTheirOwnBackgroundsApartByTheGap() throws IOException {
        Laid l = lay("""
                <html><head><style>
                .g{display:grid;grid-template-columns:1fr 1fr;gap:24px}
                .card{border:2px solid #102080;padding:12px;background:#2050c0;color:#fff}
                </style></head><body>
                <div class=g><div class=card><p>Customer</p></div><div class=card><p>Shipping</p></div></div>
                </body></html>""");
        Word left = l.word("Customer");
        Word right = l.word("Shipping");
        int y = Math.round(left.top() - 14);
        List<Boolean> runs = new ArrayList<>();
        for (int x = Math.round(left.left()); x < Math.round(right.left()); x++) {
            boolean blue = (l.first().getRGB(x, y) & 0xFF) > 0x90 && (l.first().getRGB(x, y) >> 16 & 0xFF) < 0x60;
            if (runs.isEmpty() || runs.getLast() != blue) {
                runs.add(blue);
            }
        }
        assertEquals(List.of(true, false, true), runs);
    }

    @Test
    void blocksThatAvoidBreaksMoveWholeToTheNextPage() throws IOException {
        String words = "Clause text that is long enough to fill several lines of the box on the page. ".repeat(9);
        for (String box : new String[] {"border:1px solid #999;padding:8px", "margin:0"}) {
            StringBuilder html = new StringBuilder("<html><head><style>.box{" + box
                    + ";margin-bottom:12px;page-break-inside:avoid}</style></head><body>");
            for (int i = 0; i < 9; i++) {
                html.append("<div class=box><p>Start").append(i).append(' ').append(words).append("</p><p>")
                        .append(words).append("End").append(i).append("</p></div>");
            }
            Laid l = lay(html.append("</body></html>").toString());
            assertTrue(l.pages() > 2, box + ": " + l.pages());
            for (int i = 0; i < 9; i++) {
                assertEquals(l.word("Start" + i).page(), l.word("End" + i).page(), box + ": box " + i);
            }
        }
    }

    private Map<Integer, Integer> linesPerPage(String style, int filler) throws IOException {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            text.append("w").append(i).append(' ');
        }
        StringBuilder html = new StringBuilder("<html><head><style>@page{margin:72pt}"
                + "p{margin:0;font-size:12pt;line-height:14pt}</style></head><body>");
        for (int i = 0; i < filler; i++) {
            html.append("<p>line ").append(i).append("</p>");
        }
        html.append("<p style='margin-left:54pt;").append(style).append("'>").append(text).append("</p>");
        Laid l = lay(html.append("<p>after</p></body></html>").toString());
        Map<Integer, Set<Float>> lines = new TreeMap<>();
        for (Word w : l.words()) {
            if (w.text().matches("w\\d+")) {
                lines.computeIfAbsent(w.page(), k -> new HashSet<>()).add(w.top());
            }
        }
        Map<Integer, Integer> counts = new TreeMap<>();
        lines.forEach((page, tops) -> counts.put(page, tops.size()));
        return counts;
    }

    @Test
    void aParagraphKeepsAsManyLinesTogetherAsItsWidowsAndOrphansAsk() throws IOException {
        StringBuilder fill = new StringBuilder("<html><head><style>@page{margin:72pt}"
                + "p{margin:0;font-size:12pt;line-height:14pt}</style></head><body>");
        for (int i = 0; i < 80; i++) {
            fill.append("<p>line ").append(i).append("</p>");
        }
        int room = (int) lay(fill.append("</body></html>").toString()).words().stream()
                .filter(w -> w.page() == 1 && w.text().equals("line")).count();
        for (String style : new String[] {"", "widows:3;orphans:3", "widows:1;orphans:1"}) {
            int fewest = Integer.MAX_VALUE;
            boolean split = false;
            for (int filler = room - 6; filler < room; filler++) {
                Map<Integer, Integer> counts = linesPerPage(style, filler);
                if (counts.size() > 1) {
                    split = true;
                    fewest = Math.min(fewest, counts.values().stream().min(Integer::compare).orElseThrow());
                }
            }
            assertTrue(split, "the paragraph never crossed a page with '" + style + "' and " + room + " lines a page");
            int expected = style.isEmpty() ? 2 : style.startsWith("widows:3") ? 3 : 1;
            assertEquals(expected, fewest, "the fewest lines left on a page with '" + style + "'");
        }
    }

    @Test
    void aHeadingBeforeAForcedPageBreakStaysWithTheTextAboveIt() throws IOException {
        String head = "<html><head><style>@page{margin:72pt}p,h2{margin:0;font-size:12pt;line-height:14pt}</style>"
                + "</head><body>";
        StringBuilder fill = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            fill.append("<p>line</p>");
        }
        int room = (int) lay(head + fill + "</body></html>").words().stream().filter(w -> w.page() == 1).count();
        for (int filler = room - 4; filler < room; filler++) {
            String html = head + "<p>line</p>".repeat(filler)
                    + "<h2>Heading</h2><p style='break-before:page'>first second<br>third<br>fourth</p></body></html>";
            Laid l = lay(html);
            assertEquals(1, l.word("Heading").page(), "with " + filler + " of " + room + " lines filled");
            assertEquals(2, l.word("first").page());
            assertEquals(2, l.pages());
        }
    }

    @Test
    void aBoldLabelStaysWithTheParagraphItIntroduces() throws IOException {
        String head = "<html><head><style>@page{margin:72pt}p{margin:0;font-size:12pt;line-height:14pt}</style>"
                + "</head><body>";
        int room = (int) lay(head + "<p>line</p>".repeat(80) + "</body></html>").words().stream()
                .filter(w -> w.page() == 1).count();
        for (int filler = room - 4; filler < room; filler++) {
            String html = head + "<p>line</p>".repeat(filler)
                    + "<p><strong>Label</strong></p><p>first<br>second<br>third<br>fourth</p></body></html>";
            Laid l = lay(html);
            assertEquals(l.word("first").page(), l.word("Label").page(),
                    "with " + filler + " of " + room + " lines filled");
        }
    }

    @Test
    void aBlockThatAvoidsBreaksButOutrunsAPageTakesNoMorePagesThanWithout() throws IOException {
        StringBuilder paras = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            paras.append("<p>Paragraph ").append(i).append("</p>");
        }
        int plain = lay("<html><body><div>" + paras + "</div></body></html>").pages();
        int avoiding = lay("<html><body><div style='break-inside:avoid'>" + paras + "</div></body></html>").pages();
        assertEquals(plain, avoiding);
    }

    @Test
    void aCentredRightToLeftTableSitsInTheMiddleOfItsInsetBlock() throws IOException {
        for (String dir : new String[] {"ltr", "rtl"}) {
            String side = dir.equals("rtl") ? "right" : "left";
            Laid l = lay("<html dir=" + dir + "><body><div style='margin-" + side + ":200px'><table border=1 "
                    + "style='margin:0 auto'><tr><td>Mid</td></tr></table></div></body></html>");
            Word mid = l.word("Mid");
            float centre = (mid.left() + mid.right()) / 2;
            float expected = dir.equals("rtl") ? (l.pageWidth() - 150) / 2 : (l.pageWidth() + 150) / 2;
            assertEquals(expected, centre, 4f, dir);
        }
    }

    @Test
    void aBulletListInsideANumberedOneKeepsItsBullets() throws IOException {
        Laid l = lay("<html><body><ol><li>Prepare<ol><li>Merge</li><li>Changelog</li></ol></li>"
                + "<li>Build<ul><li>Linux</li><li>macOS</li></ul></li></ol></body></html>");
        String t = l.text();
        assertTrue(t.contains("1. Merge") && t.contains("2. Changelog") && t.contains("2. Build"), t);
        assertTrue(t.contains("\u25e6 Linux") && t.contains("\u25e6 macOS"), t);
    }

    @Test
    void rightToLeftPagesStartAtTheRight() throws IOException {
        for (String page : new String[] {"<html dir=rtl><body>", "<html><body style='direction:rtl'>"}) {
            Laid l = lay(page + "<p>Opening</p><table border=1><tr><td>First</td><td>Second</td></tr></table>"
                    + "<ul><li>Item</li></ul><p style='text-align:left'>Leftward</p></body></html>");
            float margin = l.word("Leftward").left();
            assertEquals(l.pageWidth() - margin, l.word("Opening").right(), 4f, page);
            assertTrue(l.word("First").left() > l.word("Second").right(), page + l.words());
            assertTrue(l.word("Item").right() > l.pageWidth() / 2, page + l.words());
            assertTrue(l.word("\u2022").left() > l.word("Item").right(), page + l.words());
        }
    }

    @Test
    void blocksNarrowerThanThePageSitWhereTheirAutoMarginsPutThem() throws IOException {
        Laid l = lay("""
                <html><head><style>
                .mid{max-width:200px;margin:0 auto;border:1px solid #000} .end{width:120px;margin-left:auto}
                </style></head><body><p>Edge</p><div class=mid>Middle</div><div class=end>Last</div></body></html>""");
        float margin = l.word("Edge").left();
        float room = l.pageWidth() - 2 * margin;
        assertEquals(margin + (room - 150) / 2, l.word("Middle").left(), 3f);
        assertEquals(margin + room - 90, l.word("Last").left(), 3f);
    }

    @Test
    void anEmptyBoxWithAHeightDrawsItsBackgroundAsAMeter() throws IOException {
        Laid l = lay("""
                <html><head><style>
                .track{height:14px;background:#c0c0c0;width:300px}.fill{height:100%;width:64%;background:#00a000}
                </style></head><body><div class=track><div class=fill></div></div><p>Below</p></body></html>""");
        BufferedImage page = l.first();
        int green = 0;
        int grey = 0;
        for (int y = 0; y < page.getHeight() && green == 0; y++) {
            for (int x = 0; x < page.getWidth(); x++) {
                int rgb = page.getRGB(x, y) & 0xFFFFFF;
                green += rgb == 0x00A000 ? 1 : 0;
                grey += rgb == 0xC0C0C0 ? 1 : 0;
            }
        }
        assertEquals(225 * 0.64f, green, 3f);
        assertEquals(225 * 0.36f, grey, 3f);
    }

    @Test
    void aFloatedSidebarSitsBesideTheColumnAfterIt() throws IOException {
        Laid l = lay("""
                <html><head><style>
                body{margin:0} .side{float:left;width:30%;padding:10px;box-sizing:border-box;background:#203040;color:#fff}
                .main{margin-left:32%}
                </style></head><body><div class=side><p>Contact</p><p>Phone</p></div>
                <div class=main><p>Heading</p><p>Body</p></div><p>Footer</p></body></html>""");
        Word side = l.word("Contact");
        Word main = l.word("Heading");
        float room = l.pageWidth() - 2 * l.word("Footer").left();
        assertEquals(main.top() + 10 * 0.75f, side.top(), 1f, "the sidebar's padding sits above its first margin");
        assertEquals(l.word("Footer").left() + room * 0.32f, main.left(), 3f);
        assertTrue(l.word("Phone").right() < main.left());
        assertTrue(l.word("Footer").top() > l.word("Body").top());
    }

    @Test
    void aTableInsideACellKeepsItsColumnsAndBackgrounds() throws IOException {
        Laid l = lay("""
                <html><body><table width="100%" border="1"><tr><td width="30%">Outer</td><td>
                <table width="100%" border="1"><tr><td bgcolor="#2050c0">Alpha</td><td>Beta</td></tr>
                <tr><td>Gamma</td><td>Delta</td></tr></table></td></tr></table></body></html>""");
        Word alpha = l.word("Alpha");
        Word beta = l.word("Beta");
        Word gamma = l.word("Gamma");
        assertEquals(alpha.top(), beta.top(), 1f);
        assertTrue(beta.left() > alpha.right() + 40, l.words().toString());
        assertEquals(alpha.left(), gamma.left(), 1f);
        assertTrue(gamma.top() > alpha.top() + 5, l.words().toString());
        assertTrue(alpha.left() > l.word("Outer").right() + 40, l.words().toString());
        int rgb = l.first().getRGB(Math.round(alpha.right()) + 4, Math.round(alpha.top()) - 4);
        assertTrue((rgb & 0xFF) > 0x90 && (rgb >> 16 & 0xFF) < 0x60, Integer.toHexString(rgb));
    }

    private Laid nestedBesideColumns(int levels) throws IOException {
        String inner = "core";
        for (int i = 0; i < levels; i++) {
            inner = "<table border=1><tr><td>Lv" + i + " " + inner + "</td><td>Left" + i + " word</td><td>Right" + i
                    + " word</td></tr></table>";
        }
        Laid l = lay("<html><body>" + inner + "</body></html>");
        for (int i = 0; i < levels; i++) {
            l.word("Lv" + i);
            l.word("Left" + i);
            l.word("Right" + i);
        }
        l.word("core");
        return l;
    }

    @Test
    void aCellHoldingANestedTableIsWideEnoughForItsColumns() throws IOException {
        Laid l = nestedBesideColumns(5);
        for (int i = 0; i < 5; i++) {
            assertTrue(l.word("Right" + i).left() > l.word("Left" + i).right(), l.words().toString());
            assertTrue(l.word("Lv" + i).right() <= l.word("Left" + i).left(), l.words().toString());
        }
    }

    @Test
    void nestedTablesTooWideForTheirCellBecomeLinesInsteadOfBreakingWords() throws IOException {
        Laid l = nestedBesideColumns(9);
        assertTrue(l.word("Right8").left() > l.word("Left8").right(), l.words().toString());
        assertTrue(l.word("Left8").left() > l.word("Lv0").right(), l.words().toString());
        assertEquals(1, l.pages());
    }

    @Test
    void aSizedGradientBoxHoldingOnlyATableKeepsItsHeightAndGradient() throws IOException {
        Laid l = lay("""
                <html><body><div style="background:linear-gradient(to bottom,#ff0000,#0000ff);height:200px">
                <table border="1"><tr><td>Inside</td></tr></table></div><p>Below</p></body></html>""");
        assertTrue(l.word("Below").top() > l.word("Inside").top() + 140, l.words().toString());
        int rgb = l.first().getRGB(300, Math.round(l.word("Inside").top()) + 60);
        assertTrue((rgb & 0xFF) > 0x40 && (rgb >> 16 & 0xFF) > 0x40 && (rgb >> 8 & 0xFF) < 0x40, Integer.toHexString(rgb));
    }

    @Test
    void tablesNestedPastTheLimitStillShowAllTheirText() throws IOException {
        StringBuilder html = new StringBuilder("<html><body>");
        int levels = 14;
        for (int i = 1; i <= levels; i++) {
            html.append("<table border=1 width=\"100%\"><tr><td>L").append(i).append("</td><td>");
        }
        html.append("core");
        html.append("</td></tr></table>".repeat(levels)).append("</body></html>");
        Laid l = lay(html.toString());
        for (int i = 1; i <= levels; i++) {
            l.word("L" + i);
        }
        l.word("core");
        assertEquals(1, l.pages());
    }

    @Test
    void columnCountFlowsTextIntoBalancedColumnsAndContinuesBelowThem() throws IOException {
        String words = "Alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi. ".repeat(14);
        Laid l = lay("<html><head><style>.cols{column-count:3;column-gap:18px;column-rule:1px solid #bbb}</style>"
                + "</head><body><p>Before</p><div class=cols><p>First " + words + "</p><p>" + words + " Last</p></div>"
                + "<p>After</p></body></html>");
        assertEquals(1, l.pages());
        Word first = containing(l, "First");
        Word last = containing(l, "Last");
        Word after = l.word("After");
        float room = l.pageWidth() - 2 * after.left();
        float third = first.left() + 2 * (room + 18 * 0.75f) / 3;
        assertTrue(last.left() > third - 3 && last.right() < first.left() + room + 3, l.words().toString());
        float pitch = (room + 18 * 0.75f) / 3;
        assertTrue(l.words().stream().filter(w -> Math.abs(w.top() - first.top()) < 1)
                .allMatch(w -> w.right() < first.left() + pitch - 18 * 0.75f + 3 || w.left() > first.left() + pitch - 3),
                l.words().toString());
        assertTrue(l.words().stream().anyMatch(w -> w.top() < first.top() + 1
                && Math.abs(w.left() - first.left() - pitch) < 3), l.words().toString());
        float[] bottoms = new float[3];
        for (Word w : l.words()) {
            int column = Math.round((w.left() - first.left()) / ((room + 18 * 0.75f) / 3));
            if (w != after && column >= 0 && column < 3) {
                bottoms[column] = Math.max(bottoms[column], w.top());
            }
        }
        assertEquals(bottoms[0], bottoms[1], 20f);
        assertEquals(bottoms[0], bottoms[2], 20f);
        assertTrue(after.top() > bottoms[0] && after.top() < bottoms[0] + 50, l.words().toString());
    }

    @Test
    void columnsInsideColumnsKeepTheOuterColumns() throws IOException {
        String words = "Alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu. ".repeat(12);
        Laid l = lay("<html><body><p>Top</p><div style=\"column-count:2\"><p>Outera " + words + "</p>"
                + "<div style=\"column-count:3\"><p>Inner " + words + "</p></div><p>Outerb " + words + "</p></div>"
                + "<p>Bottom</p></body></html>");
        assertEquals(1, l.pages());
        Word top = l.word("Top");
        assertEquals(top.left(), containing(l, "Outera").left(), 1f);
        assertTrue(containing(l, "Outerb").left() > l.pageWidth() / 2, l.words().toString());
        assertTrue(containing(l, "Bottom").top() > containing(l, "Outerb").top(), l.words().toString());
    }

    @Test
    void svgTextIsSetAsTextWhereTheDrawingPutsIt() throws IOException {
        Laid l = lay("""
                <html><body style="margin:0;font-family:Arial"><p>Chart</p>
                <svg width="300" height="80" viewBox="0 0 600 160"><rect width="600" height="160" fill="#eef"/>
                <text x="20" y="90" font-size="40">Revenue</text><text x="580" y="140" text-anchor="end">Q4</text>
                <text x="300" y="40" transform="rotate(30 300 40)">Tilted</text></svg>
                </body></html>""");
        assertTrue(l.text().contains("Revenue"), l.text());
        assertTrue(l.text().contains("Q4"), l.text());
        assertFalse(l.text().contains("Tilted"), l.text());
        Word chart = l.word("Chart");
        Word revenue = l.word("Revenue");
        assertEquals(chart.left() + 10 * 0.75f, revenue.left(), 1.5f);
        assertEquals(chart.left() + 290 * 0.75f, l.word("Q4").right(), 2f);
        assertEquals((140 - 90) / 2f * 0.75f, l.word("Q4").top() - revenue.top(), 1.5f);
    }

    @Test
    void aGradientBackgroundRunsFromItsFirstColourToItsLast() throws IOException {
        Laid l = lay("""
                <html><body style="margin:0"><div style="height:100px;padding:10px;color:#fff;
                background:linear-gradient(to right,#ff0000,#0000ff)"><p>Banner</p></div></body></html>""");
        Word banner = l.word("Banner");
        int y = Math.round(banner.top() + 20);
        int left = l.first().getRGB(Math.round(banner.left()) - 4, y);
        int right = l.first().getRGB(Math.round(l.pageWidth() - banner.left()) - 4, y);
        assertTrue((left >> 16 & 0xFF) > 200 && (left & 0xFF) < 60, Integer.toHexString(left));
        assertTrue((right & 0xFF) > 200 && (right >> 16 & 0xFF) < 60, Integer.toHexString(right));
        int below = l.first().getRGB(Math.round(banner.left()), Math.round(banner.top() - 12 + 90));
        assertTrue((below >> 16 & 0xFF) > 150, "the box is as tall as its height: " + Integer.toHexString(below));
    }

    @Test
    void aGradientTallerThanThePageStaysInsideThePageMargins() throws IOException {
        Laid l = lay("""
                <html><body style="margin:0"><div style="height:3000px;padding:10px;
                background:linear-gradient(#ff0000,#0000ff)"><p>Tall</p></div></body></html>""");
        Word tall = l.word("Tall");
        int x = Math.round(tall.left()) + 2;
        int margin = l.first().getRGB(x, l.first().getHeight() - 8) & 0xFFFFFF;
        assertEquals(0xFFFFFF, margin, Integer.toHexString(margin));
        int lastLine = l.first().getRGB(x, Math.round(l.first().getHeight() - tall.top() - 4)) & 0xFFFFFF;
        assertTrue((lastLine & 0xFF) > 200 && (lastLine >> 16 & 0xFF) < 60, Integer.toHexString(lastLine));
    }

    @Test
    void opacityFadesABoxAndItsTextTowardsThePaper() throws IOException {
        Laid l = lay("""
                <html><body style="margin:0"><div style="opacity:.5;background:#0000ff;padding:10px">
                <p>Faded</p></div></body></html>""");
        Word faded = l.word("Faded");
        int rgb = l.first().getRGB(Math.round(faded.left()) - 4, Math.round(faded.top()) - 4) & 0xFFFFFF;
        assertEquals(0x80, rgb >> 16 & 0xFF, 2, Integer.toHexString(rgb));
        assertEquals(0x80, rgb >> 8 & 0xFF, 2, Integer.toHexString(rgb));
        assertEquals(0xFF, rgb & 0xFF, 2, Integer.toHexString(rgb));
    }

    @Test
    void aRightFloatedSpanInAHeadingSitsAtTheRightEdgeOfItsLine() throws IOException {
        Laid l = lay("""
                <html><head><style>h3 span{float:right;font-weight:normal}</style></head><body style="margin:0">
                <h3>Engineer <span>2021</span></h3><h3><span>2019</span>Analyst</h3><p>Body</p></body></html>""");
        float right = l.pageWidth() - l.word("Body").left();
        assertEquals(l.word("Engineer").top(), l.word("2021").top(), 1f);
        assertEquals(right, l.word("2021").right(), 3f);
        assertEquals(l.word("Analyst").top(), l.word("2019").top(), 1f);
        assertEquals(right, l.word("2019").right(), 3f);
        assertEquals(l.word("Body").left(), l.word("Analyst").left(), 1f);
    }

    @Test
    void paddingAddsToTheSpaceBetweenBlocksWhereMarginsCollapse() throws IOException {
        Laid l = lay("""
                <html><head><style>body{margin:0}ul{list-style:none;padding:0}li{padding:10px 0}
                p{margin:20px 0}</style></head><body>
                <ul><li>One</li><li>Two</li></ul><p>Three</p><p>Four</p></body></html>""");
        float plain = l.word("Four").top() - l.word("Three").top();
        float padded = l.word("Two").top() - l.word("One").top();
        assertEquals(plain, padded, 1.5f, l.words().toString());
    }

    @Test
    void emailMarkupStartsCentredTableTextAtTheLeftAndDrawsQuoteBarsAndButtons() throws IOException {
        Laid l = lay("""
                <!doctype html><html><head><style>body{margin:0}
                .btn{display:inline-block;background:#1a73e8;color:#fff;padding:10px 18px;text-decoration:none}
                </style></head><body><center><table width="100%"><tr><td>Centred</td></tr></table></center>
                <blockquote style="margin:0;border-left:3px solid #ff0000;padding-left:8px">Quoted</blockquote>
                <p><a class="btn" href="https://example.com/go">Open</a></p></body></html>""");
        assertTrue(l.word("Centred").left() < l.pageWidth() / 4, l.words().toString());
        Word quoted = l.word("Quoted");
        boolean bar = false;
        for (int x = 0; x < Math.round(quoted.left()); x++) {
            int rgb = l.first().getRGB(x, Math.round(quoted.top()) - 4);
            bar |= (rgb >> 16 & 0xFF) > 200 && (rgb & 0xFF) < 60;
        }
        assertTrue(bar, "a left border is drawn beside the quote");
        Word open = l.word("Open");
        int fill = l.first().getRGB(Math.round(open.left()) - 6, Math.round(open.top()) - 4) & 0xFFFFFF;
        assertEquals(0x1A73E8, fill, Integer.toHexString(fill));
    }

    @Test
    void alignAttributeCentresANestedTableButNotItsText() throws IOException {
        Laid l = lay("""
                <!doctype html><html><head><style>body{margin:0}</style></head><body>
                <table width="100%" cellpadding="0" cellspacing="0"><tr><td align="center">
                <table width="200" cellpadding="0" cellspacing="0"><tr><td>Inner</td></tr></table><p>Below</p>
                </td></tr></table></body></html>""");
        assertEquals((l.pageWidth() - 150) / 2, l.word("Inner").left(), 4f, l.words().toString());
        Word below = l.word("Below");
        assertEquals(l.pageWidth() / 2, (below.left() + below.right()) / 2, 3f);
    }

    @Test
    void bodyBackgroundColourPaintsThePageInsideItsMargins() throws IOException {
        Laid l = lay("<html><body bgcolor=\"#ffff00\"><p>Hi</p></body></html>");
        int midX = l.first().getWidth() / 2;
        int midY = l.first().getHeight() / 2;
        assertEquals(0xFFFF00, l.first().getRGB(midX, midY) & 0xFFFFFF);
        assertEquals(0xFFFFFF, l.first().getRGB(2, 2) & 0xFFFFFF);
        assertEquals(0xFFFFFF, lay("<html><body><p>Hi</p></body></html>").first().getRGB(midX, midY) & 0xFFFFFF);
    }

    @Test
    void linkButtonKeepsItsWidthAndLineHeight() throws IOException {
        Laid l = lay("""
                <!doctype html><html><body><a href="https://example.com" style="display:inline-block;width:200px;
                line-height:40px;background:#0000ff;color:#ffffff;text-align:center;text-decoration:none">Go</a>
                </body></html>""");
        Word go = l.word("Go");
        int x0 = Math.round(go.left()) - 4;
        int top = Math.round(go.top()) - 2;
        while (top > 0 && (l.first().getRGB(x0, top - 1) & 0xFFFFFF) == 0x0000FF) {
            top--;
        }
        int bottom = top;
        while ((l.first().getRGB(x0, bottom + 1) & 0xFFFFFF) == 0x0000FF) {
            bottom++;
        }
        int blue = 0;
        for (int x = 0; x < l.first().getWidth(); x++) {
            blue += (l.first().getRGB(x, top + 1) & 0xFFFFFF) == 0x0000FF ? 1 : 0;
        }
        assertEquals(150, blue, 4);
        assertEquals(30, bottom - top + 1, 3);
    }

    @Test
    void ideographsAndDigitsAreNotSpacedApart() throws IOException {
        Laid l = lay("<!doctype html><html><body><p>11\u6708 5\u65e5</p></body></html>");
        Word month = l.word("11\u6708");
        assertEquals(1, l.words().stream().filter(w -> w.text().equals("11\u6708")).count(), l.words().toString());
        assertTrue(month.right() > month.left());
    }

    private static Word containing(Laid l, String text) {
        return l.words().stream().filter(w -> w.text().contains(text)).findFirst().orElseThrow();
    }

    @Test
    void bookPagesWithoutMarginsGetBookMargins() {
        var book = HtmlDocument.page(Map.of(HtmlDocument.BOOK_PAGE, "1"));
        assertEquals(HtmlDocument.BOOK_MARGIN, book.marginTop);
        assertEquals(HtmlDocument.BOOK_MARGIN, book.marginLeft);
        var own = HtmlDocument.page(Map.of(HtmlDocument.BOOK_PAGE, "1", "margin-left", "50pt"));
        assertEquals(50f, own.marginLeft);
        assertEquals(HtmlDocument.PAGE_MARGIN, HtmlDocument.page(Map.of()).marginTop);
    }

    @Test
    void bottomPaddingOfARowSitsAboveItsBottomBorder() throws IOException {
        Laid l = lay("""
                <html><head><style>
                .f{display:flex;justify-content:space-between;border-bottom:4px solid #0000ff;padding-bottom:30px}
                </style></head><body><div class=f><span>Left</span><span>Right</span></div><p>After</p></body></html>""");
        int x = Math.round(l.word("Left").left()) + 2;
        int line = -1;
        for (int y = Math.round(l.word("Left").top()); y < l.first().getHeight() && line < 0; y++) {
            int rgb = l.first().getRGB(x, y);
            if ((rgb & 0xFF) > 0xC0 && (rgb >> 16 & 0xFF) < 0x40) {
                line = y;
            }
        }
        assertTrue(line - l.word("Left").top() > 24, "border at " + line + ", text at " + l.word("Left").top());
        assertTrue(l.word("After").top() > line, "After at " + l.word("After").top());
    }

    @Test
    void gridItemsKeepTheirBottomPadding() throws IOException {
        Laid l = lay("""
                <html><head><style>
                .g{display:grid;grid-template-columns:1fr 1fr;gap:14px}.c{background:#cccccc;padding:10px 14px 40px}
                </style></head><body><div class=g><div class=c>One</div><div class=c>Two</div></div></body></html>""");
        int rgb = l.first().getRGB(Math.round(l.word("One").left()), Math.round(l.word("One").top()) + 20);
        assertEquals(0xCC, rgb & 0xFF, Integer.toHexString(rgb));
    }

    @Test
    void tallLinesCentreTheirText() throws IOException {
        Laid l = lay("""
                <html><body style="font-size:12pt"><div style="line-height:60pt">Mid</div><div>Next</div></body></html>""");
        float gap = l.word("Next").top() - l.word("Mid").top();
        assertTrue(gap > 35, "baselines " + gap + "pt apart");
    }

    @Test
    void unreadablePictureStillTakesItsLine() throws IOException {
        float with = lay("<html><body><p>A</p><p><img src=\"missing.png\"></p><p>B</p></body></html>").word("B").top();
        float without = lay("<html><body><p>A</p><p></p><p>B</p></body></html>").word("B").top();
        assertTrue(with - without > 10, with + " vs " + without);
    }

    @Test
    void bodyTopMarginOpensThePage() throws IOException {
        float plain = lay("<html><body><p style=\"margin:0\">Top</p></body></html>").word("Top").top();
        float bare = lay("<html><body style=\"margin:0\"><p style=\"margin:0\">Top</p></body></html>").word("Top")
                .top();
        assertEquals(HtmlDocument.BODY_MARGIN, plain - bare, 1f);
    }

    @Test
    void doubleBordersPrintTwoLines() throws IOException {
        Laid l = lay("""
                <html><body><div style="border-bottom:6px double #0000ff;padding-bottom:4px">Ruled</div></body></html>""");
        int x = Math.round(l.word("Ruled").left()) + 2;
        int runs = 0;
        boolean inBlue = false;
        for (int y = Math.round(l.word("Ruled").top()); y < Math.round(l.word("Ruled").top()) + 40; y++) {
            int rgb = l.first().getRGB(x, y);
            boolean blue = (rgb & 0xFF) > 0xC0 && (rgb >> 16 & 0xFF) < 0x80;
            runs += blue && !inBlue ? 1 : 0;
            inBlue = blue;
        }
        assertEquals(2, runs);
    }

    @Test
    void doubleBordersWithoutAWidthOrWithAKeywordWidthStayDouble() {
        Map<String, String> bare = Map.of("border-bottom", "double blue");
        Map<String, String> thick = Map.of("border-bottom", "thick double blue");
        assertTrue(Flow.doubled(bare, "bottom"));
        assertEquals(2.25f, Flow.border(bare, "bottom"));
        assertTrue(Flow.doubled(thick, "bottom"));
        assertEquals(3.75f, Flow.border(thick, "bottom"));
        assertEquals(3.75f, Flow.border(Map.of("border-bottom", "thick solid red"), "bottom"));
        assertEquals(0.75f, Flow.border(Map.of("border-bottom", "solid red"), "bottom"));
        assertFalse(Flow.doubled(Map.of("border-bottom", "thin double"), "bottom"));
    }

    @Test
    void aLaterBorderShorthandReplacesEarlierBorderLonghands() throws IOException {
        String page = "<html><head><style>div{border-bottom-style:double}</style></head><body>"
                + "<div style=\"%s\">Ruled</div></body></html>";
        for (String style : new String[] {"border-bottom-style:double;border-bottom:6px solid #0000ff",
                "border-bottom:6px solid #0000ff"}) {
            Laid l = lay(page.formatted(style));
            int x = Math.round(l.word("Ruled").left()) + 2;
            int runs = 0;
            boolean inBlue = false;
            for (int y = Math.round(l.word("Ruled").top()); y < Math.round(l.word("Ruled").top()) + 40; y++) {
                int rgb = l.first().getRGB(x, y);
                boolean blue = (rgb & 0xFF) > 0xC0 && (rgb >> 16 & 0xFF) < 0x80;
                runs += blue && !inBlue ? 1 : 0;
                inBlue = blue;
            }
            assertEquals(1, runs, style);
        }
        Map<String, String> d = Css.declarations("border-bottom:solid;border-bottom-style:double;border-bottom:thick solid red");
        assertEquals(Map.of("border-bottom", "thick solid red"), d);
    }

    @Test
    void aBottomPlacedBoxCountsItsRunLineHeight() throws IOException {
        String box = "<html><body><div style=\"position:relative;height:400pt\"><div style=\"position:absolute;bottom:0;"
                + "left:0\"><span style=\"line-height:%s\">Text</span></div></div></body></html>";
        float plain = lay(box.formatted("normal")).word("Text").top();
        float tall = lay(box.formatted("100pt")).word("Text").top();
        assertTrue(plain - tall > 30, plain + " vs " + tall);
    }

    @Test
    void aLargerInlineFontWidensItsLineByItsOwnLineHeight() throws IOException {
        Laid l = lay("""
                <html><body style="font-size:11pt;line-height:1.5"><div><span style="font-size:24pt">Big</span></div>
                <div>Next</div></body></html>""");
        assertTrue(l.word("Next").top() - l.word("Big").top() > 20, l.word("Next").top() + " vs " + l.word("Big").top());
    }

    @Test
    void brokenPictureWithoutAltKeepsAnIconSizedBox() throws IOException {
        Laid l = lay("<html><body style=\"font-size:8pt\"><p>A<img src=\"missing.png\">B</p></body></html>");
        float a = l.words().getFirst().left();
        assertTrue(l.word("B").left() - a >= 15, l.words().toString());
    }

    @Test
    void aLargerInlineFontWidensOnlyItsOwnLine() throws IOException {
        Laid l = lay("""
                <html><body><div style="font-size:12pt;line-height:1.5">LineOne<br>LineTwo<br>LineThree<br>
                <span style="font-size:24pt">Big</span></div><div style="font-size:12pt;line-height:1.5">After</div>
                </body></html>""");
        assertEquals(18, l.word("LineTwo").top() - l.word("LineOne").top(), 0.5f);
        assertEquals(18, l.word("LineThree").top() - l.word("LineTwo").top(), 0.5f);
        assertEquals(18 + 36, l.word("After").top() - l.word("LineThree").top(), 1f);
    }

    private static int[] box(BufferedImage page, int rgb) {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, -1, -1};
        for (int y = 0; y < page.getHeight(); y++) {
            for (int x = 0; x < page.getWidth(); x++) {
                if ((page.getRGB(x, y) & 0xFFFFFF) == rgb) {
                    b[0] = Math.min(b[0], x);
                    b[1] = Math.min(b[1], y);
                    b[2] = Math.max(b[2], x);
                    b[3] = Math.max(b[3], y);
                }
            }
        }
        assertTrue(b[2] > b[0] && b[3] > b[1], "no " + Integer.toHexString(rgb) + " box");
        return b;
    }

    @Test
    void roundedBoxesHaveRoundCorners() throws IOException {
        for (String layout : new String[] {"", "display:grid;grid-template-columns:1fr 1fr;gap:12px"}) {
            Laid l = lay("<html><body><div style=\"" + layout + "\"><div style=\"background:#2050c0;color:#fff;"
                    + "padding:24px;border-radius:24px\"><p>Card</p><p>Second line</p></div><div>Beside</div></div>"
                    + "</body></html>");
            int[] b = box(l.first(), 0x2050c0);
            assertTrue((l.first().getRGB(b[0], b[1]) & 0xFFFFFF) != 0x2050c0, layout);
            assertTrue((l.first().getRGB(b[2], b[3]) & 0xFFFFFF) != 0x2050c0, layout);
            assertEquals(0x2050c0, l.first().getRGB((b[0] + b[2]) / 2, b[1] + 1) & 0xFFFFFF, layout);
            assertEquals(0x2050c0, l.first().getRGB(b[0] + 1, (b[1] + b[3]) / 2) & 0xFFFFFF, layout);
            assertTrue(l.word("Card").top() > b[1] + 18 && l.word("line").top() < b[3] - 18, layout);
        }
    }

    @Test
    void boxShadowsFadeOutBelowTheBox() throws IOException {
        Laid l = lay("""
                <html><body><div style="background:#dde0ff;padding:12px;border-radius:8px;
                box-shadow:0 6px 10px rgba(0,0,0,.5)">Shadowed</div></body></html>""");
        int[] b = box(l.first(), 0xdde0ff);
        int x = (b[0] + b[2]) / 2;
        int near = l.first().getRGB(x, b[3] + 3) & 0xFF;
        int far = l.first().getRGB(x, b[3] + 30) & 0xFF;
        assertTrue(near < 0xE0, Integer.toHexString(near));
        assertEquals(0xFF, far);
        assertTrue((l.first().getRGB(x, b[1] - 6) & 0xFF) > near);
    }

    @Test
    void gridItemsWithoutAHeightPaintTheirGradients() throws IOException {
        Laid l = lay("""
                <html><head><style>.g{display:grid;grid-template-columns:1fr 1fr;gap:12px}
                .c{background:linear-gradient(90deg,#ff0000,#0000ff);border-radius:12px;padding:12px;
                box-shadow:0 2px 6px rgba(0,0,0,.15)}</style></head><body><div class=g><div class=c>One<br>more</div>
                <div class=c>Two</div></div></body></html>""");
        int y = Math.round((l.word("One").top() + l.word("more").top()) / 2);
        int left = l.first().getRGB(Math.round(l.word("One").left()) - 4, y);
        int right = l.first().getRGB(Math.round(l.word("Two").left()) - 20, y);
        assertTrue((left >> 16 & 0xFF) > (left & 0xFF) + 80, Integer.toHexString(left));
        assertTrue((right & 0xFF) > (right >> 16 & 0xFF) + 80, Integer.toHexString(right));
        int twoBottom = l.first().getRGB(Math.round(l.word("Two").left()), Math.round(l.word("more").top()));
        assertTrue((twoBottom & 0xFFFFFF) != 0xFFFFFF, "the shorter item stretches to the row");
    }
}
