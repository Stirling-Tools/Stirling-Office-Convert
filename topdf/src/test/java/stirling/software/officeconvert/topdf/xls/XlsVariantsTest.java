package stirling.software.officeconvert.topdf.xls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Calendar;
import java.util.function.Consumer;

import org.apache.poi.hssf.record.crypto.Biff8EncryptionKey;
import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFCellStyle;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.util.CellRangeAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.testing.Fixtures;

class XlsVariantsTest {

    @TempDir
    Path dir;

    private XlsTest.Converted convert(String name, byte[] data) throws IOException {
        return convert(name, data, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(3)));
    }

    private XlsTest.Converted convert(String name, byte[] data, OfficeToPdf.Options options) throws IOException {
        Path in = Fixtures.write(dir, name, data);
        Path out = dir.resolve(name + ".pdf");
        return XlsTest.read(OfficeToPdf.convert(in, out, options), out);
    }

    private static byte[] xls(Consumer<HSSFWorkbook> build) {
        return XlsTest.xls(build);
    }

    @Test
    void aSheetUsingTheLastRowAndColumnConvertsWithinItsLimits() throws Exception {
        byte[] data = xls(wb -> {
            HSSFSheet s = wb.createSheet("Full");
            for (int r = 0; r < 65_536; r++) {
                HSSFRow row = s.createRow(r);
                row.createCell(0).setCellValue("Row " + (r + 1));
                row.createCell(1).setCellValue(r * 1.5);
            }
            s.getRow(65_535).createCell(255).setCellValue("Corner");
        });
        long start = System.nanoTime();
        XlsTest.Converted out = convert("full.xls", data, OfficeToPdf.Options.defaults()
                .timeout(Duration.ofMinutes(3)).maxPages(200));
        assertTrue(System.nanoTime() - start < Duration.ofMinutes(2).toNanos(), "converts in reasonable time");
        assertEquals(200, out.pages().size(), "the page limit applies");
        assertTrue(out.result().pageLimitReached());
        assertTrue(out.pages().get(0).contains("Row 1"), out.pages().get(0));
    }

    @Test
    void everySheetOfAManySheetWorkbookPrints() throws Exception {
        byte[] data = xls(wb -> {
            for (int i = 0; i < 300; i++) {
                wb.createSheet("S" + i).createRow(0).createCell(0).setCellValue("Sheet number " + i);
            }
        });
        XlsTest.Converted out = convert("many.xls", data);
        assertEquals(300, out.pages().size());
        assertTrue(out.pages().get(299).contains("Sheet number 299"), out.pages().get(299));
    }

    private static Consumer<HSSFWorkbook> grid(int rows, int cols, Consumer<HSSFSheet> setup) {
        return wb -> {
            HSSFSheet s = wb.createSheet("Grid");
            for (int r = 0; r < rows; r++) {
                HSSFRow row = s.createRow(r);
                for (int c = 0; c < cols; c++) {
                    row.createCell(c).setCellValue("R" + (r + 1) + "C" + (c + 1));
                }
            }
            setup.accept(s);
        };
    }

    @Test
    void fitToOnePageShrinksAWideTallSheetOntoOnePage() throws Exception {
        assertTrue(convert("plain.xls", xls(grid(120, 20, s -> {}))).pages().size() > 1);
        XlsTest.Converted fit = convert("fit.xls", xls(grid(120, 20, s -> {
            s.setAutobreaks(true);
            s.getPrintSetup().setFitWidth((short) 1);
            s.getPrintSetup().setFitHeight((short) 1);
        })));
        assertEquals(1, fit.pages().size());
        assertTrue(fit.pages().get(0).contains("R120C20"), "nothing is cut off");
    }

    @Test
    void aScaleFactorChangesHowManyPagesPrint() throws Exception {
        int full = convert("full.xls", xls(grid(150, 12, s -> {}))).pages().size();
        int half = convert("half.xls", xls(grid(150, 12, s -> s.getPrintSetup().setScale((short) 40)))).pages()
                .size();
        assertTrue(half < full, half + " pages at 40% against " + full);
    }

    @Test
    void aPrintAreaLimitsWhatPrintsAndTitlesRepeat() throws Exception {
        XlsTest.Converted area = convert("area.xls", xls(wb -> {
            grid(200, 4, s -> s.setRepeatingRows(CellRangeAddress.valueOf("1:1"))).accept(wb);
            wb.setPrintArea(0, "$A$1:$B$150");
        }));
        assertFalse(area.all().contains("R1C3"), "column C is outside the print area");
        assertFalse(area.all().contains("R151C1"), "row 151 is outside the print area");
        assertTrue(area.pages().size() > 1);
        assertTrue(area.pages().get(1).contains("R1C1"), "the title row repeats: " + area.pages().get(1));
    }

    @Test
    void headingsGridlinesCentringAndPageOrderCarryOver() throws Exception {
        XlsTest.Converted headed = convert("headed.xls", xls(grid(3, 3, s -> {
            s.setPrintRowAndColumnHeadings(true);
            s.setPrintGridlines(true);
            s.setHorizontallyCenter(true);
            s.setVerticallyCenter(true);
        })));
        String text = headed.pages().get(0);
        assertTrue(text.contains("A") && text.contains("B") && text.contains("C"), text);
        XlsTest.Converted down = convert("down.xls", xls(grid(120, 30, s -> {})));
        XlsTest.Converted over = convert("over.xls", xls(grid(120, 30, s -> s.getPrintSetup().setLeftToRight(true))));
        assertEquals(down.pages().size(), over.pages().size());
        assertTrue(down.pages().get(1).startsWith("R53C1 "), "down then over prints the next rows second: "
                + down.pages().get(1));
        assertTrue(over.pages().get(1).startsWith("R1C10 "), "over then down prints the next columns second: "
                + over.pages().get(1));
    }

    @Test
    void landscapeAndPaperSizeAreKept() throws Exception {
        XlsTest.Converted out = convert("land.xls", xls(grid(3, 3, s -> {
            s.getPrintSetup().setLandscape(true);
            s.getPrintSetup().setPaperSize(PrintSetup.A3_PAPERSIZE);
        })));
        float[] size = out.sizes().get(0);
        assertTrue(size[0] > size[1], "landscape");
        assertTrue(Math.abs(size[0] - 1190.55f) < 2, "A3 is 420 mm wide in landscape: " + size[0]);
    }

    @Test
    void everyNumberFormatCategoryPrints() throws Exception {
        String[][] cases = {{"General", "1234.5", "1234.5"}, {"0.00", "3.14159", "3.14"}, {"#,##0", "1234567", "1,234,567"},
            {"\"$\"#,##0.00", "-12.5", "-$12.50"}, {"0%", "0.256", "26%"}, {"0.00E+00", "12345", "1.23E+04"},
            {"# ?/?", "1.5", "1 1/2"}, {"@", "42", "42"}, {"[Red]0;[Blue]-0", "-7", "-7"},
            {"_(* #,##0_);_(* (#,##0);_(* \"-\"_);_(@_)", "-1500", "(1,500)"}, {"00000", "123", "00123"},
            {"[h]:mm", "1.5", "36:00"}};
        byte[] data = xls(wb -> {
            HSSFSheet s = wb.createSheet("Formats");
            s.setColumnWidth(0, 30 * 256);
            for (int i = 0; i < cases.length; i++) {
                HSSFCellStyle st = wb.createCellStyle();
                st.setDataFormat(wb.createDataFormat().getFormat(cases[i][0]));
                HSSFCell c = s.createRow(i).createCell(0);
                c.setCellValue(Double.parseDouble(cases[i][1]));
                c.setCellStyle(st);
            }
            HSSFCellStyle date = wb.createCellStyle();
            date.setDataFormat(wb.createDataFormat().getFormat("d mmmm yyyy"));
            Calendar cal = Calendar.getInstance();
            cal.clear();
            cal.set(2024, Calendar.MARCH, 9);
            HSSFCell d = s.createRow(cases.length).createCell(0);
            d.setCellValue(cal);
            d.setCellStyle(date);
        });
        String text = convert("formats.xls", data).all();
        for (String[] c : cases) {
            assertTrue(text.contains(c[2]), c[0] + " should print " + c[2] + " in " + text);
        }
        assertTrue(text.contains("9 March 2024"), text);
    }

    @Test
    void mergedRotatedHiddenAndGroupedCells() throws Exception {
        byte[] data = xls(wb -> {
            HSSFSheet s = wb.createSheet("Shapes");
            s.createRow(0).createCell(0).setCellValue("Merged across");
            s.addMergedRegion(CellRangeAddress.valueOf("A1:D2"));
            HSSFCellStyle up = wb.createCellStyle();
            up.setRotation((short) 90);
            HSSFCell r = s.createRow(3).createCell(0);
            r.setCellValue("Turned");
            r.setCellStyle(up);
            s.createRow(5).createCell(0).setCellValue("Hidden row");
            s.getRow(5).setZeroHeight(true);
            s.createRow(6).createCell(2).setCellValue("Hidden column");
            s.setColumnHidden(2, true);
            for (int i = 8; i < 12; i++) {
                s.createRow(i).createCell(0).setCellValue("Detail " + i);
            }
            s.groupRow(8, 11);
            s.setRowGroupCollapsed(8, true);
            s.createRow(13).createCell(0).setCellValue("Visible after");
        });
        String text = convert("cells.xls", data).all();
        assertTrue(text.contains("Merged across") && text.replace("\n", "").contains("Turned")
                && text.contains("Visible after"), text);
        assertFalse(text.contains("Hidden row") || text.contains("Hidden column"), text);
        assertFalse(text.contains("Detail 9"), "collapsed outline rows are hidden: " + text);
    }

    @Test
    void anRc4EncryptedWorkbookOpensWithItsPasswordOnly() throws Exception {
        byte[] plain = xls(wb -> wb.createSheet("Secret").createRow(0).createCell(0).setCellValue("Only for you"));
        byte[] locked;
        try (HSSFWorkbook wb = new HSSFWorkbook(new ByteArrayInputStream(plain));
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Biff8EncryptionKey.setCurrentUserPassword("open sesame");
            try {
                wb.write(out);
            } finally {
                Biff8EncryptionKey.setCurrentUserPassword(null);
            }
            locked = out.toByteArray();
        }
        Path in = Fixtures.write(dir, "rc4.xls", locked);
        assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("none.pdf")));
        assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("wrong.pdf"),
                OfficeToPdf.Options.defaults().password("wrong")));
        Path out = dir.resolve("right.pdf");
        XlsTest.Converted right = XlsTest.read(OfficeToPdf.convert(in, out,
                OfficeToPdf.Options.defaults().password("open sesame")), out);
        assertTrue(right.all().contains("Only for you"), right.all());
        assertEquals(null, Biff8EncryptionKey.getCurrentUserPassword(), "the password does not leak to the thread");
    }

    @Test
    void aWorkbookWithAHugeStringTableStaysBounded() throws Exception {
        byte[] data = xls(wb -> {
            HSSFSheet s = wb.createSheet("Strings");
            String pad = "x".repeat(200);
            for (int i = 0; i < 60_000; i++) {
                s.createRow(i).createCell(0).setCellValue("String " + i + pad);
            }
        });
        long start = System.nanoTime();
        XlsTest.Converted out = convert("sst.xls", data, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(3))
                .maxPages(5));
        assertTrue(System.nanoTime() - start < Duration.ofMinutes(2).toNanos());
        assertEquals(5, out.pages().size());
    }

    @Test
    void deeplyNestedFormulasAreNeverEvaluatedAndDoNotHang() throws Exception {
        byte[] data = xls(wb -> {
            HSSFSheet s = wb.createSheet("Deep");
            s.createRow(0).createCell(0).setCellValue(1);
            for (int i = 1; i < 2000; i++) {
                HSSFCell c = s.createRow(i).createCell(0);
                c.setCellFormula("A" + i + "+1");
            }
            s.createRow(2000).createCell(1).setCellFormula("(".repeat(200) + "A1" + ")".repeat(200));
            s.getSheetConditionalFormatting().addConditionalFormatting(
                    new CellRangeAddress[] {CellRangeAddress.valueOf("A1:A2000")},
                    s.getSheetConditionalFormatting().createConditionalFormattingRule("A1>A2000"));
        });
        long start = System.nanoTime();
        XlsTest.Converted out = convert("deep.xls", data);
        assertTrue(System.nanoTime() - start < Duration.ofMinutes(2).toNanos());
        assertFalse(out.pages().isEmpty());
    }

    private static byte[] workbookStream(byte[] xls) throws IOException {
        try (org.apache.poi.poifs.filesystem.POIFSFileSystem fs = new org.apache.poi.poifs.filesystem.POIFSFileSystem(
                new ByteArrayInputStream(xls))) {
            return fs.createDocumentInputStream("Workbook").readAllBytes();
        }
    }

    private static byte[] withStream(byte[] stream) throws IOException {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        try (org.apache.poi.poifs.filesystem.POIFSFileSystem fs = new org.apache.poi.poifs.filesystem.POIFSFileSystem()) {
            fs.createDocument(new ByteArrayInputStream(stream), "Workbook");
            fs.writeFilesystem(file);
        }
        return file.toByteArray();
    }

    @Test
    void aBrokenDrawingObjectIsDroppedAndTheCellsStillPrint() throws Exception {
        byte[] stream = workbookStream(xls(wb -> {
            HSSFSheet s = wb.createSheet("Broken");
            s.createRow(0).createCell(0).setCellValue("Cells survive");
            s.createDrawingPatriarch().createSimpleShape(new org.apache.poi.hssf.usermodel.HSSFClientAnchor(0, 0, 0,
                    0, (short) 1, 1, (short) 3, 4)).setShapeType(1);
        }));
        int at = 0;
        boolean broke = false;
        while (at + 4 <= stream.length) {
            int sid = stream[at] & 0xFF | (stream[at + 1] & 0xFF) << 8;
            int len = stream[at + 2] & 0xFF | (stream[at + 3] & 0xFF) << 8;
            if (sid == 0x5D) {
                stream[at + 6] = 0;
                broke = true;
            }
            at += 4 + len;
        }
        assertTrue(broke);
        XlsTest.Converted out = convert("broken.xls", withStream(stream));
        assertTrue(out.all().contains("Cells survive"), out.all());
        assertTrue(String.join(" ", out.result().warnings()).contains("damaged"), out.result().warnings().toString());
    }

    @Test
    void aWorkbookCutShortKeepsWhatWasWritten() throws Exception {
        byte[] stream = workbookStream(xls(wb -> {
            HSSFSheet s = wb.createSheet("Cut");
            for (int i = 0; i < 50; i++) {
                s.createRow(i).createCell(0).setCellValue("Line " + i);
            }
        }));
        byte[] cut = java.util.Arrays.copyOf(stream, stream.length - 300);
        XlsTest.Converted out = convert("cut.xls", withStream(cut));
        assertTrue(out.all().contains("Line 1"), out.all());
    }
}
