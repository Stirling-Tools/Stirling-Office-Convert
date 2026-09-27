package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfToXlsxTest {

    @TempDir static Path dir;
    static Path pdf;

    @BeforeAll
    static void makePdf() throws IOException {
        pdf = SpreadsheetPdfs.report(dir, 0);
    }

    private static Map<String, byte[]> convert(PdfToXlsx.Options options) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PdfToXlsx.convert(doc, out, options);
        }
        return SpreadsheetPdfs.parts(out.toByteArray());
    }

    private static boolean number(String sheet, String value) {
        return Pattern.compile("<c r=\"[A-Z]+[0-9]+\"( s=\"[0-9]+\")?><v>" + Pattern.quote(value) + "</v>").matcher(sheet).find();
    }

    @Test
    void aSheetPerPageWithTheTableTyped() throws IOException {
        Map<String, byte[]> parts = convert(PdfToXlsx.Options.defaults());
        String book = SpreadsheetPdfs.part(parts, "xl/workbook.xml");
        assertTrue(book.contains("<sheet name=\"Page 1\"") && book.contains("<sheet name=\"Page 2\""), book);
        assertTrue(book.contains("<definedName name=\"Page1_Table1\">"), book);
        String sheet = SpreadsheetPdfs.part(parts, "xl/worksheets/sheet1.xml");
        assertTrue(number(sheet, "1250"), "units as a number");
        assertTrue(number(sheet, "12400.5"), "an amount as a number");
        assertTrue(number(sheet, "-1250"), "a bracketed amount as a negative");
        assertTrue(number(sheet, "0.125"), "a percentage as a fraction");
        assertTrue(number(sheet, "-0.03"), "a bracketed percentage as a negative");
        assertTrue(number(sheet, "45296"), "a date as its serial");
        String strings = SpreadsheetPdfs.part(parts, "xl/sharedStrings.xml");
        for (String text : new String[] {"Sales report 0", "Region", "02134", "90210", "Notes: shares are of national revenue"}) {
            assertTrue(strings.contains(text), text);
        }
        assertFalse(strings.contains("1,250"), "no number left as text");
        String styles = SpreadsheetPdfs.part(parts, "xl/styles.xml");
        assertTrue(styles.contains("formatCode=\"&quot;$&quot;#,##0.00;(&quot;$&quot;#,##0.00)\""), styles);
        assertTrue(SpreadsheetPdfs.part(parts, "xl/worksheets/sheet2.xml").contains("<row r=\"1\""));
        assertTrue(strings.contains("Commentary"));
    }

    @Test
    void theHeaderRowIsBold() throws IOException {
        Map<String, byte[]> parts = convert(PdfToXlsx.Options.defaults());
        String sheet = SpreadsheetPdfs.part(parts, "xl/worksheets/sheet1.xml");
        String strings = SpreadsheetPdfs.part(parts, "xl/sharedStrings.xml");
        int region = index(strings, "Region");
        Matcher m = Pattern.compile("<c r=\"A[0-9]+\" s=\"([0-9]+)\" t=\"s\"><v>" + region + "</v>").matcher(sheet);
        assertTrue(m.find(), "Region cell");
        String styles = SpreadsheetPdfs.part(parts, "xl/styles.xml");
        String xf = styles.substring(styles.indexOf("<cellXfs")).split("<xf ")[Integer.parseInt(m.group(1)) + 1];
        int font = Integer.parseInt(xf.replaceAll("(?s).*fontId=\"([0-9]+)\".*", "$1"));
        String fontXml = styles.substring(styles.indexOf("<fonts")).split("<font>")[font + 1];
        assertTrue(fontXml.startsWith("<b/>"), fontXml);
    }

    private static int index(String strings, String text) {
        String[] items = strings.split("<si>");
        for (int i = 1; i < items.length; i++) {
            if (items[i].contains(">" + text + "<")) {
                return i - 1;
            }
        }
        throw new AssertionError(text + " not shared");
    }

    @Test
    void optionsChangeTheLayout() throws IOException {
        Map<String, byte[]> tables = convert(PdfToXlsx.Options.defaults().withSheets(PdfToXlsx.Sheets.TABLE));
        String book = SpreadsheetPdfs.part(tables, "xl/workbook.xml");
        assertEquals(1, book.split("<sheet ").length - 1, "a sheet per table only");
        assertFalse(SpreadsheetPdfs.part(tables, "xl/sharedStrings.xml").contains("Commentary"), "text outside tables left out");

        Map<String, byte[]> single = convert(PdfToXlsx.Options.defaults().withSheets(PdfToXlsx.Sheets.SINGLE));
        String singleBook = SpreadsheetPdfs.part(single, "xl/workbook.xml");
        assertEquals(1, singleBook.split("<sheet ").length - 1);
        assertTrue(SpreadsheetPdfs.part(single, "xl/sharedStrings.xml").contains("Commentary"));

        PdfToXlsx.Options text = PdfToXlsx.Options.defaults().withTypedValues(false);
        assertTrue(SpreadsheetPdfs.part(convert(text), "xl/sharedStrings.xml").contains("1,250"), "typing off keeps text");
        Map<String, byte[]> untabled = convert(PdfToXlsx.Options.defaults().withTables(false));
        assertFalse(SpreadsheetPdfs.part(untabled, "xl/workbook.xml").contains("definedName"), "no tables looked for");

        Map<String, byte[]> second = convert(PdfToXlsx.Options.defaults().withPages(2, 2));
        assertTrue(SpreadsheetPdfs.part(second, "xl/workbook.xml").contains("<sheet name=\"Page 2\""));
        assertEquals(1, SpreadsheetPdfs.part(second, "xl/workbook.xml").split("<sheet ").length - 1);
    }

    @Test
    void odsCarriesTheSameValues() throws IOException {
        Map<String, byte[]> parts = convert(PdfToXlsx.Options.defaults().withFormat(PdfToXlsx.Format.ODS));
        String content = SpreadsheetPdfs.part(parts, "content.xml");
        assertTrue(content.contains("table:name=\"Page 1\"") && content.contains("table:name=\"Page 2\""));
        assertTrue(content.contains("office:value-type=\"float\" office:value=\"1250\""), "units");
        assertTrue(content.contains("office:value-type=\"percentage\" office:value=\"0.125\""), "share");
        assertTrue(content.contains("office:date-value=\"2024-01-05\""), "date");
        assertTrue(content.contains("table:named-range table:name=\"Page1_Table1\""));
        assertTrue(parts.containsKey("META-INF/manifest.xml"));
    }

    @Test
    void theFormatFollowsTheFileName() throws IOException {
        assertEquals(PdfToXlsx.Format.ODS, PdfToXlsx.Format.of(Path.of("out.ODS")));
        assertEquals(PdfToXlsx.Format.XLSX, PdfToXlsx.Format.of(Path.of("out.xlsx")));
        assertFalse(PdfToXlsx.Options.defaults().withPassword("secret").toString().contains("secret"));
        Path ods = dir.resolve("named.ods");
        PdfToXlsx.convert(pdf, ods, PdfToXlsx.Options.defaults());
        assertTrue(SpreadsheetPdfs.parts(Files.readAllBytes(ods)).containsKey("content.xml"), "an .ods target gets ODS");
        Path xlsx = dir.resolve("named.xlsx");
        PdfToXlsx.convert(pdf, xlsx, PdfToXlsx.Options.defaults().withFormat(PdfToXlsx.Format.ODS));
        assertTrue(SpreadsheetPdfs.parts(Files.readAllBytes(xlsx)).containsKey("xl/workbook.xml"), "an .xlsx target gets XLSX");
    }
}
