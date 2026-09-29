package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf.Format;
import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.SecureXml;
import stirling.software.officeconvert.topdf.pdf.PageSize;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.TextStyle;
import stirling.software.officeconvert.topdf.testing.Fixtures;

class PartialOutputTest {

    @TempDir
    Path dir;

    @Test
    void aRendererThatFailsPartWayKeepsItsPagesAndSaysTheRestIsMissing() throws Exception {
        Path in = Fixtures.write(dir, "a.docx", Fixtures.docx("x"));
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        OfficeToPdf.Result r = OfficeToPdf.render(in, Format.DOCX, pdf, Options.defaults(), (source, job) -> {
            page(job, "Page one");
            page(job, "Page two");
            try (PdfCanvas c = job.newPage(PageSize.LETTER)) {
                c.save();
                c.save();
                c.text("Half drawn", 72, 72, TextStyle.of(job.fonts().find("Arial", false, false), 12));
                throw new IllegalStateException("broken table");
            }
        });
        assertEquals(3, r.pages());
        assertTrue(r.truncated());
        assertTrue(r.warnings().get(0).startsWith("Only the first 3 pages were converted"), r.warnings().toString());
        assertTrue(r.warnings().get(0).contains("broken table"), r.warnings().toString());
        assertFalse(r.warnings().stream().anyMatch(w -> w.contains("page limit")), r.warnings().toString());
        try (PDDocument doc = Loader.loadPDF(pdf.toByteArray())) {
            assertEquals(3, doc.getNumberOfPages());
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Page one") && text.contains("Page two") && text.contains("Half drawn"), text);
        }
    }

    @Test
    void aFailureThatOnlyMentionsDoctypeStillKeepsThePagesDrawn() throws Exception {
        Path in = Fixtures.write(dir, "d.docx", Fixtures.docx("x"));
        for (String reason : List.of("bad DOCTYPE-like text", "Unknown style 'DOCTYPE'", "disallow-doctype-decl")) {
            ByteArrayOutputStream pdf = new ByteArrayOutputStream();
            OfficeToPdf.Result r = OfficeToPdf.render(in, Format.DOCX, pdf, Options.defaults(), (source, job) -> {
                page(job, "One");
                page(job, "Two");
                page(job, "Three");
                throw new IllegalStateException(reason);
            });
            assertEquals(3, r.pages(), reason);
            assertTrue(r.warnings().get(0).contains(reason), r.warnings().toString());
        }
    }

    @Test
    void aRendererThatFailsOnItsFirstPageStillFailsTheDocument() throws Exception {
        Path in = Fixtures.write(dir, "b.docx", Fixtures.docx("x"));
        for (int pages = 0; pages < 2; pages++) {
            int drawn = pages;
            OfficeToPdf.Renderer broken = (source, job) -> {
                for (int i = 0; i < drawn; i++) {
                    page(job, "Page");
                }
                throw new IllegalStateException("broken table");
            };
            IOException e = assertThrows(IOException.class, () -> OfficeToPdf.run(Duration.ZERO,
                    () -> OfficeToPdf.render(in, Format.DOCX, new ByteArrayOutputStream(), Options.defaults(), broken)));
            assertTrue(e.getMessage().contains("broken table"), e.getMessage());
        }
    }

    @Test
    void aDeepDocumentThatOverflowsAfterSomePagesKeepsThem() throws Exception {
        Path in = Fixtures.write(dir, "c.docx", Fixtures.docx("x"));
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        OfficeToPdf.Result r = OfficeToPdf.render(in, Format.DOCX, pdf, Options.defaults(), (source, job) -> {
            page(job, "First page");
            page(job, "Second page");
            throw new StackOverflowError();
        });
        assertEquals(2, r.pages());
        assertTrue(r.truncated());
        assertTrue(r.warnings().get(0).startsWith("Only the first 2 pages were converted"), r.warnings().toString());
        assertTrue(r.warnings().get(0).contains("nests too deeply"), r.warnings().toString());
    }

    @Test
    void aDamagedPartIsLeftOutAndDrawnAgainRatherThanCutShort() throws Exception {
        byte[] doc = Fixtures.edit(Fixtures.docx("x")).put("word/extra.xml", "<broken><unclosed>").bytes();
        Path in = Fixtures.write(dir, "d.docx", doc);
        AtomicInteger runs = new AtomicInteger();
        OfficeToPdf.Result r = OfficeToPdf.render(in, Format.DOCX, new ByteArrayOutputStream(), Options.defaults(),
                (source, job) -> {
                    runs.incrementAndGet();
                    page(job, "First");
                    if (job.zip().exists("/word/extra.xml")) {
                        try {
                            job.zip().xml("/word/extra.xml");
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                    page(job, "Second");
                });
        assertEquals(2, runs.get());
        assertEquals(2, r.pages());
        assertFalse(r.truncated());
        assertTrue(r.warnings().stream().anyMatch(w -> w.startsWith("Left out a damaged part: ")), r.warnings().toString());
    }

    @Test
    void interruptionsDamagedPartsAndRefusedDoctypesNeverKeepPartialPages() throws Exception {
        assertTrue(OfficeToPdf.keepsPages(new IllegalStateException("bug"), 2));
        assertFalse(OfficeToPdf.keepsPages(new IllegalStateException("bug"), 1));
        assertFalse(OfficeToPdf.keepsPages(new UncheckedIOException(new InterruptedIOException("stop")), 2));
        IOException refused = assertThrows(IOException.class, () -> SecureXml.parse(
                new ByteArrayInputStream("<!DOCTYPE a [<!ENTITY e \"x\">]><a>&e;</a>".getBytes(StandardCharsets.UTF_8))));
        assertFalse(OfficeToPdf.keepsPages(new IllegalStateException(refused), 2));
        byte[] doc = Fixtures.edit(Fixtures.docx("x")).put("word/extra.xml", "<broken><unclosed>").bytes();
        try (OfficeZip zip = OfficeZip.open(Fixtures.write(dir, "f.docx", doc))) {
            OfficeZip.DamagedPart d = assertThrows(OfficeZip.DamagedPart.class, () -> zip.xml("/word/extra.xml"));
            assertFalse(OfficeToPdf.keepsPages(new UncheckedIOException(d), 2));
        }
    }

    @Test
    void runningOutOfMemoryIsReportedAsAPlainFailure() throws Exception {
        Path in = Fixtures.write(dir, "e.docx", Fixtures.docx("x"));
        Path out = dir.resolve("e.pdf");
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.writeAtomically(out, Duration.ZERO, 5_000,
                os -> OfficeToPdf.render(in, Format.DOCX, os, Options.defaults(), (source, job) -> {
                    page(job, "Page");
                    throw new OutOfMemoryError("Java heap space");
                })));
        assertTrue(e.getMessage().contains("needs more memory"), e.getMessage());
        assertFalse(Files.exists(out));
        assertEquals(List.of(), listParts());
    }

    private List<Path> listParts() throws IOException {
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".part")).toList();
        }
    }

    private static void page(RenderJob job, String text) throws IOException {
        try (PdfCanvas c = job.newPage(PageSize.LETTER)) {
            c.text(text, 72, 72, TextStyle.of(job.fonts().find("Arial", false, false), 12));
        }
    }
}
