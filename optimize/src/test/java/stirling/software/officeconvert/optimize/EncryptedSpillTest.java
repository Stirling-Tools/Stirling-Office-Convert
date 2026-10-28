package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncryptedSpillTest {

    private static final int CHUNK = 1 << 16;

    @TempDir
    Path dir;

    private static byte[] chunk(Random random, long remaining) {
        byte[] b = new byte[(int) Math.min(CHUNK, remaining)];
        random.nextBytes(b);
        return b;
    }

    private Path fixture(String name, int keyLength, boolean aes, long imageBytes) throws IOException {
        Path file = dir.resolve(name + ".pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            COSStream image = document.getDocument().createCOSStream();
            image.setItem(COSName.TYPE, COSName.XOBJECT);
            image.setItem(COSName.SUBTYPE, COSName.IMAGE);
            image.setInt(COSName.WIDTH, 4096);
            image.setInt(COSName.HEIGHT, 4096);
            image.setInt(COSName.BITS_PER_COMPONENT, 8);
            image.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
            image.setItem(COSName.FILTER, COSName.DCT_DECODE);
            Random random = new Random(imageBytes);
            try (OutputStream o = image.createRawOutputStream()) {
                for (long left = imageBytes; left > 0; left -= CHUNK) {
                    o.write(chunk(random, left));
                }
            }
            PDImageXObject x = new PDImageXObject(new org.apache.pdfbox.pdmodel.common.PDStream(image), null);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                cs.drawImage(x, 0, 0, 200, 200);
            }
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", "user", new AccessPermission());
            policy.setEncryptionKeyLength(keyLength);
            policy.setPreferAES(aes);
            document.protect(policy);
            document.save(file.toFile());
        }
        return file;
    }

    private static long rewrite(Path in, Path out) throws IOException {
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().threadId();
        long[] firstWrite = {-1};
        try (PDDocument doc = Loader.loadPDF(in.toFile(), "user");
                OutputStream os = new FilterOutputStream(new BufferedOutputStream(Files.newOutputStream(out), 1 << 16)) {
                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        if (firstWrite[0] < 0) {
                            firstWrite[0] = threads.getThreadAllocatedBytes(id);
                        }
                        out.write(b, off, len);
                    }
                }) {
            Rewrite.run(doc, os, PdfOptimizer.Options.defaults().password("user").alwaysRewrite(true), -1);
            long after = threads.getThreadAllocatedBytes(id);
            return after - firstWrite[0];
        }
    }

    private static void sameImage(Path out, long imageBytes, int keyLength, boolean aes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(out.toFile(), "user")) {
            assertTrue(doc.isEncrypted());
            assertEquals(keyLength, doc.getEncryption().getLength());
            assertEquals(aes ? keyLength == 256 ? 5 : 4 : 2, doc.getEncryption().getVersion());
            PDImageXObject image = null;
            for (COSName name : doc.getPage(0).getResources().getXObjectNames()) {
                image = (PDImageXObject) doc.getPage(0).getResources().getXObject(name);
            }
            COSStream s = image.getCOSObject();
            Random random = new Random(imageBytes);
            try (InputStream in = s.createRawInputStream()) {
                for (long left = imageBytes; left > 0; left -= CHUNK) {
                    byte[] want = chunk(random, left);
                    assertArrayEquals(want, in.readNBytes(want.length));
                }
                assertEquals(-1, in.read());
            }
        }
    }

    @Test
    void largeEncryptedStreamsAreEncryptedWhileCopied() throws Exception {
        long imageBytes = Recoder.MAX_RAW_BYTES + (1 << 20);
        Object[][] kinds = {{"rc4-128", 128, false}, {"aes-128", 128, true}, {"aes-256", 256, true}};
        for (Object[] k : kinds) {
            String name = (String) k[0];
            int keyLength = (Integer) k[1];
            boolean aes = (Boolean) k[2];
            Path in = fixture(name, keyLength, aes, imageBytes);
            Path out = dir.resolve(name + ".optimized.pdf");
            long allocated = rewrite(in, out);
            sameImage(out, imageBytes, keyLength, aes);
            assertTrue(allocated < imageBytes / 2, name + " allocated " + allocated + " bytes writing a "
                    + imageBytes + " byte stream");
            Files.delete(in);
            Files.delete(out);
        }
    }

    @Test
    void streamingEncryptionMatchesTheSecurityHandlerByteForByte() throws Exception {
        Object[][] kinds = {{40, false}, {128, false}, {128, true}, {256, true}};
        Random random = new Random(7);
        for (Object[] k : kinds) {
            int keyLength = (Integer) k[0];
            boolean aes = (Boolean) k[1];
            Path file = fixture("unit-" + keyLength + aes, keyLength, aes, 16);
            try (PDDocument doc = Loader.loadPDF(file.toFile(), "user")) {
                Encryption encryption = Encryption.of(doc, "user");
                for (int size : new int[] {0, 1, 15, 16, 17, 4096, 100_003}) {
                    for (int number : new int[] {1, 300, 70_000, 16_777_300}) {
                        byte[] plain = new byte[size];
                        random.nextBytes(plain);
                        byte[] old = encryption.encrypt(plain, number);
                        assertEquals(old.length, encryption.encryptedLength(size));
                        java.io.ByteArrayOutputStream streamed = new java.io.ByteArrayOutputStream();
                        byte[] iv = aes ? java.util.Arrays.copyOf(old, 16) : new byte[16];
                        try (OutputStream o = encryption.encrypting(streamed, number, iv)) {
                            for (int at = 0; at < size; at += 777) {
                                o.write(plain, at, Math.min(777, size - at));
                            }
                        }
                        assertArrayEquals(old, streamed.toByteArray(), keyLength + " " + aes + " " + size + " " + number);
                    }
                }
            }
        }
    }
}
