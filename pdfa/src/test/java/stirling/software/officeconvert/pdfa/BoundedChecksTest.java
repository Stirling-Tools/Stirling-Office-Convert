package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedChecksTest {

    @TempDir
    Path dir;

    @Test
    void textStreamsRefuseImageFilters() throws Exception {
        COSStream s = new COSStream();
        try (OutputStream out = s.createRawOutputStream()) {
            out.write(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        }
        s.setItem(COSName.FILTER, COSName.DCT_DECODE);
        IOException e = assertThrows(IOException.class, () -> Decoded.bytes(s, 1 << 20, "A content stream"));
        assertTrue(e.getMessage().contains("image filter"), e.getMessage());
    }

    @Test
    void attachmentValidationHasAConversionWideBudget() throws Exception {
        AttachedPdfA.Budget files = new AttachedPdfA.Budget();
        for (int i = 0; i < AttachedPdfA.Budget.MAX_FILES; i++) {
            files.spend(1);
        }
        assertThrows(AttachmentParser.LimitExceeded.class, () -> files.spend(1));
        AttachedPdfA.Budget bytes = new AttachedPdfA.Budget();
        bytes.spend(AttachedPdfA.Budget.MAX_TOTAL_BYTES);
        assertThrows(AttachmentParser.LimitExceeded.class, () -> bytes.spend(1));
    }

    @Test
    void aHugeClipPathFailsPartOneFlatteningInsteadOfStalling() throws Exception {
        StringBuilder clip = new StringBuilder("0 0 m ");
        for (int i = 0; i <= FlattenRenderer.MAX_PATH_SEGMENTS; i++) {
            clip.append(i % 500).append(i % 2 == 0 ? " 0 l " : " 300 l ");
        }
        String content = "q " + clip + "h W n /GS0 gs 0 0 1 rg 50 50 200 200 re f Q";
        Path in = dir.resolve("clip.pdf");
        Files.write(in, RawPdf.page("/ExtGState<</GS0<</ca 0.5>>>>", content).bytes());
        IOException e = assertTimeoutPreemptively(Duration.ofSeconds(120), () -> assertThrows(IOException.class,
                () -> PdfToPdfA.convert(in, dir.resolve("clip-out.pdf"),
                        PdfToPdfA.Options.defaults().level(PdfALevel.A1B))));
        assertTrue(e.getMessage().contains("segments"), e.getMessage());
    }

    @Test
    void inventedUnicodeIsReportedForLevelU() throws Exception {
        RawPdf r = RawPdf.page("/Font<</F1 5 0 R>>", "BT /F1 12 Tf 72 700 Td <00> Tj ET");
        r.add("<</Type/Font/Subtype/Type3/FontBBox[0 0 1000 1000]/FontMatrix[0.001 0 0 0.001 0 0]"
                + "/CharProcs<</zz1 6 0 R>>/Encoding<</Type/Encoding/Differences[0/zz1]>>/FirstChar 0/LastChar 0"
                + "/Widths[1000]/Resources<<>>>>");
        r.add(RawPdf.stream("", "1000 0 0 0 1000 1000 d1 0 0 1000 1000 re f"));
        Path in = dir.resolve("t3.pdf");
        Files.write(in, r.bytes());
        Path out = dir.resolve("t3-out.pdf");
        PdfToPdfA.Result result = PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2U));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("private-use code points")),
                result.warnings().toString());
        VeraPdf.assertCompliant(out, PdfALevel.A2U);
    }
}
