package stirling.software.officeconvert.topdf.xls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.hssf.record.NumberRecord;
import org.apache.poi.hssf.record.RecordBase;
import org.apache.poi.hssf.record.UnknownRecord;
import org.apache.poi.hssf.record.chart.BeginRecord;
import org.apache.poi.hssf.record.chart.EndRecord;
import org.apache.poi.hssf.usermodel.HSSFCellStyle;
import org.apache.poi.hssf.usermodel.HSSFFont;
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
    void dataLabelsShowWhatTheSeriesAsksFor() throws Exception {
        List<RecordBase> records = ChartFixtures.chart(null, 0x1019, new byte[6], new String[] {"Share"},
                new String[] {"North", "South"}, new double[][] {{1, 3}});
        records.addAll(5, List.of(new UnknownRecord(0x1006, new byte[] {(byte) 0xFF, (byte) 0xFF, 0, 0, 0, 0, 0, 0}),
                new BeginRecord(), new UnknownRecord(0x100C, new byte[] {4, 0}), new EndRecord()));
        String chart = chartXml(XlsTest.xls(wb -> ChartFixtures.chartSheet(wb, "Chart1", records)));
        assertTrue(chart.contains("<c:showCatName val=\"1\"/><c:showSerName val=\"0\"/><c:showPercent val=\"1\"/>"),
                chart);
        assertTrue(chart.contains("<c:dLblPos val=\"bestFit\"/>"), chart);
        assertTrue(chart.contains("<c:separator>\n</c:separator>"), chart);
    }

    @Test
    void aDataLabelTextSetsWhereTheLabelsSit() throws Exception {
        List<RecordBase> records = ChartFixtures.chart(null, LINE, new byte[2], new String[] {"Score"},
                new String[] {"x", "y"}, new double[][] {{50, 60}});
        records.addAll(5, List.of(new UnknownRecord(0x1006, new byte[] {(byte) 0xFF, (byte) 0xFF, 0, 0, 0, 0, 0, 0}),
                new BeginRecord(), new UnknownRecord(0x100C, new byte[] {1, 0}), new EndRecord()));
        byte[] text = new byte[32];
        text[28] = 5;
        records.addAll(10, List.of(new UnknownRecord(0x1025, text), new BeginRecord(),
                new UnknownRecord(0x1027, new byte[] {4, 0, 0, 0, (byte) 0xFF, (byte) 0xFF}), new EndRecord()));
        String chart = chartXml(XlsTest.xls(wb -> ChartFixtures.chartSheet(wb, "Chart1", records)));
        assertTrue(chart.contains("<c:dLblPos val=\"t\"/><c:showLegendKey val=\"0\"/><c:showVal val=\"1\"/>"),
                chart);
    }

    @Test
    void axisAndLegendFontsAndAHiddenAxisLineCarryOver() throws Exception {
        String chart = chartXml(XlsTest.xls(wb -> {
            HSSFFont big = wb.createFont();
            big.setFontHeightInPoints((short) 14);
            big.setBold(true);
            int font = big.getIndex();
            List<RecordBase> records = ChartFixtures.chart(null, BAR, new byte[6], new String[] {"A"},
                    new String[] {"x", "y"}, new double[][] {{1, 2}});
            int parent = 0;
            while (!(records.get(parent) instanceof UnknownRecord u && u.getSid() == 0x1041)) {
                parent++;
            }
            byte[] none = new byte[12];
            none[4] = 5;
            records.addAll(parent + 2, List.of(new UnknownRecord(0x101D, new byte[] {1, 0}), new BeginRecord(),
                    new UnknownRecord(0x1026, new byte[] {(byte) font, 0}), new UnknownRecord(0x1021, new byte[2]),
                    new UnknownRecord(0x1007, none), new EndRecord()));
            records.addAll(2, List.of(new UnknownRecord(0x1015, new byte[20]), new BeginRecord(),
                    new UnknownRecord(0x1025, new byte[32]), new BeginRecord(),
                    new UnknownRecord(0x1026, new byte[] {(byte) font, 0}), new EndRecord(), new EndRecord()));
            ChartFixtures.chartSheet(wb, "Chart1", records);
        }));
        int legend = chart.indexOf("<c:legend>");
        assertTrue(legend > 0 && chart.indexOf("<a:defRPr sz=\"1400\" b=\"1\"/>", legend) > 0, chart);
        int axis = chart.indexOf("<c:valAx>");
        assertTrue(chart.indexOf("<a:defRPr sz=\"1400\" b=\"1\"/>", axis) < legend, chart);
        assertTrue(chart.substring(axis, legend).contains("<c:spPr><a:ln><a:noFill/></a:ln></c:spPr>"), chart);
    }

    @Test
    void errorBarsAndTrendlinesAreDrawnOnTheirParentSeries() throws Exception {
        List<RecordBase> records = ChartFixtures.chart(null, LINE, new byte[2],
                new String[] {"Score", "Up", "Down", "Fit"}, new String[] {"a", "b", "c", "d"},
                new double[][] {{2, 4, 3, 5}, {0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}});
        byte[] up = new byte[14];
        up[0] = 3;
        up[1] = 2;
        up[2] = 1;
        byte[] down = up.clone();
        down[0] = 4;
        ByteBuffer.wrap(up).order(ByteOrder.LITTLE_ENDIAN).putDouble(4, 1);
        ByteBuffer.wrap(down).order(ByteOrder.LITTLE_ENDIAN).putDouble(4, 1);
        byte[] fit = new byte[28];
        fit[1] = 1;
        byte[][] aux = {up, down, fit};
        int[] colours = {0xFF0000, 0xFF0000, 0x0000FF};
        for (int k = 3; k >= 1; k--) {
            byte[] line = new byte[12];
            line[0] = (byte) (colours[k - 1] >> 16);
            line[2] = (byte) colours[k - 1];
            line[6] = 1;
            records.addAll(2 + 4 * k + 3, List.of(new UnknownRecord(0x1006, new byte[] {(byte) 0xFF, (byte) 0xFF,
                0, 0, (byte) k, 0, (byte) k, 0}), new BeginRecord(), new UnknownRecord(0x1007, line), new EndRecord(),
                    new UnknownRecord(0x104A, new byte[] {1, 0}),
                    new UnknownRecord(k == 3 ? 0x104B : 0x105B, aux[k - 1])));
        }
        byte[] data = XlsTest.xls(wb -> ChartFixtures.chartSheet(wb, "Chart1", records));
        String chart = chartXml(data);
        assertTrue(chart.contains("<c:trendline><c:spPr><a:ln w=\"19050\"><a:solidFill><a:srgbClr val=\"0000FF\"/>"
                + "</a:solidFill></a:ln></c:spPr><c:trendlineType val=\"linear\"/></c:trendline>"), chart);
        assertTrue(chart.contains("<c:errBars><c:errDir val=\"y\"/><c:errBarType val=\"both\"/>"
                + "<c:errValType val=\"fixedVal\"/><c:noEndCap val=\"0\"/><c:val val=\"1.0\"/>"), chart);
        assertFalse(chart.contains("<c:v>Up</c:v>"), chart);
        Path in = Fixtures.write(dir, "errors.xls", data);
        Path pdf = dir.resolve("errors.pdf");
        OfficeToPdf.convert(in, pdf, OfficeToPdf.Options.defaults());
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            BufferedImage img = new PDFRenderer(doc).renderImage(0, 1f);
            int red = 0;
            int blue = 0;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int rgb = img.getRGB(x, y) & 0xFFFFFF;
                    red += rgb == 0xFF0000 ? 1 : 0;
                    blue += rgb == 0x0000FF ? 1 : 0;
                }
            }
            assertTrue(red > 20, "error bars drawn: " + red);
            assertTrue(blue > 50, "trendline drawn: " + blue);
        }
    }

    @Test
    void automaticColoursFollowTheSeriesFormatIndex() throws Exception {
        List<RecordBase> records = ChartFixtures.chart(null, BAR, new byte[6], new String[] {"First", "Second"},
                new String[] {"x"}, new double[][] {{1}, {2}});
        records.add(9, new UnknownRecord(0x1006, new byte[] {(byte) 0xFF, (byte) 0xFF, 1, 0, 0, 0, 0, 0}));
        records.add(5, new UnknownRecord(0x1006, new byte[] {(byte) 0xFF, (byte) 0xFF, 0, 0, 1, 0, 0, 0}));
        String chart = chartXml(XlsTest.xls(wb -> ChartFixtures.chartSheet(wb, "Chart1", records)));
        assertTrue(chart.indexOf("993366") < chart.indexOf("9999FF"), chart);
    }

    private static int index(List<RecordBase> records, int sid) {
        int at = 0;
        while (!(records.get(at) instanceof UnknownRecord u && u.getSid() == sid)) {
            at++;
        }
        return at;
    }

    private static String colours(Path pdf) throws IOException {
        java.util.Map<Integer, Integer> c = new java.util.HashMap<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            BufferedImage img = new PDFRenderer(doc).renderImage(0, 2f);
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    c.merge(img.getRGB(x, y) & 0xFFFFFF, 1, Integer::sum);
                }
            }
        }
        StringBuilder b = new StringBuilder();
        c.entrySet().stream().sorted((a, z) -> z.getValue() - a.getValue()).limit(8)
                .forEach(e -> b.append(Integer.toHexString(e.getKey())).append('=').append(e.getValue()).append(' '));
        return b.toString();
    }

    private static boolean hasColour(Path pdf, int rgb) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            BufferedImage img = new PDFRenderer(doc).renderImage(0, 2f);
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    if ((img.getRGB(x, y) & 0xFFFFFF) == rgb) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    @Test
    void threeDimensionalColumnsAndPiesHaveDepth() throws Exception {
        List<RecordBase> bars = ChartFixtures.chart(null, BAR, new byte[6], new String[] {"A"},
                new String[] {"x", "y"}, new double[][] {{3, 5}});
        bars.add(index(bars, BAR) + 1, new UnknownRecord(0x103A, new byte[14]));
        byte[] data = XlsTest.xls(wb -> ChartFixtures.chartSheet(wb, "Chart1", bars));
        assertTrue(chartXml(data).contains("<c:bar3DChart>"), chartXml(data));
        Path in = Fixtures.write(dir, "bar3d.xls", data);
        Path pdf = dir.resolve("bar3d.pdf");
        OfficeToPdf.convert(in, pdf, OfficeToPdf.Options.defaults());
        assertTrue(hasColour(pdf, 0x6B6BB3), "the side of a column is a darker shade");
        List<RecordBase> pie = ChartFixtures.chart(null, 0x1019, new byte[6], new String[] {"A"},
                new String[] {"x", "y"}, new double[][] {{3, 5}});
        pie.add(index(pie, 0x1019) + 1, new UnknownRecord(0x103A, new byte[14]));
        byte[] pieData = XlsTest.xls(wb -> ChartFixtures.chartSheet(wb, "Chart1", pie));
        assertTrue(chartXml(pieData).contains("<c:pie3DChart>"), chartXml(pieData));
        Path pieIn = Fixtures.write(dir, "pie3d.xls", pieData);
        Path piePdf = dir.resolve("pie3d.pdf");
        OfficeToPdf.convert(pieIn, piePdf, OfficeToPdf.Options.defaults());
        assertTrue(hasColour(piePdf, 0x7373BF) && hasColour(piePdf, 0x73264D), "the rims of the slices are shaded"
                + " from the palette colours " + colours(piePdf));
    }

    @Test
    void aDataTablePrintsTheValuesUnderThePlot() throws Exception {
        List<RecordBase> records = ChartFixtures.chart(null, BAR, new byte[6], new String[] {"Sales"},
                new String[] {"North", "South"}, new double[][] {{1234, 5678}});
        records.add(index(records, 0x1041), new UnknownRecord(0x1063, new byte[] {0x0F, 0}));
        byte[] data = XlsTest.xls(wb -> ChartFixtures.chartSheet(wb, "Chart1", records));
        String chart = chartXml(data);
        assertTrue(chart.contains("<c:dTable><c:showHorzBorder val=\"1\"/>"), chart);
        Path in = Fixtures.write(dir, "table.xls", data);
        Path pdf = dir.resolve("table.pdf");
        XlsTest.Converted out = XlsTest.read(OfficeToPdf.convert(in, pdf, OfficeToPdf.Options.defaults()), pdf);
        assertTrue(out.all().contains("1234") && out.all().contains("5678") && out.all().contains("Sales"), out.all());
    }

    @Test
    void aSurfaceChartIsDrawnAsLinesRatherThanLeftOut() throws Exception {
        byte[] data = XlsTest.xls(wb -> ChartFixtures.chartSheet(wb, "Chart1", ChartFixtures.chart(null, 0x103F,
                new byte[2], new String[] {"Low", "High"}, new String[] {"x", "y"}, new double[][] {{1, 2}, {3, 4}})));
        assertTrue(chartXml(data).contains("<c:lineChart>"), chartXml(data));
    }

    @Test
    void highLowLinesAndUpDownBarsAreDrawn() throws Exception {
        List<RecordBase> records = ChartFixtures.chart(null, LINE, new byte[2], new String[] {"Open", "High", "Close"},
                new String[] {"a", "b"}, new double[][] {{1, 5}, {8, 9}, {4, 2}});
        int type = 0;
        while (!(records.get(type) instanceof UnknownRecord u && u.getSid() == LINE)) {
            type++;
        }
        byte[] red = new byte[12];
        red[0] = (byte) 0xFF;
        red[6] = 2;
        byte[] green = new byte[16];
        green[1] = (byte) 0xFF;
        green[8] = 1;
        byte[] blue = new byte[16];
        blue[2] = (byte) 0xFF;
        blue[8] = 1;
        records.addAll(type + 1, List.of(new UnknownRecord(0x101C, new byte[] {1, 0}), new UnknownRecord(0x1007, red),
                new UnknownRecord(0x103D, new byte[] {(byte) 150, 0}), new BeginRecord(),
                new UnknownRecord(0x1007, new byte[12]), new UnknownRecord(0x100A, green), new EndRecord(),
                new UnknownRecord(0x103D, new byte[] {(byte) 150, 0}), new BeginRecord(),
                new UnknownRecord(0x1007, new byte[12]), new UnknownRecord(0x100A, blue), new EndRecord()));
        byte[] data = XlsTest.xls(wb -> ChartFixtures.chartSheet(wb, "Chart1", records));
        String chart = chartXml(data);
        assertTrue(chart.contains("<c:hiLowLines><c:spPr><a:ln w=\"28575\"><a:solidFill><a:srgbClr val=\"FF0000\"/>")
                && chart.contains("<c:upDownBars>"), chart);
        Path in = Fixtures.write(dir, "stock.xls", data);
        Path pdf = dir.resolve("stock.pdf");
        OfficeToPdf.convert(in, pdf, OfficeToPdf.Options.defaults());
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            BufferedImage img = new PDFRenderer(doc).renderImage(0, 1f);
            boolean up = false;
            boolean down = false;
            boolean line = false;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int rgb = img.getRGB(x, y) & 0xFFFFFF;
                    up |= rgb == 0x00FF00;
                    down |= rgb == 0x0000FF;
                    line |= rgb == 0xFF0000;
                }
            }
            assertTrue(up && down && line, "up " + up + " down " + down + " high-low " + line);
        }
    }

    @Test
    void errorBarAndTrendlineSeriesAreNotDrawnAsSeries() throws Exception {
        List<RecordBase> records = ChartFixtures.chart(null, LINE, new byte[2], new String[] {"Score", "Error"},
                new String[] {"x", "y"}, new double[][] {{50, 60}, {2, 3}});
        records.add(8, new UnknownRecord(0x104A, new byte[] {1, 0}));
        String chart = chartXml(XlsTest.xls(wb -> ChartFixtures.chartSheet(wb, "Chart1", records)));
        assertTrue(chart.contains("<c:v>Score</c:v>"), chart);
        assertFalse(chart.contains("<c:v>Error</c:v>") || chart.contains("<c:v>3.0</c:v>"), chart);
    }

    @Test
    void aSeriesNamingItselfAsParentIsStillDrawn() throws Exception {
        List<RecordBase> records = ChartFixtures.chart(null, LINE, new byte[2], new String[] {"Only"},
                new String[] {"x", "y"}, new double[][] {{5, 6}});
        records.add(4, new UnknownRecord(0x104A, new byte[] {1, 0}));
        String chart = chartXml(XlsTest.xls(wb -> ChartFixtures.chartSheet(wb, "Chart1", records)));
        assertTrue(chart.contains("<c:v>Only</c:v>") && chart.contains("<c:v>6.0</c:v>"), chart);
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
            date.setDataFormat(wb.createDataFormat().getFormat("m\"月\"d\"日\""));
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
        assertTrue(chart.contains("<c:formatCode>m\"月\"d\"日\"</c:formatCode>"), chart);
        Path in = Fixtures.write(dir, "dated.xls", data);
        Path pdf = dir.resolve("dated.pdf");
        XlsTest.Converted out = XlsTest.read(OfficeToPdf.convert(in, pdf, OfficeToPdf.Options.defaults()), pdf);
        assertTrue(out.pages().get(1).contains("1月1日") && out.pages().get(1).contains("1月3日"), out.all());
        assertFalse(out.pages().get(1).contains("36526"), out.all());
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
