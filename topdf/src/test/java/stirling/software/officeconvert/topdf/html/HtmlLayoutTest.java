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
            assertTrue(l.text().contains("L" + i), "L" + i + " missing from " + l.text());
        }
        assertTrue(l.text().contains("core"), l.text());
        assertEquals(1, l.pages());
    }
}
