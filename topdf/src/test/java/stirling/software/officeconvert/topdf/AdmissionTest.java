package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.OfficeToPdf.Format;
import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.pdf.PageSize;
import stirling.software.officeconvert.topdf.testing.Fixtures;

class AdmissionTest {

    private static final long MB = 1L << 20;

    @TempDir
    Path dir;
    @Test
    void estimatesGrowWithTheMarkupAndTheLargestDecodedPicture() throws Exception {
        String[][] small = {{"a", "b"}};
        String[][] large = new String[2000][];
        for (int i = 0; i < large.length; i++) {
            large[i] = new String[] {"row " + i, Integer.toString(i), "text that takes some room " + i};
        }
        long s = estimate(Fixtures.write(dir, "s.xlsx", Fixtures.xlsx(small)), Format.XLSX);
        long l = estimate(Fixtures.write(dir, "l.xlsx", Fixtures.xlsx(large)), Format.XLSX);
        assertTrue(s >= Admission.BASE_BYTES && l > s, s + " " + l);
        byte[] plain = Fixtures.docx("x");
        byte[] picture = Fixtures.edit(plain).put("word/media/big.png", Fixtures.png(3000, 2000, Color.RED)).bytes();
        long p = estimate(Fixtures.write(dir, "p.docx", picture), Format.DOCX);
        long q = estimate(Fixtures.write(dir, "q.docx", plain), Format.DOCX);
        assertTrue(p - q >= 4L * 3000 * 2000, p + " " + q);
    }

    @Test
    void theEstimateIsPublicForHostsThatQueueWork() throws Exception {
        Path xlsx = Fixtures.write(dir, "e.xlsx", Fixtures.xlsx(new String[][] {{"a"}}));
        assertEquals(estimate(xlsx, Format.XLSX), OfficeToPdf.memoryEstimate(xlsx));
        Path docxNamedXlsx = Fixtures.write(dir, "d.xlsx", Fixtures.docx("x"));
        assertEquals(estimate(docxNamedXlsx, Format.DOCX), OfficeToPdf.memoryEstimate(docxNamedXlsx));
        Path pdf = Fixtures.write(dir, "a.pdf", new byte[] {1});
        Path junk = Fixtures.write(dir, "b.docx", new byte[] {1});
        assertThrows(IOException.class, () -> OfficeToPdf.memoryEstimate(pdf));
        assertThrows(IOException.class, () -> OfficeToPdf.memoryEstimate(junk));
    }

    private static long estimate(Path file, Format format) throws IOException {
        try (OfficeZip zip = OfficeZip.open(file)) {
            return Footprint.estimate(zip, format);
        }
    }

    @Test
    void aConversionWaitingForMemoryStillEndsAtItsTimeoutAndLeavesNothing() throws Exception {
        Path in = Fixtures.write(dir, "wait.docx", Fixtures.docx("x"));
        Path out = dir.resolve("wait.pdf");
        Admission.Ticket hog = Admission.jvm().enter(Long.MAX_VALUE);
        try {
            long start = System.nanoTime();
            assertThrows(OfficeToPdf.TimedOut.class,
                    () -> OfficeToPdf.convert(in, out, Options.defaults().timeout(Duration.ofMillis(300))));
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(10));
            assertEquals(1, Admission.jvm().running(), "the waiting conversion never started");
        } finally {
            hog.close();
        }
        assertFalse(Files.exists(out));
        try (Stream<Path> left = Files.list(dir)) {
            assertEquals(List.of(in), left.toList(), "no .part file is left behind");
        }
        assertEquals(1, OfficeToPdf.convert(in, out).pages());
    }

    @Test
    void aConversionThatFailsClosesItsScratchAndFreesItsShare() throws Exception {
        Path in = Fixtures.write(dir, "fail.docx", Fixtures.docx("x"));
        AtomicReference<org.apache.pdfbox.pdmodel.PDDocument> seen = new AtomicReference<>();
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.run(Duration.ZERO,
                () -> OfficeToPdf.render(in, Format.DOCX, new ByteArrayOutputStream(), Options.defaults(),
                        (source, job) -> {
                            seen.set(job.document());
                            job.newPage(PageSize.A4).close();
                            var stream = job.document().getDocument().createCOSStream();
                            try (var os = stream.createOutputStream()) {
                                byte[] block = new byte[1 << 20];
                                for (int i = 0; i < 80; i++) {
                                    os.write(block);
                                }
                            }
                            assertEquals(1, Admission.jvm().running());
                            throw new IllegalStateException("broken on purpose");
                        })));
        assertTrue(e.getMessage().contains("broken on purpose"), e.getMessage());
        assertTrue(seen.get().getDocument().isClosed(), "the PDF and its scratch file are closed at once");
        assertEquals(0, Admission.jvm().running());
    }

    @Test
    void aStoppedConversionFailsWithThePlainMemoryMessage() throws Exception {
        Path in = Fixtures.write(dir, "a.docx", Fixtures.docx("x"));
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.run(Duration.ZERO,
                () -> OfficeToPdf.render(in, Format.DOCX, new ByteArrayOutputStream(), Options.defaults(),
                        (source, job) -> {
                            job.newPage(PageSize.A4).close();
                            job.newPage(PageSize.A4).close();
                            throw new RenderJob.OutOfMemory();
                        })));
        assertEquals(RenderJob.NEEDS_MEMORY, e.getMessage());
        assertEquals(0, Admission.jvm().running());
    }
}
