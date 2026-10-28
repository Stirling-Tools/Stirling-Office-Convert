package stirling.software.officeconvert.topdf.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.build.DocSink;
import stirling.software.officeconvert.model.Block;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.Section;
import stirling.software.officeconvert.model.StyleSheet;
import stirling.software.officeconvert.model.Table;

class HtmlTablesTest {

    @TempDir
    Path dir;

    static final class Blocks implements DocSink {
        final List<Block> blocks = new ArrayList<>();
        int media;

        @Override
        public Picture.MediaRef media(byte[] bytes, String ext, int pixelWidth, int pixelHeight, Object key) {
            return new Picture.MediaRef("m" + (++media) + "." + ext, "image/" + ext, pixelWidth, pixelHeight);
        }

        @Override
        public Picture.MediaRef media(Object key) {
            return null;
        }

        @Override
        public void begin(StyleSheet styles, HeaderFooterSet running) {}

        @Override
        public void block(Block block) {
            blocks.add(block);
        }

        @Override
        public void footnote(int id, List<Paragraph> paragraphs) {}

        @Override
        public void finish(Section last, HeaderFooterSet running, Numbering numbering, StyleSheet styles, String title,
                String author) {}
    }

    Table table(String html) throws IOException {
        Path in = Files.writeString(dir.resolve("t.html"), html, StandardCharsets.UTF_8);
        Blocks sink = new Blocks();
        HtmlDocument.write(in, sink);
        for (Block b : sink.blocks) {
            if (b instanceof Table t) {
                return t;
            }
        }
        return null;
    }

    @Test
    void pictureKeepsItsWidthInAnAutoWidthColumn() throws IOException {
        Table t = table("<table style='width:100%'><tr><td><img src='" + HtmlToPdfTest.png(40, 15)
                + "' width=160 height=60></td><td style='text-align:right'><h1>INVOICE</h1></td></tr></table>");
        assertNotNull(t);
        float column = t.columnWidths.get(0) - t.cellMarginLeft - t.cellMarginRight;
        Picture pic = null;
        for (Paragraph p : t.rows.get(0).cells.get(0).paragraphs) {
            for (Inline i : p.inlines) {
                if (i instanceof Inline.Image img) {
                    pic = img.picture();
                }
            }
        }
        assertNotNull(pic);
        assertTrue(column >= 119, "column " + column);
        assertEquals(120, pic.width, 0.5);
    }

    @Test
    void eachCellKeepsItsOwnPadding() throws IOException {
        Table t = table("<table><tr><th style='padding:10px 12px'>Time</th><td style='padding:2px 4px'>09:00</td></tr>"
                + "</table>");
        assertNotNull(t);
        assertEquals(3, t.cellMarginLeft, 0.01);
        assertEquals(3, t.cellMarginRight, 0.01);
        Paragraph head = t.rows.get(0).cells.get(0).paragraphs.get(0);
        Paragraph data = t.rows.get(0).cells.get(1).paragraphs.get(0);
        assertEquals(7.5, head.spaceBefore, 0.01);
        assertEquals(7.5, head.spaceAfter, 0.01);
        assertEquals(6, head.indentLeft, 0.01);
        assertEquals(6, head.indentRight, 0.01);
        assertEquals(1.5, data.spaceBefore, 0.01);
        assertEquals(0, data.indentLeft, 0.01);
    }

    @Test
    void cellpaddingPadsEverySide() throws IOException {
        Table t = table("<table cellpadding=8><tr><td>a</td><td>b</td></tr></table>");
        assertEquals(6, t.cellMarginLeft, 0.01);
        assertEquals(6, t.rows.get(0).cells.get(0).paragraphs.get(0).spaceBefore, 0.01);
    }

    @Test
    void ideographsAndDigitsAreNotUnderestimated() {
        assertEquals(60, Glyphs.estimate("软件许可证", 12), 0.01);
        assertTrue(Glyphs.word("09:00", 12, false) > 5 * 12 * 0.5f - 1);
        assertTrue(Glyphs.word("Time", 12, true) > Glyphs.word("Time", 12, false));
        assertEquals(13, Glyphs.word("软件许可证", 12, false), 0.01);
    }

    @Test
    void shortIdeographCellsStayOnOneLine() throws IOException {
        Table t = table("<table style='border-collapse:collapse'><tr><th>项目</th><th>数量</th></tr>"
                + "<tr><td>软件许可证</td><td>1年</td></tr></table>");
        assertNotNull(t);
        assertTrue(t.columnWidths.get(0) - t.cellMarginLeft - t.cellMarginRight >= 60, t.columnWidths.toString());
    }

    @Test
    void nonBreakingPaddingAroundStyledLabelsFitsTheColumn() throws IOException {
        Table t = table("<table><tr><td>&nbsp;<b>Location</b>&nbsp;</td><td>Room</td></tr></table>");
        float width = t.columnWidths.getFirst() - t.cellMarginLeft - t.cellMarginRight;
        Look bold = new Look().child(java.util.Map.of("font-weight", "bold"), 300, true);
        float need = Measure.width("Location", bold.run()) + Measure.width("\u00a0\u00a0", new Look().run());
        assertTrue(width >= need, "column " + width + " needs " + need);
    }

    @Test
    void largerInlineLabelsDetermineTheirMinimumColumnWidth() throws IOException {
        Table t = table("<table style='width:300px'><tr><td><span style='font-size:32px'>Wide</span>Label</td>"
                + "<td>Some other words that can wrap normally within their column</td></tr></table>");
        float width = t.columnWidths.getFirst() - t.cellMarginLeft - t.cellMarginRight;
        float need = Measure.width("Wide", new Look().child(java.util.Map.of("font-size", "32px"), 300, true).run())
                + Measure.width("Label", new Look().run());
        assertTrue(width >= need, "column " + width + " needs " + need);
    }

    @Test
    void intrinsicInlineWidthsAreIndependentOfDisplayValueCasing() throws IOException {
        String html = "<table style='width:300px'><tr><td><span style='display:%s;font-size:32px'>Wide</span>"
                + "<span style='display:%s;font-size:32px'>Label</span></td>"
                + "<td>Some other words that can wrap normally within their column</td></tr></table>";
        Table lowercase = table(html.formatted("inline", "contents"));
        Table uppercase = table(html.formatted("INLINE", "CONTENTS"));
        assertEquals(lowercase.columnWidths.getFirst(), uppercase.columnWidths.getFirst(), 0.01);
    }
}
