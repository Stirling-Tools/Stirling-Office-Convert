package stirling.software.officeconvert.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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

class XlsxWriterTest {

    static Map<String, byte[]> parts(byte[] zip) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                out.put(e.getName(), z.readAllBytes());
            }
        }
        return out;
    }

    static Document xml(byte[] part) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(part));
    }

    static byte[] sample() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CellStyle bold = CellStyle.DEFAULT.withBold(true);
        CellValue amount = CellTyper.type("$1,234.50", Conventions.UNKNOWN);
        CellValue day = CellTyper.type("2024-01-05", Conventions.UNKNOWN);
        try (XlsxWriter w = new XlsxWriter(out)) {
            w.startSheet(new SheetSetup("Page 1", true, true, "Annual report", "Page 1 of 2"));
            w.row(new Row(0, Float.NaN, List.of(new Row.Cell(0, CellValue.text("Region & total"), bold, 1, 2))));
            w.row(new Row(1, Float.NaN, List.of(
                    new Row.Cell(0, CellValue.text("North"), CellStyle.DEFAULT),
                    new Row.Cell(1, amount, CellStyle.DEFAULT.withFormat(amount.format())),
                    new Row.Cell(2, day, CellStyle.DEFAULT.withFormat(day.format())))));
            w.row(new Row(3, 30f, List.of(new Row.Cell(0, CellValue.text("  two\nlines _x0041_ \u0001"),
                    CellStyle.DEFAULT.withWrap(true)))));
            w.endSheet(new SheetEnd(List.of(80f, 60f, 70f), 1, List.of(new NamedRange("Page1_Table1", 0, 0, 1, 2))));
            w.startSheet(new SheetSetup("Page 2", false, false, "", ""));
            w.endSheet(new SheetEnd(List.of(), 0, List.of()));
            w.finish("Title <x>", "Author");
        }
        return out.toByteArray();
    }

    @Test
    void everyPartIsWellFormedAndReferenced() throws Exception {
        Map<String, byte[]> parts = parts(sample());
        for (String name : List.of("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels",
                "xl/styles.xml", "xl/sharedStrings.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml",
                "docProps/core.xml", "docProps/app.xml")) {
            assertTrue(parts.containsKey(name), name);
            xml(parts.get(name));
        }
        String types = new String(parts.get("[Content_Types].xml"), StandardCharsets.UTF_8);
        assertTrue(types.contains("/xl/worksheets/sheet2.xml"));
        Document book = xml(parts.get("xl/workbook.xml"));
        assertEquals(2, book.getElementsByTagNameNS("*", "sheet").getLength());
        Element name = (Element) book.getElementsByTagNameNS("*", "definedName").item(0);
        assertEquals("Page1_Table1", name.getAttribute("name"));
        assertEquals("'Page 1'!$A$1:$C$2", name.getTextContent());
        Element titles = (Element) book.getElementsByTagNameNS("*", "definedName").item(1);
        assertEquals("_xlnm.Print_Titles", titles.getAttribute("name"), "frozen header rows print on every page");
        assertEquals("0", titles.getAttribute("localSheetId"));
        assertEquals("'Page 1'!$1:$1", titles.getTextContent());
    }

    @Test
    void cellsKeepTheirTypesStylesAndMerges() throws Exception {
        Map<String, byte[]> parts = parts(sample());
        String sheet = new String(parts.get("xl/worksheets/sheet1.xml"), StandardCharsets.UTF_8);
        assertTrue(sheet.contains("<c r=\"B2\" s=\""), "styled amount");
        assertTrue(sheet.contains("><v>1234.5</v>"), "amount stored as a number");
        assertTrue(sheet.contains("><v>45296</v>"), "date stored as its serial");
        assertTrue(sheet.contains("<mergeCell ref=\"A1:B1\"/>"));
        assertTrue(sheet.contains("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>"));
        assertTrue(sheet.contains("<row r=\"4\" ht=\"30.00\" customHeight=\"1\">"));
        assertTrue(sheet.contains("orientation=\"landscape\""));
        assertTrue(sheet.contains("<oddHeader>&amp;CAnnual report</oddHeader>"));
        Document strings = xml(parts.get("xl/sharedStrings.xml"));
        NodeList si = strings.getElementsByTagNameNS("*", "t");
        assertEquals("Region & total", si.item(0).getTextContent());
        assertEquals("  two\nlines _x005F_x0041_ _x0001_", si.item(2).getTextContent());
        String styles = new String(parts.get("xl/styles.xml"), StandardCharsets.UTF_8);
        assertTrue(styles.contains("formatCode=\"&quot;$&quot;#,##0.00\""), styles);
        assertTrue(styles.contains("formatCode=\"yyyy-mm-dd\""));
        assertTrue(styles.contains("<b/>"));
        String second = new String(parts.get("xl/worksheets/sheet2.xml"), StandardCharsets.UTF_8);
        assertTrue(second.contains("<sheetData/>"));
        assertFalse(second.contains("tabSelected"));
    }

    @Test
    void aWorkbookWithoutSheetsStillOpens() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XlsxWriter w = new XlsxWriter(out)) {
            w.finish(null, null);
        }
        Map<String, byte[]> parts = parts(out.toByteArray());
        assertTrue(parts.containsKey("xl/worksheets/sheet1.xml"));
        xml(parts.get("xl/workbook.xml"));
    }
}
