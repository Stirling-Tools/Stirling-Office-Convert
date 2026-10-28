package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.zip.DeflaterOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrotliOptimizeTest {

    @TempDir
    Path dir;

    private static final PdfOptimizer.Options REWRITE = PdfOptimizer.Options.defaults().alwaysRewrite(true);

    private static final String CONTENT = "0 0 1 rg 10 10 80 40 re f BT /F1 12 Tf 20 150 Td (Brotli page) Tj ET "
            + "q 100 0 0 50 50 60 cm /Im Do Q\n";

    private static final String IMAGE = "/Type/XObject/Subtype/Image/Width 40/Height 20/ColorSpace/DeviceRGB"
            + "/BitsPerComponent 8";

    @Test
    void brotliContentAndImagesAreRewrittenAsFlateAndDrawTheSame() throws Exception {
        Path flate = page("flate", "/Filter/FlateDecode", deflate(bytes(CONTENT)),
                "/Filter/FlateDecode", deflate(pixels()));
        Path brotli = page("brotli", "/Filter/BrotliDecode", BrotliData.stored(bytes(CONTENT)),
                "/Filter/BrotliDecode", BrotliData.stored(pixels()));
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.Result result = PdfOptimizer.optimize(brotli, out, REWRITE);
        assertTrue(result.rewritten());
        assertFalse(contains(out, "Brotli" + "Decode"));
        Pages.assertSameRendering(flate, out);
    }

    @Test
    void chainedBrotliStagesAndPredictorsAreDecoded() throws Exception {
        Path flate = page("flate", "/Filter/FlateDecode", deflate(bytes(CONTENT)),
                "/Filter/FlateDecode/DecodeParms<</Predictor 15/Colors 3/Columns 40>>", deflate(png(pixels())));
        Path brotli = page("chained", "/Filter[/BrotliDecode/ASCIIHexDecode]", BrotliData.stored(hex(bytes(CONTENT))),
                "/Filter[/ASCIIHexDecode/BrotliDecode]/DecodeParms[null<</Predictor 15/Colors 3/Columns 40>>]",
                hex(BrotliData.stored(png(pixels()))));
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(brotli, out, REWRITE);
        assertFalse(contains(out, "Brotli" + "Decode"));
        Pages.assertSameRendering(flate, out);
    }

    @Test
    void aRealBrotliStreamIsDecoded() throws Exception {
        RawPdf r = RawPdf.page(RawPdf.helvetica("F1"), "");
        r.objects.set(3, RawPdf.stream("/Filter/BrotliDecode", BrotliData.HELLO));
        Path in = r.write(dir, "hello");
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(in, out, REWRITE);
        assertFalse(contains(out, "Brotli" + "Decode"));
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertTrue(new PDFTextStripper().getText(d).contains("Hello Brotli world"));
        }
    }

    @Test
    void aBrotliFileIsWrittenWithFlateEvenWhenThatIsLarger() throws Exception {
        RawPdf r = RawPdf.page(RawPdf.helvetica("F1"), "");
        r.objects.set(3, RawPdf.stream("/Filter/BrotliDecode", BrotliData.HELLO));
        r.trailerExtra = "";
        Path in = r.write(dir, "small");
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.Result result = PdfOptimizer.optimize(in, out, PdfOptimizer.Options.defaults());
        assertTrue(result.rewritten(), result.warnings().toString());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Brotli" + "Decode")), result.warnings().toString());
        assertFalse(contains(out, "Brotli" + "Decode"));
    }

    @Test
    void aBrotliBombIsKeptAsStoredAndReported() throws Exception {
        byte[] bomb = BrotliData.zeros(Recoder.MAX_DECODED_BYTES + (1 << 20));
        RawPdf r = RawPdf.page("", "0 0 1 rg 10 10 50 50 re f");
        r.set(1, "<</Type/Catalog/Pages 2 0 R/Payload 5 0 R>>");
        r.add(RawPdf.stream("/Filter/BrotliDecode", bomb));
        Path in = r.write(dir, "bomb");
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.Result result = PdfOptimizer.optimize(in, out, REWRITE);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("size limit")), result.warnings().toString());
        assertArrayEquals(bomb, payload(out));
        assertTrue(Files.size(out) < Files.size(in) + 4096);
    }

    @Test
    void manyBrotliStreamsShareOneRunBudget() throws Exception {
        RawPdf r = RawPdf.page("", "0 0 1 rg 10 10 50 50 re f");
        StringBuilder payload = new StringBuilder("[");
        for (int i = 0; i < 6; i++) {
            payload.append(r.add(RawPdf.stream("/Filter/BrotliDecode", BrotliData.zeros(4 << 20)))).append(" 0 R ");
        }
        r.set(1, "<</Type/Catalog/Pages 2 0 R/Payload" + payload + "]>>");
        Path in = r.write(dir, "many-bombs");
        Path out = dir.resolve("out.pdf");
        System.setProperty("stirling.officeconvert.brotliBudgetMB", "8");
        PdfOptimizer.Result result;
        try {
            result = PdfOptimizer.optimize(in, out, REWRITE);
        } finally {
            System.clearProperty("stirling.officeconvert.brotliBudgetMB");
        }
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("budget")), result.warnings().toString());
        int kept = 0;
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            org.apache.pdfbox.cos.COSArray streams = (org.apache.pdfbox.cos.COSArray) d.getDocumentCatalog()
                    .getCOSObject().getDictionaryObject(COSName.getPDFName("Payload"));
            for (int i = 0; i < streams.size(); i++) {
                if (Filters.brotli(((COSStream) streams.getObject(i)).getItem(COSName.FILTER))) {
                    kept++;
                }
            }
        }
        assertTrue(kept >= 4, kept + " of 6 streams kept as stored");
    }

    @Test
    void aBrotliObjectStreamPastTheBudgetFailsTheRun() throws Exception {
        Path in = packed("packed-budget", "/BrotliDecode", true, 2 << 20);
        Path out = dir.resolve("out.pdf");
        System.setProperty("stirling.officeconvert.brotliBudgetMB", "1");
        try {
            IOException e = assertThrows(IOException.class, () -> PdfOptimizer.optimize(in, out, REWRITE));
            assertTrue(e.getMessage().contains("budget"), e.getMessage());
        } finally {
            System.clearProperty("stirling.officeconvert.brotliBudgetMB");
        }
        assertFalse(Files.exists(out));
    }

    @Test
    void aCorruptBrotliStreamIsKeptAsStoredLikeACorruptFlateStream() throws Exception {
        byte[] cut = java.util.Arrays.copyOf(BrotliData.stored(bytes("0 0 1 rg 10 10 50 50 re f\n".repeat(50))), 300);
        byte[] garbage = bytes("not brotli at all, just text");
        for (byte[] broken : new byte[][] {cut, garbage}) {
            RawPdf r = RawPdf.page("", "0 0 1 rg 10 10 50 50 re f");
            r.set(1, "<</Type/Catalog/Pages 2 0 R/Payload 5 0 R>>");
            r.add(RawPdf.stream("/Filter/BrotliDecode", broken));
            Path in = r.write(dir, "corrupt");
            Path out = dir.resolve("out.pdf");
            PdfOptimizer.Result result = PdfOptimizer.optimize(in, out, REWRITE);
            assertTrue(result.rewritten());
            assertArrayEquals(broken, payload(out));
        }
    }

    @Test
    void brotliObjectAndCrossReferenceStreamsOpen() throws Exception {
        Path flate = packed("packed-flate", "/FlateDecode", false);
        Path brotli = packed("packed-brotli", "/BrotliDecode", true);
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.Result result = PdfOptimizer.optimize(brotli, out, REWRITE);
        assertTrue(result.rewritten());
        assertFalse(contains(out, "Brotli" + "Decode"));
        Pages.assertSameRendering(flate, out);
    }

    @Test
    void aFileWithoutBrotliOptimizesAsBefore() throws Exception {
        Path in = page("plain", "/Filter/FlateDecode", deflate(bytes(CONTENT)), "/Filter/FlateDecode",
                deflate(pixels()));
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.Result result = PdfOptimizer.optimize(in, out, REWRITE);
        assertTrue(result.rewritten());
        assertEquals(java.util.List.of(), result.warnings());
        Pages.assertSameRendering(in, out);
    }

    private Path page(String name, String contentDict, byte[] content, String imageDict, byte[] image)
            throws IOException {
        RawPdf r = RawPdf.page(RawPdf.helvetica("F1") + "/XObject<</Im 5 0 R>>", "");
        r.objects.set(3, RawPdf.stream(contentDict, content));
        r.add(RawPdf.stream(IMAGE + imageDict, image));
        return r.write(dir, name);
    }

    private Path packed(String name, String filter, boolean brotli) throws IOException {
        return packed(name, filter, brotli, 0);
    }

    private Path packed(String name, String filter, boolean brotli, int padding) throws IOException {
        String[] objects = {"<</Type/Catalog/Pages 2 0 R>>", "<</Type/Pages/Kids[3 0 R]/Count 1>>",
            "<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Resources<<" + RawPdf.helvetica("F1")
                    + ">>/Contents 4 0 R>>"};
        StringBuilder head = new StringBuilder();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < objects.length; i++) {
            head.append(i + 1).append(' ').append(body.length()).append(' ');
            body.append(objects[i]).append('\n');
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(bytes("%PDF-1.7\n%âãÏÓ\n"));
        long content = out.size();
        out.writeBytes(bytes("4 0 obj\n"));
        out.writeBytes(RawPdf.stream("/Filter" + filter, encode(bytes(CONTENT.replace("q 100", "% 100")), brotli)));
        out.writeBytes(bytes("\nendobj\n"));
        long stm = out.size();
        out.writeBytes(bytes("5 0 obj\n"));
        out.writeBytes(RawPdf.stream("/Type/ObjStm/N 3/First " + head.length() + "/Filter" + filter,
                encode(bytes(head + body.toString() + " ".repeat(padding)), brotli)));
        out.writeBytes(bytes("\nendobj\n"));
        long xref = out.size();
        ByteArrayOutputStream rows = new ByteArrayOutputStream();
        row(rows, 0, 0, 65535);
        for (int i = 0; i < 3; i++) {
            row(rows, 2, 5, i);
        }
        row(rows, 1, content, 0);
        row(rows, 1, stm, 0);
        row(rows, 1, xref, 0);
        out.writeBytes(bytes("6 0 obj\n"));
        out.writeBytes(RawPdf.stream("/Type/XRef/Size 7/W[1 4 2]/Root 1 0 R/Filter" + filter,
                encode(rows.toByteArray(), brotli)));
        out.writeBytes(bytes("\nendobj\nstartxref\n" + xref + "\n%%EOF\n"));
        Path p = dir.resolve(name + ".pdf");
        Files.write(p, out.toByteArray());
        return p;
    }

    private static byte[] encode(byte[] data, boolean brotli) throws IOException {
        return brotli ? BrotliData.stored(data) : deflate(data);
    }

    private static void row(ByteArrayOutputStream out, int type, long field, int gen) {
        out.write(type);
        out.write((int) (field >>> 24));
        out.write((int) (field >>> 16));
        out.write((int) (field >>> 8));
        out.write((int) field);
        out.write(gen >>> 8);
        out.write(gen);
    }

    private static byte[] payload(Path pdf) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            COSStream kept = (COSStream) d.getDocumentCatalog().getCOSObject().getDictionaryObject(
                    COSName.getPDFName("Payload"));
            assertEquals(COSName.getPDFName("BrotliDecode"), kept.getFilters());
            try (InputStream raw = kept.createRawInputStream()) {
                return raw.readAllBytes();
            }
        }
    }

    private static boolean contains(Path pdf, String text) throws IOException {
        return new String(Files.readAllBytes(pdf), StandardCharsets.ISO_8859_1).contains(text);
    }

    private static byte[] pixels() {
        byte[] p = new byte[40 * 20 * 3];
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 40; x++) {
                int at = (y * 40 + x) * 3;
                p[at] = (byte) (x * 6);
                p[at + 1] = (byte) (y * 12);
                p[at + 2] = (byte) ((x + y) * 5);
            }
        }
        return p;
    }

    private static byte[] png(byte[] pixels) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int y = 0; y < 20; y++) {
            out.write(2);
            for (int x = 0; x < 120; x++) {
                int above = y == 0 ? 0 : pixels[(y - 1) * 120 + x];
                out.write(pixels[y * 120 + x] - above);
            }
        }
        return out.toByteArray();
    }

    private static byte[] hex(byte[] data) {
        return bytes(HexFormat.of().formatHex(data) + ">");
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] deflate(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(out)) {
            z.write(data);
        }
        return out.toByteArray();
    }
}
