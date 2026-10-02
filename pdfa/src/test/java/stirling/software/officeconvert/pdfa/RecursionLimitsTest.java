package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecursionLimitsTest {

    @TempDir
    Path dir;

    @Test
    void actionChainsSharingTheirNextActionsAreFilteredOnce() throws Exception {
        convert("actions", Hostile::actionDag, PdfALevel.A2B);
    }

    @Test
    void optionalContentOrderArraysSharingChildrenAreWalkedOnce() throws Exception {
        convert("order", Hostile.optionalContentDag(), PdfALevel.A2B);
    }

    @Test
    void embeddedFileTreesSharingKidsArePrunedOnce() throws Exception {
        convert("files", Hostile.embeddedFileTreeDag(), PdfALevel.A2B);
    }

    @Test
    void applicationDataSharingArraysIsMeasuredOnce() throws Exception {
        convert("pieceinfo", Hostile.pieceInfoDag(), PdfALevel.A1B);
    }

    private void convert(String name, Hostile.Body body, PdfALevel level) throws Exception {
        convert(name, Hostile.write(dir, name, body), level);
    }

    private void convert(String name, RawPdf raw, PdfALevel level) throws Exception {
        convert(name, Hostile.write(dir, name, raw), level);
    }

    private void convert(String name, Path in, PdfALevel level) throws Exception {
        assertTrue(Files.size(in) < 1 << 20, "input " + Files.size(in));
        Path out = dir.resolve(name + "-out.pdf");
        PdfToPdfA.Result r = PdfToPdfA.convert(in, out,
                PdfToPdfA.Options.defaults().level(level).timeout(Duration.ofSeconds(20)));
        assertEquals(1, r.pages());
        assertTrue(Files.size(out) < 1 << 20);
    }
}
