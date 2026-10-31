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
    void timesNewRomanRowsLeaveOutTheExternalLeadingOnScreen() throws IOException {
        FontLibrary none = FontLibrary.of(List.of(Files.createDirectories(dir.resolve("none"))));
        assertEquals(17, FontMeasure.of(none, "Times New Roman", false, false).screenLinePx(10));
        assertEquals(20, FontMeasure.of(none, "Arial", false, false).screenLinePx(12));
    }
}
