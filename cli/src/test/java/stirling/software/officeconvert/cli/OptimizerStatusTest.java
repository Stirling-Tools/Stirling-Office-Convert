package stirling.software.officeconvert.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OptimizerStatusTest {

    @TempDir
    Path dir;

    private record Result(int code, String out, String err) {}

    private static Result run(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            return new Result(Main.run(args), out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @Test
    void largerRepairsAreNotReportedAsNegativeSavings() throws Exception {
        Path in = dir.resolve("broken.pdf");
        Files.writeString(in, "%PDF-1.4\n1 0 obj <</Type/Catalog/Pages 2 0 R>> endobj\n"
                + "2 0 obj <</Type/Pages/Kids[2 0 R]/Count 1>> endobj\n"
                + "3 0 obj <</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>> endobj\n");
        Path out = dir.resolve("repaired.pdf");
        Result result = run(in.toString(), "--optimize", "-o", out.toString());
        assertEquals(0, result.code(), result.err());
        assertTrue(Files.size(out) > Files.size(in));
        assertTrue(result.err().contains("larger"), result.err());
        assertFalse(result.err().contains("% smaller"), result.err());
        try (var document = Loader.loadPDF(out.toFile())) {
            assertEquals(1, document.getNumberOfPages());
        }
    }

    @Test
    void unchangedCopiesReportRetainedPrivateData() throws Exception {
        Path in = dir.resolve("tiny.pdf");
        String[] objects = {"<</Type/Catalog/Pages 2 0 R/PieceInfo<</Editor<</Private(secret)>>>>>>",
                "<</Type/Pages/Kids[3 0 R]/Count 1>>", "<</Type/Page/Parent 2 0 R/MediaBox[0 0 20 20]>>"};
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        int[] offsets = new int[objects.length];
        for (int i = 0; i < objects.length; i++) {
            offsets[i] = pdf.length();
            pdf.append(i + 1).append(" 0 obj ").append(objects[i]).append(" endobj\n");
        }
        int xref = pdf.length();
        pdf.append("xref\n0 4\n0000000000 65535 f\n");
        for (int offset : offsets) {
            pdf.append(String.format(Locale.ROOT, "%010d 00000 n\n", offset));
        }
        pdf.append("trailer<</Size 4/Root 1 0 R>>\nstartxref\n").append(xref).append("\n%%EOF\n");
        Files.writeString(in, pdf, StandardCharsets.ISO_8859_1);
        Path out = dir.resolve("copy.pdf");
        Result result = run(in.toString(), "--optimize", "-o", out.toString());
        assertEquals(0, result.code(), result.err());
        assertArrayEquals(Files.readAllBytes(in), Files.readAllBytes(out));
        assertTrue(result.err().contains("copied unchanged"), result.err());
        assertTrue(result.err().contains("private data is retained"), result.err());
        try (var document = Loader.loadPDF(out.toFile())) {
            assertTrue(document.getDocumentCatalog().getCOSObject().containsKey("PieceInfo"));
        }
    }

    @Test
    void helpQualifiesPrivateDataRemovalAndLargerRepairs() {
        Result result = run("--help");
        assertEquals(0, result.code());
        assertTrue(result.out().contains("copied input retains its private data"), result.out());
        assertTrue(result.out().contains("repaired PDFs may be larger"), result.out());
    }
}
