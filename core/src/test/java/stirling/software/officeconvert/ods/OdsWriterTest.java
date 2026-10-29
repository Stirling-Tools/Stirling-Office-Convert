package stirling.software.officeconvert.ods;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import stirling.software.officeconvert.sheet.CellStyle;
import stirling.software.officeconvert.sheet.CellTyper;
import stirling.software.officeconvert.sheet.CellValue;
import stirling.software.officeconvert.sheet.Conventions;
import stirling.software.officeconvert.sheet.Row;
import stirling.software.officeconvert.sheet.WorkbookSink.NamedRange;
import stirling.software.officeconvert.sheet.WorkbookSink.SheetEnd;
import stirling.software.officeconvert.sheet.WorkbookSink.SheetSetup;

class OdsWriterTest {

    private static byte[] sample() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CellValue pct = CellTyper.type("(12.5%)", Conventions.UNKNOWN);
        CellValue day = CellTyper.type("5 Jan 2024", Conventions.UNKNOWN);
        try (OdsWriter w = new OdsWriter(out)) {
            w.startSheet(new SheetSetup("Page 1", false, true, "Header", ""));
            w.row(new Row(0, Float.NaN, List.of(new Row.Cell(0, CellValue.text("Merged"), CellStyle.DEFAULT, 2, 2),
                    new Row.Cell(2, pct, CellStyle.DEFAULT.withFormat(pct.format())))));
            w.row(new Row(1, Float.NaN, List.of(new Row.Cell(2, day, CellStyle.DEFAULT.withFormat(day.format())))));
            w.row(new Row(3, 20f, List.of(new Row.Cell(1, CellValue.text("a  b\tc"), CellStyle.DEFAULT))));
            w.endSheet(new SheetEnd(List.of(50f, 50f, 90f), 1, List.of(new NamedRange("Page1_Table1", 0, 0, 1, 2))));
            w.finish("Title", "Author");
        }
        return out.toByteArray();
    }

    @Test
    void theSignatureComesFirstUncompressed() throws Exception {
        byte[] zip = sample();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry first = z.getNextEntry();
            assertEquals("mimetype", first.getName());
            assertEquals(ZipEntry.STORED, first.getMethod());
            assertEquals(OdsWriter.MIME, new String(z.readAllBytes(), StandardCharsets.US_ASCII));
        }
        assertEquals(OdsWriter.MIME, new String(zip, 38, OdsWriter.MIME.length(), StandardCharsets.US_ASCII));
    }

    @Test
    void mergesTypesAndNamesAreWritten() throws Exception {
        Document content = null;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(sample()))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                byte[] b = z.readAllBytes();
                if (e.getName().endsWith(".xml")) {
                    Document d = parse(b);
                    if (e.getName().equals("content.xml")) {
                        content = d;
                    }
                }
            }
        }
        NodeList rows = content.getElementsByTagNameNS("*", "table-row");
        assertEquals(4, rows.getLength(), "the skipped row is written empty");
        Element first = (Element) rows.item(0);
        Element merged = (Element) first.getElementsByTagNameNS("*", "table-cell").item(0);
        assertEquals("2", merged.getAttributeNS("urn:oasis:names:tc:opendocument:xmlns:table:1.0", "number-columns-spanned"));
        assertEquals(1, first.getElementsByTagNameNS("*", "covered-table-cell").getLength());
        Element second = (Element) rows.item(1);
        assertEquals(2, second.getElementsByTagNameNS("*", "covered-table-cell").getLength(), "the merge reaches down");
        String office = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";
        Element pct = (Element) first.getElementsByTagNameNS("*", "table-cell").item(1);
        assertEquals("percentage", pct.getAttributeNS(office, "value-type"));
        assertEquals("-0.125", pct.getAttributeNS(office, "value"));
        Element date = (Element) second.getElementsByTagNameNS("*", "table-cell").item(0);
        assertEquals("2024-01-05", date.getAttributeNS(office, "date-value"));
        assertEquals(1, content.getElementsByTagNameNS("*", "named-range").getLength());
        assertTrue(content.getElementsByTagNameNS("*", "s").getLength() > 0, "runs of spaces kept");
        assertEquals(1, content.getElementsByTagNameNS("*", "tab").getLength());
    }

    @Test
    void sheetsWiderThanThePageFitToOnePageWide() throws Exception {
        Element props = pageLayout(sheet(true, List.of(400f, 400f)));
        String style = "urn:oasis:names:tc:opendocument:xmlns:style:1.0";
        assertEquals("1", props.getAttributeNS(style, "scale-to-X"));
        assertEquals("0", props.getAttributeNS(style, "scale-to-Y"));
    }

    @Test
    void sheetsThatFitThePageAreNotScaled() throws Exception {
        Element props = pageLayout(sheet(true, List.of(569.5f)));
        String style = "urn:oasis:names:tc:opendocument:xmlns:style:1.0";
        assertFalse(props.hasAttributeNS(style, "scale-to-X"));
        assertFalse(props.hasAttributeNS(style, "scale-to-Y"));
        assertFalse(pageLayout(sample()).hasAttributeNS(style, "scale-to-X"));
    }

    private static byte[] sheet(boolean landscape, List<Float> widths) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OdsWriter w = new OdsWriter(out)) {
            w.startSheet(new SheetSetup("Page 1", landscape, false, "", ""));
            w.row(new Row(0, Float.NaN, List.of(new Row.Cell(0, CellValue.text("Title"), CellStyle.DEFAULT))));
            w.endSheet(new SheetEnd(widths, 0, List.of()));
            w.finish("Title", "Author");
        }
        return out.toByteArray();
    }

    private static Element pageLayout(byte[] ods) throws Exception {
        Document styles = null;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(ods))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                byte[] b = z.readAllBytes();
                if (e.getName().equals("styles.xml")) {
                    styles = parse(b);
                }
            }
        }
        return (Element) styles.getElementsByTagNameNS("*", "page-layout-properties").item(0);
    }

    private static Document parse(byte[] b) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(b));
    }
}
