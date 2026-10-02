package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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

    @Test
    void deeplyNestedGraphicsStatesMoveIntoFormsInLinearTime() throws Exception {
        String content = "q ".repeat(1_000) + "0 0 1 rg 0 0 10 10 re f " + "Q ".repeat(1_000);
        Path in = Hostile.write(dir, "nest", RawPdf.page("", content));
        Path out = dir.resolve("nest-out.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
        String bomb = "q ".repeat(80_000) + "0 0 1 rg 0 0 10 10 re f " + "Q ".repeat(80_000);
        Path deep = Hostile.write(dir, "deep", RawPdf.page("", bomb));
        IOException e = assertThrows(IOException.class, () -> PdfToPdfA.convert(deep, dir.resolve("deep-out.pdf"),
                PdfToPdfA.Options.defaults().level(PdfALevel.A2B).timeout(Duration.ofSeconds(60))));
        assertTrue(e.getMessage().contains("80000 levels deep"), e.getMessage());
    }

    @Test
    void aLongChainOfFormsNeverOverflowsTheStack() throws Exception {
        Path in = Hostile.write(dir, "chain", Hostile.formChain(30_000));
        try {
            PdfToPdfA.convert(in, dir.resolve("chain-out.pdf"),
                    PdfToPdfA.Options.defaults().level(PdfALevel.A2B).timeout(Duration.ofSeconds(60)));
        } catch (IOException e) {
            assertFalse(e instanceof PdfToPdfA.TimedOut, e.toString());
        }
    }

    @Test
    void fontsInheritedDownALongChainOfFormsAreResolvedInLinearTime() throws Exception {
        Path in = Hostile.write(dir, "fontchain", Hostile.fontChain(8_000));
        Path out = dir.resolve("fontchain-out.pdf");
        PdfToPdfA.Result r = PdfToPdfA.convert(in, out,
                PdfToPdfA.Options.defaults().level(PdfALevel.A2B).timeout(Duration.ofSeconds(15)));
        assertEquals(1, r.pages());
    }

    @Test
    void aStructureArrayContainingItselfIsWalkedOnce() {
        RawPdf r = structured("6 0 R");
        r.add("[6 0 R]");
        finishesInTime("selfk", r);
    }

    @Test
    void structureArraysSharedDownADiamondAreWalkedOnce() {
        RawPdf r = structured("6 0 R");
        for (int i = 0; i < 60; i++) {
            r.add("[" + (7 + i) + " 0 R " + (7 + i) + " 0 R]");
        }
        r.add("<</S/P/K[]>>");
        finishesInTime("diamk", r);
    }

    private static RawPdf structured(String k) {
        RawPdf r = RawPdf.page(RawPdf.helvetica(), "BT /F1 12 Tf 72 720 Td (Hello) Tj ET");
        r.set(1, "<</Type/Catalog/Pages 2 0 R/StructTreeRoot 5 0 R/MarkInfo<</Marked true>>/Lang(en)>>");
        r.add("<</Type/StructTreeRoot/K " + k + ">>");
        return r;
    }

    private void finishesInTime(String name, RawPdf raw) {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            Path in = Hostile.write(dir, name, raw);
            try {
                PdfToPdfA.convert(in, dir.resolve(name + "-out.pdf"),
                        PdfToPdfA.Options.defaults().level(PdfALevel.A2A).timeout(Duration.ofSeconds(10)));
            } catch (IOException e) {
                assertFalse(e instanceof PdfToPdfA.TimedOut, e.toString());
            }
        });
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
