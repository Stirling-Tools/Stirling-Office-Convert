package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.nio.file.Path;
import java.util.Calendar;
import java.util.TimeZone;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.Fixtures;

class XlsxRendererTest {

    @TempDir
    Path dir;

    @Test
    void numberFormatsUseTheCachedValuesWithExcelsDisplayRules() throws Exception {
        byte[] xlsx = XlsxTesting.workbook(wb -> {
            XSSFSheet s = wb.createSheet("Values");
            Row r = s.createRow(0);
            r.createCell(0).setCellValue("Label");
            r.createCell(1).setCellValue(1234.5);
            r.getCell(1).setCellStyle(format(wb, "#,##0.00"));
            r.createCell(2).setCellValue(0.125);
            r.getCell(2).setCellStyle(format(wb, "0.0%"));
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            c.clear();
            c.set(2024, Calendar.JANUARY, 15);
            r.createCell(3).setCellValue(c);
            r.getCell(3).setCellStyle(format(wb, "yyyy-mm-dd"));
            r.createCell(4).setCellValue(true);
            r.createCell(5).setCellValue(-42);
            r.getCell(5).setCellStyle(format(wb, "0;(0)"));
            for (int i = 0; i < 6; i++) {
                s.setColumnWidth(i, 14 * 256);
            }
        });
        String text = XlsxTesting.convert(dir, "values.xlsx", xlsx).all();
        for (String want : new String[] {"Label", "1,234.50", "12.5%", "2024-01-15", "TRUE", "(42)"}) {
            assertTrue(text.contains(want), want + " in " + text);
        }
    }

    @Test
    void formulasShowTheirCachedResultAndAreNeverRecalculated() throws Exception {
        byte[] xlsx = XlsxTesting.workbook(wb -> {
            XSSFSheet s = wb.createSheet("F");
            Row r = s.createRow(0);
            r.createCell(0).setCellValue(2);
            r.createCell(1).setCellValue(3);
            XSSFCell sum = (XSSFCell) r.createCell(2);
            sum.setCellFormula("A1+B1");
            sum.getCTCell().setV("777");
            XSSFCell text = (XSSFCell) r.createCell(3);
            text.setCellFormula("\"x\"&\"y\"");
            text.getCTCell().setT(org.openxmlformats.schemas.spreadsheetml.x2006.main.STCellType.STR);
            text.getCTCell().setV("CACHEDTEXT");
        });
        String text = XlsxTesting.convert(dir, "formula.xlsx", xlsx).all();
        assertTrue(text.contains("777"), text);
        assertFalse(text.contains(" 5"), text);
        assertTrue(text.contains("CACHEDTEXT"), text);
        assertFalse(text.contains("xy"), text);
    }

    @Test
    void hiddenSheetsRowsAndColumnsAreLeftOut() throws Exception {
        byte[] xlsx = XlsxTesting.workbook(wb -> {
            XSSFSheet shown = wb.createSheet("Shown");
            shown.createRow(0).createCell(0).setCellValue("visible");
            shown.getRow(0).createCell(1).setCellValue("hiddencolumn");
            shown.createRow(1).createCell(0).setCellValue("hiddenrow");
            shown.createRow(2).createCell(0).setCellValue("afterwards");
            shown.setColumnHidden(1, true);
            shown.getRow(1).setZeroHeight(true);
            XSSFSheet hidden = wb.createSheet("Hidden");
            hidden.createRow(0).createCell(0).setCellValue("secretsheet");
            wb.setSheetHidden(1, true);
        });
        XlsxTesting.Converted out = XlsxTesting.convert(dir, "hidden.xlsx", xlsx);
        assertEquals(1, out.pages().size());
        assertTrue(out.all().contains("visible") && out.all().contains("afterwards"), out.all());
        assertFalse(out.all().contains("hiddencolumn"), out.all());
        assertFalse(out.all().contains("hiddenrow"), out.all());
        assertFalse(out.all().contains("secretsheet"), out.all());
    }

    @Test
    void thePrintAreaLimitsWhatIsPrinted() throws Exception {
        byte[] xlsx = XlsxTesting.workbook(wb -> {
            XSSFSheet s = wb.createSheet("Area");
            for (int i = 0; i < 10; i++) {
                s.createRow(i).createCell(0).setCellValue("row" + i);
            }
            wb.setPrintArea(0, 0, 0, 2, 4);
        });
        String text = XlsxTesting.convert(dir, "area.xlsx", xlsx).all();
        assertTrue(text.contains("row2") && text.contains("row4"), text);
        assertFalse(text.contains("row1") || text.contains("row5") || text.contains("row9"), text);
    }

    @Test
    void manualBreaksStartPagesAndFitToPageShrinksToOne() throws Exception {
        byte[] broken = XlsxTesting.workbook(wb -> {
            XSSFSheet s = rows(wb, 6);
            s.setRowBreak(2);
        });
        XlsxTesting.Converted two = XlsxTesting.convert(dir, "breaks.xlsx", broken);
        assertEquals(2, two.pages().size());
        assertTrue(two.pages().get(0).contains("line2") && !two.pages().get(0).contains("line3"), two.pages().get(0));
        byte[] tall = XlsxTesting.workbook(wb -> rows(wb, 300));
        assertTrue(XlsxTesting.convert(dir, "tall.xlsx", tall).pages().size() > 3);
        byte[] fitted = XlsxTesting.workbook(wb -> {
            XSSFSheet s = rows(wb, 300);
            s.setFitToPage(true);
            s.getPrintSetup().setFitWidth((short) 1);
            s.getPrintSetup().setFitHeight((short) 1);
        });
        XlsxTesting.Converted one = XlsxTesting.convert(dir, "fit.xlsx", fitted);
        assertEquals(1, one.pages().size());
        assertTrue(one.all().contains("line299"), one.all());
    }

    @Test
    void paperSizeAndOrientationComeFromThePageSetup() throws Exception {
        byte[] xlsx = XlsxTesting.workbook(wb -> {
            XSSFSheet a3 = rows(wb, 2);
            a3.getPrintSetup().setPaperSize(PrintSetup.A3_PAPERSIZE);
            a3.getPrintSetup().setLandscape(true);
            XSSFSheet legal = wb.createSheet("Legal");
            legal.createRow(0).createCell(0).setCellValue("legal");
            legal.getPrintSetup().setPaperSize(PrintSetup.LEGAL_PAPERSIZE);
        });
        XlsxTesting.Converted out = XlsxTesting.convert(dir, "paper.xlsx", xlsx);
        assertEquals(2, out.sizes().size());
        assertEquals(1190.55f, out.sizes().get(0)[0], 1f);
        assertEquals(841.89f, out.sizes().get(0)[1], 1f);
        assertEquals(612f, out.sizes().get(1)[0], 1f);
        assertEquals(1008f, out.sizes().get(1)[1], 1f);
    }

    @Test
    void headerAndFooterCodesAreExpandedPerPage() throws Exception {
        byte[] xlsx = XlsxTesting.workbook(wb -> {
            XSSFSheet s = rows(wb, 6);
            s.setRowBreak(2);
            s.getHeader().setCenter("&A report");
            s.getFooter().setRight("Page &P of &N");
        });
        XlsxTesting.Converted out = XlsxTesting.convert(dir, "hf.xlsx", xlsx);
        assertEquals(2, out.pages().size());
        assertTrue(out.pages().get(0).contains("Lines report"), out.pages().get(0));
        assertTrue(out.pages().get(0).contains("Page 1 of 2"), out.pages().get(0));
        assertTrue(out.pages().get(1).contains("Page 2 of 2"), out.pages().get(1));
    }

    @Test
    void printTitlesRepeatOnEveryPage() throws Exception {
        byte[] xlsx = XlsxTesting.workbook(wb -> {
            XSSFSheet s = wb.createSheet("T");
            s.createRow(0).createCell(0).setCellValue("TITLEROW");
            for (int i = 1; i < 200; i++) {
                s.createRow(i).createCell(0).setCellValue("item" + i);
            }
            s.setRepeatingRows(CellRangeAddress.valueOf("1:1"));
        });
        XlsxTesting.Converted out = XlsxTesting.convert(dir, "titles.xlsx", xlsx);
        assertTrue(out.pages().size() >= 3);
        for (String page : out.pages()) {
            assertTrue(page.contains("TITLEROW"), page);
        }
    }

    @Test
    void mergedCellsDrawTheirTextOnceAndOverflowStopsAtValues() throws Exception {
        byte[] xlsx = XlsxTesting.workbook(wb -> {
            XSSFSheet s = wb.createSheet("M");
            Row r = s.createRow(0);
            r.createCell(0).setCellValue("Merged heading");
            CellStyle center = wb.createCellStyle();
            center.setAlignment(HorizontalAlignment.CENTER);
            r.getCell(0).setCellStyle(center);
            s.addMergedRegion(CellRangeAddress.valueOf("A1:D1"));
            s.createRow(1).createCell(0).setCellValue(123456789.123);
            s.getRow(1).getCell(0).setCellStyle(format(wb, "#,##0.00"));
            s.setColumnWidth(0, 3 * 256);
        });
        String text = XlsxTesting.convert(dir, "merged.xlsx", xlsx).all();
        assertEquals(1, text.split("Merged heading", -1).length - 1, text);
        assertTrue(text.contains("##"), text);
        assertFalse(text.contains("123,456"), text);
    }

    @Test
    void textOverflowingPastThePageEdgeContinuesOnTheNextPage() throws Exception {
        String tail = "overflowtail";
        byte[] xlsx = XlsxTesting.workbook(wb -> {
            XSSFSheet s = wb.createSheet("O");
            for (int i = 0; i < 12; i++) {
                s.setColumnWidth(i, 20 * 256);
            }
            s.createRow(0).createCell(0).setCellValue("start " + "long overflowing words ".repeat(12) + tail);
            s.createRow(1).createCell(0).setCellValue("short");
        });
        XlsxTesting.Converted out = XlsxTesting.convert(dir, "overflow.xlsx", xlsx);
        assertTrue(out.pages().size() >= 3, "pages: " + out.pages().size());
        assertTrue(out.pages().get(0).contains("short"), out.pages().get(0));
        assertFalse(out.pages().get(1).isBlank(), "second page is empty");
        assertFalse(out.pages().get(out.pages().size() - 1).contains("short"));
    }

    @Test
    void anEmptyWorkbookPrintsOneBlankPage() throws Exception {
        byte[] xlsx = XlsxTesting.workbook(wb -> wb.createSheet("Empty"));
        XlsxTesting.Converted out = XlsxTesting.convert(dir, "empty.xlsx", xlsx);
        assertEquals(1, out.pages().size());
        assertTrue(out.all().isBlank(), out.all());
    }

    @Test
    void picturesAnchoredToCellsAreDrawn() throws Exception {
        byte[] xlsx = XlsxTesting.workbook(wb -> {
            XSSFSheet s = wb.createSheet("P");
            s.createRow(0).createCell(0).setCellValue("with picture");
            int pic = wb.addPicture(Fixtures.png(20, 10, Color.BLUE), Workbook.PICTURE_TYPE_PNG);
            XSSFDrawing d = s.createDrawingPatriarch();
            XSSFClientAnchor anchor = new XSSFClientAnchor(0, 0, 0, 0, 1, 2, 4, 8);
            anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_AND_RESIZE);
            d.createPicture(anchor, pic);
        });
        XlsxTesting.Converted out = XlsxTesting.convert(dir, "picture.xlsx", xlsx);
        assertEquals(1, out.pages().size());
        assertEquals(1, out.images());
    }

    @Test
    void headingsAndWrappedTextAreDrawn() throws Exception {
        byte[] xlsx = XlsxTesting.workbook(wb -> {
            XSSFSheet s = wb.createSheet("H");
            Row r = s.createRow(0);
            r.createCell(0).setCellValue("alpha beta gamma delta epsilon zeta");
            CellStyle wrap = wb.createCellStyle();
            wrap.setWrapText(true);
            r.getCell(0).setCellStyle(wrap);
            s.setColumnWidth(0, 10 * 256);
            s.setPrintRowAndColumnHeadings(true);
            s.setPrintGridlines(true);
        });
        XlsxTesting.Converted out = XlsxTesting.convert(dir, "headings.xlsx", xlsx);
        String text = out.all();
        assertTrue(text.contains("A"), text);
        assertTrue(text.lines().anyMatch(l -> l.trim().equals("1")), text);
        assertTrue(text.lines().filter(l -> l.contains("alpha") || l.contains("zeta")).count() >= 1, text);
        assertFalse(text.lines().anyMatch(l -> l.contains("alpha") && l.contains("zeta")), text);
    }

    @Test
    void theStreamingReaderHandlesImplicitReferencesAndEveryValueType() throws Exception {
        Fixtures.Zip z = Fixtures.edit(Fixtures.xlsx(new String[][] {{"x"}}));
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            rows.append("<row><c><v>").append(i).append("</v></c><c t=\"inlineStr\"><is><r><rPr><b/></rPr><t>bold")
                    .append(i).append("</t></r><r><t> plain</t></r></is></c></row>");
        }
        String data = rows.toString().replace("<", "<x:").replace("<x:/", "</x:");
        z.put("xl/worksheets/sheet1.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><x:worksheet xmlns:x="
                + "\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><x:sheetData><x:row r=\"1\">"
                + "<x:c r=\"A1\" t=\"b\"><x:v>1</x:v></x:c><x:c r=\"B1\" t=\"e\"><x:v>#DIV/0!</x:v></x:c>"
                + "<x:c r=\"C1\" t=\"str\"><x:f>1+1</x:f><x:v>cached</x:v></x:c><x:c r=\"D1\" t=\"d\"><x:v>"
                + "2024-03-05T00:00:00</x:v></x:c></x:row>" + data + "</x:sheetData><x:pageMargins left=\"0.7\""
                + " right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/></x:worksheet>");
        XlsxTesting.Converted out = XlsxTesting.convert(dir, "stream.xlsx", z.bytes());
        String text = out.all();
        for (String want : new String[] {"TRUE", "#DIV/0!", "cached", "bold0 plain", "bold2999 plain", "2999"}) {
            assertTrue(text.contains(want), want);
        }
        assertTrue(out.pages().size() > 20, String.valueOf(out.pages().size()));
    }

    private static XSSFSheet rows(XSSFWorkbook wb, int n) {
        XSSFSheet s = wb.createSheet("Lines");
        for (int i = 0; i < n; i++) {
            s.createRow(i).createCell(0).setCellValue("line" + i);
        }
        return s;
    }

    private static CellStyle format(XSSFWorkbook wb, String format) {
        CellStyle st = wb.createCellStyle();
        st.setDataFormat(wb.createDataFormat().getFormat(format));
        return st;
    }
}
