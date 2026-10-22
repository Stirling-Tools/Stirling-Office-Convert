package stirling.software.officeconvert.sink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.Pictures;
import stirling.software.officeconvert.build.DocSink;
import stirling.software.officeconvert.docx.DocxWriter;
import stirling.software.officeconvert.html.HtmlWriter;
import stirling.software.officeconvert.json.Json;
import stirling.software.officeconvert.json.JsonModel;
import stirling.software.officeconvert.json.JsonWriter;
import stirling.software.officeconvert.model.Block;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture.MediaRef;
import stirling.software.officeconvert.model.Section;
import stirling.software.officeconvert.model.StyleSheet;
import stirling.software.officeconvert.model.Table;
import stirling.software.officeconvert.odt.OdtWriter;
import stirling.software.officeconvert.rtf.RtfWriter;
import stirling.software.officeconvert.text.TextWriter;

class NestedTablesTest {

    private static Table table(String... cells) {
        Table t = new Table();
        Table.Row row = new Table.Row();
        for (String text : cells) {
            t.columnWidths.add(80f);
            Table.Cell cell = new Table.Cell();
            cell.paragraphs.add(SampleDocument.paragraph(text));
            row.cells.add(cell);
        }
        t.rows.add(row);
        return t;
    }

    private static Table outer() {
        Table t = table("Outer A", "Before inner");
        Table.Cell holder = t.rows.getFirst().cells.get(1);
        Table inner = table("Inner 1", "Inner 2");
        inner.rows.getFirst().cells.getFirst().add(table("Deep x", "Deep y"));
        holder.add(inner);
        holder.add(SampleDocument.paragraph("After inner"));
        holder.add(table("Last 1", "Last 2"));
        return t;
    }

    private static Table nestedDeep(int levels) {
        Table t = table("Level " + levels);
        if (levels > 1) {
            t.rows.getFirst().cells.getFirst().add(nestedDeep(levels - 1));
        }
        return t;
    }

    private static void write(DocSink sink, Block... blocks) throws IOException {
        StyleSheet styles = new StyleSheet(SampleDocument.BODY);
        sink.begin(styles, null);
        for (Block b : blocks) {
            sink.block(b);
        }
        sink.finish(JsonModel.defaultPage(), null, new Numbering(), styles, "Nested", "Tester");
    }

    private static String part(byte[] zip, String name) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (e.getName().equals(name)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError(name + " missing");
    }

    private static void inOrder(String s, String... words) {
        int at = -1;
        for (String w : words) {
            int next = s.indexOf(w, at + 1);
            assertTrue(next > at, w + " out of order in " + s);
            at = next;
        }
    }

    private static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void aCellKeepsItsParagraphsAndTablesInOrder() {
        Table.Cell holder = outer().rows.getFirst().cells.get(1);
        List<Block> blocks = holder.blocks();
        assertEquals(4, blocks.size());
        assertTrue(blocks.get(0) instanceof Paragraph);
        assertTrue(blocks.get(1) instanceof Table);
        assertTrue(blocks.get(2) instanceof Paragraph);
        assertTrue(blocks.get(3) instanceof Table);
        assertEquals(2, holder.paragraphs.size());
        assertEquals(3, outer().nesting());
        List<String> flat = new ArrayList<>();
        for (Paragraph p : holder.flatParagraphs()) {
            flat.add(text(p));
        }
        assertEquals(List.of("Before inner", "Inner 1", "Deep x", "Deep y", "Inner 2", "After inner", "Last 1", "Last 2"),
                flat);
        assertFalse(holder.empty());
        assertTrue(new Table.Cell().empty());
    }

    @Test
    void wordNestsTablesAndClosesACellEndingInOneWithAParagraph() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DocxWriter w = new DocxWriter(out)) {
            write(w, outer());
        }
        String xml = part(out.toByteArray(), "word/document.xml");
        assertEquals(4, count(xml, "<w:tbl>"));
        inOrder(xml, "Outer A", "Before inner", "<w:tbl>", "Inner 1", "<w:tbl>", "Deep x", "Deep y", "Inner 2",
                "After inner", "<w:tbl>", "Last 1", "Last 2");
        assertTrue(Pattern.compile("</w:tbl><w:p>(?:(?!</?w:t[bc][ >]).)*</w:p></w:tc>").matcher(xml).find(), xml);
        assertFalse(xml.contains("</w:tbl></w:tc>"), xml);
    }

    @Test
    void wordFlattensTablesDeeperThanTheLimit() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DocxWriter w = new DocxWriter(out)) {
            write(w, nestedDeep(Table.MAX_NESTING + 3));
        }
        String xml = part(out.toByteArray(), "word/document.xml");
        assertEquals(Table.MAX_NESTING, count(xml, "<w:tbl>"));
        for (int level = 1; level <= Table.MAX_NESTING + 3; level++) {
            assertTrue(xml.contains(">Level " + level + "<"), "Level " + level);
        }
    }

    @Test
    void openDocumentAndHtmlNestTables() throws IOException {
        ByteArrayOutputStream odt = new ByteArrayOutputStream();
        try (OdtWriter w = new OdtWriter(odt, false)) {
            write(w, outer());
        }
        String content = part(odt.toByteArray(), "content.xml");
        assertEquals(4, count(content, "<table:table "));
        inOrder(content, "Before inner", "<table:table ", "Inner 1", "<table:table ", "Deep x", "</table:table>",
                "Inner 2", "</table:table>", "After inner", "Last 1");

        ByteArrayOutputStream html = new ByteArrayOutputStream();
        try (HtmlWriter w = new HtmlWriter(html, Pictures.COMPACT)) {
            write(w, outer());
        }
        String page = html.toString(StandardCharsets.UTF_8);
        assertEquals(4, count(page, "<table"));
        inOrder(page, "Before inner", "<table", "Inner 1", "<table", "Deep x", "</table>", "Inner 2", "</table>",
                "After inner", "<table", "Last 1", "</table>", "</td>");
    }

    @Test
    void textAndRtfKeepNestedTextInReadingOrder() throws IOException {
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        try (TextWriter w = new TextWriter(text)) {
            write(w, outer());
        }
        String plain = text.toString(StandardCharsets.UTF_8);
        inOrder(plain, "Outer A", "Before inner", "Inner 1", "Deep x", "Deep y", "Inner 2", "After inner", "Last 1",
                "Last 2");

        ByteArrayOutputStream rtf = new ByteArrayOutputStream();
        try (RtfWriter w = new RtfWriter(rtf)) {
            write(w, outer());
        }
        inOrder(rtf.toString(StandardCharsets.ISO_8859_1), "Outer A", "Before inner", "Inner 1", "Deep x", "Deep y",
                "Inner 2", "After inner", "Last 1", "Last 2");
    }

    @Test
    void jsonWritesNestedTablesAsCellBlocksAndReadsThemBack() throws IOException {
        String json = json(outer());
        assertTrue(json.contains("\"blocks\":["), json);
        assertTrue(json.contains("\"paragraphs\":["), json);
        List<String> warnings = new ArrayList<>();
        ByteArrayOutputStream again = new ByteArrayOutputStream();
        try (JsonWriter w = new JsonWriter(again, Pictures.COMPACT)) {
            JsonModel.replay(Json.parse(json), w, warnings);
        }
        assertEquals(json, again.toString(StandardCharsets.UTF_8));
        assertEquals(List.of(), warnings);
    }

    @Test
    void jsonReadsTablesDeeperThanTheLimitAsTheirText() throws IOException {
        String json = json(nestedDeep(Table.MAX_NESTING + 2));
        List<String> warnings = new ArrayList<>();
        List<Block> read = new ArrayList<>();
        JsonModel.replay(Json.parse(json), new Collect(read), warnings);
        Table t = (Table) read.getFirst();
        assertEquals(Table.MAX_NESTING, t.nesting());
        assertEquals(1, warnings.size(), warnings.toString());
        List<String> flat = new ArrayList<>();
        for (Paragraph p : t.flatParagraphs()) {
            flat.add(text(p));
        }
        assertEquals(Table.MAX_NESTING + 2, flat.size());
        assertEquals("Level 1", flat.getLast());
    }

    @Test
    void jsonFromBeforeNestingStillReads() throws IOException {
        String json = json(table("Plain"));
        assertFalse(json.contains("\"blocks\""), json);
        Matcher m = Pattern.compile("\"cells\":\\[\\{(.*?)\"paragraphs\"").matcher(json);
        assertTrue(m.find(), json);
        List<String> warnings = new ArrayList<>();
        List<Block> read = new ArrayList<>();
        JsonModel.replay(Json.parse(json), new Collect(read), warnings);
        Table.Cell cell = ((Table) read.getFirst()).rows.getFirst().cells.getFirst();
        assertTrue(cell.tables.isEmpty());
        assertEquals(List.of("Plain"), cell.paragraphs.stream().map(NestedTablesTest::text).toList());
        assertEquals(List.of(), warnings);
    }

    private static String json(Table t) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonWriter w = new JsonWriter(out, Pictures.COMPACT)) {
            write(w, t);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String text(Paragraph p) {
        StringBuilder sb = new StringBuilder();
        for (Inline in : p.inlines) {
            if (in instanceof Inline.Text t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    private record Collect(List<Block> blocks) implements DocSink {
        @Override
        public MediaRef media(byte[] bytes, String ext, int pixelWidth,
                int pixelHeight, Object key) {
            return null;
        }

        @Override
        public MediaRef media(Object key) {
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
        public void finish(Section last, HeaderFooterSet running,
                Numbering numbering, StyleSheet styles, String title,
                String author) {}
    }
}
