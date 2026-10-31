package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.testing.Workers;
import stirling.software.officeconvert.topdf.OfficeToPdf.Format;
import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.pdf.PageSize;
import stirling.software.officeconvert.topdf.pdf.PdfOutput;
import stirling.software.officeconvert.topdf.pdf.TextStyle;
import stirling.software.officeconvert.topdf.testing.Fixtures;
import stirling.software.officeconvert.topdf.testing.OwnTempFolder;

class OfficeToPdfTest {

    @TempDir
    Path dir;

    @Test
    void picksTheFormatFromTheExtension() {
        for (String ext : new String[] {"docx", "docm", "dotx", "dotm", "DOCX"}) {
            assertEquals(Format.DOCX, Format.of(Path.of("a." + ext)), ext);
        }
        for (String ext : new String[] {"pptx", "pptm", "ppsx", "ppsm", "potx", "potm"}) {
            assertEquals(Format.PPTX, Format.of(Path.of("a." + ext)), ext);
        }
        for (String ext : new String[] {"xlsx", "xlsm", "xltx", "xltm", "xls", "xlt"}) {
            assertEquals(Format.XLSX, Format.of(Path.of("dir/a." + ext)), ext);
        }
        for (String ext : new String[] {"ppt", "pps", "POT"}) {
            assertEquals(Format.PPT, Format.of(Path.of("a." + ext)), ext);
        }
        for (String ext : new String[] {"doc", "DOT"}) {
            assertEquals(Format.DOCX, Format.of(Path.of("a." + ext)), ext);
            assertTrue(Format.recognises(Path.of("a." + ext)));
        }
        IllegalArgumentException pdf = assertThrows(IllegalArgumentException.class, () -> Format.of(Path.of("a.pdf")));
        for (String ext : new String[] {".docm", ".dotm", ".doc", ".dot", ".ppsm", ".potx", ".potm", ".xlsm", ".xltx", ".xltm",
                ".ppt", ".pps", ".pot"}) {
            String m = pdf.getMessage();
            assertTrue(m.contains(ext + ",") || m.contains(ext + " ") || m.endsWith(ext), m);
        }
        assertThrows(IllegalArgumentException.class, () -> Format.of(Path.of("noextension")));
        assertFalse(Format.recognises(Path.of("a.pdf")));
        assertThrows(NullPointerException.class, () -> Format.of(null));
    }

    @Test
    void validatesOptions() {
        Options d = Options.defaults();
        assertEquals(Duration.ofMinutes(5), d.timeout());
        assertEquals(List.of(), d.fontDirs());
        assertEquals(Options.DEFAULT_MAX_PAGES, d.maxPages());
        assertEquals(10_000, d.maxPages());
        assertEquals(Options.DEFAULT_MAX_SCRATCH_BYTES, d.maxScratchBytes());
        assertEquals(3, d.maxPages(3).maxPages());
        assertEquals(0, d.maxPages(0).maxPages());
        assertEquals(1L << 20, d.maxScratchBytes(1L << 20).maxScratchBytes());
        assertEquals(Options.DEFAULT_MAX_SCRATCH_BYTES, new Options(Duration.ZERO, List.of(), 1).maxScratchBytes());
        assertThrows(IllegalArgumentException.class, () -> d.maxScratchBytes(-1));
        assertEquals(Duration.ZERO, d.timeout(Duration.ZERO).timeout());
        assertEquals(List.of(Path.of("f")), d.fontDirs(List.of(Path.of("f"))).fontDirs());
        assertThrows(IllegalArgumentException.class, () -> d.maxPages(-1));
        assertThrows(IllegalArgumentException.class, () -> d.timeout(Duration.ofSeconds(-1)));
        assertThrows(NullPointerException.class, () -> d.timeout(null));
        assertThrows(NullPointerException.class, () -> d.fontDirs(null));
        List<Path> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> d.fontDirs(withNull));
    }

    @Test
    void rejectsNulls() {
        Path p = dir.resolve("a.docx");
        assertThrows(NullPointerException.class, () -> OfficeToPdf.convert(null, p));
        assertThrows(NullPointerException.class, () -> OfficeToPdf.convert(p, null));
        assertThrows(NullPointerException.class, () -> OfficeToPdf.convert(p, p, null));
        assertThrows(NullPointerException.class,
                () -> OfficeToPdf.convert(null, Format.DOCX, new ByteArrayOutputStream(), Options.defaults()));
        assertThrows(NullPointerException.class,
                () -> OfficeToPdf.convert(new ByteArrayInputStream(new byte[0]), null, new ByteArrayOutputStream(),
                        Options.defaults()));
    }

    @Test
    void explainsBadInputAndLeavesNothingBehind() throws Exception {
        assertFails("text.docx", "not a zip, just text".getBytes(), "not a zip");
        assertFails("empty.xlsx", new byte[0], "empty");
        assertFails("scan.pptx", "%PDF-1.4\n".getBytes(), "PDF");
        assertFails("locked.docx", Fixtures.encryptedOle2(), "password");
        assertFails("bomb.docx", Fixtures.zipBomb(Fixtures.docx("x"), "word/media/z.bin", 64L << 20), "zip bomb");
        assertFails("vsdx.docx", Fixtures.edit(Fixtures.docx("x")).remove("[Content_Types].xml")
                .remove("word/document.xml").bytes(), "not an Office document");
        assertFails("old.xls", new byte[] {1, 2, 3}, "not a zip");
        assertFails("deck.ppt", new byte[] {1, 2, 3}, "not a zip");
        assertFails("legacy.doc", new byte[] {1, 2, 3}, "not a zip");
        for (String name : List.of("plan.mpp", "upload", "notes.md")) {
            Path in = Files.write(dir.resolve(name), new byte[] {1, 2, 3});
            IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve(name + ".pdf")),
                    name);
            assertTrue(e.getCause() instanceof IllegalArgumentException, name);
        }
        assertThrows(java.nio.file.NoSuchFileException.class,
                () -> OfficeToPdf.convert(dir.resolve("missing.docx"), dir.resolve("out.pdf")));
        try (Stream<Path> left = Files.list(dir)) {
            assertTrue(left.noneMatch(p -> p.getFileName().toString().endsWith(".pdf")
                    || p.getFileName().toString().endsWith(".part")), "partial output was left behind");
        }
    }

    @Test
    void streamVariantWritesNothingOnFailure() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(
                new ByteArrayInputStream("garbage".getBytes()), Format.DOCX, out, Options.defaults()));
        assertTrue(e.getMessage().contains("not a zip"), e.getMessage());
        assertEquals(0, out.size());
    }

    @Test
    void aValidDocumentEitherConvertsOrFailsCleanly() throws Exception {
        byte[][] docs = {Fixtures.docx("Hello"), Fixtures.pptx("Hello"), Fixtures.xlsx(new String[][] {{"Hello"}})};
        String[] names = {"a.docx", "b.pptx", "c.xlsx"};
        for (int i = 0; i < docs.length; i++) {
            Path in = Fixtures.write(dir, names[i], docs[i]);
            Path out = dir.resolve(names[i] + ".pdf");
            try {
                OfficeToPdf.convert(in, out, Options.defaults().timeout(Duration.ofMinutes(2)));
                assertTrue(new String(Files.readAllBytes(out), 0, 5).startsWith("%PDF-"));
            } catch (IOException e) {
                assertFalse(e instanceof OfficeToPdf.TimedOut, names[i]);
                assertFalse(Files.exists(out), names[i]);
            }
        }
        try (Stream<Path> left = Files.list(dir)) {
            assertTrue(left.noneMatch(p -> p.getFileName().toString().endsWith(".part")));
        }
    }

    @Test
    void anInterruptedCallerKeepsItsFlag() throws Exception {
        Path in = Fixtures.write(dir, "a.docx", Fixtures.docx("x"));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> OfficeToPdf.convert(in, dir.resolve("a.pdf")));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertFalse(Files.exists(dir.resolve("a.pdf")));
    }

    @Test
    void interruptingTheCallerStopsTheWork() throws Exception {
        Thread caller = Thread.currentThread();
        Thread interrupter = Thread.ofPlatform().start(() -> {
            while (caller.getState() != Thread.State.WAITING && caller.getState() != Thread.State.TIMED_WAITING) {
                if (Thread.interrupted()) {
                    return;
                }
                Thread.onSpinWait();
            }
            caller.interrupt();
        });
        try {
            assertThrows(InterruptedIOException.class,
                    () -> OfficeToPdf.run(Duration.ZERO, OfficeToPdfTest::blockUntilInterrupted));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            interrupter.join();
        }
    }

    @Test
    void timesOut() {
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.run(Duration.ofMillis(100),
                OfficeToPdfTest::blockUntilInterrupted));
        assertInstanceOf(OfficeToPdf.TimedOut.class, e);
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void aTimeoutTooLongToMeasureMeansNoLimit() throws Exception {
        Duration forever = Duration.ofSeconds(Long.MAX_VALUE);
        assertEquals("done", OfficeToPdf.run(forever, () -> "done"));
        Path in = Fixtures.write(dir, "a.docx", Fixtures.docx("x"));
        Path out = dir.resolve("a.pdf");
        try {
            OfficeToPdf.convert(in, out, Options.defaults().timeout(forever));
            assertTrue(Files.size(out) > 0);
        } catch (IOException e) {
            assertFalse(e instanceof OfficeToPdf.TimedOut, e.toString());
        }
        try {
            OfficeToPdf.convert(new ByteArrayInputStream(Fixtures.docx("x")), Format.DOCX, new ByteArrayOutputStream(),
                    Options.defaults().timeout(forever));
        } catch (IOException e) {
            assertFalse(e instanceof OfficeToPdf.TimedOut, e.toString());
        }
        assertNoPartFiles();
    }

    @Test
    void aWorkerThatIgnoresTheInterruptStillRemovesItsPartFile() throws Exception {
        // a loaded machine may not start the worker within a short timeout, so longer ones are tried
        for (long millis = 100; !abandonsAStubbornWorker(dir.resolve("slow" + millis + ".pdf"), millis); millis *= 2) {
            assertTrue(millis < 25_600, "the worker never started, even within " + millis + " ms");
        }
    }

    private boolean abandonsAStubbornWorker(Path target, long millis) throws Exception {
        Set<Thread> before = Workers.named("office-to-pdf");
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread started;
        try {
            IOException e = assertThrows(IOException.class, () -> OfficeToPdf.writeAtomically(target,
                    Duration.ofMillis(millis), 10, os -> {
                        worker.set(Thread.currentThread());
                        ignoreInterrupt(interrupted);
                        os.write("%PDF-".getBytes(StandardCharsets.US_ASCII));
                        os.flush();
                        awaitIgnoringInterrupts(release, interrupted);
                        os.write("1.7".getBytes(StandardCharsets.US_ASCII));
                    }));
            assertInstanceOf(OfficeToPdf.TimedOut.class, e);
            started = worker.get();
            if (started != null) {
                assertTrue(started.isAlive(), "the caller returns while the worker still runs");
            }
        } finally {
            release.countDown();
        }
        Workers.awaitNew(before, "office-to-pdf");
        assertFalse(Files.exists(target));
        assertNoPartFiles();
        if (started == null) {
            return false;
        }
        assertTrue(interrupted.get(), "the worker was interrupted and went on");
        return true;
    }

    private static void ignoreInterrupt(AtomicBoolean interrupted) {
        if (Thread.interrupted()) {
            interrupted.set(true);
        }
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch, AtomicBoolean interrupted) {
        while (true) {
            try {
                latch.await();
                ignoreInterrupt(interrupted);
                return;
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        }
    }

    @Test
    void theStreamVariantNeverReadsTheInputAfterReturning() throws Exception {
        Thread caller = Thread.currentThread();
        AtomicInteger reads = new AtomicInteger();
        AtomicBoolean elsewhere = new AtomicBoolean();
        InputStream slow = new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0];
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (Thread.currentThread() != caller) {
                    elsewhere.set(true);
                }
                reads.incrementAndGet();
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("stopped");
                }
                b[off] = 'x';
                return 1;
            }
        };
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(slow, Format.DOCX,
                new ByteArrayOutputStream(), Options.defaults().timeout(Duration.ofMillis(150))));
        assertInstanceOf(OfficeToPdf.TimedOut.class, e);
        int seen = reads.get();
        Thread.sleep(200);
        assertEquals(seen, reads.get());
        assertFalse(elsewhere.get());
    }

    @Test
    void wrapsRendererCrashesAsIOExceptions() {
        IOException crash = assertThrows(IOException.class, () -> OfficeToPdf.run(Duration.ZERO, () -> {
            throw new IllegalStateException("broken table");
        }));
        assertTrue(crash.getMessage().contains("broken table"));
        IOException deep = assertThrows(IOException.class, () -> OfficeToPdf.run(Duration.ZERO, () -> recurse(0)));
        assertTrue(deep.getMessage().contains("nests too deeply"));
    }

    @Test
    void detectsTheRealFormatFromTheContent() throws Exception {
        Path docx = Fixtures.write(dir, "really-a-docx.pptx", Fixtures.docx("x"));
        Path xlsx = Fixtures.write(dir, "really-a-xlsx.docx", Fixtures.xlsx(new String[][] {{"x"}}));
        Path pptx = Fixtures.write(dir, "really-a-pptx.xlsx", Fixtures.pptx("x"));
        try (OfficeZip a = OfficeZip.open(docx); OfficeZip b = OfficeZip.open(xlsx); OfficeZip c = OfficeZip.open(pptx)) {
            assertEquals(Format.DOCX, OfficeToPdf.detect(a, Format.PPTX));
            assertEquals(Format.XLSX, OfficeToPdf.detect(b, Format.DOCX));
            assertEquals(Format.PPTX, OfficeToPdf.detect(c, Format.XLSX));
        }
    }

    @Test
    void renderJobEnforcesThePageLimit() throws Exception {
        Path docx = Fixtures.write(dir, "a.docx", Fixtures.docx("x"));
        FontLibrary fonts = FontLibrary.of(List.of(Files.createDirectories(dir.resolve("fonts"))));
        try (OfficeZip zip = OfficeZip.open(docx); PdfOutput out = new PdfOutput(fonts)) {
            RenderJob job = new RenderJob(zip, Format.DOCX, Options.defaults().maxPages(2), fonts, out);
            assertEquals(docx, job.source());
            job.newPage(PageSize.A4).close();
            assertFalse(job.pageLimitReached());
            job.newPage(PageSize.LETTER).close();
            assertTrue(job.pageLimitReached());
            assertThrows(RenderJob.PageLimitReached.class, () -> job.newPage(PageSize.A4));
            assertEquals(2, job.pageCount());
            job.warn("one");
            job.warn("one");
            assertEquals(List.of("one"), job.warnings());
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, job::checkpoint);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void returnsPagesTruncationAndWarnings() throws Exception {
        byte[] docx = Fixtures.edit(Fixtures.docx("x"))
                .put("word/vbaProject.bin", new byte[] {1, 2, 3})
                .defaultType("bin", "application/vnd.ms-office.vbaProject")
                .relationship("word/document.xml", "rIdVba", "http://schemas.microsoft.com/office/2006/relationships/vbaProject",
                        "vbaProject.bin", false)
                .relationship("word/document.xml", "rIdPic", Fixtures.REL + "image", "http://192.0.2.7/x.png", true)
                .bytes();
        Path in = Fixtures.write(dir, "active.docx", docx);
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        OfficeToPdf.Result r = OfficeToPdf.render(in, Format.DOCX, pdf, Options.defaults().maxPages(2), (source, job) -> {
            for (int i = 0; i < 3; i++) {
                try (var page = job.newPage(PageSize.A4)) {
                    var face = job.fonts().find("No Such Family 4711", false, false);
                    page.text("page " + i, 72, 72, TextStyle.of(face, 12));
                }
            }
            job.warn("renderer note");
        });
        assertEquals(2, r.pages());
        assertTrue(r.truncated());
        assertTrue(r.pageLimitReached());
        assertTrue(new String(pdf.toByteArray(), 0, 5, StandardCharsets.US_ASCII).startsWith("%PDF-"));
        List<String> w = r.warnings();
        assertTrue(w.get(0).contains("page limit of 2"), w.toString());
        assertTrue(w.contains("Skipped active content: macros (not run)"), w.toString());
        assertTrue(w.contains("Skipped active content: linked files and pictures (not fetched)"), w.toString());
        assertFalse(w.stream().anyMatch(s -> s.contains("192.0.2.7") || s.contains("vbaProject")), w.toString());
        assertTrue(w.stream().anyMatch(s -> s.contains("No Such Family 4711 is not installed")), w.toString());
        assertFalse(w.contains("renderer note"), "a renderer that hit the page limit stops before its own warning");
        OfficeToPdf.Result plain = OfficeToPdf.render(Fixtures.write(dir, "plain.docx", Fixtures.docx("x")), Format.DOCX,
                new ByteArrayOutputStream(), Options.defaults(), (source, job) -> job.warn("renderer note"));
        assertEquals(1, plain.pages());
        assertFalse(plain.truncated());
        assertFalse(plain.pageLimitReached());
        assertEquals(List.of("renderer note"), plain.warnings());
    }

    @Test
    void aResultAtThePageLimitIsAlsoTruncated() {
        assertTrue(new OfficeToPdf.Result(2, false, List.of(), true).truncated());
        assertFalse(new OfficeToPdf.Result(2, true, List.of()).pageLimitReached());
    }

    @Test
    void anOutputPastTheScratchLimitIsItsOwnFailureAndLeavesNothingBehind() throws Exception {
        String[] paragraphs = new String[4000];
        java.util.Arrays.fill(paragraphs, "A paragraph long enough to fill the pages of this document quickly, again.");
        Path in = Fixtures.write(dir, "big.docx", Fixtures.docx(paragraphs));
        Path out = dir.resolve("big.pdf");
        OfficeToPdf.OutputTooLarge e = assertThrows(OfficeToPdf.OutputTooLarge.class,
                () -> OfficeToPdf.convert(in, out, Options.defaults().maxScratchBytes(64 << 10)));
        assertTrue(e.getMessage().contains("output limit"), e.getMessage());
        assertFalse(Files.exists(out));
        assertTrue(OfficeToPdf.convert(in, out, Options.defaults()).pages() > 10);
    }

    @Test
    void theWarmUpSamplesAreRealDocumentsAndLeaveNothingBehind() throws Exception {
        for (Format f : Format.values()) {
            Path in = Fixtures.write(dir, "sample." + f.name().toLowerCase(java.util.Locale.ROOT), OfficeToPdf.sample(f));
            OfficeToPdf.Result r = OfficeToPdf.convert(in, dir.resolve(f + ".pdf"));
            assertEquals(1, r.pages(), f.name());
            try (org.apache.pdfbox.pdmodel.PDDocument pdf = org.apache.pdfbox.Loader.loadPDF(dir.resolve(f + ".pdf")
                    .toFile())) {
                assertTrue(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf).contains("Warm up"), f.name());
            }
        }
        List<String> left = OwnTempFolder.run(dir.resolve("tmp"), WarmUpEverything.class);
        assertEquals(List.of(), left.stream().filter(name -> name.startsWith("office-warm-up-")).toList());
    }

    public static final class WarmUpEverything {
        public static void main(String[] args) {
            OfficeToPdf.warmUp(Format.values());
            OfficeToPdf.warmUp();
        }
    }

    private void assertNoPartFiles() throws IOException {
        try (Stream<Path> left = Files.list(dir)) {
            List<Path> parts = left.filter(p -> p.getFileName().toString().endsWith(".part")).toList();
            assertEquals(List.of(), parts);
        }
    }

    private void assertFails(String name, byte[] data, String reason) throws IOException {
        Path in = Fixtures.write(dir, name, data);
        Path out = dir.resolve(name + ".pdf");
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, out));
        assertTrue(e.getMessage().contains(reason), name + ": " + e.getMessage());
        assertFalse(Files.exists(out), name);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        IOException s = assertThrows(IOException.class, () -> OfficeToPdf.convert(new ByteArrayInputStream(data),
                Format.of(in), sink, Options.defaults()));
        assertTrue(s.getMessage().contains(reason), name + ": " + s.getMessage());
        assertEquals(0, sink.size());
    }

    private static Void blockUntilInterrupted() throws IOException {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("stopped");
        }
        return null;
    }

    private static Void recurse(int depth) {
        return depth < 0 ? null : recurse(depth + 1);
    }
}
