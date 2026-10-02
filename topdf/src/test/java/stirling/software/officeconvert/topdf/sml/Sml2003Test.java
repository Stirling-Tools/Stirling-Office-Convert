package stirling.software.officeconvert.topdf.sml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.font.FontLibrary;

class Sml2003Test {

    @TempDir
    Path dir;

    private static String workbook(String styles, String names, String worksheets) {
        return "<?xml version=\"1.0\"?><?mso-application progid=\"Excel.Sheet\"?><Workbook xmlns=\"urn:schemas-microsoft-com:"
                + "office:spreadsheet\" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\" xmlns:x=\"urn:schemas-"
                + "microsoft-com:office:excel\" xmlns:html=\"http://www.w3.org/TR/REC-html40\"><Styles><Style ss:ID=\"Default\""
                + " ss:Name=\"Normal\"><Font ss:FontName=\"Arial\" ss:Size=\"10\"/></Style>" + styles + "</Styles>" + names
                + worksheets + "</Workbook>";
    }

    private static final String SHEET = "<Worksheet ss:Name=\"Sales\"><Table><Column ss:Width=\"120\"/><Column ss:Width=\"80\"/><Column ss:Width=\"90\"/>"
            + "<Row><Cell ss:StyleID=\"head\"><Data ss:Type=\"String\">Region</Data></Cell><Cell><Data ss:Type=\"String\">"
            + "Total</Data></Cell></Row>"
            + "<Row><Cell><Data ss:Type=\"String\">North</Data></Cell><Cell ss:StyleID=\"money\" ss:Formula=\"=1+1\">"
            + "<Data ss:Type=\"Number\">1234.5</Data></Cell><Cell ss:StyleID=\"date\"><Data ss:Type=\"DateTime\">"
            + "2004-03-05T00:00:00.000</Data></Cell><Cell><Data ss:Type=\"Boolean\">1</Data></Cell></Row>"
            + "<Row ss:Index=\"4\"><Cell ss:MergeAcross=\"2\"><ss:Data ss:Type=\"String\" xmlns=\"http://www.w3.org/TR/"
            + "REC-html40\"><B>Bold</B> and <Font html:Color=\"#FF0000\">red</Font></ss:Data></Cell></Row></Table>"
            + "<WorksheetOptions xmlns=\"urn:schemas-microsoft-com:office:excel\"><PageSetup><Layout x:Orientation="
            + "\"Landscape\"/><Header x:Data=\"&amp;CRegional sales\"/></PageSetup></WorksheetOptions></Worksheet>";

    private static final String STYLES = "<Style ss:ID=\"head\"><Font ss:Bold=\"1\"/><Interior ss:Color=\"#FFFF00\""
            + " ss:Pattern=\"Solid\"/><Borders><Border ss:Position=\"Bottom\" ss:LineStyle=\"Continuous\" ss:Weight=\"2\"/>"
            + "</Borders></Style><Style ss:ID=\"money\"><NumberFormat ss:Format=\"Standard\"/></Style>"
            + "<Style ss:ID=\"date\"><NumberFormat ss:Format=\"yyyy-mm-dd\"/></Style>";

    private Path file(String name, String xml) throws IOException {
        return Files.write(dir.resolve(name), xml.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(Path pdf) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(d);
        }
    }

    private String part(Path in, String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Sml2003Package.write(in, out, FontLibrary.system());
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null;) {
                if (e.getName().equals(name)) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    @Test
    void anXmlSpreadsheetSavedAsXlsConvertsWithValuesStylesAndPageSetup() throws IOException {
        Path in = file("export.xls", workbook(STYLES, "", SHEET));
        Path pdf = dir.resolve("export.pdf");
        OfficeToPdf.convert(in, pdf);
        String t = text(pdf);
        for (String want : new String[] {"Region", "North", "1,234.50", "2004-03-05", "TRUE", "Bold and red",
            "Regional sales"}) {
            assertTrue(t.contains(want), want + " in " + t);
        }
        assertFalse(t.contains("\n2\n"), t);
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            assertTrue(d.getPage(0).getMediaBox().getWidth() > d.getPage(0).getMediaBox().getHeight());
        }
    }

    @Test
    void stylesMergesAndRichTextAreKept() throws IOException {
        Path in = file("styles.xml", workbook(STYLES, "", SHEET));
        String sheet = part(in, "xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("<mergeCell ref=\"A4:C4\"/>"), sheet);
        assertTrue(sheet.contains("<r><rPr><b/>") && sheet.contains("rgb=\"FFFF0000\""), sheet);
        String styles = part(in, "xl/styles.xml");
        assertTrue(styles.contains("<fgColor rgb=\"FFFFFF00\"/>") && styles.contains("style=\"medium\"")
                && styles.contains("formatCode=\"#,##0.00\""), styles);
    }

    @Test
    void printAreasInR1C1BecomeDefinedNames() throws IOException {
        Path in = file("area.xml", workbook("", "<Names><NamedRange ss:Name=\"Print_Area\" ss:RefersTo=\"=Sales!R1C1:R2C2\"/>"
                + "</Names>", SHEET));
        assertTrue(part(in, "xl/workbook.xml").contains(
                "<definedName name=\"_xlnm.Print_Area\" localSheetId=\"0\">'Sales'!$A$1:$B$2</definedName>"));
    }

    @Test
    void datesCountFromExcelsEpochWithItsLeapYear() {
        assertEquals(1, Formats.serial("1900-01-01T00:00:00.000", false));
        assertEquals(61, Formats.serial("1900-03-01T00:00:00.000", false));
        assertEquals(38051.5, Formats.serial("2004-03-05T12:00:00.000", false));
        assertEquals(0, Formats.serial("1904-01-01T00:00:00", true));
    }

    @Test
    void otherXmlIsRefusedPlainly() throws IOException {
        Path in = file("feed.xml", "<?xml version=\"1.0\"?><rss><channel/></rss>");
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("feed.pdf")));
        assertTrue(e.getMessage().contains("not a Word, Excel"), e.getMessage());
    }
}
