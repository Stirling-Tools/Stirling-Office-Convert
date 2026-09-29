package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.apache.poi.ooxml.POIXMLTypeLoader;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.xmlbeans.XmlOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import stirling.software.officeconvert.topdf.testing.Fixtures;
import stirling.software.officeconvert.topdf.testing.NoNetwork;

class PoiPackagesTest {

    private static final String ESCAPED = "a & b <c> \"d\"";

    private static final int CELLS = 60_000;

    @TempDir
    Path dir;

    @Test
    void skipsPartsWithoutAContentTypeInsteadOfRefusingThePackage() throws Exception {
        byte[] deck = Fixtures.edit(Fixtures.pptx("One", "Two")).put("ppt/slides/.slide1.xml.swp", new byte[] {1, 2})
                .put("ppt/junk.bin", new byte[] {3}).put("[trash]/0000.dat", new byte[] {4}).bytes();
        try (OfficeZip zip = OfficeZip.open(Fixtures.write(dir, "swap.pptx", deck));
                XMLSlideShow ppt = PoiPackages.slideShow(zip)) {
            assertEquals(2, ppt.getSlides().size());
            assertTrue(zip.notes().stream().anyMatch(n -> n.contains("/ppt/slides/.slide1.xml.swp")), zip.notes().toString());
            assertTrue(zip.notes().stream().anyMatch(n -> n.contains("/ppt/junk.bin")), zip.notes().toString());
            assertTrue(zip.notes().stream().noneMatch(n -> n.contains("[trash]")), zip.notes().toString());
        }
        byte[] book = Fixtures.edit(Fixtures.xlsx(new String[][] {{"a"}})).put("xl/~lock.tmp", new byte[] {1}).bytes();
        try (OfficeZip zip = OfficeZip.open(Fixtures.write(dir, "lock.xlsx", book));
                XSSFWorkbook wb = PoiPackages.workbook(zip)) {
            assertEquals("a", wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
        }
    }

    @Test
    void poiReadsThroughOfficeZipSoDensePartsWithinItsLimitsOpen() throws Exception {
        Fixtures.Zip z = Fixtures.edit(Fixtures.pptx("Dense"));
        String slide = z.text("ppt/slides/slide1.xml");
        z.put("ppt/slides/slide1.xml", slide.replace("</p:sld>", "<!--" + " ".repeat(4 << 20) + "--></p:sld>"));
        Path file = Fixtures.write(dir, "dense.pptx", z.bytes());
        try (OfficeZip zip = OfficeZip.open(file); XMLSlideShow ppt = PoiPackages.slideShow(zip)) {
            assertTrue(zip.size("/ppt/slides/slide1.xml") / (double) Files.size(file) > 100);
            assertEquals(1, ppt.getSlides().size());
        }
    }

    @Test
    void namesTheDamagedPartWhenPoiCannotReadThePackage() throws Exception {
        byte[] deck = Fixtures.edit(Fixtures.pptx("One", "Two")).put("ppt/slides/slide2.xml", "<p:sld>").bytes();
        try (OfficeZip zip = OfficeZip.open(Fixtures.write(dir, "bad.pptx", deck))) {
            OfficeZip.DamagedPart e = assertThrows(OfficeZip.DamagedPart.class, () -> PoiPackages.slideShow(zip));
            assertEquals("/ppt/slides/slide2.xml", e.part());
        }
        try (OfficeZip zip = OfficeZip.open(Fixtures.write(dir, "bad.pptx", deck), OfficeZip.Limits.DEFAULT,
                java.util.Set.of("/ppt/slides/slide2.xml")); XMLSlideShow ppt = PoiPackages.slideShow(zip)) {
            assertEquals(1, ppt.getSlides().size());
        }
    }

    @Test
    void runsOnTheExpectedJava() {
        int expected = Integer.getInteger("topdf.expectJava", 0);
        assertTrue(Runtime.version().feature() >= expected, "running on " + Runtime.version() + ", expected " + expected);
    }

    @Test
    void opensALargeWorkbookWhateverTheJdkLimits() throws Exception {
        Path file = bigWorkbook();
        try (OfficeZip zip = OfficeZip.open(file); XSSFWorkbook wb = PoiPackages.workbook(zip)) {
            XSSFSheet sheet = wb.getSheetAt(0);
            assertEquals(CELLS / 4 - 1, sheet.getLastRowNum());
            assertEquals(ESCAPED + " 14999/3", sheet.getRow(CELLS / 4 - 1).getCell(3).getStringCellValue());
        }
    }

    @Test
    void plainPoiFailsOnJava24AndLaterWithoutTheReader() throws Exception {
        Path file = bigWorkbook();
        PoiXml.install();
        XmlOptions options = POIXMLTypeLoader.DEFAULT_XML_OPTIONS;
        XMLReader installed = options.getLoadUseXMLReader();
        options.setLoadUseXMLReader(null);
        try (OPCPackage pkg = OPCPackage.open(file.toFile(), PackageAccess.READ)) {
            boolean strict = Runtime.version().feature() >= 24 && System.getProperty("jdk.xml.totalEntitySizeLimit") == null;
            try (XSSFWorkbook wb = new XSSFWorkbook(pkg)) {
                assertFalse(strict, "POI opened " + wb.getNumberOfSheets() + " sheet(s) on " + Runtime.version());
            } catch (RuntimeException e) {
                assertTrue(strict, e.toString());
            }
        } finally {
            options.setLoadUseXMLReader(installed);
        }
    }

    private Path bigWorkbook() throws IOException {
        Path file = dir.resolve("big.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            XSSFSheet sheet = wb.createSheet("Data");
            for (int r = 0; r < CELLS / 4; r++) {
                var row = sheet.createRow(r);
                for (int c = 0; c < 4; c++) {
                    row.createCell(c).setCellValue(ESCAPED + " " + r + "/" + c);
                }
            }
            wb.write(out);
        }
        return file;
    }

    @Test
    void opensALargeDeepPresentationWhateverTheJdkLimits() throws Exception {
        Path file = dir.resolve("big.pptx");
        try (XMLSlideShow ppt = new XMLSlideShow(); OutputStream out = Files.newOutputStream(file)) {
            XSLFSlide slide = ppt.createSlide();
            slide.createTextBox().setText("slide");
            XSLFNotes notes = ppt.getNotesSlide(slide);
            XSLFTextBox box = notes.createTextBox();
            box.setAnchor(new Rectangle2D.Double(10, 10, 600, 400));
            box.clearText();
            for (int i = 0; i < CELLS / 2; i++) {
                box.addNewTextParagraph().addNewTextRun().setText(ESCAPED + " " + i);
            }
            XSLFGroupShape group = notes.createGroup();
            for (int depth = 0; depth < 120; depth++) {
                group = group.createGroup();
            }
            group.createTextBox().setText("deep");
            ppt.write(out);
        }
        try (OfficeZip zip = OfficeZip.open(file); XMLSlideShow ppt = PoiPackages.slideShow(zip)) {
            XSLFNotes notes = ppt.getSlides().get(0).getNotes();
            XSLFTextShape text = notes.getShapes().stream().filter(XSLFTextShape.class::isInstance)
                    .map(XSLFTextShape.class::cast).max(Comparator.comparingInt(t -> t.getTextParagraphs().size()))
                    .orElseThrow();
            assertEquals(CELLS / 2, text.getTextParagraphs().size());
            assertEquals(ESCAPED + " 29999", text.getTextParagraphs().get(CELLS / 2 - 1).getText());
        }
    }

    @Test
    void slidesParsedAsDomEitherOpenOrNameTheJdkLimit() throws Exception {
        Path file = dir.resolve("deep-slide.pptx");
        try (XMLSlideShow ppt = new XMLSlideShow(); OutputStream out = Files.newOutputStream(file)) {
            XSLFGroupShape group = ppt.createSlide().createGroup();
            for (int depth = 0; depth < 120; depth++) {
                group = group.createGroup();
            }
            group.createTextBox().setText("deep");
            ppt.write(out);
        }
        try (OfficeZip zip = OfficeZip.open(file); XMLSlideShow ppt = PoiPackages.slideShow(zip)) {
            assertEquals(1, ppt.getSlides().size());
        } catch (IOException e) {
            assertTrue(Runtime.version().feature() >= 24, e.toString());
            assertTrue(e.getMessage().contains("jdk.xml.maxElementDepth"), e.getMessage());
            assertTrue(e.getMessage().contains("PoiXml.raiseProcessLimits"), e.getMessage());
        }
    }

    @Test
    void raisesProcessLimitsOnlyWhereTheHostSetNothing() {
        String depth = "jdk.xml.maxElementDepth";
        String total = "jdk.xml.totalEntitySizeLimit";
        String oldDepth = System.getProperty(depth);
        String oldTotal = System.getProperty(total);
        try {
            System.setProperty(depth, "77");
            System.clearProperty(total);
            List<String> set = PoiXml.raiseProcessLimits();
            assertEquals("77", System.getProperty(depth));
            assertEquals("0", System.getProperty(total));
            assertTrue(set.contains(total) && !set.contains(depth), set.toString());
        } finally {
            restore(depth, oldDepth);
            restore(total, oldTotal);
            for (String p : List.of("jdk.xml.maxGeneralEntitySizeLimit", "jdk.xml.elementAttributeLimit")) {
                System.clearProperty(p);
            }
        }
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    @Test
    void opensWordDocumentsToo() throws Exception {
        Path file = Fixtures.write(dir, "a.docx", Fixtures.docx("one", ESCAPED));
        try (OfficeZip zip = OfficeZip.open(file); XWPFDocument doc = PoiPackages.document(zip)) {
            assertEquals(ESCAPED, doc.getParagraphs().get(1).getText());
        }
    }

    @Test
    void stillRefusesDoctypesAndNeverFetches() throws Exception {
        try (NoNetwork net = NoNetwork.start()) {
            String dtd = "<?xml version=\"1.0\"?><!DOCTYPE worksheet [<!ENTITY x SYSTEM \"" + net.url("sheet.dtd")
                    + "\">]>";
            Fixtures.Zip base = Fixtures.edit(Fixtures.xlsx(new String[][] {{"x"}}));
            String sheet = base.text("xl/worksheets/sheet1.xml");
            String hostile = dtd + sheet.substring(sheet.indexOf("?>") + 2).replace("<sheetData>", "<sheetData>&x;");
            Path file = Fixtures.write(dir, "dtd.xlsx", base.put("xl/worksheets/sheet1.xml", hostile).bytes());
            try (OfficeZip zip = OfficeZip.open(file)) {
                IOException e = assertThrows(IOException.class, () -> PoiPackages.workbook(zip).close());
                assertTrue(String.valueOf(e.getMessage()).contains("DOCTYPE") || causeMentions(e, "DOCTYPE"), e.toString());
            }
            XMLReader reader = PoiXml.reader();
            assertThrows(SAXException.class, () -> reader.parse(net.url("x.xml")));
            reader.setContentHandler(new org.xml.sax.helpers.DefaultHandler());
            SAXException e = assertThrows(SAXException.class, () -> reader.parse(new InputSource(new ByteArrayInputStream(
                    (dtd + "<worksheet>&x;</worksheet>").getBytes(StandardCharsets.UTF_8)))));
            assertTrue(e.getMessage().contains("DOCTYPE"), e.getMessage());
            net.assertNothingConnected();
        }
    }

    @Test
    void theReusedParserIsResetAndStaysSafeBetweenParts() throws Exception {
        XMLReader reader = PoiXml.reader();
        String deep = "<a>".repeat(SecureXml.MAX_ELEMENT_DEPTH + 5) + "</a>".repeat(SecureXml.MAX_ELEMENT_DEPTH + 5);
        try (NoNetwork net = NoNetwork.start()) {
            String dtd = "<?xml version=\"1.0\"?><!DOCTYPE a [<!ENTITY x SYSTEM \"" + net.url("a.dtd") + "\">]><a>&x;</a>";
            for (int i = 0; i < 4; i++) {
                int[] elements = {0};
                reader.setFeature("http://xml.org/sax/features/namespace-prefixes", true);
                reader.setFeature("http://xml.org/sax/features/external-general-entities", true);
                reader.setContentHandler(new org.xml.sax.helpers.DefaultHandler() {
                    @Override
                    public void startElement(String uri, String local, String q, org.xml.sax.Attributes a) {
                        elements[0]++;
                    }
                });
                reader.parse(new InputSource(new ByteArrayInputStream(
                        "<a xmlns=\"urn:x\"><b/><b/></a>".getBytes(StandardCharsets.UTF_8))));
                assertEquals(3, elements[0]);
                assertFalse(reader.getFeature("http://xml.org/sax/features/external-general-entities"));
                reader.setContentHandler(new org.xml.sax.helpers.DefaultHandler());
                assertThrows(SAXException.class, () -> reader.parse(new InputSource(new ByteArrayInputStream(
                        dtd.getBytes(StandardCharsets.UTF_8)))));
                reader.setContentHandler(new org.xml.sax.helpers.DefaultHandler());
                assertThrows(SAXException.class, () -> reader.parse(new InputSource(new ByteArrayInputStream(
                        deep.getBytes(StandardCharsets.UTF_8)))));
            }
            net.assertNothingConnected();
        }
    }

    private static boolean causeMentions(Throwable t, String text) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (String.valueOf(c.getMessage()).contains(text)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void hostileDocumentsOpenThroughPoiWithoutTouchingTheNetwork() throws Exception {
        try (NoNetwork net = NoNetwork.start()) {
            Path xlsx = Fixtures.write(dir, "hostile.xlsx", Fixtures.hostileXlsx(net));
            Path pptx = Fixtures.write(dir, "hostile.pptx", Fixtures.hostilePptx(net));
            Path docx = Fixtures.write(dir, "hostile.docx", Fixtures.hostileDocx(net));
            try (OfficeZip zip = OfficeZip.open(xlsx); XSSFWorkbook wb = PoiPackages.workbook(zip)) {
                for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                    wb.getSheetAt(i).forEach(row -> row.forEach(cell -> cell.toString()));
                }
            } catch (IOException refused) {
                assertFalse(refused.getMessage().isEmpty());
            }
            try (OfficeZip zip = OfficeZip.open(pptx); XMLSlideShow ppt = PoiPackages.slideShow(zip)) {
                ppt.getSlides().forEach(slide -> slide.getShapes().forEach(shape -> shape.getShapeName()));
            } catch (IOException refused) {
                assertFalse(refused.getMessage().isEmpty());
            }
            try (OfficeZip zip = OfficeZip.open(docx); XWPFDocument doc = PoiPackages.document(zip)) {
                doc.getParagraphs().forEach(p -> p.getText());
            } catch (IOException refused) {
                assertFalse(refused.getMessage().isEmpty());
            }
            net.assertNothingConnected();
        }
    }

    @Test
    void installsOnlyWhenTheHostHasNotChosenAReader() {
        PoiXml.install();
        XMLReader installed = POIXMLTypeLoader.DEFAULT_XML_OPTIONS.getLoadUseXMLReader();
        assertTrue(installed == PoiXml.reader(), String.valueOf(installed));
        PoiXml.install();
        assertTrue(POIXMLTypeLoader.DEFAULT_XML_OPTIONS.getLoadUseXMLReader() == installed);
        assertFalse(PoiXml.reader().getClass().getName().startsWith("com.sun."));
    }

    @Test
    void refusesInputThatIsNotAPackage() throws Exception {
        Path file = Fixtures.write(dir, "a.xlsx", Fixtures.xlsx(new String[][] {{"x"}}));
        try (OfficeZip zip = OfficeZip.open(file)) {
            IOException e = assertThrows(IOException.class, () -> PoiPackages.slideShow(zip));
            assertTrue(e.getMessage().contains("presentation"), e.getMessage());
        }
    }
}
