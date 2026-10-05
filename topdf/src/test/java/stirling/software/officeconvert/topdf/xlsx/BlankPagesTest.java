package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlankPagesTest {

    private static final String BREAKS = "<rowBreaks count=\"2\" manualBreakCount=\"2\"><brk id=\"10\" max=\"16383\""
            + " man=\"1\"/><brk id=\"20\" max=\"16383\" man=\"1\"/></rowBreaks>";

    private static final String STYLES = "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font>"
            + "<b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills count=\"2\"><fill><patternFill"
            + " patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills><borders"
            + " count=\"2\"><border/><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs"
            + " count=\"1\"><xf/></cellStyleXfs><cellXfs count=\"3\"><xf/><xf fontId=\"1\" applyFont=\"1\"/><xf"
            + " borderId=\"1\" applyBorder=\"1\"/></cellXfs>";

    @TempDir
    Path dir;

    @Test
    void aBlankPageBeforeTheLastPrintedPageIsStillPrinted() throws IOException {
        String rows = "<row r=\"1\">" + RawXlsx.inline("A1", "First") + "</row><row r=\"25\">"
                + RawXlsx.inline("A25", "Last") + "</row>";
        XlsxTesting.Converted c = convert("gap.xlsx", rows);
        assertEquals(3, c.pages().size());
        assertTrue(c.pages().get(0).contains("First"));
        assertEquals("", c.pages().get(1).strip());
        assertTrue(c.pages().get(2).contains("Last"));
    }

    @Test
    void anEmptyCellWithAnInvisibleStyleIsNotPrinted() throws IOException {
        String rows = "<row r=\"1\">" + RawXlsx.inline("A1", "First") + "</row><row r=\"15\"><c r=\"A15\" s=\"1\"/>"
                + "</row>";
        assertEquals(1, convert("styled.xlsx", rows).pages().size());
    }

    @Test
    void anEmptyCellWithItsOwnBorderRecordIsPrintedEvenWhenTheBorderDrawsNothing() throws IOException {
        String rows = "<row r=\"1\">" + RawXlsx.inline("A1", "First") + "</row><row r=\"25\"><c r=\"A25\" s=\"2\"/>"
                + "</row>";
        assertEquals(3, convert("ruled.xlsx", rows).pages().size());
    }

    @Test
    void aBlankMergedRangeDoesNotExtendThePrint() throws IOException {
        StringBuilder rows = new StringBuilder("<row r=\"1\">" + RawXlsx.inline("A1", "First") + "</row>");
        StringBuilder merges = new StringBuilder();
        for (int r = 2; r <= 3000; r++) {
            rows.append("<row r=\"").append(r).append("\"><c r=\"A").append(r).append("\" s=\"1\"/><c r=\"B")
                    .append(r).append("\" s=\"1\"/></row>");
            merges.append("<mergeCell ref=\"A").append(r).append(":B").append(r).append("\"/>");
        }
        String sheet = "<sheetData>" + rows + "</sheetData><mergeCells count=\"2999\">" + merges + "</mergeCells>";
        assertEquals(1, XlsxTesting.convert(dir, "merge.xlsx", new RawXlsx().styles(STYLES).sheet("S", sheet).bytes())
                .pages().size());
    }

    @Test
    void whiteFillsAndWhiteBordersDoNotExtendThePrint() throws IOException {
        String styles = "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills"
                + " count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/>"
                + "</fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFFFFFF\"/></patternFill></fill></fills>"
                + "<borders count=\"2\"><border/><border><left style=\"thin\"><color rgb=\"FFFFFFFF\"/></left><right/><top/>"
                + "<bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf/></cellStyleXfs><cellXfs count=\"3\">"
                + "<xf/><xf fillId=\"2\" applyFill=\"1\"/><xf borderId=\"1\" applyBorder=\"1\"/></cellXfs>";
        StringBuilder rows = new StringBuilder("<row r=\"1\">" + RawXlsx.inline("A1", "First") + "</row>");
        for (int r = 2; r <= 400; r++) {
            rows.append("<row r=\"").append(r).append("\"><c r=\"A").append(r).append("\" s=\"1\"/><c r=\"Z")
                    .append(r).append("\" s=\"2\"/></row>");
        }
        assertEquals(1, XlsxTesting.convert(dir, "white.xlsx", new RawXlsx().styles(styles)
                .sheet("S", "<sheetData>" + rows + "</sheetData>").bytes()).pages().size());
    }

    @Test
    void aMergedRangeWithAValuePrintsDownToItsLastRow() throws IOException {
        String sheet = "<sheetData><row r=\"1\">" + RawXlsx.inline("A1", "First") + "</row><row r=\"20\">"
                + RawXlsx.inline("A20", "Merged") + "</row></sheetData><mergeCells count=\"1\"><mergeCell"
                + " ref=\"A20:B25\"/></mergeCells>" + BREAKS;
        assertEquals(3, XlsxTesting.convert(dir, "valued.xlsx", new RawXlsx().styles(STYLES).sheet("S", sheet).bytes())
                .pages().size());
    }

    @Test
    void blankPagesAfterTheLastPrintedPageAreLeftOut() throws IOException {
        String rows = "<row r=\"1\">" + RawXlsx.inline("A1", "Only") + "</row><row r=\"25\" ht=\"30\""
                + " customHeight=\"1\"/>";
        assertEquals(1, convert("tail.xlsx", rows).pages().size());
    }

    @Test
    void everyPageOfAPrintAreaIsPrintedEvenWhenItIsBlank() throws IOException {
        String sheet = "<sheetData><row r=\"1\">" + RawXlsx.inline("A1", "First") + "</row></sheetData>" + BREAKS;
        String area = "<definedNames><definedName name=\"_xlnm.Print_Area\" localSheetId=\"0\">S!$A$1:$B$25"
                + "</definedName></definedNames>";
        XlsxTesting.Converted c = XlsxTesting.convert(dir, "area.xlsx", new RawXlsx().styles(STYLES)
                .sheet("S", sheet).workbookExtra(area).bytes());
        assertEquals(3, c.pages().size());
        assertTrue(c.pages().get(0).contains("First"));
    }

    @Test
    void aValueInAHiddenColumnDoesNotPrintTheEmptyColumnsBeforeIt() throws IOException {
        String sheet = "<cols><col min=\"2\" max=\"20\" width=\"40\" customWidth=\"1\"/><col min=\"26\""
                + " max=\"26\" width=\"9\" hidden=\"1\" customWidth=\"1\"/></cols><sheetData><row r=\"1\">"
                + RawXlsx.inline("A1", "Shown") + RawXlsx.inline("Z1", "Hidden") + "</row></sheetData>";
        XlsxTesting.Converted c = XlsxTesting.convert(dir, "hidden.xlsx", new RawXlsx().styles(STYLES)
                .sheet("S", sheet).bytes());
        assertEquals(1, c.pages().size());
        assertTrue(c.pages().get(0).contains("Shown"));
    }

    @Test
    void aValueInAHiddenColumnStillPrintsTheRowsItSitsIn() throws IOException {
        String sheet = "<cols><col min=\"2\" max=\"2\" width=\"9\" hidden=\"1\" customWidth=\"1\"/></cols>"
                + "<sheetData><row r=\"1\">" + RawXlsx.inline("A1", "Shown") + "</row><row r=\"25\">"
                + RawXlsx.inline("B25", "Hidden") + "</row></sheetData>" + BREAKS;
        assertEquals(3, XlsxTesting.convert(dir, "rows.xlsx", new RawXlsx().styles(STYLES).sheet("S", sheet).bytes())
                .pages().size());
    }

    @Test
    void textPastTheFirst1024CharactersDoesNotSpillOntoMorePages() throws IOException {
        String words = "spill over ".repeat(300);
        int shown = spilled("shown.xlsx", words.substring(0, Grid.SHOWN_CHARS));
        assertTrue(shown > 1, "pages " + shown);
        assertEquals(shown, spilled("long.xlsx", words));
    }

    @Test
    void aShapeEndingInsideAColumnWiderThanThePageDoesNotPrintTheNextColumn() throws IOException {
        String drawing = "<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" "
                + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><xdr:oneCellAnchor><xdr:from>"
                + "<xdr:col>0</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>1</xdr:row><xdr:rowOff>0</xdr:rowOff>"
                + "</xdr:from>"
                + "<xdr:ext cx=\"7620000\" cy=\"635000\"/><xdr:sp><xdr:nvSpPr><xdr:cNvPr id=\"2\" name=\"Box\"/>"
                + "<xdr:cNvSpPr/></xdr:nvSpPr><xdr:spPr><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:solidFill>"
                + "<a:srgbClr val=\"FF0000\"/></a:solidFill></xdr:spPr></xdr:sp><xdr:clientData/></xdr:oneCellAnchor>"
                + "</xdr:wsDr>";
        String sheet = "<cols><col min=\"1\" max=\"1\" width=\"120\" customWidth=\"1\"/></cols><sheetData>"
                + "<row r=\"1\">" + RawXlsx.inline("A1", "Wide") + "</row></sheetData><drawing r:id=\"rIdD\"/>";
        byte[] xlsx = new RawXlsx().styles(STYLES).sheet("S", sheet)
                .part("xl/drawings/drawing1.xml", "application/vnd.openxmlformats-officedocument.drawing+xml", drawing)
                .rel("xl/worksheets/sheet1.xml", "rIdD", "drawing", "../drawings/drawing1.xml").bytes();
        assertEquals(1, XlsxTesting.convert(dir, "shape.xlsx", xlsx).pages().size());
    }

    private int spilled(String name, String text) throws IOException {
        String sheet = "<sheetData><row r=\"1\">" + RawXlsx.inline("A1", text) + "</row></sheetData>";
        return XlsxTesting.convert(dir, name, new RawXlsx().styles(STYLES).sheet("S", sheet).bytes()).pages().size();
    }

    private XlsxTesting.Converted convert(String name, String rows) throws IOException {
        String sheet = "<sheetData>" + rows + "</sheetData>" + BREAKS;
        return XlsxTesting.convert(dir, name, new RawXlsx().styles(STYLES).sheet("S", sheet).bytes());
    }
}
