package stirling.software.officeconvert.topdf.xls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.poi.hssf.record.NumberRecord;
import org.apache.poi.hssf.record.RecordBase;
import org.apache.poi.hssf.record.UnknownRecord;
import org.apache.poi.hssf.usermodel.HSSFCellStyle;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.formula.ptg.Area3DPtg;
import org.apache.poi.ss.util.AreaReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.testing.Fixtures;

class XlsChartTest {

    private static final int BAR = 0x1017;

    private static final int LINE = 0x1018;

    @TempDir
    Path dir;

    @Test
    void aChartSheetPrintsItsChartOnOnePage() throws Exception {
        byte[] data = XlsTest.xls(wb -> {
            wb.createSheet("Data").createRow(0).createCell(0).setCellValue("Data sheet");
            ChartFixtures.chartSheet(wb, "Chart1", ChartFixtures.chart("Quarterly sales", BAR, new byte[6],
                    new String[] {"Sales"}, new String[] {"Q1", "Q2", "Q3"}, new double[][] {{3, 5, 4}}));
        });
        Path in = Fixtures.write(dir, "chartsheet.xls", data);
        Path pdf = dir.resolve("chartsheet.pdf");
        XlsTest.Converted out = XlsTest.read(OfficeToPdf.convert(in, pdf,
                OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2))), pdf);
        assertEquals(2, out.pages().size(), out.all());
        assertTrue(out.pages().get(1).contains("Quarterly sales"), out.pages().get(1));
        assertTrue(out.pages().get(1).contains("Q2"), out.pages().get(1));
        assertFalse(out.warnings().contains("chart"), out.warnings());
    }

    @Test
    void barFlagsAndAutomaticColoursCarryOver() throws Exception {
        byte[] data = XlsTest.xls(wb -> ChartFixtures.chartSheet(wb, "Chart1", ChartFixtures.chart(null, BAR,
                new byte[] {(byte) 0x9C, (byte) 0xFF, 50, 0, 3, 0}, new String[] {"A", "B"},
                new String[] {"x", "y"}, new double[][] {{1, 2}, {3, 4}})));
        String chart = chartXml(data);
        assertTrue(chart.contains("<c:barDir val=\"bar\"/>"), chart);
        assertTrue(chart.contains("<c:grouping val=\"stacked\"/>"), chart);
        assertTrue(chart.contains("<c:overlap val=\"100\"/>"), chart);
        assertTrue(chart.contains("<c:gapWidth val=\"50\"/>"), chart);
        assertTrue(chart.contains("9999FF") && chart.contains("993366"), chart);
    }

    @Test
    void lineSeriesTakeTheAutomaticLineColours() throws Exception {
        byte[] data = XlsTest.xls(wb -> ChartFixtures.chartSheet(wb, "Chart1", ChartFixtures.chart("Trend", LINE,
                new byte[2], new String[] {"A"}, new String[] {"x", "y", "z"}, new double[][] {{1, 2, 3}})));
        String chart = chartXml(data);
        assertTrue(chart.contains("<c:lineChart>"), chart);
        assertTrue(chart.contains("<a:ln w=\"9525\"><a:solidFill><a:srgbClr val=\"000080\"/>"), chart);
        assertTrue(chart.contains("<c:v>z</c:v>") && chart.contains("<c:v>3.0</c:v>"), chart);
    }

    @Test
    void aChartWithoutCachedValuesReadsTheCellsItRefersTo() throws Exception {
        byte[] data = XlsTest.xls(wb -> {
            HSSFSheet sheet = wb.createSheet("Data");
            for (int i = 0; i < 3; i++) {
                sheet.createRow(i).createCell(0).setCellValue(10 * (i + 1));
            }
            int extern = wb.getInternalWorkbook().checkExternSheet(0);
            ChartFixtures.chartSheet(wb, "Chart1", ChartFixtures.linkedChart(BAR, "Linked", 3,
                    new Area3DPtg(new AreaReference("A1:A3", SpreadsheetVersion.EXCEL97), extern)));
        });
        String chart = chartXml(data);
        assertTrue(chart.contains("<c:v>10.0</c:v>") && chart.contains("<c:v>30.0</c:v>"), chart);
    }

    @Test
    void theCellsAChartLinksToOutrankItsCachedValues() throws Exception {
        byte[] data = XlsTest.xls(wb -> {
            HSSFSheet sheet = wb.createSheet("Data");
            for (int i = 0; i < 3; i++) {
                sheet.createRow(i).createCell(0).setCellValue(10 * (i + 1));
            }
            int extern = wb.getInternalWorkbook().checkExternSheet(0);
            List<RecordBase> chart = ChartFixtures.linkedChart(BAR, "Linked", 3,
                    new Area3DPtg(new AreaReference("A1:A3", SpreadsheetVersion.EXCEL97), extern));
            chart.add(new UnknownRecord(0x1065, new byte[] {1, 0}));
            for (int i = 0; i < 3; i++) {
                NumberRecord stale = new NumberRecord();
                stale.setRow(i);
                stale.setValue(0);
                chart.add(stale);
            }
            ChartFixtures.chartSheet(wb, "Chart1", chart);
        });
        String chart = chartXml(data);
        assertTrue(chart.contains("<c:v>10.0</c:v>") && chart.contains("<c:v>30.0</c:v>"), chart);
        assertFalse(chart.contains("<c:v>0.0</c:v>"), chart);
    }

    @Test
    void aSeriesNamedByACellTakesTheCellText() throws Exception {
        byte[] data = XlsTest.xls(wb -> {
            HSSFSheet sheet = wb.createSheet("Data");
            sheet.createRow(0).createCell(0).setCellValue("Revenue");
            for (int i = 1; i < 4; i++) {
                sheet.createRow(i).createCell(0).setCellValue(i);
            }
            int extern = wb.getInternalWorkbook().checkExternSheet(0);
            ChartFixtures.chartSheet(wb, "Chart1", ChartFixtures.linkedChart(BAR, null, 3,
                    new Area3DPtg(new AreaReference("A2:A4", SpreadsheetVersion.EXCEL97), extern),
                    null, new Area3DPtg(new AreaReference("A1:A1", SpreadsheetVersion.EXCEL97), extern)));
        });
        String chart = chartXml(data);
        assertTrue(chart.contains("<c:tx><c:v>Revenue</c:v></c:tx>"), chart);
    }

    @Test
    void aSeriesNamedByAWholeColumnKeepsAShortName() throws Exception {
        String text = "n".repeat(2_000);
        byte[] data = XlsTest.xls(wb -> {
            HSSFSheet sheet = wb.createSheet("Data");
            for (int i = 0; i < 4_096; i++) {
                sheet.createRow(i).createCell(0).setCellValue(text);
                sheet.getRow(i).createCell(1).setCellValue(i);
            }
            int extern = wb.getInternalWorkbook().checkExternSheet(0);
            ChartFixtures.chartSheet(wb, "Chart1", ChartFixtures.linkedChart(BAR, null, 3,
                    new Area3DPtg(new AreaReference("B1:B3", SpreadsheetVersion.EXCEL97), extern),
                    null, new Area3DPtg(new AreaReference("A1:A4096", SpreadsheetVersion.EXCEL97), extern)));
        });
        String chart = chartXml(data);
        assertTrue(chart.length() < 100_000, chart.length() + " characters of chart");
        assertTrue(chart.contains("<c:tx><c:v>" + text + " n"), "the name starts with the cells' text");
    }

    @Test
    void clusteredHorizontalBarsListTheLastSeriesFirst() throws Exception {
        byte[] data = XlsTest.xls(wb -> ChartFixtures.chartSheet(wb, "Chart1", ChartFixtures.chart(null, BAR,
                new byte[] {0, 0, (byte) 150, 0, 1, 0}, new String[] {"First", "Second"},
                new String[] {"x", "y"}, new double[][] {{1, 2}, {3, 4}})));
        String chart = chartXml(data);
        assertTrue(chart.contains("<c:barDir val=\"bar\"/>"), chart);
        assertTrue(chart.indexOf("<c:v>Second</c:v>") < chart.indexOf("<c:v>First</c:v>"), chart);
    }

    @Test
    void radarChartsStayRadarsAndAFilledOneIsFilled() throws Exception {
        for (int type : new int[] {0x103E, 0x1040}) {
            byte[] data = XlsTest.xls(wb -> ChartFixtures.chartSheet(wb, "Chart1", ChartFixtures.chart(null, type,
                    new byte[2], new String[] {"Speed"}, new String[] {"a", "b", "c"}, new double[][] {{1, 2, 3}})));
            String chart = chartXml(data);
            assertTrue(chart.contains("<c:radarChart><c:radarStyle val=\"" + (type == 0x1040 ? "filled" : "marker")),
                    chart);
            assertTrue(chart.contains("<c:spPr><a:noFill/></c:spPr></c:plotArea>"), chart);
        }
    }

    @Test
    void dateCategoriesKeepTheFormatOfTheirCells() throws Exception {
        byte[] data = XlsTest.xls(wb -> {
            HSSFSheet sheet = wb.createSheet("Data");
            HSSFCellStyle date = wb.createCellStyle();
            date.setDataFormat(wb.createDataFormat().getFormat("m/d/yyyy"));
            for (int i = 0; i < 3; i++) {
                HSSFRow row = sheet.createRow(i);
                row.createCell(0).setCellValue(36526 + i);
                row.getCell(0).setCellStyle(date);
                row.createCell(1).setCellValue(i + 1);
            }
            int extern = wb.getInternalWorkbook().checkExternSheet(0);
            ChartFixtures.chartSheet(wb, "Chart1", ChartFixtures.linkedChart(BAR, "Dated", 3,
                    new Area3DPtg(new AreaReference("B1:B3", SpreadsheetVersion.EXCEL97), extern),
                    new Area3DPtg(new AreaReference("A1:A3", SpreadsheetVersion.EXCEL97), extern), null));
        });
        String chart = chartXml(data);
        assertTrue(chart.contains("<c:v>1/1/2000</c:v>") && chart.contains("<c:v>1/3/2000</c:v>"), chart);
        assertFalse(chart.contains("<c:v>36526"), chart);
    }

    @Test
    void longCategoryTextFromCellsIsCappedPerCategory() throws Exception {
        String longText = "c".repeat(32_767);
        byte[] data = XlsTest.xls(wb -> {
            HSSFSheet sheet = wb.createSheet("Data");
            for (int i = 0; i < 20; i++) {
                HSSFRow row = sheet.createRow(i);
                row.createCell(0).setCellValue(longText);
                row.createCell(1).setCellValue(i);
            }
            int extern = wb.getInternalWorkbook().checkExternSheet(0);
            ChartFixtures.chartSheet(wb, "Chart1", ChartFixtures.linkedChart(BAR, "Linked", 20,
                    new Area3DPtg(new AreaReference("B1:B20", SpreadsheetVersion.EXCEL97), extern),
                    new Area3DPtg(new AreaReference("A1:A20", SpreadsheetVersion.EXCEL97), extern), null));
        });
        String chart = chartXml(data);
        assertTrue(chart.contains("<c:v>" + "c".repeat(ChartRecords.MAX_TEXT_CHARS) + "</c:v>"), "category kept");
        assertFalse(chart.contains("c".repeat(ChartRecords.MAX_TEXT_CHARS + 1)), "category capped");
        assertTrue(chart.length() < 20 * (ChartRecords.MAX_TEXT_CHARS + 100) + 10_000, "chart xml " + chart.length());
    }

    @Test
    void longCachedCategoryTextIsCappedPerCategory() throws Exception {
        String chart = chartXml(XlsTest.xls(wb -> ChartFixtures.chartSheet(wb, "Chart1", ChartFixtures.chart(null,
                BAR, new byte[6], new String[] {"A"}, new String[] {"z".repeat(6000), "y"},
                new double[][] {{1, 2}}))));
        assertTrue(chart.contains("<c:v>" + "z".repeat(ChartRecords.MAX_TEXT_CHARS) + "</c:v>"), "category kept");
        assertFalse(chart.contains("z".repeat(ChartRecords.MAX_TEXT_CHARS + 1)), "category capped");
    }

    private static String chartXml(byte[] xls) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(xls))) {
            XlsPackage.write(fs.getRoot(), out);
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().equals("xl/charts/chart1.xml")) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return "";
    }
}
