package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import stirling.software.officeconvert.memory.Admission;

class SilentLossTest {

    private static final String PADDING = "q Q ".repeat(600);

    @TempDir
    Path dir;

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A1B", "A2B", "A2U"})
    void aBadTokenMidStreamIsReportedAndTheKeptContentIsChecked(PdfALevel level) throws Exception {
        String content = "q BT /F1 12 Tf 72 700 Td (Before) Tj ET << /A >> BDC BT /F2 12 Tf 72 600 Td (After) Tj ET "
                + PADDING + "Q";
        RawPdf raw = RawPdf.page(RawPdf.helvetica().replace(">>>>",
                ">>/F2<</Type/Font/Subtype/Type1/BaseFont/Courier/Encoding/WinAnsiEncoding>>>>"), content);
        Path in = dir.resolve("cut.pdf");
        Files.write(in, raw.bytes());
        Path out = dir.resolve("cut-" + level + ".pdf");
        PdfToPdfA.Result r = PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
        assertTrue(r.warnings().stream().anyMatch(w -> w.startsWith("Cut content streams short")), r.warnings().toString());
        String text = ContentRewriteTest.pageText(out, 1);
        assertTrue(text.contains("Before"), text);
        assertFalse(text.contains("After"), text);
        VeraPdf.assertCompliant(out, level);
    }

    @Test
    void aBrokenTailKeepsItsOwnWarning() throws Exception {
        Path in = dir.resolve("tail.pdf");
        Files.write(in, RawPdf.page(RawPdf.helvetica(), PADDING + "BT /F1 12 Tf 72 700 Td (Kept) Tj ET << /A >> BDC").bytes());
        PdfToPdfA.Result r = PdfToPdfA.convert(in, dir.resolve("tail-out.pdf"));
        assertTrue(r.warnings().contains("Removed a broken token at the end of a content stream"), r.warnings().toString());
        assertTrue(ContentRewriteTest.pageText(dir.resolve("tail-out.pdf"), 1).contains("Kept"));
    }

    @Test
    void aTrailingCommentIsNotABrokenToken() throws Exception {
        Path in = dir.resolve("comment.pdf");
        Files.write(in, RawPdf.page(RawPdf.helvetica(), PADDING + "BT /F1 12 Tf 72 700 Td (Kept) Tj ET\n% producer\n").bytes());
        PdfToPdfA.Result r = PdfToPdfA.convert(in, dir.resolve("comment-out.pdf"));
        assertFalse(r.warnings().stream().anyMatch(w -> w.contains("broken token") || w.startsWith("Cut content")),
                r.warnings().toString());
        assertTrue(ContentRewriteTest.pageText(dir.resolve("comment-out.pdf"), 1).contains("Kept"));
    }

    @Test
    void anUndecodableContentArrayMemberIsReported() throws Exception {
        RawPdf raw = RawPdf.page(RawPdf.helvetica(), "BT /F1 12 Tf 72 700 Td (Readable) Tj ET");
        raw.set(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Resources<<" + RawPdf.helvetica()
                + ">>/Contents[4 0 R 5 0 R]>>");
        raw.add(RawPdf.stream("/Filter/NoSuchDecode", "garbage"));
        Path in = dir.resolve("member.pdf");
        Files.write(in, raw.bytes());
        Path out = dir.resolve("member-out.pdf");
        PdfToPdfA.Result r = PdfToPdfA.convert(in, out);
        assertTrue(r.warnings().stream().anyMatch(w -> w.startsWith("Emptied content streams that could not be decoded")),
                r.warnings().toString());
        assertTrue(ContentRewriteTest.pageText(out, 1).contains("Readable"));
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
    }

    @Test
    void tooManyContentStreamsFailInsteadOfGoingUnchecked() throws Exception {
        try (PDDocument d = new PDDocument()) {
            PDPage page = new PDPage();
            d.addPage(page);
            COSDictionary xobjects = new COSDictionary();
            for (int i = 0; i <= ContentGraph.MAX_NODES; i++) {
                COSStream form = new COSStream();
                form.setItem(COSName.SUBTYPE, COSName.FORM);
                xobjects.setItem(COSName.getPDFName("X" + i), form);
            }
            COSDictionary res = new COSDictionary();
            res.setItem(COSName.XOBJECT, xobjects);
            page.getCOSObject().setItem(COSName.RESOURCES, res);
            IOException e = assertThrows(IOException.class, () -> ContentGraph.of(d));
            assertTrue(e.getMessage().contains("too many to check"), e.getMessage());
        }
    }

    @Test
    void levelARefusesFormsNestedTooDeeplyToCheck() {
        RawPdf r = RawPdf.page(RawPdf.helvetica() + "/XObject<</X 6 0 R>>", "/X Do");
        r.set(1, "<</Type/Catalog/Pages 2 0 R/StructTreeRoot 5 0 R/MarkInfo<</Marked true>>/Lang(en)>>");
        r.add("<</Type/StructTreeRoot/K[]>>");
        int forms = 14;
        for (int i = 0; i < forms; i++) {
            boolean last = i == forms - 1;
            String res = last ? RawPdf.helvetica() : "/XObject<</X " + (7 + i) + " 0 R>>";
            r.add(RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 300 300]/Resources<<" + res + ">>",
                    last ? "BT /F1 12 Tf 10 10 Td (Deep text) Tj ET" : "/X Do"));
        }
        IOException e = assertThrows(IOException.class, () -> PdfToPdfA.convert(Hostile.write(dir, "deep", r),
                dir.resolve("deep-out.pdf"), PdfToPdfA.Options.defaults().level(PdfALevel.A2A)));
        assertTrue(e.getMessage().contains("nested more than 12 deep"), e.getMessage());
    }

    @Test
    void removingEncryptionIsReported() throws Exception {
        Path in = dir.resolve("locked.pdf");
        try (PDDocument d = new PDDocument()) {
            Hostile.plainPage(d);
            AccessPermission none = new AccessPermission();
            none.setCanPrint(false);
            d.protect(new StandardProtectionPolicy("owner", "", none));
            d.save(in.toFile());
        }
        PdfToPdfA.Result r = PdfToPdfA.convert(in, dir.resolve("unlocked.pdf"));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("encryption")), r.warnings().toString());
        assertTrue(r.unverified().isEmpty(), r.unverified().toString());
    }

    @Test
    void anIdWhoseSecondElementIsNotAStringIsReplaced() throws Exception {
        RawPdf raw = RawPdf.page(RawPdf.helvetica(), "BT /F1 12 Tf 72 700 Td (Id) Tj ET");
        raw.trailerExtra = "/ID[<0123456789ABCDEF0123456789ABCDEF> 7]";
        Path in = dir.resolve("id.pdf");
        Files.write(in, raw.bytes());
        Path out = dir.resolve("id-out.pdf");
        PdfToPdfA.convert(in, out);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSArray id = d.getDocument().getDocumentID();
            assertEquals(2, id.size());
            assertInstanceOf(org.apache.pdfbox.cos.COSString.class, id.getObject(1));
        }
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
    }

    @Test
    void theDocumentOverloadWaitsForAdmissionAndWritesNothingOnTimeout() throws Exception {
        int slots = Admission.jvm().status().slots();
        CountDownLatch entered = new CountDownLatch(slots);
        CountDownLatch release = new CountDownLatch(1);
        List<Thread> holders = new ArrayList<>();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument d = new PDDocument()) {
            Hostile.plainPage(d);
            // A thread's later tickets nest in its first, so each slot needs its own thread.
            for (int i = 0; i < slots; i++) {
                holders.add(Thread.startVirtualThread(() -> {
                    try {
                        Admission.Ticket t = Admission.jvm().enter(0);
                        entered.countDown();
                        release.await();
                        t.close();
                    } catch (IOException | InterruptedException e) {
                        entered.countDown();
                    }
                }));
            }
            entered.await();
            PdfToPdfA.Options options = PdfToPdfA.Options.defaults().timeout(Duration.ofSeconds(1));
            assertTimeoutPreemptively(Duration.ofSeconds(60),
                    () -> assertThrows(PdfToPdfA.TimedOut.class, () -> PdfToPdfA.convert(d, out, options)));
        } finally {
            release.countDown();
            for (Thread t : holders) {
                t.join();
            }
        }
        assertEquals(0, out.size());
        try (PDDocument d = new PDDocument()) {
            Hostile.plainPage(d);
            PdfToPdfA.Result r = PdfToPdfA.convert(d, out, PdfToPdfA.Options.defaults());
            assertEquals(PdfALevel.A2B, r.achievedLevel());
        }
        Path written = dir.resolve("stream.pdf");
        Files.write(written, out.toByteArray());
        VeraPdf.assertCompliant(written, PdfALevel.A2B);
    }

    @Test
    void reportKeepsRoomForFixedWarningsAndSaysHowManyWereOmitted() {
        Report report = new Report();
        for (int i = 0; i < 500; i++) {
            report.warn("Removed a %s annotation", "Bogus" + i + "\u0007");
        }
        report.warn("Removed a %s annotation", "x".repeat(10_000));
        for (int i = 0; i < 150; i++) {
            report.warn("Fixed message " + i);
        }
        List<String> w = report.warnings();
        assertEquals(20, w.stream().filter(s -> s.startsWith("Removed a ")).count());
        assertTrue(w.stream().noneMatch(s -> s.indexOf('\u0007') >= 0));
        assertTrue(w.stream().allMatch(s -> s.length() <= 300));
        assertTrue(w.contains("Fixed message 149"), w.toString());
        assertTrue(w.get(w.size() - 1).matches("\\d+ more warnings omitted"), w.get(w.size() - 1));
        assertEquals("481 more warnings omitted", w.get(w.size() - 1));
    }
}
