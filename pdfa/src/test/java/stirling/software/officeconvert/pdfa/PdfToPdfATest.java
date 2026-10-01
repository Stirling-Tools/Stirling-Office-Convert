package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfToPdfATest {

    @TempDir
    Path dir;

    @Test
    void levelsParseFromCommonSpellings() {
        assertEquals(PdfALevel.A2B, PdfALevel.parse("2b"));
        assertEquals(PdfALevel.A1B, PdfALevel.parse("PDF/A-1b"));
        assertEquals(PdfALevel.A3U, PdfALevel.parse("pdfa-3u"));
        assertEquals(PdfALevel.A2B, PdfALevel.parse("2"));
        assertEquals(PdfALevel.A2A, PdfALevel.parse("2a"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> PdfALevel.parse("4"));
        assertTrue(e.getMessage().contains("1a, 1b, 2a"));
    }

    @Test
    void optionsRejectNonsense() {
        PdfToPdfA.Options o = PdfToPdfA.Options.defaults();
        assertThrows(IllegalArgumentException.class, () -> o.timeout(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> o.maxPages(-1));
        assertThrows(IllegalArgumentException.class, () -> o.flattenDpi(10));
        assertFalse(o.password("secret").toString().contains("secret"));
    }

    @Test
    void aDocumentOverThePageLimitIsRefusedAndNothingIsWritten() throws Exception {
        Path in = dir.resolve("many.pdf");
        try (PDDocument d = new PDDocument()) {
            for (int i = 0; i < 5; i++) {
                d.addPage(new PDPage());
            }
            d.save(in.toFile());
        }
        Path out = dir.resolve("out.pdf");
        IOException e = assertThrows(IOException.class,
                () -> PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().maxPages(4)));
        assertTrue(e.getMessage().contains("limit"));
        assertFalse(Files.exists(out));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of("many.pdf"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void levelARefusesAnUntaggedFileAndFixesATaggedOne() throws Exception {
        Path plain = Samples.write(dir, "s01_std14_unembedded");
        IOException e = assertThrows(IOException.class, () -> PdfToPdfA.convert(plain, dir.resolve("a.pdf"),
                PdfToPdfA.Options.defaults().level(PdfALevel.A2A)));
        assertTrue(e.getMessage().contains("tagged"), e.getMessage());
        Path tagged = Samples.write(dir, "s21_tagged");
        Path out = dir.resolve("tagged.pdf");
        PdfToPdfA.Result r = PdfToPdfA.convert(tagged, out, PdfToPdfA.Options.defaults().level(PdfALevel.A1A));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("Paragraph")), r.warnings().toString());
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            var root = d.getDocumentCatalog().getStructureTreeRoot().getCOSObject();
            var roles = root.getCOSDictionary(org.apache.pdfbox.cos.COSName.getPDFName("RoleMap"));
            assertEquals("NonStruct", roles.getNameAsString("Paragraph"));
            assertEquals(null, roles.getDictionaryObject("P"));
            assertTrue(d.getDocumentCatalog().getMarkInfo().isMarked());
        }
        VeraPdf.assertCompliant(out, PdfALevel.A1A);
    }

    @Test
    void aSlowConversionTimesOut() throws Exception {
        Path in = Samples.write(dir, "s01_std14_unembedded");
        Path out = dir.resolve("out.pdf");
        assertThrows(PdfToPdfA.TimedOut.class,
                () -> PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().timeout(Duration.ofNanos(1))));
        assertFalse(Files.exists(out));
    }

    @Test
    void anInterruptedCallerStopsAtOnce() throws Exception {
        Path in = Samples.write(dir, "s01_std14_unembedded");
        Path out = dir.resolve("out.pdf");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread t = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults());
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        t.start();
        t.join();
        assertTrue(thrown.get() instanceof InterruptedIOException, String.valueOf(thrown.get()));
        assertFalse(Files.exists(out));
    }

    @Test
    void notAPdfFailsWithAReason() throws Exception {
        Path in = dir.resolve("text.pdf");
        Files.writeString(in, "hello");
        IOException e = assertThrows(IOException.class, () -> PdfToPdfA.convert(in, dir.resolve("o.pdf")));
        assertTrue(e.getMessage().contains("not a PDF"), e.getMessage());
    }

    @Test
    void anOpenDocumentConvertsToAStream() throws Exception {
        Path in = Samples.write(dir, "s17_all_in_one");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PdfToPdfA.Result r;
        try (PDDocument d = Loader.loadPDF(in.toFile())) {
            r = PdfToPdfA.convert(d, bytes, PdfToPdfA.Options.defaults().level(PdfALevel.A3B));
        }
        assertEquals(PdfALevel.A3B, r.level());
        assertEquals(1, r.pages());
        Path out = dir.resolve("out.pdf");
        Files.write(out, bytes.toByteArray());
        VeraPdf.assertCompliant(out, PdfALevel.A3B);
    }

    @Test
    void aConvertedFileConvertsAgainUnchangedInLookAndStaysCompliant() throws Exception {
        Converted.convert(dir, "s08_cmyk_lab_separation", PdfALevel.A2B);
        Path first = Converted.out(dir, "s08_cmyk_lab_separation", PdfALevel.A2B);
        Path second = dir.resolve("again.pdf");
        PdfToPdfA.Result r = PdfToPdfA.convert(first, second, PdfToPdfA.Options.defaults());
        assertTrue(r.substitutedFonts().isEmpty());
        VeraPdf.assertCompliant(second, PdfALevel.A2B);
        assertEquals(Converted.text(first), Converted.text(second));
    }
}
