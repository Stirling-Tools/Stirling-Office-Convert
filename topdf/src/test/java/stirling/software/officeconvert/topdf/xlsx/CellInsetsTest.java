package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.font.FontLibrary;

class CellInsetsTest {

    @TempDir
    Path dir;

    private static final String STYLES = "<numFmts count=\"0\"/><fonts count=\"3\"><font><sz val=\"11\"/><name"
            + " val=\"Calibri\"/></font><font><sz val=\"9\"/><name val=\"Calibri\"/></font><font><sz val=\"20\"/><name"
            + " val=\"Calibri\"/></font></fonts><fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill>"
            + "</fills><borders count=\"1\"><border/></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\""
            + " fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"4\"><xf numFmtId=\"0\" fontId=\"0\""
            + " fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\""
            + " xfId=\"0\" applyFont=\"1\"/><xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\""
            + " applyFont=\"1\"/><xf numFmtId=\"3\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\""
            + " applyNumberFormat=\"1\"/></cellXfs>";

    private static final String SETUP = "<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\""
            + " header=\"0.3\" footer=\"0.3\"/><pageSetup paperSize=\"9\" orientation=\"portrait\"/>";

    private static double pad(Typesetter t, String family, double size) {
        FontSpec f = new FontSpec(family, size, false, false, null, false, null, null);
        return CellLayout.pad(t, new CellText(CellText.Kind.TEXT, List.of(new TextRun("x", f)), null, false, 0), null)
                / PrintMetrics.PX;
    }

    @Test
    void textSitsFourPrinterPixelsPlusAQuarterDigitFromItsEdge() {
        Typesetter t = new Typesetter(FontLibrary.of(List.of()));
        assertEquals(16, pad(t, "Calibri", 11), 1e-9);
        assertEquals(14, pad(t, "Calibri", 9), 1e-9);
        assertEquals(11, pad(t, "Calibri", 6), 1e-9);
        assertEquals(26, pad(t, "Calibri", 20), 1e-9);
        assertEquals(17, pad(t, "Arial", 11), 1e-9);
    }

    private static List<float[]> starts(Path pdf) throws Exception {
        List<float[]> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> p) {
                    out.add(new float[] {p.get(0).getXDirAdj(), text.length(), p.get(0).getFontSizeInPt()});
                }
            }.getText(doc);
        }
        return out;
    }

    @Test
    void leftAlignedTextStartsAtItsFontsInset() throws Exception {
        String sheet = "<sheetData><row r=\"1\"><c r=\"A1\" s=\"1\" t=\"inlineStr\"><is><t>Nine</t></is></c></row>"
                + "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>Eleven</t></is></c></row><row r=\"3\" ht=\"30\""
                + " customHeight=\"1\"><c r=\"A3\" s=\"2\" t=\"inlineStr\"><is><t>Twenty</t></is></c></row></sheetData>"
                + SETUP;
        XlsxTesting.convert(dir, "insets.xlsx", new RawXlsx().styles(STYLES).sheet("S", sheet).bytes());
        List<float[]> s = starts(dir.resolve("insets.xlsx.pdf"));
        double edge = 0.7 * 72 + PrintMetrics.ORIGIN;
        assertEquals(3, s.size());
        assertEquals(edge + 14 * PrintMetrics.PX, s.get(0)[0], 0.01);
        assertEquals(edge + 16 * PrintMetrics.PX, s.get(1)[0], 0.01);
        assertEquals(edge + 26 * PrintMetrics.PX, s.get(2)[0], 0.01);
    }

    @Test
    void anOverflowingNumberShowsHashesCentredFourPixelsRight() throws Exception {
        String sheet = "<cols><col min=\"1\" max=\"1\" width=\"4.625\" customWidth=\"1\"/></cols><sheetData><row"
                + " r=\"1\"><c r=\"A1\" s=\"3\"><v>22305.23</v></c></row></sheetData>" + SETUP;
        XlsxTesting.Converted c = XlsxTesting.convert(dir, "hash.xlsx",
                new RawXlsx().styles(STYLES).sheet("S", sheet).bytes());
        FontLibrary fonts = FontLibrary.system();
        Typesetter t = new Typesetter(fonts);
        FontSpec f = new FontSpec("Calibri", 11, false, false, null, false, null, null);
        double hash = t.width("#", f, 11);
        double width = new PrintMetrics(FontMeasure.of(fonts, "Calibri", false, false), 11).columnPoints(4.625);
        int n = (int) Math.floor((width - 2 * CellLayout.PAD) / hash + 1e-9);
        assertEquals("#".repeat(n), c.all().trim());
        double x = 0.7 * 72 + PrintMetrics.ORIGIN + 8 * PrintMetrics.PX + (width - 8 * PrintMetrics.PX - n * hash) / 2;
        assertEquals(x, starts(dir.resolve("hash.xlsx.pdf")).get(0)[0], 0.01);
    }

    private static List<String> lines(String text, double width) {
        List<String> out = new ArrayList<>();
        FontSpec f = new FontSpec("Calibri", 11, false, false, null, false, null, null);
        for (CellLayout.Line l : CellLayout.wrap(List.of(new TextRun(text, f)), width, (s, font) -> s.length())) {
            StringBuilder b = new StringBuilder();
            l.runs().forEach(r -> b.append(r.text()));
            out.add(b.toString());
        }
        return out;
    }

    @Test
    void wrappedTextBreaksAfterAHyphenInsideAWord() {
        assertEquals(List.of("aa bb-", "cc"), lines("aa bb-cc", 6));
        assertEquals(List.of("1VA-3VA, 5WS-", "8WS"), lines("1VA-3VA, 5WS-8WS", 13));
        assertEquals(List.of("x", "-12"), lines("x -12", 3));
    }

    @Test
    void aFractionThatRoundsAwayKeepsItsPlaceholdersAsBlankSpace() {
        char digit = (char) (FormatCode.SPACE_BASE + '0');
        String blank = " " + digit + (char) (FormatCode.SPACE_BASE + '/') + digit;
        assertEquals(blank, ValueFormatter.blankFraction("# ?/?"));
        assertEquals(" " + digit + digit + (char) (FormatCode.SPACE_BASE + '/') + digit + digit,
                ValueFormatter.blankFraction("[Red]# ??/??"));
        assertEquals("", ValueFormatter.blankFraction("0.00"));
    }

    private static final String ALIGNED = "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
            + "</fonts><fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills><borders"
            + " count=\"1\"><border/></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\""
            + " borderId=\"0\"/></cellStyleXfs><cellXfs count=\"5\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\""
            + " borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\""
            + " applyAlignment=\"1\"><alignment horizontal=\"left\" indent=\"2\"/></xf><xf numFmtId=\"0\" fontId=\"0\""
            + " fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment textRotation=\"90\"/></xf><xf"
            + " numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment"
            + " textRotation=\"180\"/></xf><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\""
            + " applyAlignment=\"1\"><alignment horizontal=\"centerContinuous\"/></xf></cellXfs>";

    @Test
    void anIndentLevelIsThreeSpacesOfTheNormalFont() throws Exception {
        String sheet = "<sheetData><row r=\"1\"><c r=\"A1\" s=\"1\" t=\"inlineStr\"><is><t>Indented</t></is></c></row>"
                + "</sheetData>" + SETUP;
        XlsxTesting.convert(dir, "indent.xlsx", new RawXlsx().styles(ALIGNED).sheet("S", sheet).bytes());
        FontSpec f = new FontSpec("Calibri", 11, false, false, null, false, null, null);
        double space = new Typesetter(FontLibrary.system()).width(" ", f, 11);
        double x = 0.7 * 72 + PrintMetrics.ORIGIN + 16 * PrintMetrics.PX + 2 * 3 * space;
        assertEquals(x, starts(dir.resolve("indent.xlsx.pdf")).get(0)[0], 0.01);
    }

    private static float originX(Path pdf) throws Exception {
        float[] x = new float[1];
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> p) {
                    x[0] = p.get(0).getTextMatrix().getTranslateX();
                }
            }.getText(doc);
        }
        return x[0];
    }

    @Test
    void verticalTextKeepsItsBaselineSideAgainstTheCellEdge() throws Exception {
        String row = "<cols><col min=\"1\" max=\"1\" width=\"30\" customWidth=\"1\"/></cols><sheetData><row r=\"1\""
                + " ht=\"60\" customHeight=\"1\"><c r=\"A1\" s=\"%d\" t=\"inlineStr\"><is><t>Upright</t></is></c></row>"
                + "</sheetData>" + SETUP;
        XlsxTesting.convert(dir, "up.xlsx", new RawXlsx().styles(ALIGNED).sheet("S", row.formatted(2)).bytes());
        XlsxTesting.convert(dir, "down.xlsx", new RawXlsx().styles(ALIGNED).sheet("S", row.formatted(3)).bytes());
        double left = 0.7 * 72 + PrintMetrics.ORIGIN;
        double width = new PrintMetrics(FontMeasure.of(FontLibrary.system(), "Calibri", false, false), 11)
                .columnPoints(30);
        double up = originX(dir.resolve("up.xlsx.pdf"));
        double down = originX(dir.resolve("down.xlsx.pdf"));
        assertTrue(up > left + width - 10 && up < left + width, "reading up at " + up);
        assertTrue(down > left && down < left + 10, "reading down at " + down);
    }

    @Test
    void centringAcrossASelectionRunsOnToTheNextPage() throws Exception {
        String sheet = "<cols><col min=\"1\" max=\"4\" width=\"30\" customWidth=\"1\"/></cols><sheetData><row r=\"1\">"
                + "<c r=\"A1\" s=\"4\" t=\"inlineStr\"><is><t>Centred over four columns</t></is></c><c r=\"B1\""
                + " s=\"4\"/><c r=\"C1\" s=\"4\"/><c r=\"D1\" s=\"4\"/></row><row r=\"2\"><c r=\"D2\""
                + " t=\"inlineStr\"><is><t>End</t></is></c></row></sheetData>" + SETUP;
        XlsxTesting.Converted c = XlsxTesting.convert(dir, "across.xlsx",
                new RawXlsx().styles(ALIGNED).sheet("S", sheet).bytes());
        assertEquals(2, c.pages().size());
        assertTrue(c.pages().get(0).contains("Centred"), c.pages().get(0));
        assertTrue(c.pages().get(1).contains("columns"), c.pages().get(1));
    }
}
