package stirling.software.officeconvert.csv;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.sheet.CellValue;
import stirling.software.officeconvert.sheet.NumberFormat;
import stirling.software.officeconvert.sheet.Row;
import stirling.software.officeconvert.sheet.WorkbookSink;

class CsvWriterTest {

    private static Row.Cell cell(int col, CellValue v) {
        return new Row.Cell(col, v, null);
    }

    @Test
    void quotesWhatNeedsQuotingAndKeepsGapsAndTables() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (CsvWriter w = new CsvWriter(bytes)) {
            w.startSheet(new WorkbookSink.SheetSetup("T1", false, true, null, null));
            w.row(new Row(0, Float.NaN, List.of(cell(0, CellValue.text("Region")), cell(2, CellValue.text("Say \"hi\", then")))));
            w.row(new Row(1, Float.NaN, List.of(cell(0, CellValue.text("North")), cell(1, CellValue.number("41,250", 41250, NumberFormat.GENERAL)),
                    cell(2, CellValue.number("", 0.5, NumberFormat.GENERAL)))));
            w.endSheet(new WorkbookSink.SheetEnd(List.of(), 0, List.of()));
            w.startSheet(new WorkbookSink.SheetSetup("T2", false, true, null, null));
            w.row(new Row(0, Float.NaN, List.of(cell(1, CellValue.text(" padded")))));
            w.endSheet(new WorkbookSink.SheetEnd(List.of(), 0, List.of()));
            w.finish(null, null);
        }
        assertEquals("Region,,\"Say \"\"hi\"\", then\"\r\nNorth,\"41,250\",0.5\r\n\r\n,\" padded\"\r\n",
                bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void textThatASpreadsheetWouldRunAsAFormulaIsKeptAsText() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (CsvWriter w = new CsvWriter(bytes)) {
            w.startSheet(new WorkbookSink.SheetSetup("T1", false, true, null, null));
            w.row(new Row(0, Float.NaN, List.of(cell(0, CellValue.text("=HYPERLINK(\"http://x\")")),
                    cell(1, CellValue.text("@SUM(A1)")), cell(2, CellValue.number("-5", -5, NumberFormat.GENERAL)),
                    cell(3, CellValue.text("-1,250.5")), cell(4, CellValue.text("-cmd")), cell(5, CellValue.text("-")))));
            w.endSheet(new WorkbookSink.SheetEnd(List.of(), 0, List.of()));
            w.finish(null, null);
        }
        assertEquals("\"'=HYPERLINK(\"\"http://x\"\")\",'@SUM(A1),-5,\"-1,250.5\",'-cmd,-\r\n", bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void anEmptySheetAddsNoBlankLine() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (CsvWriter w = new CsvWriter(bytes)) {
            for (String name : new String[] {"T1", "Empty", "T2", "Last"}) {
                w.startSheet(new WorkbookSink.SheetSetup(name, false, true, null, null));
                if (name.startsWith("T")) {
                    w.row(new Row(0, Float.NaN, List.of(cell(0, CellValue.text(name)))));
                }
                w.endSheet(new WorkbookSink.SheetEnd(List.of(), 0, List.of()));
            }
            w.finish(null, null);
        }
        assertEquals("T1\r\n\r\nT2\r\n", bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void everyRowOfATableHasTheTableWidth() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (CsvWriter w = new CsvWriter(bytes)) {
            w.startSheet(new WorkbookSink.SheetSetup("T1", false, true, null, null));
            w.row(new Row(0, Float.NaN, List.of(cell(0, CellValue.text("Title")))));
            w.row(new Row(1, Float.NaN, List.of(cell(0, CellValue.text("a")), cell(1, CellValue.text("b")), cell(2, CellValue.text("c")))));
            w.row(new Row(2, Float.NaN, List.of(cell(0, CellValue.text("d")), new Row.Cell(1, CellValue.text("e"), null, 1, 2))));
            w.endSheet(new WorkbookSink.SheetEnd(List.of(), 0, List.of(new WorkbookSink.NamedRange("Table1", 1, 0, 2, 2))));
            w.finish(null, null);
        }
        assertEquals("Title\r\na,b,c\r\nd,e,\r\n", bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aMergeAnchorKeepsItsColumnWhenAFrameCellSharesIt() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (CsvWriter w = new CsvWriter(bytes)) {
            w.startSheet(new WorkbookSink.SheetSetup("T1", false, true, null, null));
            w.row(new Row(0, Float.NaN, List.of(cell(0, CellValue.text("x")), cell(1, CellValue.text("")),
                    new Row.Cell(1, CellValue.text("merged"), null, 1, 2), cell(3, CellValue.text("y")))));
            w.endSheet(new WorkbookSink.SheetEnd(List.of(), 0, List.of()));
            w.finish(null, null);
        }
        assertEquals("x,merged,,y\r\n", bytes.toString(StandardCharsets.UTF_8));
    }
}
