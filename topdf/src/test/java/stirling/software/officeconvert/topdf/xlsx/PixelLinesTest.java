package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Rectangle2D;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PixelLinesTest {

    @TempDir
    Path dir;

    private static final double PX = PrintMetrics.PX;

    @Test
    void anOddPixelWidthPutsItsExtraPixelAfterTheEdge() {
        assertEquals(10 + PX / 2, BorderPainter.centre(10, PX, 1), 1e-9);
        assertEquals(10, BorderPainter.centre(10, 8 * PX, 1), 1e-9);
        assertEquals(10 + PX / 0.87 / 2, BorderPainter.centre(10, 7 * PX / 0.87, 0.87), 1e-9);
        assertEquals(10, BorderPainter.centre(10, 24 * PX / 0.87, 0.87), 1e-9);
    }

    private static final String STYLES = "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
            + "</fonts><fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills><borders count=\"4\">"
            + "<border/><border><top style=\"thin\"/></border><border><bottom style=\"thin\"/></border><border><left"
            + " style=\"hair\"/><right style=\"hair\"/><top style=\"hair\"/><bottom style=\"hair\"/></border></borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            + "<cellXfs count=\"4\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf"
            + " numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\"/><xf numFmtId=\"0\""
            + " fontId=\"0\" fillId=\"0\" borderId=\"2\" xfId=\"0\" applyBorder=\"1\"/><xf numFmtId=\"0\" fontId=\"0\""
            + " fillId=\"0\" borderId=\"3\" xfId=\"0\" applyBorder=\"1\"/></cellXfs>";

    private static final String SETUP = "<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\""
            + " header=\"0.3\" footer=\"0.3\"/><pageSetup paperSize=\"9\" orientation=\"portrait\"/>";

    private List<Rectangle2D> fills(String name, String sheet, String workbookExtra) throws Exception {
        byte[] xlsx = new RawXlsx().styles(STYLES).sheet("S", sheet).workbookExtra(workbookExtra).bytes();
        XlsxTesting.convert(dir, name, xlsx);
        return Fills.of(dir.resolve(name + ".pdf"), 0);
    }

    @Test
    void hairBordersFillTheDevicePixelBelowAndRightOfTheirEdge() throws Exception {
        String sheet = "<sheetData><row r=\"1\"><c r=\"A1\" s=\"3\" t=\"inlineStr\"><is><t>Hair</t></is></c></row>"
                + "</sheetData>" + SETUP;
        List<Rectangle2D> hair = fills("hair.xlsx", sheet, "").stream()
                .filter(r -> Math.min(r.getWidth(), r.getHeight()) < 0.2).toList();
        double left = 0.7 * 72 + PrintMetrics.ORIGIN;
        double top = 0.75 * 72 + PrintMetrics.ORIGIN;
        assertEquals(4, hair.size(), "hair lines " + hair);
        assertTrue(hair.stream().anyMatch(r -> r.getWidth() > 1 && Math.abs(r.getMinY() - top) < 0.005
                && Math.abs(r.getMaxY() - (top + PX)) < 0.005), "top edge " + hair);
        assertTrue(hair.stream().anyMatch(r -> r.getHeight() > 1 && Math.abs(r.getMinX() - left) < 0.005
                && Math.abs(r.getMaxX() - (left + PX)) < 0.005), "left edge " + hair);
    }

    private static final String PRINT_AREA = "<definedNames><definedName name=\"_xlnm.Print_Area\""
            + " localSheetId=\"0\">S!$A$1:$A$1</definedName></definedNames>";

    private int thinLines(String name, int a1Style, int a2Style) throws Exception {
        String sheet = "<sheetData><row r=\"1\"><c r=\"A1\" s=\"" + a1Style + "\" t=\"inlineStr\"><is><t>Printed</t>"
                + "</is></c></row><row r=\"2\"><c r=\"A2\" s=\"" + a2Style + "\" t=\"inlineStr\"><is><t>Left out</t>"
                + "</is></c></row></sheetData>" + SETUP;
        return (int) fills(name, sheet, PRINT_AREA).stream()
                .filter(r -> Math.abs(r.getHeight() - 8 * PX) < 0.01 && r.getWidth() > 10).count();
    }

    @Test
    void aPageDrawsItsOwnCellsEdgesButNotTheEdgesOfCellsBeyondIt() throws Exception {
        assertEquals(1, thinLines("own.xlsx", 2, 0));
        assertEquals(0, thinLines("beyond.xlsx", 0, 1));
    }
}
