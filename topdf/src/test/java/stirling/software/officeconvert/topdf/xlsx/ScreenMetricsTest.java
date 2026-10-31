package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void timesNewRomanRowsLeaveOutTheExternalLeadingOnScreen() throws IOException {
        FontLibrary none = FontLibrary.of(List.of(Files.createDirectories(dir.resolve("none"))));
        assertEquals(17, FontMeasure.of(none, "Times New Roman", false, false).screenLinePx(10));
        assertEquals(20, FontMeasure.of(none, "Arial", false, false).screenLinePx(12));
    }
}
