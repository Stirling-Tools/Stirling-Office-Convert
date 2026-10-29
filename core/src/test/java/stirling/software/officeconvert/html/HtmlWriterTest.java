package stirling.software.officeconvert.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.Pictures;
import stirling.software.officeconvert.build.DocSink;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.Section;
import stirling.software.officeconvert.model.StyleSheet;
import stirling.software.officeconvert.model.Table;

class HtmlWriterTest {

    static final RunStyle TEXT = new RunStyle("Arial", 11, false, false, false, false, 0, -1, 0, false);

    static Paragraph para(String text) {
        Paragraph p = new Paragraph();
        p.inlines.add(new Inline.Text(text, TEXT, null, -1));
        p.sourceTop = 100;
        return p;
    }

    static Section page(int columns) {
        Section s = new Section();
        s.pageWidth = 595;
        s.pageHeight = 842;
        s.marginLeft = 60;
        s.marginRight = 60;
        s.marginTop = 72;
        s.marginBottom = 72;
        for (int i = 0; i < columns; i++) {
            s.columns.add(new float[] {(475f - 15 * (columns - 1)) / columns, 15});
        }
        return s;
    }

    static String html(Section last, Numbering numbering, Consumer<DocSink> body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (HtmlWriter w = new HtmlWriter(out, Pictures.COMPACT)) {
            StyleSheet styles = new StyleSheet(TEXT);
            w.begin(styles, null);
            body.accept(w);
            w.finish(last, null, numbering, styles, "t", null);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    static void block(DocSink w, stirling.software.officeconvert.model.Block b) {
        try {
            w.block(b);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Paragraph numbered(String... parts) {
        Paragraph p = new Paragraph();
        for (String part : parts) {
            p.inlines.add(part == null ? new Inline.PageNumber(TEXT, false) : new Inline.Text(part, TEXT, null, -1));
        }
        return p;
    }

    static String running(List<Paragraph> header, List<Paragraph> footer) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (HtmlWriter w = new HtmlWriter(out, Pictures.COMPACT)) {
            StyleSheet styles = new StyleSheet(TEXT);
            w.begin(styles, new DocSink.HeaderFooterSet(header, footer, List.of(), List.of(), false));
            w.block(para("Body text"));
            w.finish(page(1), null, null, styles, "t", null);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void pageNumberLinesLeaveTheFooterAndRunningTextIsNotPrinted() throws IOException {
        String html = running(List.of(para("Northwind Traders - Annual Report")),
                List.of(numbered("Page ", null, " of ", null), numbered("Confidential draft, page ", null)));
        assertTrue(html.contains("<header class=\"running\">"), html);
        assertTrue(html.contains("Northwind Traders - Annual Report"), html);
        assertFalse(html.contains("Page </span>") || html.contains(">Page "), html);
        assertTrue(html.contains("Confidential draft"), html);
        assertTrue(html.contains("@media print{html{background:none}main{max-width:none;padding:0}.running{display:none}}"),
                html);
        assertFalse(running(List.of(), List.of(numbered("- ", null, " -"))).contains("<footer"));
    }

    @Test
    void familiesFallBackToFontsWithTheSameWidths() {
        assertEquals("'Calibri', 'Carlito', sans-serif", HtmlText.family("Calibri"));
        assertEquals("'Cambria', 'Caladea', serif", HtmlText.family("Cambria"));
        assertEquals("'Times New Roman', 'Liberation Serif', 'Tinos', serif", HtmlText.family("Times New Roman"));
        assertEquals("'Courier New', 'Liberation Mono', 'Cousine', monospace", HtmlText.family("Courier New"));
        assertEquals("'Georgia', serif", HtmlText.family("Georgia"));
    }

    @Test
    void rightTabStopsKeepTheirLeaderAndAlignment() throws IOException {
        Paragraph toc = new Paragraph();
        toc.inlines.add(new Inline.Text("1. Background", TEXT, null, -1));
        toc.inlines.add(new Inline.Tab(TEXT));
        toc.inlines.add(new Inline.Text("2", TEXT, null, -1));
        toc.tabs.add(new Paragraph.TabStop(450, Paragraph.TabStop.Kind.RIGHT, '.'));
        Paragraph left = new Paragraph();
        left.inlines.add(new Inline.Text("Name", TEXT, null, -1));
        left.inlines.add(new Inline.Tab(TEXT));
        left.inlines.add(new Inline.Text("Value", TEXT, null, -1));
        left.tabs.add(new Paragraph.TabStop(100, Paragraph.TabStop.Kind.LEFT, (char) 0));
        String out = html(page(1), new Numbering(), w -> {
            block(w, toc);
            block(w, left);
        });
        assertTrue(out.contains("display:flex;align-items:baseline;\"><span>1. Background</span><span style=\"flex:1;"
                + "min-width:6pt;margin:0 2pt;border-bottom:1pt dotted;\"></span><span>2</span></p>"), out);
        assertTrue(out.contains(">Name\tValue</p>"), out);
    }

    @Test
    void vectorShapesBecomeAnSvgBehindTheText() throws IOException {
        Paragraph p = para("Sales by quarter");
        p.inlines.add(0, new Inline.Shape(80, 160, 30, 90, 0x1F77B4));
        p.inlines.add(0, new Inline.Shape(65, 250, 225, 0.75f, 0x000000));
        p.inlines.add(0, new Inline.Shape(130, 150, 40, 40, -1, 0xFF0000, 2, false, true, false, 0));
        String html = html(page(1), null, w -> block(w, p));
        assertTrue(html.contains("<svg class=\"drawing\""), html);
        assertTrue(html.contains("fill=\"#1f77b4\""));
        assertTrue(html.contains("<ellipse"));
        assertTrue(html.contains("stroke=\"#ff0000\""));
        Matcher m = Pattern.compile("style=\"left:([-0-9.]+)pt;top:([-0-9.]+)pt").matcher(html);
        assertTrue(m.find(), html);
        assertEquals(5, Float.parseFloat(m.group(1)), 0.11);
        assertEquals(49, Float.parseFloat(m.group(2)), 0.11);
        assertFalse(html.contains("\0"));
    }

    @Test
    void shapesOnATextlessPageStartAreMeasuredFromTheTopMargin() throws IOException {
        Paragraph p = new Paragraph();
        p.bookmark = "_Pg1";
        p.spaceBefore = 10;
        p.inlines.add(new Inline.Shape(77, 359, 229, 13, 0x1F4E79));
        String html = html(page(1), null, w -> block(w, p));
        assertTrue(html.contains("style=\"left:17pt;top:277pt;"), html);
    }

    @Test
    void shapesWithNoPlaceToMeasureFromAreLeftOut() throws IOException {
        Paragraph p = new Paragraph();
        p.inlines.add(new Inline.Shape(77, 359, 229, 13, 0x1F4E79));
        assertFalse(html(page(1), null, w -> block(w, p)).contains("<svg"));
    }

    @Test
    void columnSectionsUseCssColumnsPerPage() throws IOException {
        Paragraph first = para("Left column text");
        first.bookmark = "_Pg1";
        Paragraph breaker = new Paragraph();
        breaker.inlines.add(new Inline.ColumnBreak());
        Paragraph second = para("Second page");
        second.bookmark = "_Pg2";
        String html = html(page(2), null, w -> {
            block(w, first);
            block(w, breaker);
            block(w, second);
        });
        assertTrue(html.contains(".s1{column-count:2;column-gap:15pt}"), html);
        assertTrue(html.contains("break-after:column"));
        assertEquals(2, html.split("<div class=\"s1\">", -1).length - 1);
    }

    @Test
    void singleColumnSectionsAreNotSplitAtPages() throws IOException {
        Paragraph second = para("Second page");
        second.bookmark = "_Pg2";
        String html = html(page(1), null, w -> {
            block(w, para("First"));
            block(w, second);
        });
        assertFalse(html.contains("column-count"));
        assertEquals(1, html.split("<div class=\"s1\">", -1).length - 1);
    }

    @Test
    void justificationGapsAreSpacesAndTheLineJoinsItsParagraph() throws IOException {
        Paragraph line = para("The optional survey");
        line.align = Paragraph.Align.JUSTIFY;
        line.tabs.add(new Paragraph.TabStop(120, Paragraph.TabStop.Kind.LEFT, ' '));
        line.inlines.add(new Inline.Tab(TEXT));
        line.inlines.add(new Inline.Text("supports", TEXT, null, -1));
        Paragraph rest = para("the program alongside the budget.");
        rest.align = Paragraph.Align.JUSTIFY;
        String html = html(page(1), null, w -> {
            block(w, line);
            block(w, rest);
        });
        assertFalse(html.contains("\t"), html);
        assertTrue(html.contains("The optional survey supports the program alongside the budget."), html);
    }

    @Test
    void tabsOutsideJustifiedTextStay() throws IOException {
        Paragraph p = para("Issue 14");
        p.inlines.add(new Inline.Tab(TEXT));
        p.inlines.add(new Inline.Text("Spring", TEXT, null, -1));
        assertTrue(html(page(1), null, w -> block(w, p)).contains("Issue 14\tSpring"));
    }

    @Test
    void listsAreRealListsWithTheirNumbers() throws IOException {
        Numbering n = new Numbering();
        Numbering.Instance ordered = n.create();
        ordered.definition.levels[0] = new Numbering.Level("decimal", "%1.", null, 18, 18);
        ordered.definition.levels[1] = new Numbering.Level("bullet", "•", null, 36, 18);
        ordered.starts[0] = 3;
        Paragraph a = para("Third");
        a.list = new Paragraph.ListRef(1, 0);
        Paragraph b = para("Nested");
        b.list = new Paragraph.ListRef(1, 1);
        Paragraph c = para("Fourth");
        c.list = new Paragraph.ListRef(1, 0);
        String html = html(page(1), n, w -> {
            block(w, a);
            block(w, b);
            block(w, c);
            block(w, para("After"));
        });
        String main = html.substring(html.indexOf("<main>"));
        assertTrue(main.contains("<ol class=\"list\">\n<li value=\"3\"><p><span class=\"marker\">3. </span>Third</p>"), main);
        assertTrue(main.contains("<ul class=\"list\">\n<li><p><span class=\"marker\">• </span>Nested</p>"), main);
        assertTrue(main.contains("<li value=\"4\"><p><span class=\"marker\">4. </span>Fourth</p>"), main);
        assertTrue(main.indexOf("</ol>") < main.indexOf("After"), main);
        assertEquals(main.split("<li", -1).length - 1, main.split("</li>", -1).length - 1);
    }

    @Test
    void pictureWordsAreKeptAsText() throws IOException {
        String html = html(page(1), null, w -> {
            try {
                Picture pic = new Picture(w.media(png(), "png", 1, 1, "k"), 140, 200);
                pic.wrap = Picture.Wrap.SQUARE;
                pic.x = 400;
                pic.description = "Core skills\nFinancial modelling";
                Paragraph p = para("Experience");
                p.inlines.add(0, new Inline.Image(pic));
                w.block(p);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(html.contains("<span class=\"picture-text\""), html);
        assertTrue(html.contains("Core skills\nFinancial modelling</span>"));
    }

    @Test
    void justifiedProseSplitIntoABorderlessTableIsOneParagraph() throws IOException {
        String[][] rows = {{"alongside the", "digital forecast"}, {"laboratories and", "the budget"}, {"budget for", "each strategy. The"}};
        String html = html(page(2), null, w -> block(w, table(rows)));
        assertFalse(html.contains("<table"), html);
        assertTrue(html.contains("alongside the digital forecast laboratories and the budget budget for each strategy. The"),
                html);
    }

    @Test
    void proseKeepsTheSpacesBetweenDifferentlyStyledWords() throws IOException {
        RunStyle bold = new RunStyle("Arial", 11, true, false, false, false, 0, -1, 0, false);
        Table t = table(new String[][] {{"alongside the", "digital forecast"}, {"laboratories and", "the budget"},
                {"budget for", "each strategy. The"}});
        for (Table.Row row : t.rows) {
            for (Table.Cell cell : row.cells) {
                Paragraph p = cell.paragraphs.get(0);
                String text = ((Inline.Text) p.inlines.get(0)).text();
                int space = text.indexOf(' ');
                p.inlines.clear();
                p.inlines.add(new Inline.Text(text.substring(0, space), bold, null, -1));
                p.inlines.add(new Inline.Text(" ", TEXT, null, -1));
                p.inlines.add(new Inline.Text(text.substring(space + 1), TEXT, null, -1));
            }
        }
        String html = html(page(2), null, w -> block(w, t));
        assertFalse(html.contains("<table"), html);
        assertTrue(html.contains("<b>alongside</b> the <b>digital</b> forecast"), html);
    }

    @Test
    void lowercaseTermsAndDefinitionsStayATable() throws IOException {
        Table plain = table(new String[][] {{"apple", "a red fruit that grows on trees"},
                {"banana", "a long yellow fruit from the tropics"}, {"cherry", "a small stone fruit eaten in summer"}});
        Table sentences = table(new String[][] {{"apple", "a red fruit. It grows on trees."},
                {"banana", "a long yellow fruit. It comes from the tropics."}, {"cherry", "a small stone fruit."}});
        assertTrue(html(page(1), null, w -> block(w, plain)).contains("<table"));
        assertTrue(html(page(1), null, w -> block(w, sentences)).contains("<table"));
    }

    @Test
    void realBorderlessTablesStay() throws IOException {
        Table t = table(new String[][] {{"Website redesign", "4,800.00"}, {"Hosting for twelve months", "540.00"},
                {"Support retainer", "1,200.00"}});
        assertTrue(html(page(1), null, w -> block(w, t)).contains("<table"));
    }

    @Test
    void cellTextKeepsRoomForItsSourceWidthInsideTheBorders() throws IOException {
        Table t = new Table();
        t.columnWidths.addAll(List.of(21.4f, 80f));
        t.cellMarginLeft = 0;
        t.cellMarginRight = 0;
        Table.Row row = new Table.Row();
        for (int i = 0; i < 2; i++) {
            Table.Cell cell = new Table.Cell();
            cell.left = new Table.Border(0.3f, 0);
            cell.right = new Table.Border(1f, 0);
            Paragraph p = para(i == 0 ? "10 11 12" : "Moderate");
            p.align = Paragraph.Align.JUSTIFY;
            p.indentLeft = 5.6f;
            p.indentRight = 4.2f;
            p.textWidth = 11.63f;
            cell.paragraphs.add(p);
            row.cells.add(cell);
        }
        t.rows.add(row);
        String html = html(page(1), null, w -> block(w, t));
        assertTrue(html.contains("margin-left:5.6pt;margin-right:2.5pt;\">10 11 12"), html);
        assertTrue(html.contains("margin-left:5.6pt;margin-right:4.2pt;\">Moderate"), html);
    }

    @Test
    void aLeaderSplitsAtTheTabNotInsideAPictureAfterIt() throws IOException {
        String html = html(page(1), new Numbering(), w -> {
            try {
                Picture pic = new Picture(w.media(png(), "png", 1, 1, "k"), 10, 10);
                pic.description = "Q1\tQ2";
                Paragraph toc = new Paragraph();
                toc.inlines.add(new Inline.Text("Chart", TEXT, null, -1));
                toc.inlines.add(new Inline.Tab(TEXT));
                toc.inlines.add(new Inline.Image(pic));
                toc.inlines.add(new Inline.Text("7", TEXT, null, -1));
                toc.tabs.add(new Paragraph.TabStop(450, Paragraph.TabStop.Kind.RIGHT, '.'));
                w.block(toc);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(html.contains("<span>Chart</span><span style=\"flex:1;"), html);
        assertTrue(html.contains("alt=\"Q1\tQ2\" style=\"width:10pt;height:10pt\">7</span></p>"), html);
    }

    static Table table(String[][] rows) {
        Table t = new Table();
        t.columnWidths.addAll(List.of(100f, 100f));
        for (String[] r : rows) {
            Table.Row row = new Table.Row();
            for (String c : r) {
                Table.Cell cell = new Table.Cell();
                cell.paragraphs.add(para(c));
                row.cells.add(cell);
            }
            t.rows.add(row);
        }
        return t;
    }

    static byte[] png() throws IOException {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
