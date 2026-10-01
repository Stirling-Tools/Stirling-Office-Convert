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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
