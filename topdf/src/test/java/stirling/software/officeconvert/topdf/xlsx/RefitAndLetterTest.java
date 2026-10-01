package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorksheet;

class RefitAndLetterTest {

    private static final String WRAP_STYLES = "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
            + "</fonts><fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills><borders count=\"1\">"
            + "<border/></borders><cellStyleXfs count=\"1\"><xf/></cellStyleXfs><cellXfs count=\"2\"><xf/><xf>"
            + "<alignment wrapText=\"1\"/></xf></cellXfs>";

    @TempDir
    Path dir;

    @Test
    void anEmptyRowWithoutCustomHeightPrintsAtTheDefault() throws Exception {
        float stale = gap("<row r=\"2\" ht=\"85.5\" customHeight=\"0\"/>", "stale.xlsx");
        float none = gap("", "none.xlsx");
        float custom = gap("<row r=\"2\" ht=\"85.5\" customHeight=\"1\"/>", "custom.xlsx");
        assertEquals(none, stale, 0.5);
        assertTrue(custom > none + 50, "custom " + custom + ", default " + none);
    }

    private float gap(String row2, String name) throws Exception {
        String sheet = "<sheetFormatPr defaultRowHeight=\"15\"/><sheetData><row r=\"1\">" + RawXlsx.inline("A1", "One")
                + "</row>" + row2 + "<row r=\"3\">" + RawXlsx.inline("A3", "Three") + "</row></sheetData>";
        XlsxTesting.convert(dir, name, new RawXlsx().sheet("S", sheet).bytes());
        return position(dir.resolve(name + ".pdf"), "Three")[1] - position(dir.resolve(name + ".pdf"), "One")[1];
    }

    @Test
    void wrappedTextGrowsAHeightAnotherProgramStoredButNotExcelsOwn() throws Exception {
        String words = "one two three four five six seven eight nine ten eleven twelve";
        String sheet = "<cols><col min=\"1\" max=\"1\" width=\"8\" customWidth=\"1\"/></cols><sheetData>"
                + "<row r=\"1\" ht=\"15\" customHeight=\"false\"><c r=\"A1\" s=\"1\" t=\"inlineStr\"><is><t>" + words
                + "</t></is></c></row><row r=\"2\">" + RawXlsx.inline("A2", "Below") + "</row></sheetData>";
        XlsxTesting.convert(dir, "calc.xlsx", new RawXlsx().styles(WRAP_STYLES).sheet("S", sheet).bytes());
        XlsxTesting.convert(dir, "excel.xlsx", new RawXlsx().styles(WRAP_STYLES).sheet("S", sheet)
                .workbookExtra("<fileVersion appName=\"xl\"/>").bytes());
        float calc = position(dir.resolve("calc.xlsx.pdf"), "Below")[1];
        float excel = position(dir.resolve("excel.xlsx.pdf"), "Below")[1];
        assertTrue(calc > excel + 30, "grown " + calc + ", stored " + excel);
    }

    @Test
    void windowsExcelRefitsTheRowsMacExcelSized() throws Exception {
        String words = "one two three four five six seven eight nine ten eleven twelve";
        String sheet = "<cols><col min=\"1\" max=\"1\" width=\"8\" customWidth=\"1\"/></cols><sheetData>"
                + "<row r=\"1\" ht=\"15\"><c r=\"A1\" s=\"1\" t=\"inlineStr\"><is><t>" + words
                + "</t></is></c></row><row r=\"2\">" + RawXlsx.inline("A2", "Below") + "</row></sheetData>";
        String app = "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">"
                + "<Application>Microsoft Macintosh Excel</Application></Properties>";
        XlsxTesting.convert(dir, "windows.xlsx", new RawXlsx().styles(WRAP_STYLES).sheet("S", sheet)
                .workbookExtra("<fileVersion appName=\"xl\"/>").bytes());
        XlsxTesting.convert(dir, "mac.xlsx", new RawXlsx().styles(WRAP_STYLES).sheet("S", sheet)
                .workbookExtra("<fileVersion appName=\"xl\"/>")
                .part("docProps/app.xml", "application/vnd.openxmlformats-officedocument.extended-properties+xml", app)
                .rel("", "rId2", "extended-properties", "docProps/app.xml").bytes());
        float windows = position(dir.resolve("windows.xlsx.pdf"), "Below")[1];
        float mac = position(dir.resolve("mac.xlsx.pdf"), "Below")[1];
        assertTrue(mac > windows + 30, "refit " + mac + ", stored " + windows);
    }

    @Test
    void alignmentAppliesWithoutApplyAlignment() throws Exception {
        String styles = "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills "
                + "count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills><borders count=\"1\"><border/>"
                + "</borders><cellStyleXfs count=\"1\"><xf/></cellStyleXfs><cellXfs count=\"2\"><xf/><xf>"
                + "<alignment horizontal=\"center\"/></xf></cellXfs>";
        String sheet = "<cols><col min=\"1\" max=\"1\" width=\"40\" customWidth=\"1\"/></cols><sheetData><row r=\"1\">"
                + "<c r=\"A1\" s=\"1\" t=\"inlineStr\"><is><t>Middle</t></is></c></row><row r=\"2\">"
                + RawXlsx.inline("A2", "Left") + "</row></sheetData>";
        XlsxTesting.convert(dir, "align.xlsx", new RawXlsx().styles(styles).sheet("S", sheet).bytes());
        Path pdf = dir.resolve("align.xlsx.pdf");
        assertTrue(position(pdf, "Middle")[0] > position(pdf, "Left")[0] + 80);
    }

    @Test
    void aNumberInATextCellShowsAsGeneralOnTheLeft() throws Exception {
        String styles = "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills "
                + "count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills><borders count=\"1\"><border/>"
                + "</borders><cellStyleXfs count=\"1\"><xf/></cellStyleXfs><cellXfs count=\"2\"><xf/>"
                + "<xf numFmtId=\"49\" applyNumberFormat=\"1\"/></cellXfs>";
        String sheet = "<cols><col min=\"1\" max=\"1\" width=\"30\" customWidth=\"1\"/></cols><sheetData><row r=\"1\">"
                + RawXlsx.number("A1", 1, "1234") + "</row><row r=\"2\">" + RawXlsx.inline("A2", "Text")
                + "</row></sheetData>";
        XlsxTesting.Converted c = XlsxTesting.convert(dir, "at.xlsx", new RawXlsx().styles(styles).sheet("S", sheet)
                .bytes());
        assertFalse(c.all().contains("@"), c.all());
        Path pdf = dir.resolve("at.xlsx.pdf");
        assertEquals(position(pdf, "Text")[0], position(pdf, "1234")[0], 1);
    }

    @Test
    void anyLetterPageSetupPrintsOnA4AndItsHeaderKeepsTheA4Margin() throws Exception {
        CTWorksheet bare = CTWorksheet.Factory.newInstance();
        CTWorksheet letter = CTWorksheet.Factory.newInstance();
        letter.addNewPageSetup();
        assertFalse(PageSetup.of(bare).resized());
        assertTrue(PageSetup.of(letter).resized());
        String sheet = "<sheetData><row r=\"1\">" + RawXlsx.inline("A1", "Body") + "</row></sheetData><pageMargins "
                + "left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/><pageSetup "
                + "orientation=\"portrait\"/><headerFooter><oddHeader>&amp;CHeading</oddHeader></headerFooter>";
        XlsxTesting.Converted c = XlsxTesting.convert(dir, "letter.xlsx", new RawXlsx().sheet("S", sheet).bytes());
        assertEquals(841.89f, c.sizes().get(0)[1], 1f);
        float top = position(dir.resolve("letter.xlsx.pdf"), "Heading")[1];
        assertTrue(top > 0.3f * 72 && top < 0.3f * 72 + 16, "header baseline at " + top);
    }

    @Test
    void aSheetWithoutFormatPropertiesUsesFifteenPointRows() throws Exception {
        String styles = "<fonts count=\"1\"><font><sz val=\"10\"/><name val=\"Arial\"/></font></fonts><fills "
                + "count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills><borders count=\"1\"><border/>"
                + "</borders><cellStyleXfs count=\"1\"><xf/></cellStyleXfs><cellXfs count=\"1\"><xf/></cellXfs>";
        String rows = "<sheetData><row r=\"1\">" + RawXlsx.inline("A1", "Top") + "</row><row r=\"12\">"
                + RawXlsx.inline("A12", "Low") + "</row></sheetData>";
        XlsxTesting.convert(dir, "bare.xlsx", new RawXlsx().styles(styles).sheet("S", rows).bytes());
        XlsxTesting.convert(dir, "fifteen.xlsx", new RawXlsx().styles(styles)
                .sheet("S", "<sheetFormatPr defaultRowHeight=\"15\" customHeight=\"1\"/>" + rows).bytes());
        Path bare = dir.resolve("bare.xlsx.pdf");
        Path fifteen = dir.resolve("fifteen.xlsx.pdf");
        assertEquals(position(fifteen, "Low")[1] - position(fifteen, "Top")[1],
                position(bare, "Low")[1] - position(bare, "Top")[1], 0.5);
    }

    @Test
    void aOneCellAnchorExtentPrintsAtItsSizeInPoints() throws Exception {
        String drawing = "<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" "
                + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><xdr:oneCellAnchor><xdr:from>"
                + "<xdr:col>1</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>1</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>"
                + "<xdr:ext cx=\"2540000\" cy=\"1270000\"/><xdr:sp><xdr:nvSpPr><xdr:cNvPr id=\"2\" name=\"Box\"/>"
                + "<xdr:cNvSpPr/></xdr:nvSpPr><xdr:spPr><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:solidFill>"
                + "<a:srgbClr val=\"FF0000\"/></a:solidFill><a:ln><a:noFill/></a:ln></xdr:spPr></xdr:sp><xdr:clientData/>"
                + "</xdr:oneCellAnchor></xdr:wsDr>";
        byte[] xlsx = new RawXlsx().sheet("S", "<sheetData><row r=\"1\">" + RawXlsx.inline("A1", "x")
                + "</row></sheetData><drawing r:id=\"rIdD\"/>")
                .part("xl/drawings/drawing1.xml", "application/vnd.openxmlformats-officedocument.drawing+xml", drawing)
                .rel("xl/worksheets/sheet1.xml", "rIdD", "drawing", "../drawings/drawing1.xml").bytes();
        XlsxTesting.convert(dir, "extent.xlsx", xlsx);
        try (PDDocument doc = Loader.loadPDF(dir.resolve("extent.xlsx.pdf").toFile())) {
            BufferedImage page = new PDFRenderer(doc).renderImageWithDPI(0, 72);
            int x0 = Integer.MAX_VALUE;
            int x1 = -1;
            int y0 = Integer.MAX_VALUE;
            int y1 = -1;
            for (int y = 0; y < page.getHeight(); y++) {
                for (int x = 0; x < page.getWidth(); x++) {
                    int rgb = page.getRGB(x, y);
                    if (((rgb >> 16) & 0xFF) > 200 && ((rgb >> 8) & 0xFF) < 60 && (rgb & 0xFF) < 60) {
                        x0 = Math.min(x0, x);
                        x1 = Math.max(x1, x);
                        y0 = Math.min(y0, y);
                        y1 = Math.max(y1, y);
                    }
                }
            }
            assertEquals(200, x1 - x0 + 1, 2);
            assertEquals(100, y1 - y0 + 1, 2);
        }
    }

    private static float[] position(Path pdf, String prefix) throws Exception {
        List<float[]> found = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    if (text.strip().startsWith(prefix) && found.isEmpty()) {
                        TextPosition p = positions.get(0);
                        found.add(new float[] {p.getXDirAdj(), p.getYDirAdj()});
                    }
                }
            }.getText(doc);
        }
        assertFalse(found.isEmpty(), prefix + " not found");
        return found.get(0);
    }
}
