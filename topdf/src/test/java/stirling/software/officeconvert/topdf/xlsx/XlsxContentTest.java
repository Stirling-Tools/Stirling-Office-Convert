package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class XlsxContentTest {

    @TempDir
    Path dir;

    @Test
    void spreadsheetEscapesAreDecoded() throws Exception {
        byte[] xlsx = new RawXlsx().sharedStrings("<t xml:space=\"preserve\">Line1_x000D_\nLine2</t>",
                "<t>Code_x005F_x0041_End</t>", "<r><rPr><b/></rPr><t>Rich_x0041_Run</t></r>")
                .sheet("S", "<sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row><row r=\"2\"><c r=\"A2\""
                        + " t=\"s\"><v>1</v></c></row><row r=\"3\"><c r=\"A3\" t=\"s\"><v>2</v></c></row><row r=\"4\">"
                        + "<c r=\"A4\" t=\"inlineStr\"><is><t>Inline_x0042_Str</t></is></c></row><row r=\"5\"><c r=\"A5\""
                        + " t=\"str\"><f>\"a\"</f><v>Cached_x0043_Str</v></c></row></sheetData>")
                .bytes();
        String text = XlsxTesting.convert(dir, "esc.xlsx", xlsx).all();
        assertFalse(text.contains("_x000D_") || text.contains("_x0041_R") || text.contains("_x0042_"), text);
        assertTrue(text.contains("Code_x0041_End"), text);
        assertTrue(text.contains("RichARun") && text.contains("InlineBStr") && text.contains("CachedCStr"), text);
        assertTrue(text.contains("Line1") && text.contains("Line2"), text);
    }

    @Test
    void numberFormatsUseExcelsRules() throws Exception {
        String styles = "<numFmts count=\"4\"><numFmt numFmtId=\"164\" formatCode=\"0.0,&quot;K&quot;\"/><numFmt"
                + " numFmtId=\"165\" formatCode=\"[$-416]d&quot; de &quot;mmmm&quot; de &quot;yyyy;@\"/><numFmt"
                + " numFmtId=\"44\" formatCode=\"_(&quot;$&quot;* #,##0.00_);_(&quot;$&quot;* \\(#,##0.00\\);_(&quot;$&quot;*"
                + " &quot;-&quot;??_);_(@_)\"/><numFmt numFmtId=\"166\" formatCode=\"yyyy&quot;年&quot;m&quot;月"
                + "&quot;d&quot;日&quot;\"/></numFmts><fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Arial\"/>"
                + "</font></fonts><fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill"
                + " patternType=\"gray125\"/></fill></fills><borders count=\"1\"><border/></borders><cellXfs count=\"5\">"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/><xf numFmtId=\"164\" fontId=\"0\""
                + " fillId=\"0\" borderId=\"0\" applyNumberFormat=\"1\"/><xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\""
                + " borderId=\"0\" applyNumberFormat=\"1\"/><xf numFmtId=\"44\" fontId=\"0\" fillId=\"0\" borderId=\"0\""
                + " applyNumberFormat=\"1\"/><xf numFmtId=\"166\" fontId=\"0\" fillId=\"0\" borderId=\"0\""
                + " applyNumberFormat=\"1\"/></cellXfs>";
        byte[] xlsx = new RawXlsx().styles(styles).sheet("S", "<cols><col min=\"1\" max=\"1\" width=\"40\""
                + " customWidth=\"1\"/></cols><sheetData><row r=\"1\">" + RawXlsx.number("A1", 1, "12345") + "</row>"
                + "<row r=\"2\">" + RawXlsx.number("A2", 2, "45000") + "</row><row r=\"3\">" + RawXlsx.number("A3", 3, "0")
                + "</row><row r=\"4\">" + RawXlsx.number("A4", 4, "45000") + "</row></sheetData>").bytes();
        String text = XlsxTesting.convert(dir, "fmt.xlsx", xlsx).all();
        assertTrue(text.contains("12.3K"), text);
        assertTrue(text.contains("15 de março de 2023"), text);
        assertTrue(text.contains("2023年3月15日") || text.contains("2023"), text);
        assertFalse(text.contains("45000"), text);
        assertFalse(text.contains("- 0") || text.contains("-0"), text);
        assertEquals(0, text.chars().filter(c -> c == '"').count(), text);
    }

    static final String XDR = "xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\""
            + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"" + RawXlsx.R + "\"";

    static String anchor(int c0, int r0, int c1, int r1, String object, String client) {
        return "<xdr:twoCellAnchor><xdr:from><xdr:col>" + c0 + "</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>" + r0
                + "</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:to><xdr:col>" + c1 + "</xdr:col><xdr:colOff>0"
                + "</xdr:colOff><xdr:row>" + r1 + "</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>" + object
                + "<xdr:clientData" + client + "/></xdr:twoCellAnchor>";
    }

    static String shape(int id, String nvExtra, String geometry, String color, String text) {
        return "<xdr:sp><xdr:nvSpPr><xdr:cNvPr id=\"" + id + "\" name=\"S" + id + "\"" + nvExtra + "/><xdr:cNvSpPr/>"
                + "</xdr:nvSpPr><xdr:spPr>" + geometry + "<a:solidFill><a:srgbClr val=\"" + color + "\"/></a:solidFill>"
                + "</xdr:spPr><xdr:txBody><a:bodyPr/><a:p><a:r><a:t>" + text + "</a:t></a:r></a:p></xdr:txBody></xdr:sp>";
    }

    static String chartFrame(String id) {
        return "<xdr:graphicFrame><xdr:nvGraphicFramePr><xdr:cNvPr id=\"20\" name=\"Chart\"/><xdr:cNvGraphicFramePr/>"
                + "</xdr:nvGraphicFramePr><xdr:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/></xdr:xfrm><a:graphic>"
                + "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"><c:chart"
                + " xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" r:id=\"" + id + "\"/>"
                + "</a:graphicData></a:graphic></xdr:graphicFrame>";
    }

    private RawXlsx drawing(RawXlsx x, int sheet, String anchors) {
        return x.part("xl/drawings/drawing1.xml", "application/vnd.openxmlformats-officedocument.drawing+xml",
                "<xdr:wsDr " + XDR + ">" + anchors + "</xdr:wsDr>")
                .rel("xl/worksheets/sheet" + sheet + ".xml", "rIdD", "drawing", "../drawings/drawing1.xml");
    }

    @Test
    void chartsAndChartSheetsArePrintedFromTheirCaches() throws Exception {
        String chart = stirling.software.officeconvert.topdf.testing.ChartXml.bar("EmbeddedChartTitle");
        String sheetChart = stirling.software.officeconvert.topdf.testing.ChartXml.bar("SheetChartTitle");
        RawXlsx x = new RawXlsx().sheet("Data", "<sheetData><row r=\"1\">" + RawXlsx.inline("A1", "CellMarker")
                + "</row></sheetData><drawing r:id=\"rIdD\"/>");
        drawing(x, 1, anchor(1, 2, 6, 14, chartFrame("rIdC"), ""));
        x.part("xl/charts/chart1.xml", "application/vnd.openxmlformats-officedocument.drawingml.chart+xml", chart)
                .rel("xl/drawings/drawing1.xml", "rIdC", "chart", "../charts/chart1.xml");
        x.chartsheet("ChartOnly", "<chartsheet xmlns=\"" + RawXlsx.MAIN + "\" xmlns:r=\"" + RawXlsx.R + "\"><sheetPr/>"
                + "<sheetViews><sheetView workbookViewId=\"0\"/></sheetViews><pageMargins left=\"0.7\" right=\"0.7\""
                + " top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/><drawing r:id=\"rIdD2\"/></chartsheet>");
        x.part("xl/drawings/drawing2.xml", "application/vnd.openxmlformats-officedocument.drawing+xml",
                "<xdr:wsDr " + XDR + "><xdr:absoluteAnchor><xdr:pos x=\"0\" y=\"0\"/><xdr:ext cx=\"8672000\""
                        + " cy=\"6010000\"/>" + chartFrame("rIdC2") + "<xdr:clientData/></xdr:absoluteAnchor></xdr:wsDr>")
                .rel("xl/chartsheets/sheet2.xml", "rIdD2", "drawing", "../drawings/drawing2.xml")
                .part("xl/charts/chart2.xml", "application/vnd.openxmlformats-officedocument.drawingml.chart+xml",
                        sheetChart)
                .rel("xl/drawings/drawing2.xml", "rIdC2", "chart", "../charts/chart2.xml");
        XlsxTesting.Converted c = XlsxTesting.convert(dir, "charts.xlsx", x.bytes());
        assertEquals(2, c.pages().size());
        assertTrue(c.pages().get(0).contains("EmbeddedChartTitle") && c.pages().get(0).contains("CatAlpha"), c.all());
        assertTrue(c.pages().get(1).contains("SheetChartTitle"), c.all());
    }

    @Test
    void chartFormatsJavaCannotParseStillPrintTheChart() throws Exception {
        String chart = stirling.software.officeconvert.topdf.testing.ChartXml.bar("OddFormatChart")
                .replaceFirst("<c:cat>.*</c:cat>", "<c:cat><c:numRef><c:numCache>"
                        + "<c:formatCode>00,,</c:formatCode><c:ptCount val=\"2\"/><c:pt idx=\"0\"><c:v>4400000</c:v>"
                        + "</c:pt><c:pt idx=\"1\"><c:v>7654321</c:v></c:pt></c:numCache></c:numRef></c:cat>")
                .replace("<c:valAx><c:axId val=\"2\"/>", "<c:valAx><c:axId val=\"2\"/><c:numFmt"
                        + " formatCode=\"0#,##0.00\" sourceLinked=\"0\"/>");
        RawXlsx x = new RawXlsx().sheet("Data", "<sheetData><row r=\"1\">" + RawXlsx.inline("A1", "CellMarker")
                + "</row></sheetData><drawing r:id=\"rIdD\"/>");
        drawing(x, 1, anchor(1, 2, 6, 14, chartFrame("rIdC"), ""));
        x.part("xl/charts/chart1.xml", "application/vnd.openxmlformats-officedocument.drawingml.chart+xml", chart)
                .rel("xl/drawings/drawing1.xml", "rIdC", "chart", "../charts/chart1.xml");
        String text = XlsxTesting.convert(dir, "fmt.xlsx", x.bytes()).all();
        assertTrue(text.contains("OddFormatChart") && text.contains("04") && text.contains("08"), text);
        assertTrue(text.contains("00,020.00"), text);
    }

    @Test
    void adjustedPresetShapesAreDrawnWithTheirAdjustments() throws Exception {
        String arrow = "<a:prstGeom prst=\"rightArrow\"><a:avLst><a:gd name=\"adj1\" fmla=\"val 100000\"/>"
                + "</a:avLst></a:prstGeom>";
        RawXlsx x = new RawXlsx().sheet("S", "<sheetData><row r=\"1\">" + RawXlsx.inline("A1", "CellText")
                + "</row></sheetData><drawing r:id=\"rIdD\"/>");
        drawing(x, 1, anchor(1, 1, 12, 12, shape(2, "", arrow, "FF0000", "ArrowLabel"), ""));
        XlsxTesting.Converted c = XlsxTesting.convert(dir, "adj.xlsx", x.bytes());
        assertTrue(c.all().contains("ArrowLabel") && c.all().contains("CellText"), c.all());
    }

    @Test
    void hiddenAndNonPrintingObjectsAreLeftOutAndPresetsKeepTheirOutline() throws Exception {
        String rect = "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>";
        String arrow = "<a:prstGeom prst=\"rightArrow\"><a:avLst/></a:prstGeom>";
        String anchors = anchor(1, 1, 4, 4, shape(2, "", rect, "FFFF00", "ShownShape"), "")
                + anchor(1, 5, 4, 8, shape(3, " hidden=\"1\"", rect, "FFFF00", "HiddenShape"), "")
                + anchor(1, 9, 4, 12, shape(4, "", rect, "FFFF00", "NoPrintShape"), " fPrintsWithSheet=\"0\"")
                + anchor(6, 1, 12, 12, shape(5, "", arrow, "FF0000", ""), "");
        RawXlsx x = new RawXlsx().sheet("S", "<sheetData><row r=\"1\">" + RawXlsx.inline("A1", "CellText")
                + "</row></sheetData><drawing r:id=\"rIdD\"/>");
        drawing(x, 1, anchors);
        XlsxTesting.Converted c = XlsxTesting.convert(dir, "hidden.xlsx", x.bytes());
        assertTrue(c.all().contains("ShownShape"), c.all());
        assertFalse(c.all().contains("HiddenShape") || c.all().contains("NoPrintShape"), c.all());
    }

    static java.awt.image.BufferedImage page(Path pdf) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument d = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
            return new org.apache.pdfbox.rendering.PDFRenderer(d).renderImageWithDPI(0, 72);
        }
    }

    static java.awt.Color at(java.awt.image.BufferedImage img, int x, int y) {
        return new java.awt.Color(img.getRGB(x, y));
    }

    private static String rows(int n) {
        StringBuilder b = new StringBuilder("<sheetData><row r=\"1\">" + RawXlsx.inline("A1", "Name")
                + RawXlsx.inline("B1", "Amount") + "</row>");
        for (int i = 2; i <= n; i++) {
            b.append("<row r=\"").append(i).append("\">").append(RawXlsx.inline("A" + i, "Item" + i))
                    .append(RawXlsx.number("B" + i, 0, Integer.toString(i * 10))).append("</row>");
        }
        return b.append("</sheetData>").toString();
    }

    @Test
    void tableStylesAreDrawn() throws Exception {
        RawXlsx x = new RawXlsx().sheet("S", rows(7) + "<tableParts count=\"1\"><tablePart r:id=\"rIdT\"/></tableParts>")
                .part("xl/tables/table1.xml", "application/vnd.openxmlformats-officedocument.spreadsheetml.table+xml",
                        "<table xmlns=\"" + RawXlsx.MAIN + "\" id=\"1\" name=\"T1\" displayName=\"T1\" ref=\"A1:B7\">"
                                + "<autoFilter ref=\"A1:B7\"/><tableColumns count=\"2\"><tableColumn id=\"1\" name=\"Name\"/>"
                                + "<tableColumn id=\"2\" name=\"Amount\"/></tableColumns><tableStyleInfo"
                                + " name=\"TableStyleMedium2\" showFirstColumn=\"0\" showLastColumn=\"0\""
                                + " showRowStripes=\"1\" showColumnStripes=\"0\"/></table>")
                .rel("xl/worksheets/sheet1.xml", "rIdT", "table", "../tables/table1.xml");
        Path in = stirling.software.officeconvert.topdf.testing.Fixtures.write(dir, "table.xlsx", x.bytes());
        Path out = dir.resolve("table.pdf");
        stirling.software.officeconvert.topdf.OfficeToPdf.convert(in, out);
        java.awt.image.BufferedImage img = page(out);
        int[] header = find(img, "Name", out);
        java.awt.Color c = at(img, header[0] - 2, header[1]);
        assertTrue(c.getBlue() > 150 && c.getRed() < 120, "header fill " + c);
    }

    // The pixel position (72 dpi) of a word's first letter, found through PDFBox
    static int[] find(java.awt.image.BufferedImage img, String word, Path pdf) throws Exception {
        int[] out = new int[2];
        try (org.apache.pdfbox.pdmodel.PDDocument d = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
            new org.apache.pdfbox.text.PDFTextStripper() {
                @Override
                protected void writeString(String text, java.util.List<org.apache.pdfbox.text.TextPosition> ps) {
                    int at = 0;
                    for (String token : text.split(" ", -1)) {
                        if (token.equals(word) && out[0] == 0 && at < ps.size()) {
                            out[0] = Math.round(ps.get(at).getXDirAdj());
                            out[1] = Math.round(ps.get(at).getYDirAdj() - ps.get(at).getHeightDir() / 2);
                        }
                        at += token.length() + 1;
                    }
                }
            }.getText(d);
        }
        return out;
    }

    @Test
    void conditionalFormatsThatNeedNoFormulaAreApplied() throws Exception {
        String styles = "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Arial\"/></font></fonts><fills count=\"2\">"
                + "<fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"
                + "</fills><borders count=\"1\"><border/></borders><cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\""
                + " fillId=\"0\" borderId=\"0\"/></cellXfs><dxfs count=\"1\"><dxf><font><color rgb=\"FF9C0006\"/></font>"
                + "<fill><patternFill><bgColor rgb=\"FFFFC7CE\"/></patternFill></fill></dxf></dxfs>";
        String cf = "<conditionalFormatting sqref=\"B2:B7\"><cfRule type=\"cellIs\" dxfId=\"0\" priority=\"1\""
                + " operator=\"greaterThan\"><formula>45</formula></cfRule></conditionalFormatting>"
                + "<conditionalFormatting sqref=\"C2:C7\"><cfRule type=\"colorScale\" priority=\"2\"><colorScale><cfvo"
                + " type=\"min\"/><cfvo type=\"max\"/><color rgb=\"FFF8696B\"/><color rgb=\"FF63BE7B\"/></colorScale>"
                + "</cfRule></conditionalFormatting><conditionalFormatting sqref=\"A2:A7\"><cfRule type=\"expression\""
                + " dxfId=\"0\" priority=\"3\"><formula>INDIRECT(\"x\")</formula></cfRule></conditionalFormatting>";
        StringBuilder data = new StringBuilder("<sheetData>");
        for (int i = 2; i <= 7; i++) {
            data.append("<row r=\"").append(i).append("\">").append(RawXlsx.inline("A" + i, "Item" + i))
                    .append(RawXlsx.number("B" + i, 0, Integer.toString(i * 10)))
                    .append(RawXlsx.number("C" + i, 0, Integer.toString(i))).append("</row>");
        }
        data.append("</sheetData>");
        RawXlsx x = new RawXlsx().styles(styles).sheet("S", data + cf);
        Path in = stirling.software.officeconvert.topdf.testing.Fixtures.write(dir, "cf.xlsx", x.bytes());
        Path out = dir.resolve("cf.pdf");
        stirling.software.officeconvert.topdf.OfficeToPdf.convert(in, out);
        java.awt.image.BufferedImage img = page(out);
        int[] low = find(img, "20", out);
        int[] high = find(img, "70", out);
        java.awt.Color plain = at(img, low[0] - 4, low[1]);
        java.awt.Color hit = at(img, high[0] - 4, high[1]);
        assertTrue(plain.getRed() > 240 && plain.getGreen() > 240, "unmatched cell " + plain);
        assertTrue(hit.getRed() > 240 && hit.getGreen() < 220, "matched cell " + hit);
        int[] minCell = find(img, "2", out);
        java.awt.Color scale = at(img, minCell[0] - 4, minCell[1]);
        assertTrue(scale.getRed() > 200 && scale.getGreen() < 150, "colour scale minimum " + scale);
        int[] item = find(img, "Item2", out);
        java.awt.Color expr = at(img, item[0] - 2, item[1]);
        assertTrue(expr.getGreen() > 240, "formula rule applied: " + expr);
    }

    @Test
    void headerPicturesArePrinted() throws Exception {
        String vml = "<xml xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:o=\"urn:schemas-microsoft-com:office:office\">"
                + "<v:shape id=\"LH\" o:spid=\"_x0000_s1025\" type=\"#_x0000_t75\" style=\"position:absolute;"
                + "margin-left:0;margin-top:0;width:72pt;height:36pt;z-index:1\"><v:imagedata o:relid=\"rIdP\""
                + " o:title=\"logo\"/></v:shape></xml>";
        RawXlsx x = new RawXlsx().sheet("S", "<sheetData><row r=\"1\">" + RawXlsx.inline("A1", "Body")
                + "</row></sheetData><headerFooter><oddHeader>&amp;L&amp;G&amp;CHeaderTitle</oddHeader></headerFooter>"
                + "<legacyDrawingHF r:id=\"rIdV\"/>")
                .part("xl/drawings/vmlDrawing1.vml", "application/vnd.openxmlformats-officedocument.vmlDrawing", vml)
                .binary("xl/media/image1.png", "png", "image/png",
                        stirling.software.officeconvert.topdf.testing.Fixtures.png(64, 32, java.awt.Color.RED))
                .rel("xl/worksheets/sheet1.xml", "rIdV", "vmlDrawing", "../drawings/vmlDrawing1.vml")
                .rel("xl/drawings/vmlDrawing1.vml", "rIdP", "image", "../media/image1.png");
        XlsxTesting.Converted c = XlsxTesting.convert(dir, "hfpic.xlsx", x.bytes());
        assertTrue(c.all().contains("HeaderTitle"), c.all());
        assertEquals(1, c.images());
    }

    @Test
    void escapedCharactersInAHeaderAreDecoded() throws Exception {
        RawXlsx x = new RawXlsx().sheet("S", "<sheetData><row r=\"1\">" + RawXlsx.inline("A1", "Body")
                + "</row></sheetData><headerFooter><oddHeader>&amp;COFFICIAL_x000D_</oddHeader><oddFooter>&amp;CA_x005F_xB"
                + "</oddFooter></headerFooter>");
        XlsxTesting.Converted c = XlsxTesting.convert(dir, "hfescape.xlsx", x.bytes());
        assertTrue(c.all().contains("OFFICIAL"), c.all());
        assertTrue(!c.all().contains("_x000D_"), c.all());
        assertTrue(c.all().contains("A_xB"), c.all());
    }
}
