package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldScriptSheetTest {

    @TempDir
    Path dir;

    private static final String STYLES = "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"[$-2000401]0\"/>"
            + "</numFmts><fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills"
            + " count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills><borders count=\"1\"><border/>"
            + "</borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>"
            + "</cellStyleXfs><cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\""
            + " xfId=\"0\"/><xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\""
            + " applyNumberFormat=\"1\"/></cellXfs>";

    private static final String SETUP = "<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\""
            + " header=\"0.3\" footer=\"0.3\"/><pageSetup paperSize=\"9\" orientation=\"portrait\"/>";

    private static Map<String, Float> starts(Path pdf) throws Exception {
        Map<String, Float> out = new HashMap<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> p) {
                    for (TextPosition t : p) {
                        out.putIfAbsent(t.getUnicode(), t.getXDirAdj());
                    }
                }
            }.getText(doc);
        }
        return out;
    }

    @Test
    void aRightToLeftSheetPutsColumnAOnTheRight() throws Exception {
        String cols = "<cols><col min=\"1\" max=\"3\" width=\"20\" customWidth=\"1\"/></cols>";
        String sheet = "<sheetViews><sheetView rightToLeft=\"1\" workbookViewId=\"0\"/></sheetViews>" + cols
                + "<sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>A</t></is></c><c r=\"B1\""
                + " t=\"inlineStr\"><is><t>B</t></is></c><c r=\"C1\" t=\"inlineStr\"><is><t>C</t></is></c></row>"
                + "</sheetData>" + SETUP;
        XlsxTesting.convert(dir, "rtl.xlsx", new RawXlsx().styles(STYLES).sheet("S", sheet).bytes());
        Map<String, Float> x = starts(dir.resolve("rtl.xlsx.pdf"));
        assertTrue(x.get("A") > x.get("B") && x.get("B") > x.get("C"), x.toString());
    }

    @Test
    void generalHebrewTextStartsOnTheRightOfItsCell() throws Exception {
        String cols = "<cols><col min=\"1\" max=\"1\" width=\"40\" customWidth=\"1\"/></cols>";
        String sheet = cols + "<sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>\u05E9\u05DC\u05D5\u05DD</t>"
                + "</is></c></row><row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>Latin</t></is></c></row>"
                + "</sheetData>" + SETUP;
        XlsxTesting.convert(dir, "general.xlsx", new RawXlsx().styles(STYLES).sheet("S", sheet).bytes());
        Map<String, Float> x = starts(dir.resolve("general.xlsx.pdf"));
        assertTrue(x.get("\u05E9") > x.get("L") + 100, x.toString());
    }

    @Test
    void aNumeralSystemTagShowsNativeDigits() throws Exception {
        String sheet = "<sheetData><row r=\"1\"><c r=\"A1\" s=\"1\"><v>1948</v></c></row></sheetData>" + SETUP;
        XlsxTesting.Converted c = XlsxTesting.convert(dir, "digits.xlsx",
                new RawXlsx().styles(STYLES).sheet("S", sheet).bytes());
        assertEquals("\u0661\u0669\u0664\u0668", c.all().trim());
        assertEquals("\u0664\u0665\u0662\u0669\u0662\u066B\u0660\u0660",
                ExcelFormat.nativeDigits("[$-2000401]0.00", "45292.00"));
        assertEquals("\u0E51\u0E52", ExcelFormat.nativeDigits("[$-D00041E]0", "12"));
        assertEquals("12", ExcelFormat.nativeDigits("[$-409]0", "12"));
    }

    @Test
    void thaiWrapsAtWordEndsAndNeverBeforeAMark() {
        FontSpec f = new FontSpec("Calibri", 11, false, false, null, false, null, null);
        String thai = "\u0E20\u0E32\u0E29\u0E32\u0E44\u0E17\u0E22\u0E40\u0E1B\u0E47\u0E19\u0E20\u0E32\u0E29\u0E32\u0E17\u0E35\u0E48\u0E44\u0E21\u0E48\u0E21\u0E35\u0E01\u0E32\u0E23\u0E40\u0E27\u0E49\u0E19\u0E27\u0E23\u0E23\u0E04\u0E23\u0E30\u0E2B\u0E27\u0E48\u0E32\u0E07\u0E04\u0E33";
        List<String> lines = new ArrayList<>();
        for (CellLayout.Line l : CellLayout.wrap(List.of(new TextRun(thai, f)), 8, (s, font) -> s.length())) {
            StringBuilder b = new StringBuilder();
            l.runs().forEach(r -> b.append(r.text()));
            lines.add(b.toString());
        }
        assertEquals(thai, String.join("", lines));
        assertTrue(lines.size() > 2, lines.toString());
        for (String line : lines) {
            int type = Character.getType(line.codePointAt(0));
            assertTrue(type != Character.NON_SPACING_MARK && type != Character.COMBINING_SPACING_MARK, lines.toString());
        }
        assertTrue(lines.contains("\u0E20\u0E32\u0E29\u0E32\u0E44\u0E17\u0E22"), lines.toString());
    }
}
