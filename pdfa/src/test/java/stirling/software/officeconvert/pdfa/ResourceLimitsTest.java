package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceLimitsTest {

    @TempDir
    Path dir;

    @Test
    void aSmallFileThatInflatesToHugeContentIsRefusedWithAClearMessage() throws Exception {
        Path in = Hostile.write(dir, "bomb", Hostile::contentBomb);
        assertTrue(Files.size(in) < 2 << 20);
        Path out = dir.resolve("bomb-out.pdf");
        IOException e = assertThrows(IOException.class, () -> PdfToPdfA.convert(in, out));
        assertTrue(e.getMessage().contains("content stream is larger than 64 MB"), e.getMessage());
        assertFalse(Files.exists(out));
    }

    @Test
    void hugeCompressedMetadataIsReplacedWithoutReadingItAll() throws Exception {
        Path in = Hostile.write(dir, "mdbomb", Hostile::metadataBomb);
        for (PdfALevel level : new PdfALevel[] {PdfALevel.A1B, PdfALevel.A2B}) {
            Path out = dir.resolve("mdbomb-" + level + ".pdf");
            PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
            VeraPdf.assertCompliant(out, level);
            assertTrue(Files.size(out) < 1 << 20);
        }
    }
}
