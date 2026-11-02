package stirling.software.officeconvert.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OutputFormatTest {

    @Test
    void everyExtensionResolvesToItsFormat() {
        for (OutputFormat f : OutputFormat.values()) {
            assertSame(f, OutputFormat.of(f.extension()));
        }
        assertNull(OutputFormat.of(OutputFormat.PDF));
        assertNull(OutputFormat.of("DOCX"));
    }

    @Test
    void unknownFormatListsTheChoices() throws Usage {
        assertEquals("pdf", OutputFormat.known("pdf"));
        assertEquals("ods", OutputFormat.known("ods"));
        assertEquals("unknown output format 'foo': use docx, odt, fodt, xml, rtf, doc, txt, html, json, md, epub, azw3,"
                + " pptx, odp, ppt, xlsx or ods (pdf for Office input)",
                assertThrows(Usage.class, () -> OutputFormat.known("foo")).getMessage());
    }
}
