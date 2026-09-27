package stirling.software.officeconvert.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.sink.SampleDocument;

class TextWriterTest {

    private static String convert() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TextWriter w = new TextWriter(out)) {
            SampleDocument.write(w);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void readsInOrderWithListLabelsAndTableRows() throws IOException {
        String text = convert();
        assertTrue(text.startsWith("Quarterly report\n"), "the header once, page number left out");
        assertTrue(text.contains("Annual  Results\n"));
        assertTrue(text.contains("Plain, bold red and a link[1]\tafter tab\n"));
        assertTrue(text.contains("\u2022 First point\n  a) Nested point\n\u2022 Second point\n"));
        assertTrue(text.contains("3. Step three\n"), "the list's own start number");
        assertTrue(text.contains("Region\t\tTotal\nNorth\t12\t30\nSouth\t18\n"), "rows, a merged cell keeping its columns");
        assertTrue(text.indexOf("Boxed words") < text.indexOf("Text beside a box."), "a box reads before its anchor");
        assertTrue(text.indexOf("Left column text.") < text.indexOf("Right column text."));
        assertTrue(text.contains("[1] The note text.\n"));
        assertFalse(text.contains("Page"), "a footer that only numbers pages is left out");
        assertFalse(text.contains("\0") || text.contains("\u00AD"));
        assertEquals(1, text.split("Quarterly report", -1).length - 1);
    }
}
