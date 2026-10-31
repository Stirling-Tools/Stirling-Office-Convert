package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipFile;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.extract.PdfFiles;
import stirling.software.officeconvert.extract.StreamGuard;
import stirling.software.officeconvert.testing.BrotliData;

class BrotliPdfTest {

    @TempDir Path dir;

    private static final String FONT = "<</Type/Font/Subtype/Type1/BaseFont/Helvetica/Encoding/WinAnsiEncoding>>";

    private static final String TEXT = "BT /F1 14 Tf 30 250 Td (Brotli streams read like Flate ones) Tj ET "
            + "BT /F1 10 Tf 30 230 Td (A second line of text) Tj ET";

    private static final String BROTLI = "/BrotliDecode";

    private static final String FLATE = "/FlateDecode";

    private static final String BUDGET = "stirling.officeconvert.brotliBudgetMB";

    @Test
    void textOfAPackedBrotliPdfMatchesTheFlateVersion() throws IOException {
        Path brotli = write("packed-brotli", packed(BROTLI, BrotliData::stored));
        Path flate = write("packed-flate", packed(FLATE, BrotliPdfTest::deflate));
        String expected = OfficeConvert.text(flate);
        assertTrue(expected.contains("Brotli streams read like Flate ones"), expected);
        assertEquals(expected, OfficeConvert.text(brotli));
    }

    @Test
    void docxOfABrotliPdfMatchesTheFlateVersion() throws IOException {
        Path brotli = write("page-brotli", page(BROTLI, BrotliData::stored, Picture.NONE));
        Path flate = write("page-flate", page(FLATE, BrotliPdfTest::deflate, Picture.NONE));
        String expected = documentXml(docx(flate));
        assertTrue(expected.contains("A second line of text"), expected);
        assertEquals(expected, documentXml(docx(brotli)));
    }

    @Test
    void aBrotliPdfRendersLikeTheFlateVersion() throws IOException {
        Path brotli = write("render-brotli", page(BROTLI, BrotliData::stored, Picture.PLAIN));
        Path flate = write("render-flate", page(FLATE, BrotliPdfTest::deflate, Picture.PLAIN));
        assertArrayEquals(pixels(flate), pixels(brotli));
    }

    @Test
    void chainedBrotliStagesAndPredictorsDecode() throws IOException {
        UnaryOperator<byte[]> hexThenBrotli = d -> BrotliData.stored(hex(d));
        Path brotli = write("chained-brotli", page("[/BrotliDecode/ASCIIHexDecode]", hexThenBrotli,
                Picture.PREDICTED));
        Path flate = write("chained-flate", page(FLATE, BrotliPdfTest::deflate, Picture.PREDICTED));
        assertArrayEquals(pixels(flate), pixels(brotli));
        assertEquals(OfficeConvert.text(flate), OfficeConvert.text(brotli));
    }

    @Test
    void aRealBrotliContentStreamIsRead() throws IOException {
        RawPdf r = new RawPdf();
        r.add("<</Type/Catalog/Pages 2 0 R>>");
        r.add("<</Type/Pages/Kids[3 0 R]/Count 1>>");
        r.add("<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 300]/Resources<</Font<</F1 4 0 R>>>>/Contents 5 0 R>>");
        r.add(FONT);
        r.add(RawPdf.stream("/Filter/BrotliDecode", BrotliData.HELLO));
        Path pdf = write("hello", r.classic());
        assertTrue(OfficeConvert.text(pdf).contains("Hello Brotli world"));
        try (PDDocument doc = Loader.loadPDF(pdf.toFile()); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            OfficeConvert.convert(doc, out, OfficeConvert.Format.DOCX, OfficeConvert.Settings.defaults());
            Path docx = dir.resolve("hello-api.docx");
            Files.write(docx, out.toByteArray());
            assertTrue(documentXml(docx).contains("Hello Brotli world"));
        }
    }

    @Test
    void aBrotliBombIsBoundedLikeAFlateBomb() throws IOException {
        RawPdf r = base("[5 0 R 6 0 R]", "");
        r.add(RawPdf.stream("/Filter/BrotliDecode", BrotliData.zeros(StreamGuard.MAX_DECODED * 4)));
        r.add(RawPdf.stream("", "BT /F1 12 Tf 30 250 Td (Still here) Tj ET"));
        Path pdf = write("bomb", r.classic());
        long start = cpu();
        try (PDDocument doc = PdfFiles.open(pdf, null)) {
            assertEquals(1, StreamGuard.check(doc));
        }
        assertTrue(documentXml(docx(pdf)).contains("Still here"));
        long ms = (cpu() - start) / 1_000_000;
        assertTrue(ms < 30_000, "took " + ms + " ms of CPU");
    }

    @Test
    void manyBrotliStreamsShareOneDocumentBudget() throws IOException {
        RawPdf r = base("5 0 R", "");
        r.add(RawPdf.stream("", "BT /F1 12 Tf 30 250 Td (Still here) Tj ET"));
        StringBuilder payload = new StringBuilder("[");
        for (int i = 0; i < 10; i++) {
            r.add(RawPdf.stream("/Filter/BrotliDecode", BrotliData.zeros(16 << 20)));
            payload.append(i + 6).append(" 0 R ");
        }
        r.objects.set(0, ("<</Type/Catalog/Pages 2 0 R/Payload" + payload + "]>>").getBytes(StandardCharsets.ISO_8859_1));
        Path pdf = write("many-bombs", r.classic());
        System.setProperty(BUDGET, "64");
        try {
            int decoded = 0;
            int emptied = 0;
            try (PDDocument doc = PdfFiles.open(pdf, null)) {
                StreamGuard.check(doc);
                for (int i = 6; i <= 15; i++) {
                    COSStream s = (COSStream) doc.getDocument().getObjectFromPool(
                            new org.apache.pdfbox.cos.COSObjectKey(i, 0)).getObject();
                    if (COSName.FLATE_DECODE.equals(s.getFilters())) {
                        decoded++;
                    } else if (s.getFilters() == null && s.getLength() == 0) {
                        emptied++;
                    }
                }
            }
            assertTrue(decoded <= 4, decoded + " streams decoded past a 64 MB budget");
            assertEquals(10 - decoded, emptied);
            assertTrue(OfficeConvert.text(pdf).contains("Still here"));
        } finally {
            System.clearProperty(BUDGET);
        }
    }

    @Test
    void aBrotliObjectStreamPastTheBudgetFailsTheDocument() throws IOException {
        Path pdf = write("packed-budget", packed(BROTLI, BrotliData::stored, 2 << 20));
        System.setProperty(BUDGET, "1");
        try {
            IOException e = assertThrows(IOException.class, () -> OfficeConvert.text(pdf));
            assertTrue(e.getMessage().contains("budget"), e.getMessage());
        } finally {
            System.clearProperty(BUDGET);
        }
        assertTrue(OfficeConvert.text(pdf).contains("Brotli streams read like Flate ones"));
    }

    @Test
    void aCorruptBrotliStreamIsHandledLikeACorruptFlateStream() throws IOException {
        byte[] garbage = "this is not compressed data at all".getBytes(StandardCharsets.ISO_8859_1);
        String brotli = OfficeConvert.text(write("corrupt-brotli", twoPages("/Filter/BrotliDecode", garbage)));
        String flate = OfficeConvert.text(write("corrupt-flate", twoPages("/Filter/FlateDecode", garbage)));
        assertTrue(flate.contains("Second page"), flate);
        assertEquals(flate, brotli);
        byte[] cut = java.util.Arrays.copyOf(BrotliData.stored(new byte[4000]), 900);
        String truncated = OfficeConvert.text(write("cut-brotli", twoPages("/Filter/BrotliDecode", cut)));
        assertTrue(truncated.contains("Second page"), truncated);
    }

    @Test
    void aFileWithoutBrotliKeepsItsStreamsAsStored() throws IOException {
        byte[] stored = deflate(TEXT.getBytes(StandardCharsets.ISO_8859_1));
        RawPdf r = base("5 0 R", "");
        r.add(RawPdf.stream("/Filter/FlateDecode", stored));
        Path pdf = write("plain", r.classic());
        try (PDDocument doc = PdfFiles.open(pdf, null)) {
            COSStream s = (COSStream) doc.getPage(0).getCOSObject().getDictionaryObject(COSName.CONTENTS);
            assertEquals(COSName.FLATE_DECODE, s.getFilters());
            try (InputStream raw = s.createRawInputStream()) {
                assertArrayEquals(stored, raw.readAllBytes());
            }
        }
    }

    private static RawPdf base(String contents, String resources) {
        RawPdf r = new RawPdf();
        r.add("<</Type/Catalog/Pages 2 0 R>>");
        r.add("<</Type/Pages/Kids[3 0 R]/Count 1>>");
        r.add("<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 300]/Resources<</Font<</F1 4 0 R>>" + resources
                + ">>/Contents " + contents + ">>");
        r.add(FONT);
        return r;
    }

    private enum Picture {
        NONE, PLAIN, PREDICTED
    }

    private static byte[] page(String contentFilter, UnaryOperator<byte[]> contentEncoding, Picture picture) {
        boolean brotli = !contentFilter.equals(FLATE);
        RawPdf r = base("5 0 R", picture == Picture.NONE ? "" : "/XObject<</Im 6 0 R>>");
        String content = TEXT + (picture == Picture.NONE ? "" : " q 100 0 0 50 30 100 cm /Im Do Q");
        r.add(RawPdf.stream("/Filter" + contentFilter, contentEncoding.apply(content.getBytes(StandardCharsets.ISO_8859_1))));
        if (picture == Picture.NONE) {
            return r.classic();
        }
        byte[] pixels = new byte[40 * 20 * 3];
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 40; x++) {
                int at = (y * 40 + x) * 3;
                pixels[at] = (byte) (x * 6);
                pixels[at + 1] = (byte) (y * 12);
                pixels[at + 2] = (byte) ((x + y) * 5);
            }
        }
        String dict = "/Type/XObject/Subtype/Image/Width 40/Height 20/ColorSpace/DeviceRGB/BitsPerComponent 8";
        String predictor = "<</Predictor 15/Colors 3/Columns 40>>";
        if (picture == Picture.PLAIN) {
            r.add(RawPdf.stream(dict + "/Filter" + (brotli ? BROTLI : FLATE),
                    brotli ? BrotliData.stored(pixels) : deflate(pixels)));
        } else if (brotli) {
            r.add(RawPdf.stream(dict + "/Filter[/ASCIIHexDecode/BrotliDecode]/DecodeParms[null" + predictor + "]",
                    hex(BrotliData.stored(png(pixels, 120, 20)))));
        } else {
            r.add(RawPdf.stream(dict + "/Filter/FlateDecode/DecodeParms" + predictor, deflate(png(pixels, 120, 20))));
        }
        return r.classic();
    }

    private static byte[] packed(String filter, UnaryOperator<byte[]> encoding) {
        return packed(filter, encoding, 0);
    }

    private static byte[] packed(String filter, UnaryOperator<byte[]> encoding, int padding) {
        String[] objects = {"<</Type/Catalog/Pages 2 0 R>>", "<</Type/Pages/Kids[3 0 R]/Count 1>>",
            "<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 300]/Resources<</Font<</F1 4 0 R>>>>/Contents 5 0 R>>", FONT};
        StringBuilder head = new StringBuilder();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < objects.length; i++) {
            head.append(i + 1).append(' ').append(body.length()).append(' ');
            body.append(objects[i]).append('\n');
        }
        byte[] objStm = encoding.apply((head + body.toString() + " ".repeat(padding))
                .getBytes(StandardCharsets.ISO_8859_1));
        String text = TEXT;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "%PDF-1.7\n%âãÏÓ\n");
        long content = out.size();
        write(out, "5 0 obj\n");
        out.writeBytes(RawPdf.stream("/Filter" + filter, encoding.apply(text.getBytes(StandardCharsets.ISO_8859_1))));
        write(out, "\nendobj\n");
        long stm = out.size();
        write(out, "6 0 obj\n");
        out.writeBytes(RawPdf.stream("/Type/ObjStm/N 4/First " + head.length() + "/Filter" + filter, objStm));
        write(out, "\nendobj\n");
        long xref = out.size();
        ByteArrayOutputStream rows = new ByteArrayOutputStream();
        row(rows, 0, 0, 65535);
        for (int i = 0; i < 4; i++) {
            row(rows, 2, 6, i);
        }
        row(rows, 1, content, 0);
        row(rows, 1, stm, 0);
        row(rows, 1, xref, 0);
        write(out, "7 0 obj\n");
        out.writeBytes(RawPdf.stream("/Type/XRef/Size 8/W[1 4 2]/Root 1 0 R/Filter" + filter,
                encoding.apply(rows.toByteArray())));
        write(out, "\nendobj\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static byte[] twoPages(String filter, byte[] broken) {
        RawPdf r = new RawPdf();
        r.add("<</Type/Catalog/Pages 2 0 R>>");
        r.add("<</Type/Pages/Kids[3 0 R 6 0 R]/Count 2>>");
        r.add("<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 300]/Resources<</Font<</F1 4 0 R>>>>/Contents 5 0 R>>");
        r.add(FONT);
        r.add(RawPdf.stream(filter, broken));
        r.add("<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 300]/Resources<</Font<</F1 4 0 R>>>>/Contents 7 0 R>>");
        r.add(RawPdf.stream("", "BT /F1 12 Tf 30 250 Td (Second page) Tj ET"));
        return r.classic();
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

    private static byte[] png(byte[] pixels, int rowBytes, int rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int y = 0; y < rows; y++) {
            out.write(2);
            for (int x = 0; x < rowBytes; x++) {
                int above = y == 0 ? 0 : pixels[(y - 1) * rowBytes + x];
                out.write(pixels[y * rowBytes + x] - above);
            }
        }
        return out.toByteArray();
    }

    private static byte[] hex(byte[] data) {
        return (HexFormat.of().formatHex(data) + ">").getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] deflate(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(out)) {
            z.write(data);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private int[] pixels(Path pdf) throws IOException {
        try (PDDocument doc = PdfFiles.open(pdf, null)) {
            BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(0, 72);
            return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        }
    }

    private Path docx(Path pdf) throws IOException {
        Path docx = dir.resolve(pdf.getFileName() + ".docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        return docx;
    }

    private static String documentXml(Path docx) throws IOException {
        try (ZipFile z = new ZipFile(docx.toFile())) {
            return new String(z.getInputStream(z.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Path write(String name, byte[] data) throws IOException {
        Path p = dir.resolve(name + ".pdf");
        Files.write(p, data);
        return p;
    }

    private static long cpu() {
        return ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static final class RawPdf {

        final List<byte[]> objects = new ArrayList<>();

        void add(String body) {
            add(body.getBytes(StandardCharsets.ISO_8859_1));
        }

        void add(byte[] body) {
            objects.add(body);
        }

        static byte[] stream(String dict, String data) {
            return stream(dict, data.getBytes(StandardCharsets.ISO_8859_1));
        }

        static byte[] stream(String dict, byte[] data) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            write(b, "<<" + dict + "/Length " + data.length + ">>stream\n");
            b.writeBytes(data);
            write(b, "\nendstream");
            return b.toByteArray();
        }

        byte[] classic() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            write(out, "%PDF-1.7\n%âãÏÓ\n");
            long[] offsets = new long[objects.size() + 1];
            for (int i = 0; i < objects.size(); i++) {
                offsets[i + 1] = out.size();
                write(out, (i + 1) + " 0 obj\n");
                out.writeBytes(objects.get(i));
                write(out, "\nendobj\n");
            }
            long xref = out.size();
            StringBuilder b = new StringBuilder("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f\r\n");
            for (int i = 1; i <= objects.size(); i++) {
                b.append(String.format("%010d 00000 n\r\n", offsets[i]));
            }
            b.append("trailer\n<</Size ").append(objects.size() + 1).append("/Root 1 0 R>>\nstartxref\n").append(xref)
                    .append("\n%%EOF\n");
            write(out, b.toString());
            return out.toByteArray();
        }
    }
}
