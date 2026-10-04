package stirling.software.officeconvert.topdf.xls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.Deflater;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ddf.EscherPropertyTypes;
import org.apache.poi.hssf.record.DefaultColWidthRecord;
import org.apache.poi.hssf.record.RecordBase;
import org.apache.poi.hssf.record.UnknownRecord;
import org.apache.poi.hssf.record.crypto.Biff8EncryptionKey;
import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFCellStyle;
import org.apache.poi.hssf.usermodel.HSSFClientAnchor;
import org.apache.poi.hssf.usermodel.HSSFFont;
import org.apache.poi.hssf.usermodel.HSSFHyperlink;
import org.apache.poi.hssf.usermodel.HSSFPatriarch;
import org.apache.poi.hssf.usermodel.HSSFRichTextString;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFChildAnchor;
import org.apache.poi.hssf.usermodel.HSSFShape;
import org.apache.poi.hssf.usermodel.HSSFShapeGroup;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFSimpleShape;
import org.apache.poi.hssf.usermodel.HSSFTextbox;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hssf.util.HSSFColor;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.testing.Fixtures;
import stirling.software.officeconvert.topdf.testing.HostileImagePlugin;
import stirling.software.officeconvert.topdf.testing.NoNetwork;

class XlsTest {

    @TempDir
    Path dir;

    record Converted(OfficeToPdf.Result result, List<String> pages, List<float[]> sizes, int images) {
        String all() {
            return String.join("\n", pages);
        }

        String warnings() {
            return String.join("\n", result.warnings());
        }
    }

    static byte[] xls(Consumer<HSSFWorkbook> build) {
        try (HSSFWorkbook wb = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            build.accept(wb);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Converted convert(String name, byte[] data) throws IOException {
        Path in = Fixtures.write(dir, name, data);
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.Result result = OfficeToPdf.convert(in, out,
                OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        return read(result, out);
    }

    static Converted read(OfficeToPdf.Result result, Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<String> pages = new ArrayList<>();
            List<float[]> sizes = new ArrayList<>();
            int images = 0;
            PDFTextStripper strip = new PDFTextStripper();
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                strip.setStartPage(i);
                strip.setEndPage(i);
                pages.add(strip.getText(doc));
                PDPage page = doc.getPage(i - 1);
                sizes.add(new float[] {page.getMediaBox().getWidth(), page.getMediaBox().getHeight()});
                for (COSName n : page.getResources().getXObjectNames()) {
                    if (page.getResources().getXObject(n) instanceof PDImageXObject) {
                        images++;
                    }
                }
            }
            return new Converted(result, pages, sizes, images);
        }
    }

    @Test
    void cellsStylesAndCachedFormulaValuesPrint() throws Exception {
        byte[] data = xls(wb -> {
            HSSFSheet s = wb.createSheet("Budget");
            HSSFFont bold = wb.createFont();
            bold.setBold(true);
            bold.setFontName("Arial");
            bold.setFontHeightInPoints((short) 14);
            wb.getCustomPalette().setColorAtIndex(HSSFColor.HSSFColorPredefined.LIME.getIndex(), (byte) 0x12,
                    (byte) 0x34, (byte) 0xAB);
            HSSFCellStyle head = wb.createCellStyle();
            head.setFont(bold);
            head.setFillForegroundColor(HSSFColor.HSSFColorPredefined.LIME.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setBorderBottom(BorderStyle.DOUBLE);
            HSSFCellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00 \"EUR\""));
            HSSFCellStyle date = wb.createCellStyle();
            date.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));
            HSSFRow r0 = s.createRow(0);
            HSSFCell title = r0.createCell(0);
            title.setCellValue("Quarterly budget");
            title.setCellStyle(head);
            s.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));
            HSSFRow r1 = s.createRow(1);
            r1.createCell(0).setCellValue("Rent");
            HSSFCell amount = r1.createCell(1);
            amount.setCellValue(1234.5);
            amount.setCellStyle(money);
            HSSFCell when = r1.createCell(2);
            Calendar c = Calendar.getInstance();
            c.clear();
            c.set(2024, Calendar.MARCH, 15);
            when.setCellValue(c);
            when.setCellStyle(date);
            HSSFRow r2 = s.createRow(2);
            HSSFCell f = r2.createCell(1);
            f.setCellFormula("1+1");
            f.setCellValue(424242);
            r2.createCell(0).setCellValue(true);
            HSSFRow hidden = s.createRow(3);
            hidden.createCell(0).setCellValue("HiddenRowText");
            hidden.setZeroHeight(true);
            s.createRow(4).createCell(4).setCellValue("HiddenColumnText");
            s.setColumnHidden(4, true);
            s.setColumnWidth(0, 30 * 256);
            s.setColumnWidth(1, 16 * 256);
            s.setColumnWidth(2, 14 * 256);
            s.createRow(5).createCell(0).setCellValue("Visible end");
        });
        Converted out = convert("budget.xls", data);
        String text = out.all();
        assertEquals(1, out.pages().size(), text);
        for (String s : new String[] {"Quarterly budget", "Rent", "1,234.50 EUR", "2024-03-15", "424242", "TRUE",
            "Visible end"}) {
            assertTrue(text.contains(s), s + " in " + text);
        }
        assertFalse(text.contains("HiddenRowText") || text.contains("HiddenColumnText"), text);
        assertFalse(text.contains("1+1"), text);
        assertFalse(out.result().truncated());
        assertTrue(hasColour(dir.resolve("budget.xls.pdf"), new Color(0x12, 0x34, 0xAB)), "custom palette fill");
    }

    @Test
    void richTextAndWorldScriptsKeepTheirText() throws Exception {
        byte[] data = xls(wb -> {
            HSSFSheet s = wb.createSheet("Scripts");
            HSSFFont red = wb.createFont();
            red.setColor(HSSFColor.HSSFColorPredefined.RED.getIndex());
            red.setItalic(true);
            HSSFRichTextString rich = new HSSFRichTextString("plain RED tail");
            rich.applyFont(6, 9, red);
            s.createRow(0).createCell(0).setCellValue(rich);
            String[] words = {"مرحبا بالعالم", "שלום עולם", "नमस्ते दुनिया", "你好世界", "こんにちは", "안녕하세요",
                "Γειά σου", "Привет мир"};
            for (int i = 0; i < words.length; i++) {
                s.createRow(i + 1).createCell(0).setCellValue(words[i]);
            }
            s.setColumnWidth(0, 40 * 256);
        });
        Converted out = convert("scripts.xls", data);
        String text = out.all();
        assertTrue(text.contains("plain RED tail"), text);
        for (String w : new String[] {"你好世界", "Привет мир", "Γειά σου"}) {
            assertTrue(text.contains(w), w + " in " + text);
        }
    }

    @Test
    void printSetupHeadersAreasAndTitlesAreKept() throws Exception {
        byte[] data = xls(wb -> {
            HSSFSheet s = wb.createSheet("Report");
            s.getPrintSetup().setLandscape(true);
            s.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
            s.getHeader().setCenter("Header &P of &N");
            s.getFooter().setRight("FooterText");
            for (int r = 0; r < 120; r++) {
                HSSFRow row = s.createRow(r);
                row.createCell(0).setCellValue(r == 0 ? "TitleRow" : "Row " + r);
                row.createCell(1).setCellValue(r);
                row.createCell(8).setCellValue("OutsideArea");
            }
            wb.setPrintArea(0, 0, 1, 0, 119);
            s.setRepeatingRows(CellRangeAddress.valueOf("1:1"));
            s.setRowBreak(59);
        });
        Converted out = convert("report.xls", data);
        assertTrue(out.pages().size() >= 3, "pages " + out.pages().size());
        float[] size = out.sizes().get(0);
        assertEquals(842, size[0], 2, "A4 landscape width");
        assertEquals(595, size[1], 2, "A4 landscape height");
        String text = out.all();
        assertFalse(text.contains("OutsideArea"), "the print area limits the columns");
        assertTrue(out.pages().get(1).contains("TitleRow"), "title row repeats: " + out.pages().get(1));
        assertTrue(out.pages().get(0).contains("Header 1 of " + out.pages().size()), out.pages().get(0));
        assertTrue(text.contains("FooterText"), text);
        int before = -1;
        for (int i = 0; i < out.pages().size(); i++) {
            if (out.pages().get(i).lines().anyMatch(l -> l.strip().startsWith("Row 59"))) {
                before = i;
            }
        }
        assertTrue(before >= 0 && !out.pages().get(before).contains("Row 60")
                && out.pages().get(before + 1).contains("Row 60"), "the manual break comes after row 60");
    }

    @Test
    void picturesAndTextBoxesAreDrawn() throws Exception {
        byte[] data = xls(wb -> {
            HSSFSheet s = wb.createSheet("Drawing");
            s.createRow(0).createCell(0).setCellValue("Drawing sheet");
            int png = wb.addPicture(Fixtures.png(40, 30, Color.RED), Workbook.PICTURE_TYPE_PNG);
            HSSFPatriarch p = s.createDrawingPatriarch();
            p.createPicture(new HSSFClientAnchor(0, 0, 0, 0, (short) 1, 2, (short) 4, 10), png);
            HSSFTextbox box = p.createTextbox(new HSSFClientAnchor(0, 0, 0, 0, (short) 5, 2, (short) 8, 6));
            box.setString(new HSSFRichTextString("Boxed note"));
            HSSFHyperlink link = wb.getCreationHelper().createHyperlink(HyperlinkType.URL);
            link.setAddress("https://example.invalid/");
            s.getRow(0).getCell(0).setHyperlink(link);
        });
        try (HostileImagePlugin plugin = HostileImagePlugin.register()) {
            Converted out = convert("drawing.xls", data);
            assertEquals(1, out.images(), "the PNG is drawn");
            assertTrue(out.all().contains("Boxed note"), out.all());
            plugin.assertNeverUsed();
        }
    }

    @Test
    void macrosAndObjectsAreNeverRunAndTheNetworkIsNeverTouched() throws Exception {
        try (NoNetwork net = NoNetwork.start()) {
            byte[] base = xls(wb -> {
                HSSFSheet s = wb.createSheet("Hostile");
                s.createRow(0).createCell(0).setCellValue("Hostile");
                for (String target : net.hostileTargets("book.xls")) {
                    HSSFCell c = s.createRow(s.getLastRowNum() + 1).createCell(0);
                    c.setCellValue("link");
                    HSSFHyperlink link = wb.getCreationHelper().createHyperlink(HyperlinkType.URL);
                    link.setAddress(target);
                    c.setHyperlink(link);
                }
            });
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(base))) {
                DirectoryEntry vba = fs.getRoot().createDirectory("_VBA_PROJECT_CUR");
                vba.createDocument("PROJECT", new ByteArrayInputStream(("ID=\"x\"\r\nDocument=Sheet1/&H00000000\r\n"
                        + "HelpFile=\"" + net.uncPath("help.chm") + "\"").getBytes()));
                DirectoryEntry pool = fs.getRoot().createDirectory("ObjPool");
                pool.createDirectory("_1234").createDocument("\u0001Ole", new ByteArrayInputStream(new byte[20]));
                fs.writeFilesystem(out);
            }
            Converted c = convert("hostile.xls", out.toByteArray());
            assertTrue(c.all().contains("Hostile"), c.all());
            assertTrue(c.warnings().contains("macros (not run)"), c.warnings());
            assertTrue(c.warnings().contains("OLE objects"), c.warnings());
            net.assertNothingConnected();
        }
    }

    @Test
    void aMetafileThatWouldInflatePastTheLimitIsLeftOut() throws Exception {
        byte[] emf = new byte[PictureLimits.OVER];
        emf[0] = 1;
        emf[40] = ' ';
        emf[41] = 'E';
        emf[42] = 'M';
        emf[43] = 'F';
        byte[] data = xls(wb -> {
            HSSFSheet s = wb.createSheet("Bomb");
            s.createRow(0).createCell(0).setCellValue("Still here");
            int pic = wb.addPicture(emf, Workbook.PICTURE_TYPE_EMF);
            s.createDrawingPatriarch().createPicture(new HSSFClientAnchor(0, 0, 0, 0, (short) 1, 1, (short) 3, 5), pic);
        });
        assertTrue(data.length < 1 << 20, "the metafile is stored deflated: " + data.length);
        Converted out = convert("bomb.xls", data);
        assertTrue(out.all().contains("Still here"), out.all());
        assertEquals(0, out.images());
        assertTrue(out.warnings().contains("inflate past"), out.warnings());
        assertTrue(out.result().truncated());
    }

    @Test
    void theGuardCountsEveryCompressedMetafile() {
        byte[] small = deflate(new byte[1 << 20]);
        byte[] big = deflate(new byte[PictureLimits.OVER]);
        assertTrue(new BlipGuard().safe(blip(0xF01A, 0x3D40, small)));
        assertFalse(new BlipGuard().safe(blip(0xF01B, 0x2170, big)), "the second-UID form is found too");
        BlipGuard total = new BlipGuard();
        byte[] many = deflate(new byte[30 << 20]);
        boolean ok = true;
        for (int i = 0; i < 5 && ok; i++) {
            ok = total.safe(blip(0xF01A, 0x3D40, many));
        }
        assertFalse(ok, "the workbook-wide budget runs out");
        assertTrue(new BlipGuard().safe(new byte[] {1, 2, 3}));
        assertTrue(new BlipGuard().safe(null));
    }

    @Test
    void passwordProtectedAndOldWorkbooksAreRefusedPlainly() throws Exception {
        byte[] plain = xls(wb -> wb.createSheet("Secret").createRow(0).createCell(0).setCellValue("secret"));
        byte[] locked;
        try (HSSFWorkbook wb = new HSSFWorkbook(new ByteArrayInputStream(plain));
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Biff8EncryptionKey.setCurrentUserPassword("pass");
            try {
                wb.write(out);
            } finally {
                Biff8EncryptionKey.setCurrentUserPassword(null);
            }
            locked = out.toByteArray();
        }
        Path in = Fixtures.write(dir, "locked.xls", locked);
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("locked.pdf")));
        assertTrue(e.getMessage().contains("password"), e.getMessage());
        ByteArrayOutputStream old = new ByteArrayOutputStream();
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            fs.createDocument(new ByteArrayInputStream(new byte[] {9, 8, 0, 0, 0, 5, 0x10, 0}), "Book");
            fs.writeFilesystem(old);
        }
        Path book = Fixtures.write(dir, "old.xls", old.toByteArray());
        IOException o = assertThrows(IOException.class, () -> OfficeToPdf.convert(book, dir.resolve("old.pdf")));
        assertTrue(o.getMessage().contains("Excel 5.0/95"), o.getMessage());
        try (Stream<Path> left = Files.list(dir)) {
            assertTrue(left.noneMatch(p -> p.getFileName().toString().endsWith(".pdf")), "no partial output");
        }
    }

    @Test
    void theContentDecidesNotTheExtension() throws Exception {
        byte[] data = xls(wb -> wb.createSheet("One").createRow(0).createCell(0).setCellValue("From a stream"));
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        OfficeToPdf.Result r = OfficeToPdf.convert(new ByteArrayInputStream(data), OfficeToPdf.Format.XLSX, pdf,
                OfficeToPdf.Options.defaults());
        assertEquals(1, r.pages());
        Path file = Files.write(dir.resolve("stream.pdf"), pdf.toByteArray());
        assertTrue(read(r, file).all().contains("From a stream"));
        assertTrue(convert("renamed.xlsx", data).all().contains("From a stream"));
        assertTrue(convert("template.xlt", data).all().contains("From a stream"));
        assertEquals(OfficeToPdf.Format.XLSX, OfficeToPdf.Format.of(Path.of("a.XLS")));
        assertTrue(OfficeToPdf.memoryEstimate(Fixtures.write(dir, "size.xls", data)) > data.length);
        byte[] xlsx = Fixtures.xlsx(new String[][] {{"Real xlsx"}});
        assertTrue(convert("misnamed.xls", xlsx).all().contains("Real xlsx"));
    }

    @Test
    void aLineWithoutTextOrDashingIsDrawnSolid() throws Exception {
        byte[] data = xls(wb -> {
            HSSFSheet s = wb.createSheet("Lines");
            s.createRow(0).createCell(0).setCellValue("Lined");
            HSSFSimpleShape line = s.createDrawingPatriarch()
                    .createSimpleShape(new HSSFClientAnchor(0, 0, 0, 0, (short) 1, 2, (short) 6, 2));
            line.setShapeType(HSSFSimpleShape.OBJECT_TYPE_LINE);
            line.setLineStyleColor(255, 0, 0);
            line.setLineWidth(HSSFShape.LINEWIDTH_ONE_PT * 4);
            line.getOptRecord().removeEscherProperty(EscherPropertyTypes.LINESTYLE__LINEDASHING);
        });
        Path in = Fixtures.write(dir, "line.xls", data);
        Path out = dir.resolve("line.pdf");
        OfficeToPdf.Result result = OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults());
        assertFalse(String.join(" ", result.warnings()).contains("could not be drawn"), result.warnings().toString());
        assertTrue(hasColour(out, Color.RED));
    }

    @Test
    void aSidewaysShapeInAGroupKeepsTheBoxItsAnchorShows() throws Exception {
        byte[] data = xls(wb -> {
            HSSFSheet s = wb.createSheet("Group");
            s.createRow(0).createCell(0).setCellValue("Grouped");
            HSSFShapeGroup g = s.createDrawingPatriarch()
                    .createGroup(new HSSFClientAnchor(0, 0, 0, 0, (short) 1, 2, (short) 9, 12));
            g.setCoordinates(0, 0, 1000, 1000);
            HSSFSimpleShape line = g.createShape(new HSSFChildAnchor(0, 500, 1000, 500));
            line.setShapeType(HSSFSimpleShape.OBJECT_TYPE_LINE);
            line.setLineStyleColor(255, 0, 0);
            line.setLineWidth(HSSFShape.LINEWIDTH_ONE_PT * 4);
            line.setRotationDegree((short) -90);
        });
        Path in = Fixtures.write(dir, "group.xls", data);
        Path out = dir.resolve("group.pdf");
        OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults());
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            BufferedImage img = new PDFRenderer(doc).renderImage(0, 1f);
            int left = Integer.MAX_VALUE;
            int right = -1;
            int top = Integer.MAX_VALUE;
            int bottom = -1;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    if ((img.getRGB(x, y) & 0xFFFFFF) == 0xFF0000) {
                        left = Math.min(left, x);
                        right = Math.max(right, x);
                        top = Math.min(top, y);
                        bottom = Math.max(bottom, y);
                    }
                }
            }
            assertTrue(right - left > 4 * (bottom - top), left + "," + top + " " + right + "," + bottom);
        }
    }

    @Test
    void theStandardWidthRecordSetsTheDefaultColumnWidth() throws Exception {
        Consumer<HSSFWorkbook> sheet = wb -> {
            HSSFRow r = wb.createSheet("Wide").createRow(0);
            r.createCell(0).setCellValue("Aye");
            r.createCell(5).setCellValue("Eff");
        };
        assertEquals(1, convert("plain.xls", xls(sheet)).pages().size());
        byte[] wide = xls(wb -> {
            sheet.accept(wb);
            List<RecordBase> records = wb.getSheetAt(0).getSheet().getRecords();
            for (int i = 0; i < records.size(); i++) {
                if (records.get(i) instanceof DefaultColWidthRecord) {
                    records.add(i + 1, new UnknownRecord(UnknownRecord.STANDARDWIDTH_0099, new byte[] {0, 20}));
                    break;
                }
            }
        });
        Converted out = convert("wide.xls", wide);
        assertEquals(2, out.pages().size());
        assertTrue(out.pages().get(1).contains("Eff"), out.all());
    }

    @Test
    void anEmptyWorkbookGivesOneBlankPage() throws Exception {
        Converted out = convert("empty.xls", xls(wb -> wb.createSheet("Empty")));
        assertEquals(1, out.pages().size());
    }

    private static boolean hasColour(Path pdf, Color c) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            BufferedImage img = new PDFRenderer(doc).renderImage(0, 1f);
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    if ((img.getRGB(x, y) & 0xFFFFFF) == (c.getRGB() & 0xFFFFFF)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private static byte[] deflate(byte[] raw) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        while (!d.finished()) {
            out.write(buf, 0, d.deflate(buf));
        }
        d.end();
        return out.toByteArray();
    }

    private static byte[] blip(int type, int options, byte[] deflated) {
        boolean second = (options ^ (type == 0xF01A ? 0x3D40 : type == 0xF01B ? 0x2160 : 0x5420)) == 0x10;
        int uids = second ? 32 : 16;
        byte[] b = new byte[8 + uids + 34 + deflated.length];
        b[0] = (byte) options;
        b[1] = (byte) (options >> 8);
        b[2] = (byte) type;
        b[3] = (byte) (type >> 8);
        int h = 8 + uids;
        int cb = deflated.length;
        b[h + 28] = (byte) cb;
        b[h + 29] = (byte) (cb >> 8);
        b[h + 30] = (byte) (cb >> 16);
        b[h + 31] = (byte) (cb >> 24);
        System.arraycopy(deflated, 0, b, h + 34, deflated.length);
        return b;
    }

    private static final class PictureLimits {
        static final int OVER = (32 << 20) + (1 << 20);
    }
}
