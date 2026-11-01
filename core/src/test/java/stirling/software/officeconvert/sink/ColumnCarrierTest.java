package stirling.software.officeconvert.sink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.build.DocSink;
import stirling.software.officeconvert.docx.DocxWriter;
import stirling.software.officeconvert.model.Block;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.StyleSheet;
import stirling.software.officeconvert.model.Table;
import stirling.software.officeconvert.rtf.RtfWriter;

class ColumnCarrierTest {

    private static final Pattern CARRIER = Pattern.compile("<w:p>(?:<w:pPr>(?:(?!</w:pPr>).)*</w:pPr>)?"
            + "<w:r><w:br w:type=\"column\"/></w:r></w:p>");

    @Test
    void wordOpensTheNextColumnsFirstParagraphWithTheBreak() throws IOException {
        String xml = docx(paragraph("Left column text."), carrier(), paragraph("Right column text."));
        assertFalse(CARRIER.matcher(xml).find(), xml);
        assertTrue(Pattern.compile("<w:br w:type=\"column\"/></w:r>(?:(?!</w:p>).)*Right column text\\.")
                .matcher(xml).find(), xml);
        assertTrue(xml.indexOf("Left column text.") < xml.indexOf("w:type=\"column\""), xml);
    }

    @Test
    void wordPutsTheBreakInAnEmptySpacerAndMovesItsHeightBelowTheBreak() throws IOException {
        Paragraph spacer = spacer(26);
        String xml = docx(paragraph("Left column text."), carrier(), spacer, paragraph("Right column text."));
        assertEquals(1, xml.split("w:type=\"column\"", -1).length - 1, xml);
        assertTrue(Pattern.compile("<w:spacing w:before=\"0\" w:after=\"500\" w:line=\"20\" w:lineRule=\"exact\"/>"
                + "(?:(?!</w:p>).)*<w:br w:type=\"column\"/></w:r></w:p>").matcher(xml).find(), xml);
        assertTrue(xml.indexOf("w:type=\"column\"") < xml.indexOf("Right column text."), xml);
        assertEquals(26f, spacer.lineHeight);
        assertEquals(0f, spacer.spaceAfter);
    }

    @Test
    void rtfPutsTheBreakInAnEmptySpacer() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (RtfWriter w = new RtfWriter(out)) {
            write(w, paragraph("Left column text."), carrier(), spacer(26), paragraph("Right column text."));
        }
        String rtf = out.toString(StandardCharsets.ISO_8859_1);
        int brk = rtf.indexOf("\\column ");
        assertTrue(brk > rtf.indexOf("Left column text."), rtf);
        assertTrue(rtf.substring(rtf.lastIndexOf("\\pard", brk), brk).contains("\\sa500"), rtf);
    }

    @Test
    void wordKeepsTheBreakOnItsOwnBeforeATable() throws IOException {
        Table table = new Table();
        Table.Row row = new Table.Row();
        Table.Cell cell = new Table.Cell();
        cell.paragraphs.add(paragraph("In a cell"));
        row.cells.add(cell);
        table.rows.add(row);
        table.columnWidths.add(80f);
        String xml = docx(paragraph("Left column text."), carrier(), table);
        assertTrue(CARRIER.matcher(xml).find(), xml);
        assertTrue(xml.indexOf("w:type=\"column\"") < xml.indexOf("<w:tbl>"), xml);
    }

    @Test
    void wordKeepsABreakThatEndsTheDocument() throws IOException {
        String xml = docx(paragraph("Left column text."), carrier());
        assertTrue(CARRIER.matcher(xml).find(), xml);
    }

    @Test
    void rtfOpensTheNextColumnsFirstParagraphWithTheBreak() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (RtfWriter w = new RtfWriter(out)) {
            write(w, paragraph("Left column text."), carrier(), paragraph("Right column text."));
        }
        String rtf = out.toString(StandardCharsets.ISO_8859_1);
        int brk = rtf.indexOf("\\column ");
        assertTrue(brk > rtf.indexOf("Left column text."), rtf);
        assertFalse(rtf.substring(brk, rtf.indexOf("Right column text.")).contains("\\par"), rtf);
    }

    private static Paragraph paragraph(String text) {
        return SampleDocument.paragraph(text);
    }

    private static Paragraph carrier() {
        Paragraph p = new Paragraph();
        p.inlines.add(new Inline.ColumnBreak());
        return p;
    }

    private static Paragraph spacer(float height) {
        Paragraph p = new Paragraph();
        p.lineRule = Paragraph.LineRule.EXACT;
        p.lineHeight = height;
        return p;
    }

    private static String docx(Block... blocks) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DocxWriter w = new DocxWriter(out)) {
            write(w, blocks);
        }
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (e.getName().equals("word/document.xml")) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("document.xml missing");
    }

    private static void write(DocSink sink, Block... blocks) throws IOException {
        StyleSheet styles = new StyleSheet(SampleDocument.BODY);
        sink.begin(styles, null);
        for (Block b : blocks) {
            sink.block(b);
        }
        sink.finish(SampleDocument.section(2), null, new Numbering(), styles, "Columns", "Tester");
    }
}
