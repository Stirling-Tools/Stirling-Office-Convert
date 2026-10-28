package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.junit.jupiter.api.Test;

class StreamBudgetTest {

    private static COSStream forbiddenInput() {
        return new COSStream() {
            @Override
            public InputStream createRawInputStream() {
                throw new AssertionError("Oversized layout reached stream allocation");
            }
        };
    }

    private static byte[] compressed(int size) throws Exception {
        return compressed(size, 0);
    }

    private static byte[] compressed(int size, int level) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(level);
        try (OutputStream z = new DeflaterOutputStream(out, deflater)) {
            byte[] zero = new byte[4096];
            for (int n = 0; n < size; n += zero.length) {
                z.write(zero, 0, Math.min(zero.length, size - n));
            }
        } finally {
            deflater.end();
        }
        return out.toByteArray();
    }

    @Test
    void anUntrustedGigabytePredictorRowIsDeclinedBeforeOpeningInput() throws Exception {
        try (COSStream stream = forbiddenInput(); Spill spill = new Spill()) {
            COSDictionary parms = new COSDictionary();
            parms.setInt(COSName.PREDICTOR, 15);
            parms.setInt(COSName.COLORS, 32);
            parms.setInt(COSName.BITS_PER_COMPONENT, 16);
            parms.setInt(COSName.COLUMNS, 1 << 24);
            stream.setItem(COSName.FILTER, COSName.FLATE_DECODE);
            stream.setItem(COSName.DECODE_PARMS, parms);
            assertNull(Streamed.recode(stream, Recoder.Kind.OTHER, null, 0, spill,
                    MessageDigest.getInstance("SHA-256"), PdfOptimizer.Effort.DEFAULT));
        }
    }

    @Test
    void anUntrustedImageRowCannotOverflowTheTwoRowBuffer() throws Exception {
        try (COSStream stream = forbiddenInput(); Spill spill = new Spill()) {
            assertNull(Streamed.recode(stream, Recoder.Kind.IMAGE, new Predictors.Layout(32, 16, 1 << 24), 1, spill,
                    MessageDigest.getInstance("SHA-256"), PdfOptimizer.Effort.DEFAULT));
        }
    }

    @Test
    void aKnownImageLargerThanTheDecodedBudgetIsDeclinedBeforeInput() throws Exception {
        try (COSStream stream = forbiddenInput(); Spill spill = new Spill()) {
            assertNull(Streamed.recode(stream, Recoder.Kind.IMAGE, new Predictors.Layout(3, 8, 100), 100, spill,
                    MessageDigest.getInstance("SHA-256"), PdfOptimizer.Effort.DEFAULT, 1024, 4096));
        }
    }

    @Test
    void aCompressedExpansionStopsEarlyAndLeavesRawBytesAndDigestUntouched() throws Exception {
        byte[] raw = compressed(2 << 20);
        int[] read = {0};
        try (COSStream stream = new COSStream() {
            @Override
            public InputStream createRawInputStream() {
                return new ByteArrayInputStream(raw) {
                    @Override
                    public synchronized int read(byte[] b, int off, int len) {
                        int n = super.read(b, off, len);
                        if (n > 0) {
                            read[0] += n;
                        }
                        return n;
                    }
                };
            }
        }; Spill spill = new Spill()) {
            stream.setItem(COSName.FILTER, COSName.FLATE_DECODE);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            assertNull(Streamed.recode(stream, Recoder.Kind.OTHER, null, 0, spill, sha,
                    PdfOptimizer.Effort.DEFAULT, 1024, 1 << 16));
            assertTrue(read[0] < raw.length / 2, "Read " + read[0] + " of " + raw.length);
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(), sha.digest());
            assertEquals(COSName.FLATE_DECODE, stream.getItem(COSName.FILTER));
            try (InputStream input = stream.createRawInputStream()) {
                assertArrayEquals(raw, input.readAllBytes());
            }
        }
    }

    @Test
    void cancellationAfterReadingAChunkStopsBeforeCompression() throws Exception {
        byte[] raw = compressed(1 << 16);
        try (COSStream stream = new COSStream() {
            @Override
            public InputStream createRawInputStream() {
                return new ByteArrayInputStream(raw) {
                    @Override
                    public synchronized int read(byte[] b, int off, int len) {
                        int n = super.read(b, off, len);
                        Thread.currentThread().interrupt();
                        return n;
                    }
                };
            }
        }; Spill spill = new Spill()) {
            stream.setItem(COSName.FILTER, COSName.FLATE_DECODE);
            assertThrows(InterruptedIOException.class, () -> Streamed.recode(stream, Recoder.Kind.OTHER, null, 0,
                    spill, MessageDigest.getInstance("SHA-256"), PdfOptimizer.Effort.DEFAULT));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aValidStreamBeyondTheMaterializedDecodeBudgetStillUsesStreaming() throws Exception {
        int size = (int) Recoder.MAX_DECODED_BYTES + (1 << 16);
        byte[] raw = compressed(size, 1);
        try (COSStream stream = new COSStream(); Spill spill = new Spill()) {
            try (OutputStream out = stream.createRawOutputStream()) {
                out.write(raw);
            }
            stream.setItem(COSName.FILTER, COSName.FLATE_DECODE);
            Recoder.Job job = Recoder.prepare(stream, Recoder.Kind.OTHER, null);
            assertNotNull(job);
            assertNull(Recoder.plan(job, PdfOptimizer.Effort.FAST).data());
            Streamed.Result result = Streamed.recode(stream, Recoder.Kind.OTHER, null, 0, spill,
                    MessageDigest.getInstance("SHA-256"), PdfOptimizer.Effort.DEFAULT);
            assertNotNull(result);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            spill.copy(result.spilled(), out);
            try (InputStream in = new java.util.zip.InflaterInputStream(new ByteArrayInputStream(out.toByteArray()))) {
                byte[] buffer = new byte[4096];
                long count = 0;
                int nonzero = 0;
                for (int n; (n = in.read(buffer)) > 0; ) {
                    for (int i = 0; i < n; i++) {
                        nonzero |= buffer[i];
                    }
                    count += n;
                }
                assertEquals(size, count);
                assertEquals(0, nonzero);
            }
        }
    }

    @Test
    void invalidPredictorTagsStopWithoutReadingTheFullExpansion() throws Exception {
        byte[] body = new byte[1 << 18];
        body[0] = 5;
        byte[] raw = Deflate.zlib(body, 0, 0);
        int[] read = {0};
        try (COSStream stream = new COSStream() {
            @Override
            public InputStream createRawInputStream() {
                return new ByteArrayInputStream(raw) {
                    @Override
                    public synchronized int read(byte[] b, int off, int len) {
                        int n = super.read(b, off, len);
                        if (n > 0) {
                            read[0] += n;
                        }
                        return n;
                    }
                };
            }
        }; Spill spill = new Spill()) {
            stream.setItem(COSName.FILTER, COSName.FLATE_DECODE);
            stream.setItem(COSName.DECODE_PARMS, Recoder.predictorParms(new Predictors.Layout(1, 8, 16)));
            assertNull(Streamed.recode(stream, Recoder.Kind.OTHER, null, 0, spill,
                    MessageDigest.getInstance("SHA-256"), PdfOptimizer.Effort.DEFAULT, 128, 1 << 16));
            assertTrue(read[0] < raw.length / 2);
        }
    }

    @Test
    void thePublicApiKeepsAnOverBudgetStreamAndItsFilterBytes() throws Exception {
        int size = (int) Math.min(512L << 20, 4 * Recoder.MAX_DECODED_BYTES) + (1 << 16);
        byte[] raw = compressed(size, 1);
        COSName payload = COSName.getPDFName("BudgetPayload");
        try (org.apache.pdfbox.pdmodel.PDDocument document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            COSStream stream = document.getDocument().createCOSStream();
            try (OutputStream out = stream.createRawOutputStream()) {
                out.write(raw);
            }
            stream.setItem(COSName.FILTER, COSName.FLATE_DECODE);
            stream.setInt(COSName.getPDFName("DL"), size);
            document.getDocumentCatalog().getCOSObject().setItem(payload, stream);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfOptimizer.optimize(document, out,
                    PdfOptimizer.Options.defaults().keepPrivateData(true).alwaysRewrite(true));
            assertEquals(1, document.getNumberOfPages());
            try (org.apache.pdfbox.pdmodel.PDDocument optimized = org.apache.pdfbox.Loader.loadPDF(out.toByteArray())) {
                COSStream kept = (COSStream) optimized.getDocumentCatalog().getCOSObject().getDictionaryObject(payload);
                assertNotNull(kept);
                assertEquals(COSName.FLATE_DECODE, kept.getItem(COSName.FILTER));
                assertEquals(size, kept.getInt(COSName.getPDFName("DL")));
                try (InputStream in = kept.createRawInputStream()) {
                    assertArrayEquals(raw, in.readAllBytes());
                }
            }
        }
    }

    @Test
    void concurrentJobsShareOneDecodeBudgetAndOverBudgetJobsRunAlone() throws Exception {
        assertTrue(Rewrite.WINDOW_DECODED_BYTES * Rewrite.MAX_THREADS <= Recoder.MAX_DECODED_BYTES);
        assertTrue(PdfOptimizer.estimate(0) >= 3 * Recoder.MAX_DECODED_BYTES);
        int size = (int) (Rewrite.WINDOW_DECODED_BYTES * 2);
        byte[] zlib = compressed(size, 9);
        StringBuilder hex = new StringBuilder(zlib.length * 2 + 1);
        for (byte b : zlib) {
            hex.append(String.format("%02X", b & 0xFF));
        }
        byte[] raw = hex.append('>').toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertTrue(raw.length < Rewrite.INLINE_JOB_BYTES);
        COSName payload = COSName.getPDFName("Payload");
        try (org.apache.pdfbox.pdmodel.PDDocument document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            COSStream stream = document.getDocument().createCOSStream();
            try (OutputStream out = stream.createRawOutputStream()) {
                out.write(raw);
            }
            org.apache.pdfbox.cos.COSArray filters = new org.apache.pdfbox.cos.COSArray();
            filters.add(COSName.ASCII_HEX_DECODE);
            filters.add(COSName.FLATE_DECODE);
            stream.setItem(COSName.FILTER, filters);
            Recoder.Job job = Recoder.prepare(stream, Recoder.Kind.OTHER, null);
            assertNull(Recoder.plan(job, PdfOptimizer.Effort.FAST, Rewrite.WINDOW_DECODED_BYTES).data());
            assertNotNull(Recoder.plan(job, PdfOptimizer.Effort.FAST).data());
            document.getDocumentCatalog().getCOSObject().setItem(payload, stream);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfOptimizer.Result result = PdfOptimizer.optimize(document, out,
                    PdfOptimizer.Options.defaults().effort(PdfOptimizer.Effort.FAST).alwaysRewrite(true));
            assertTrue(result.recodedStreams() >= 1);
            try (org.apache.pdfbox.pdmodel.PDDocument optimized = org.apache.pdfbox.Loader.loadPDF(out.toByteArray())) {
                COSStream kept = (COSStream) optimized.getDocumentCatalog().getCOSObject().getDictionaryObject(payload);
                assertEquals(COSName.FLATE_DECODE, kept.getItem(COSName.FILTER));
                try (InputStream in = kept.createInputStream()) {
                    assertEquals(size, in.readAllBytes().length);
                }
            }
        }
    }

    @Test
    void boundedRowsStillRoundTripPredictedData() throws Exception {
        byte[] pixels = new byte[64 * 128];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (byte) (i % 64);
        }
        Predictors.Layout layout = new Predictors.Layout(1, 8, 64);
        byte[] encoded = Predictors.encodePng(pixels, layout, Predictors.UP);
        try (COSStream stream = new COSStream(); Spill spill = new Spill()) {
            try (OutputStream out = stream.createRawOutputStream()) {
                out.write(Deflate.zlib(encoded, 0, 0));
            }
            stream.setItem(COSName.FILTER, COSName.FLATE_DECODE);
            stream.setItem(COSName.DECODE_PARMS, Recoder.predictorParms(layout));
            Streamed.Result result = Streamed.recode(stream, Recoder.Kind.IMAGE, layout, 128, spill,
                    MessageDigest.getInstance("SHA-256"), PdfOptimizer.Effort.DEFAULT, 128, 1 << 16);
            assertNotNull(result);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            spill.copy(result.spilled(), out);
            byte[] decoded = Filters.inflate(out.toByteArray(), 1 << 16);
            assertArrayEquals(pixels, Predictors.decode(decoded, (COSDictionary) result.replace().get(COSName.DECODE_PARMS), 1 << 16));
        }
    }
}
