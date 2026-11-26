package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.memory.ConversionTimedOut;
import stirling.software.officeconvert.testing.Workers;

class OfficeConvertTest {

    @TempDir Path dir;

    private Path sample() throws IOException {
        Path pdf = dir.resolve("in.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Quarterly figures");
                cs.endText();
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    @Test
    void everyFormatByExtension() throws IOException {
        Path pdf = sample();
        for (String ext : new String[] {"docx", "odt", "fodt", "xml", "rtf", "txt", "pptx", "odp", "xlsx", "ods"}) {
            Path out = dir.resolve("out." + ext);
            OfficeConvert.convert(pdf, out);
            assertTrue(Files.size(out) > 0, ext);
        }
        assertTrue(Files.readString(dir.resolve("out.txt")).contains("Quarterly figures"));
        assertTrue(Files.readString(dir.resolve("out.rtf"), StandardCharsets.ISO_8859_1).startsWith("{\\rtf"));
        assertTrue(Files.readString(dir.resolve("out.xml")).contains("<office:document "));
    }

    @Test
    void anExplicitFormatWinsOverTheFileName() throws IOException {
        Path pdf = SpreadsheetPdfs.report(dir, 0);
        Path out = dir.resolve("table.xlsx");
        OfficeConvert.convert(pdf, out, OfficeConvert.Format.CSV, OfficeConvert.Settings.defaults());
        String csv = Files.readString(out);
        assertFalse(csv.startsWith("PK"), "a workbook was written");
        assertTrue(csv.contains("Region"), csv);
    }

    @Test
    void streamsAndText() throws IOException {
        Path pdf = sample();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            OfficeConvert.convert(doc, out, OfficeConvert.Format.DOCX, OfficeConvert.Settings.defaults());
        }
        assertEquals('P', out.toByteArray()[0]);
        assertTrue(OfficeConvert.text(pdf).contains("Quarterly figures"));
    }

    @Test
    void conversionsTimeOutAfterFiveMinutesByDefault() {
        assertEquals(Duration.ofMinutes(5), OfficeConvert.Settings.defaults().timeout());
    }

    @Test
    void aStackOverflowInAConversionFailsAsAPlainIOException() {
        for (Duration timeout : new Duration[] {Duration.ZERO, Duration.ofMinutes(1)}) {
            IOException e = assertThrows(IOException.class, () -> OfficeConvert.run(timeout, () -> deep(0)));
            assertEquals(OfficeConvert.TOO_DEEP, e.getMessage());
        }
    }

    private static int deep(int n) {
        return deep(n + 1) + 1;
    }

    @Test
    void aWorkerThatIgnoresTheTimeoutIsStuckAndLaterWorkFailsBusy() throws Exception {
        Admission.Limits saved = Admission.jvm().limits();
        Admission.configure(new Admission.Limits(60, 1, Duration.ofMillis(300), false));
        long stopMillis = OfficeConvert.stopMillis;
        OfficeConvert.stopMillis = 200;
        AtomicBoolean release = new AtomicBoolean();
        CountDownLatch holding = new CountDownLatch(1);
        try {
            long start = System.nanoTime();
            assertThrows(OfficeConvert.TimedOut.class, () -> OfficeConvert.run(Duration.ofMillis(100), () -> {
                try (Admission.Ticket _ = Admission.jvm().enter(Admission.BASE_BYTES)) {
                    holding.countDown();
                    while (!release.get()) {
                        Thread.onSpinWait();
                    }
                }
                return null;
            }));
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(30));
            assertTrue(holding.await(1, TimeUnit.SECONDS));
            assertEquals(1, Admission.jvm().status().stuck());
            Path out = dir.resolve("busy.docx");
            IOException busy = assertThrows(IOException.class, () -> OfficeConvert.convert(sample(), out));
            assertTrue(busy instanceof Admission.Busy, busy.toString());
        } finally {
            release.set(true);
            OfficeConvert.stopMillis = stopMillis;
            Admission.configure(saved);
        }
    }

    @Test
    void aCarriedWorkerThatOutlivesItsTimeoutKeepsTheCallersSlot() throws Exception {
        Admission.Limits saved = Admission.jvm().limits();
        Admission.configure(new Admission.Limits(60, 1, Duration.ofMillis(300), false));
        long stopMillis = OfficeConvert.stopMillis;
        OfficeConvert.stopMillis = 200;
        AtomicBoolean release = new AtomicBoolean();
        CountDownLatch holding = new CountDownLatch(1);
        Thread[] worker = new Thread[1];
        try {
            try (Admission.Ticket _ = Admission.jvm().enter(Admission.BASE_BYTES)) {
                assertThrows(OfficeConvert.TimedOut.class, () -> OfficeConvert.run(Duration.ofMillis(100), () -> {
                    worker[0] = Thread.currentThread();
                    try (Admission.Ticket _ = Admission.jvm().enter(Admission.BASE_BYTES)) {
                        holding.countDown();
                        while (!release.get()) {
                            Thread.onSpinWait();
                        }
                    }
                    return null;
                }));
            }
            assertTrue(holding.await(1, TimeUnit.SECONDS));
            assertEquals(1, Admission.jvm().running());
            assertEquals(1, Admission.jvm().status().stuck());
            assertThrows(Admission.Busy.class, () -> Admission.jvm().enter(Admission.BASE_BYTES).close());
        } finally {
            release.set(true);
            if (worker[0] != null) {
                worker[0].join();
            }
            OfficeConvert.stopMillis = stopMillis;
            Admission.configure(saved);
        }
        assertEquals(0, Admission.jvm().running());
    }

    @Test
    void nestedWorkThatEndsBeforeItsCallerFreesTheSlotWhenTheCallerCloses() throws Exception {
        try (Admission.Ticket _ = Admission.jvm().enter(Admission.BASE_BYTES)) {
            OfficeConvert.run(Duration.ofMinutes(1), () -> {
                try (Admission.Ticket _ = Admission.jvm().enter(Admission.BASE_BYTES)) {
                    return null;
                }
            });
            assertEquals(1, Admission.jvm().running());
        }
        assertEquals(0, Admission.jvm().running());
    }

    @Test
    void timeoutStopsTheConversionAndLeavesNothing() throws Exception {
        Path pdf = dir.resolve("slow.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDStream content = new PDStream(doc);
            try (OutputStream os = content.createOutputStream(COSName.FLATE_DECODE)) {
                os.write("q Q ".repeat(4_000_000).getBytes(StandardCharsets.ISO_8859_1));
            }
            page.setContents(content);
            doc.save(pdf.toFile());
        }
        Path out = dir.resolve("slow.docx");
        OfficeConvert.Settings settings = OfficeConvert.Settings.defaults().timeout(Duration.ofMillis(100));
        Set<Thread> before = Workers.named("office-convert");
        assertThrows(OfficeConvert.TimedOut.class, () -> OfficeConvert.convert(pdf, out, settings));
        Workers.awaitNew(before, "office-convert");
        assertFalse(Files.exists(out));
        try (Stream<Path> left = Files.list(dir)) {
            assertTrue(left.noneMatch(p -> p.getFileName().toString().endsWith(".part")), "a partial file was left");
        }
    }

    @Test
    void wordDocOutputIsRefusedAsUnsupported() throws IOException {
        Path pdf = sample();
        Path out = dir.resolve("out.doc");
        assertFalse(OfficeConvert.Format.supports(out));
        assertTrue(OfficeConvert.Format.supports(dir.resolve("out.rtf")));
        UnsupportedFormatException e = assertThrows(UnsupportedFormatException.class, () -> OfficeConvert.convert(pdf, out));
        assertTrue(e.getMessage().contains(".doc output is not supported"), e.getMessage());
        assertFalse(Files.exists(out));
        assertThrows(IllegalArgumentException.class, () -> OfficeConvert.Format.of(out));
    }

    @Test
    void anUnknownOutputExtensionIsRefusedAsUnsupported() throws IOException {
        Path pdf = sample();
        Path out = dir.resolve("out.pdfx");
        assertFalse(OfficeConvert.Format.supports(out));
        assertThrows(UnsupportedFormatException.class, () -> OfficeConvert.convert(pdf, out));
        assertFalse(Files.exists(out));
    }

    @Test
    void inputThatIsNotAPdfIsRefusedAsUnsupported() throws IOException {
        Path notPdf = Files.writeString(dir.resolve("in.pdf"), "Just some plain text.\n");
        UnsupportedFormatException e = assertThrows(UnsupportedFormatException.class,
                () -> OfficeConvert.convert(notPdf, dir.resolve("out.docx")));
        assertEquals("The PDF could not be opened: the file is not a PDF", e.getMessage());
    }

    @Test
    void anEmptyPdfIsNotCalledUnsupported() throws IOException {
        Path empty = Files.write(dir.resolve("empty.pdf"), new byte[0]);
        IOException e = assertThrows(IOException.class, () -> OfficeConvert.convert(empty, dir.resolve("out.docx")));
        assertFalse(e instanceof UnsupportedFormatException, e.toString());
    }

    @Test
    void aTruncatedPdfWithALongPreambleIsNotCalledUnsupported() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write("x".repeat(2000).getBytes(StandardCharsets.ISO_8859_1));
        bytes.write("\n%PDF-1.7\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R".getBytes(StandardCharsets.ISO_8859_1));
        Path damaged = Files.write(dir.resolve("damaged.pdf"), bytes.toByteArray());
        IOException e = assertThrows(IOException.class, () -> OfficeConvert.convert(damaged, dir.resolve("out.docx")));
        assertFalse(e instanceof UnsupportedFormatException, e.toString());
    }

    @Test
    void unknownExtensionsAndSecretsStayOut() {
        assertThrows(IllegalArgumentException.class, () -> OfficeConvert.Format.of(Path.of("out.pdfx")));
        assertFalse(OfficeConvert.Settings.defaults().password("hunter2").toString().contains("hunter2"));
    }

    @Test
    void aRuntimeExceptionInAConversionArrivesAsAnIOException() {
        IOException e = assertThrows(IOException.class, () -> OfficeConvert.run(Duration.ofMinutes(1), () -> {
            throw new IllegalStateException("broken table");
        }));
        assertTrue(e.getMessage().contains("broken table"), e.getMessage());
        assertTrue(e.getCause() instanceof IllegalStateException);
    }

    @Test
    void onlyTheCallingThreadWritesTheCallersStream() throws IOException {
        Path pdf = sample();
        Thread caller = Thread.currentThread();
        AtomicBoolean foreign = new AtomicBoolean();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream out = new OutputStream() {
            @Override
            public void write(int b) {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                foreign.compareAndSet(false, Thread.currentThread() != caller);
                bytes.write(b, off, len);
            }
        };
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            OfficeConvert.convert(doc, out, OfficeConvert.Format.DOCX, OfficeConvert.Settings.defaults());
        }
        assertFalse(foreign.get(), "the worker wrote the caller's stream");
        assertEquals('P', bytes.toByteArray()[0]);
    }

    @Test
    void aSocketTimeoutOnTheCallersStreamDoesNotInterruptTheCaller() throws IOException {
        Path pdf = sample();
        OutputStream slow = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new SocketTimeoutException("write timed out");
            }
        };
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertThrows(SocketTimeoutException.class, () -> OfficeConvert.convert(doc, slow,
                    OfficeConvert.Format.DOCX, OfficeConvert.Settings.defaults()));
        }
        assertFalse(Thread.interrupted());
    }

    @Test
    void aConversionStartedInsideAnotherIsNestedAndNeverWaitsForASecondSlot() throws IOException {
        Path pdf = sample();
        Admission.Limits saved = Admission.jvm().limits();
        Admission.configure(new Admission.Limits(60, 1, Duration.ZERO, false));
        try {
            assertTimeoutPreemptively(Duration.ofMinutes(1), () -> OfficeConvert.run(Duration.ofMinutes(1), () -> {
                try (Admission.Ticket _ = Admission.jvm().enter(Admission.BASE_BYTES)) {
                    OfficeConvert.convert(pdf, dir.resolve("nested.docx"));
                }
                return null;
            }));
            assertTrue(Files.size(dir.resolve("nested.docx")) > 0);
        } finally {
            Admission.configure(saved);
        }
    }

    @Test
    void timeoutsShareABaseAndRefusalsAreMarked() {
        assertTrue(ConversionTimedOut.class.isAssignableFrom(OfficeConvert.TimedOut.class));
        for (IOException e : List.of(new Admission.Busy(), new Admission.TooLarge(), new Admission.NeedsMemory(),
                new Admission.Stopped())) {
            assertTrue(e instanceof Admission.Refusal, e.getClass().getName());
        }
        assertFalse(new InterruptedIOException() instanceof Admission.Refusal);
    }
}
