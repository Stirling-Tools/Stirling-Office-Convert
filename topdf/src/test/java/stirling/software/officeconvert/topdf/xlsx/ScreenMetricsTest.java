package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.font.FontLibrary;

class ScreenMetricsTest {

    @TempDir
    Path dir;

    @Test
    void theDigitWidthComesFromAMetricCompatibleStandInsHinting() throws IOException {
        Path fonts = Files.createDirectories(dir.resolve("fonts"));
        try (InputStream in = PDDocument.class.getResourceAsStream(
                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            Files.copy(in, fonts.resolve("LiberationSans-Regular.ttf"));
        }
        FontLibrary library = FontLibrary.of(List.of(fonts));
        assertEquals(9, ColumnUnits.screenDigit(library, "Arial", 12));
        assertEquals(7, ColumnUnits.screenDigit(library, "Arial", 9));
        assertEquals(7, ColumnUnits.screenDigit(library, "Calibri", 11));
    }

    @Test
    void officeFontDigitsAreExcelsWhicheverStandInIsInstalled() throws IOException {
        Path fonts = Files.createDirectories(dir.resolve("sans"));
        try (InputStream in = PDDocument.class.getResourceAsStream(
                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            Files.copy(in, fonts.resolve("LiberationSans-Regular.ttf"));
        }
        FontLibrary none = FontLibrary.of(List.of(Files.createDirectories(dir.resolve("none"))));
        for (FontLibrary library : List.of(FontLibrary.of(List.of(fonts)), none, FontLibrary.system())) {
            assertEquals(6, ColumnUnits.screenDigit(library, "Times New Roman", 10));
            assertEquals(7, ColumnUnits.screenDigit(library, "Times New Roman", 11));
            assertEquals(7, ColumnUnits.screenDigit(library, "Calibri", 11));
            assertEquals(7, ColumnUnits.screenDigit(library, "Arial", 10));
            assertEquals(9, ColumnUnits.screenDigit(library, "Arial", 12));
            assertEquals(8, ColumnUnits.screenDigit(library, "Verdana", 10));
            assertEquals(7, ColumnUnits.screenDigit(library, "Verdana", 7));
            assertEquals(8, ColumnUnits.screenDigit(library, "Georgia", 9));
            assertEquals(7, ColumnUnits.screenDigit(library, "Aptos Narrow", 11));
        }
    }

    @Test
    void arialRowsTakeExcelsHeightsWhicheverStandInIsInstalled() throws IOException {
        Path sans = Files.createDirectories(dir.resolve("liberation"));
        try (InputStream in = PDDocument.class.getResourceAsStream(
                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            Files.copy(in, sans.resolve("LiberationSans-Regular.ttf"));
        }
        FontLibrary none = FontLibrary.of(List.of(Files.createDirectories(dir.resolve("none"))));
        for (FontLibrary library : List.of(FontLibrary.of(List.of(sans)), none)) {
            assertEquals(20, FontMeasure.of(library, "Arial", true, false).screenLinePx(11));
            assertEquals(21, FontMeasure.of(library, "Arial", true, false).screenLinePx(12));
            assertEquals(24, FontMeasure.of(library, "Arial", true, false).screenLinePx(14));
            assertEquals(15, FontMeasure.of(library, "Arial", false, false).screenLinePx(8));
            assertEquals(17, FontMeasure.of(library, "Arial", false, false).screenLinePx(10));
            assertEquals(19, FontMeasure.of(library, "Arial", false, false).screenLinePx(11));
            assertEquals(20, FontMeasure.of(library, "Arial", false, false).screenLinePx(12));
            assertEquals(27, FontMeasure.of(library, "Arial", false, false).screenLinePx(16));
            assertEquals(20, FontMeasure.of(library, "Calibri", true, false).screenLinePx(11));
        }
    }

    @Test
    void aOnePointDefaultFontStillLaysOutItsCells() throws IOException {
        FontLibrary none = FontLibrary.of(List.of(Files.createDirectories(dir.resolve("none"))));
        assertEquals(1, ColumnUnits.screenDigit(none, "Garamond", 1));
        assertEquals(1, ColumnUnits.screenDigit(none, "Arial Narrow", 1));
        String styles = "<fonts count=\"1\"><font><sz val=\"1\"/><name val=\"Garamond\"/></font></fonts><fills"
                + " count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill"
                + " patternType=\"gray125\"/></fill></fills><borders count=\"1\"><border/></borders><cellStyleXfs"
                + " count=\"1\"><xf/></cellStyleXfs><cellXfs count=\"1\"><xf/></cellXfs>";
        String sheet = "<sheetData><row r=\"1\">" + RawXlsx.inline("A1", "Hello") + RawXlsx.inline("C1", "World")
                + "</row></sheetData>";
        String text = XlsxTesting.convert(dir, "tiny.xlsx", new RawXlsx().styles(styles).sheet("S", sheet).bytes())
                .all();
        assertTrue(text.contains("Hello") && text.contains("World"), text);
    }

    @Test
    void timesNewRomanRowsLeaveOutTheExternalLeadingOnScreen() throws IOException {
        FontLibrary none = FontLibrary.of(List.of(Files.createDirectories(dir.resolve("none"))));
        assertEquals(17, FontMeasure.of(none, "Times New Roman", false, false).screenLinePx(10));
        assertEquals(20, FontMeasure.of(none, "Arial", false, false).screenLinePx(12));
    }
}
