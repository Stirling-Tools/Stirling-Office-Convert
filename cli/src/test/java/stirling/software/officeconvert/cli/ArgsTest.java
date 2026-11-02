package stirling.software.officeconvert.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.lineart.LineArt;

class ArgsTest {

    @Test
    void pageRanges() throws Usage {
        assertArrayEquals(new int[] {2, 5}, Args.pages("2-5"));
        assertArrayEquals(new int[] {2, 5}, Args.pages(" 2 - 5 "));
        assertArrayEquals(new int[] {3, 3}, Args.pages("3"));
        assertArrayEquals(new int[] {2, 0}, Args.pages("2-"));
        assertArrayEquals(new int[] {0, 5}, Args.pages("-5"));
        assertArrayEquals(new int[] {123456789, 123456789}, Args.pages("123456789"));
    }

    @Test
    void badPageRanges() {
        for (String bad : List.of("", " ", "abc", "1-2-3", "1234567890", "1,2")) {
            Usage e = assertThrows(Usage.class, () -> Args.pages(bad));
            assertEquals("--pages needs a range like 2-5, 3, 2- or -5, not '" + bad + "'", e.getMessage());
        }
    }

    @Test
    void helpWinsOnlyBeforeAMistake() throws Usage {
        assertNull(Args.parse(new String[] {"in.pdf", "--help", "--bogus"}));
        assertEquals("unknown option --bogus",
                assertThrows(Usage.class, () -> Args.parse(new String[] {"--bogus", "-h"})).getMessage());
    }

    @Test
    void missingValueAndInput() {
        assertEquals("-o needs a value", assertThrows(Usage.class, () -> Args.parse(new String[] {"in.pdf", "-o"}))
                .getMessage());
        assertEquals("no PDF or Office document given",
                assertThrows(Usage.class, () -> Args.parse(new String[] {"-q"})).getMessage());
    }

    @Test
    void formatIsNormalised() throws Usage {
        Args a = Args.parse(new String[] {"in.pdf", "--format", ".TXT"});
        assertEquals("txt", a.format());
        assertTrue(a.asksForOfficeFormat());
        assertFalse(a.asksForPdf());
        Args pdf = Args.parse(new String[] {"in.docx", "--format", "pdf"});
        assertTrue(pdf.asksForPdf());
        assertFalse(pdf.asksForOfficeFormat());
        Args none = Args.parse(new String[] {"in.pdf"});
        assertEquals("docx", none.format());
        assertFalse(none.asksForPdf() || none.asksForOfficeFormat());
    }

    @Test
    void urlsAndPathsAreSeparated() throws Usage {
        Args a = Args.parse(new String[] {"HTTPS://example.com", "in.pdf", "-"});
        assertEquals(List.of("HTTPS://example.com"), a.urls());
        assertEquals(List.of(Path.of("in.pdf"), Path.of("-")), a.inputs());
    }

    @Test
    void rewritesAreCollected() throws Usage {
        Args a = Args.parse(new String[] {"in.pdf", "--pdfa", "2b", "--line-art", "otsu", "--optimize"});
        assertEquals(Set.of(Mode.PDFA, Mode.LINE_ART, Mode.OPTIMIZE), a.rewrites());
        assertFalse(a.lineArtTuned());
        assertEquals(LineArt.Method.OTSU, a.lineArt().method());
        assertTrue(Args.parse(new String[] {"in.pdf", "--ink-color"}).lineArtTuned());
        assertTrue(Args.parse(new String[] {"in.pdf"}).rewrites().isEmpty());
    }

    @Test
    void pagesAndPicturesAreRead() throws Usage {
        Args a = Args.parse(new String[] {"in.pdf", "--pages", "2-", "--pictures", " LossLess "});
        assertEquals(2, a.first());
        assertEquals(0, a.last());
        assertTrue(a.pagesGiven());
        assertEquals("--pictures is compact or lossless, not 'raw'",
                assertThrows(Usage.class, () -> Args.parse(new String[] {"in.pdf", "--pictures", "raw"})).getMessage());
    }
}
