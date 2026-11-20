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
        assertEquals("unknown output format 'foo': use docx, odt, fodt, xml, rtf, txt, html, json, md, epub, azw3,"
                + " pptx, odp, ppt, xlsx, ods or csv (pdf for Office input)",
                assertThrows(Usage.class, () -> OutputFormat.known("foo")).getMessage());
    }

    @Test
    void wordDocOutputIsRefused() {
        assertNull(OutputFormat.of("doc"));
        assertEquals("Word 97-2003 .doc output is not supported: use docx, or rtf for a file Word 97 opens",
                assertThrows(Usage.class, () -> OutputFormat.known("doc")).getMessage());
    }
}
