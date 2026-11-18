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
                    cell(3, CellValue.text("-1,250.5")), cell(4, CellValue.text("-cmd")))));
            w.endSheet(new WorkbookSink.SheetEnd(List.of(), 0, List.of()));
            w.finish(null, null);
        }
        assertEquals("\"'=HYPERLINK(\"\"http://x\"\")\",'@SUM(A1),-5,\"-1,250.5\",'-cmd\r\n", bytes.toString(StandardCharsets.UTF_8));
    }
}
