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
import java.util.List;

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
        Path in = Files.writeString(dir.resolve("page.html"), html);
        Path out = dir.resolve("page.pdf");
        OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
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
        assertEquals(side.top(), main.top(), 1f);
        assertEquals(l.word("Footer").left() + room * 0.32f, main.left(), 3f);
        assertTrue(l.word("Phone").right() < main.left());
        assertTrue(l.word("Footer").top() > l.word("Body").top());
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
        assertEquals(first.left() + 2 * (room + 18 * 0.75f) / 3, last.left(), 3f);
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

    private static Word containing(Laid l, String text) {
        return l.words().stream().filter(w -> w.text().contains(text)).findFirst().orElseThrow();
    }
}
