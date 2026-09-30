package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTColor;

class FormControlsTest {

    private static final String VML_HEAD = "<xml xmlns:v=\"urn:schemas-microsoft-com:vml\" "
            + "xmlns:o=\"urn:schemas-microsoft-com:office:office\" xmlns:x=\"urn:schemas-microsoft-com:office:excel\">";

    private static final String VML_TYPE = "application/vnd.openxmlformats-officedocument.vmlDrawing";

    @TempDir
    Path dir;

    private static String control(String type, String style, String text, String extra) {
        return "<v:shape id=\"_x0000_s10" + type.length() + "\" type=\"#_x0000_t201\" style='position:absolute;" + style
                + "'><v:textbox><div style='text-align:left'><font face=\"Arial\" size=\"200\" color=\"#000000\">" + text
                + "</font></div></v:textbox><x:ClientData ObjectType=\"" + type + "\"><x:Anchor>1, 10, 1, 4, 3, 20, 2, 4"
                + "</x:Anchor>" + extra + "</x:ClientData></v:shape>";
    }

    private static byte[] withControls(String shapes) {
        return new RawXlsx().sheet("S", "<sheetData><row r=\"1\">" + RawXlsx.inline("A1", "Cell") + "</row></sheetData>"
                + "<legacyDrawing r:id=\"rIdV\"/>")
                .part("xl/drawings/vmlDrawing1.vml", VML_TYPE, VML_HEAD + shapes + "</xml>")
                .rel("xl/worksheets/sheet1.xml", "rIdV", "vmlDrawing", "../drawings/vmlDrawing1.vml").bytes();
    }

    @Test
    void checkBoxesAndLabelsPrintButHiddenAndNonPrintingControlsDoNot() throws Exception {
        String shapes = control("Checkbox", "", "Tick me<br>second line", "<x:Checked>1</x:Checked>")
                + control("Label", "", "A label", "")
                + control("Radio", "visibility:hidden", "Hidden radio", "")
                + control("Button", "", "Press", "<x:PrintObject>False</x:PrintObject>");
        XlsxTesting.Converted c = XlsxTesting.convert(dir, "controls.xlsx", withControls(shapes));
        String text = c.all();
        assertTrue(text.contains("Tick me") && text.contains("second line") && text.contains("A label"), text);
        assertFalse(text.contains("Hidden radio") || text.contains("Press"), text);
    }

    @Test
    void aCheckBoxLabelStartsPastItsBox() throws Exception {
        XlsxTesting.convert(dir, "box.xlsx", withControls(control("Checkbox", "", "Label text", "")));
        float cell = start(dir.resolve("box.xlsx.pdf"), "Cell");
        float label = start(dir.resolve("box.xlsx.pdf"), "Label");
        assertTrue(label - cell > 60, "cell at " + cell + ", label at " + label);
    }

    @Test
    void aBrokenLegacyDrawingLeavesTheSheet() throws Exception {
        byte[] xlsx = new RawXlsx().sheet("S", "<sheetData><row r=\"1\">" + RawXlsx.inline("A1", "Still here")
                + "</row></sheetData><legacyDrawing r:id=\"rIdV\"/>")
                .part("xl/drawings/vmlDrawing1.vml", VML_TYPE, VML_HEAD + "<v:shape><unclosed></xml>")
                .rel("xl/worksheets/sheet1.xml", "rIdV", "vmlDrawing", "../drawings/vmlDrawing1.vml").bytes();
        XlsxTesting.Converted c = XlsxTesting.convert(dir, "broken.xlsx", xlsx);
        assertTrue(c.all().contains("Still here"), c.all());
    }

    @Test
    void aOneCellAnchoredShapeWidensThePrintedRange() throws Exception {
        String drawing = "<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" "
                + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><xdr:oneCellAnchor><xdr:from>"
                + "<xdr:col>0</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>0</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>"
                + "<xdr:ext cx=\"9144000\" cy=\"914400\"/><xdr:sp><xdr:nvSpPr><xdr:cNvPr id=\"2\" name=\"Wide\"/>"
                + "<xdr:cNvSpPr/></xdr:nvSpPr><xdr:spPr><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:solidFill>"
                + "<a:srgbClr val=\"FF0000\"/></a:solidFill></xdr:spPr></xdr:sp><xdr:clientData/></xdr:oneCellAnchor>"
                + "</xdr:wsDr>";
        byte[] xlsx = new RawXlsx().sheet("S", "<sheetData><row r=\"1\">" + RawXlsx.inline("A1", "x")
                + "</row></sheetData><drawing r:id=\"rIdD\"/>")
                .part("xl/drawings/drawing1.xml", "application/vnd.openxmlformats-officedocument.drawing+xml", drawing)
                .rel("xl/worksheets/sheet1.xml", "rIdD", "drawing", "../drawings/drawing1.xml").bytes();
        XlsxTesting.Converted c = XlsxTesting.convert(dir, "wide.xlsx", xlsx);
        assertEquals(2, c.pages().size());
    }

    @Test
    void aColourNamingBothAThemeColourAndRgbTakesTheTheme() {
        ExcelColors colors = new ExcelColors(null, null);
        CTColor c = CTColor.Factory.newInstance();
        c.setTheme(1);
        c.setRgb(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        assertEquals(colors.themeColor(1), colors.resolve(c, Color.MAGENTA));
        CTColor rgb = CTColor.Factory.newInstance();
        rgb.setRgb(new byte[] {(byte) 0xFF, 0x12, 0x34, 0x56});
        assertEquals(new Color(0x12, 0x34, 0x56), colors.resolve(rgb, Color.MAGENTA));
    }

    @Test
    void customRowsPrintAtThisMachinesScaleWhenTheFileDefaultIsNearIt() throws Exception {
        float[] near = rows("14.4");
        float[] same = rows("15");
        assertEquals(same[0], near[0], 0.01);
        assertEquals(same[1], near[1], 0.01);
    }

    private float[] rows(String defaultHeight) throws Exception {
        String sheet = "<sheetFormatPr defaultRowHeight=\"" + defaultHeight + "\"/><sheetData><row r=\"1\">"
                + RawXlsx.inline("A1", "One") + "</row><row r=\"2\" ht=\"40\" customHeight=\"1\">"
                + RawXlsx.inline("A2", "Two") + "</row><row r=\"3\">" + RawXlsx.inline("A3", "Three") + "</row></sheetData>";
        String name = "rows" + defaultHeight + ".xlsx";
        XlsxTesting.convert(dir, name, new RawXlsx().sheet("S", sheet).bytes());
        float one = top(dir.resolve(name + ".pdf"), "One");
        return new float[] {top(dir.resolve(name + ".pdf"), "Two") - one, top(dir.resolve(name + ".pdf"), "Three") - one};
    }

    private static float start(Path pdf, String prefix) throws Exception {
        return position(pdf, prefix)[0];
    }

    private static float top(Path pdf, String prefix) throws Exception {
        return position(pdf, prefix)[1];
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
