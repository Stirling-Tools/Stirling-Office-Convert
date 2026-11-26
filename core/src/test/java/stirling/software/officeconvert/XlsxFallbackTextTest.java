package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.csv.CsvWriter;
import stirling.software.officeconvert.extract.PageData;
import stirling.software.officeconvert.extract.Warnings;
import stirling.software.officeconvert.layout.DocStats;
import stirling.software.officeconvert.sheet.Conventions;
import stirling.software.officeconvert.sheet.SheetBuilder;

class XlsxFallbackTextTest {

    private static SheetBuilder builder() {
        return new SheetBuilder(new CsvWriter(new ByteArrayOutputStream()), new DocStats(), Conventions.UNKNOWN,
                new SheetBuilder.Settings(SheetBuilder.Split.TABLE, false, false, false), 1);
    }

    @Test
    void textLostWhenBothReconstructionsFailMakesTheResultPartial() throws IOException {
        PageData unreadable = new PageData(0, 612, 792, 0, null, List.of(), List.of(), null, List.of());
        try (Warnings.Sink sink = Warnings.open()) {
            PdfToXlsx.plainPage(builder(), unreadable, new IllegalStateException("analysis"), true, false);
            assertTrue(sink.partial(), () -> sink.messages().toString());
        }
    }

    @Test
    void textKeptAsLinesIsOnlyANote() throws IOException {
        PageData empty = new PageData(0, 612, 792, 0, List.of(), List.of(), List.of(), null, List.of());
        try (Warnings.Sink sink = Warnings.open()) {
            PdfToXlsx.plainPage(builder(), empty, new IllegalStateException("analysis"), true, false);
            assertFalse(sink.partial(), () -> sink.messages().toString());
            assertTrue(sink.messages().stream().anyMatch(m -> m.contains("kept as lines of text")));
        }
    }
}
