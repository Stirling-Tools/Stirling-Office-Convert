package stirling.software.officeconvert.topdf.odf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.crypt.Argon2id;
import stirling.software.officeconvert.topdf.crypt.Passwords;
import stirling.software.officeconvert.topdf.testing.Allocation;

class OdfPasswordTest {

    private static final String PASSWORD = "s3crét pass";

    private static final String MANIFEST_NS = "urn:oasis:names:tc:opendocument:xmlns:manifest:1.0";

    private static final SecureRandom RANDOM = new SecureRandom();

    enum Scheme {
        AES_CBC,
        BLOWFISH_CFB,
        WHOLE_PACKAGE
    }

    @TempDir
    Path dir;

    private static byte[] textPackage(String words) {
        return OdfFixtures.odf(OdfFixtures.TEXT, OdfFixtures.content("", OdfFixtures.text("<text:p>" + words
                + "</text:p>")), null);
    }

    private static Map<String, byte[]> parts(byte[] zip) throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null;) {
                parts.put(e.getName(), in.readAllBytes());
            }
        }
        return parts;
    }

    private static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1 << 16];
        while (!d.finished()) {
            out.write(buffer, 0, d.deflate(buffer));
        }
        d.end();
        return out.toByteArray();
    }

    private static byte[] pbkdf2(byte[] secret, byte[] salt, int iterations, int length)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret, "HmacSHA1"));
        byte[] out = new byte[length];
        for (int block = 1, pos = 0; pos < length; block++, pos += 20) {
            mac.update(salt);
            byte[] u = mac.doFinal(new byte[] {0, 0, 0, (byte) block});
            byte[] t = u.clone();
            for (int i = 1; i < iterations; i++) {
                u = mac.doFinal(u);
                for (int j = 0; j < t.length; j++) {
                    t[j] ^= u[j];
                }
            }
            System.arraycopy(t, 0, out, pos, Math.min(20, length - pos));
        }
        return out;
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private record Sealed(byte[] data, String encryptionData) {}

    private static byte[] deflatedZeros(long count) {
        Deflater d = new Deflater(Deflater.BEST_SPEED, true);
        byte[] zeros = new byte[1 << 20];
        byte[] buffer = new byte[1 << 16];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (long fed = 0; fed < count; fed += zeros.length) {
            d.setInput(zeros);
            while (!d.needsInput()) {
                out.write(buffer, 0, d.deflate(buffer));
            }
        }
        d.finish();
        while (!d.finished()) {
            out.write(buffer, 0, d.deflate(buffer));
        }
        d.end();
        return out.toByteArray();
    }

    private static Sealed seal(byte[] deflated, Scheme scheme, String password) throws Exception {
        byte[] utf8 = password.getBytes(StandardCharsets.UTF_8);
        byte[] salt = random(16);
        if (scheme == Scheme.AES_CBC) {
            byte[] iv = random(16);
            byte[] key = pbkdf2(MessageDigest.getInstance("SHA-256").digest(utf8), salt, 1000, 32);
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            String data = "<manifest:encryption-data manifest:checksum-type=\"urn:oasis:names:tc:opendocument:xmlns:"
                    + "manifest:1.0#sha256-1k\" manifest:checksum=\"" + b64(checksum("SHA-256", deflated)) + "\">"
                    + "<manifest:algorithm manifest:algorithm-name=\"http://www.w3.org/2001/04/xmlenc#aes256-cbc\""
                    + " manifest:initialisation-vector=\"" + b64(iv) + "\"/><manifest:start-key-generation"
                    + " manifest:start-key-generation-name=\"http://www.w3.org/2000/09/xmldsig#sha256\""
                    + " manifest:key-size=\"32\"/><manifest:key-derivation manifest:key-derivation-name=\"PBKDF2\""
                    + " manifest:iteration-count=\"1000\" manifest:salt=\"" + b64(salt) + "\" manifest:key-size=\"32\"/>"
                    + "</manifest:encryption-data>";
            return new Sealed(c.doFinal(deflated), data);
        }
        if (scheme == Scheme.BLOWFISH_CFB) {
            byte[] iv = random(8);
            byte[] key = pbkdf2(MessageDigest.getInstance("SHA-1").digest(utf8), salt, 1024, 16);
            Cipher c = Cipher.getInstance("Blowfish/CFB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "Blowfish"), new IvParameterSpec(iv));
            String data = "<manifest:encryption-data manifest:checksum-type=\"SHA1/1K\" manifest:checksum=\""
                    + b64(checksum("SHA-1", deflated)) + "\"><manifest:algorithm manifest:algorithm-name=\"Blowfish"
                    + " CFB\" manifest:initialisation-vector=\"" + b64(iv) + "\"/><manifest:key-derivation"
                    + " manifest:key-derivation-name=\"PBKDF2\" manifest:iteration-count=\"1024\" manifest:salt=\""
                    + b64(salt) + "\"/></manifest:encryption-data>";
            return new Sealed(c.doFinal(deflated), data);
        }
        byte[] iv = random(12);
        byte[] key = Argon2id.hash(MessageDigest.getInstance("SHA-256").digest(utf8), salt, 1, 64, 2, 32);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(iv);
        data.write(c.doFinal(deflated));
        String meta = "<manifest:encryption-data><manifest:algorithm manifest:algorithm-name=\"http://www.w3.org/2009/"
                + "xmlenc11#aes256-gcm\" manifest:initialisation-vector=\"" + b64(iv) + "\"/>"
                + "<manifest:start-key-generation manifest:start-key-generation-name=\"http://www.w3.org/2001/04/"
                + "xmlenc#sha256\" manifest:key-size=\"32\"/><manifest:key-derivation manifest:key-derivation-name="
                + "\"urn:org:documentfoundation:names:experimental:office:manifest:argon2id\" loext:argon2-iterations="
                + "\"1\" loext:argon2-memory=\"64\" loext:argon2-lanes=\"2\" manifest:salt=\"" + b64(salt)
                + "\" manifest:key-size=\"32\"/></manifest:encryption-data>";
        return new Sealed(data.toByteArray(), meta);
    }

    private static byte[] checksum(String digest, byte[] deflated) throws GeneralSecurityException {
        return MessageDigest.getInstance(digest).digest(Arrays.copyOf(deflated, Math.min(1024, deflated.length)));
    }

    private static byte[] encrypt(byte[] pkg, Scheme scheme, String password) throws Exception {
        return encrypt(pkg, scheme, password, Map.of());
    }

    private static byte[] encrypt(byte[] pkg, Scheme scheme, String password, Map<String, byte[]> bombs)
            throws Exception {
        Map<String, byte[]> parts = parts(pkg);
        String mimetype = new String(parts.get("mimetype"), StandardCharsets.US_ASCII);
        StringBuilder manifest = new StringBuilder("<manifest:manifest xmlns:manifest=\"" + MANIFEST_NS + "\""
                + " xmlns:loext=\"urn:org:documentfoundation:names:experimental:office:xmlns:loext:1.0\">"
                + "<manifest:file-entry manifest:full-path=\"/\" manifest:media-type=\"" + mimetype + "\"/>");
        Map<String, byte[]> sealed = new LinkedHashMap<>();
        if (scheme == Scheme.WHOLE_PACKAGE) {
            Sealed s = seal(deflate(pkg), scheme, password);
            sealed.put(OdfEncryption.WHOLE_PACKAGE, s.data());
            manifest.append(entry(OdfEncryption.WHOLE_PACKAGE, pkg.length, s.encryptionData()));
        } else {
            for (Map.Entry<String, byte[]> p : parts.entrySet()) {
                if (p.getKey().equals("mimetype") || p.getKey().startsWith("META-INF/")) {
                    continue;
                }
                Sealed s = seal(deflate(p.getValue()), scheme, password);
                sealed.put(p.getKey(), s.data());
                manifest.append(entry(p.getKey(), p.getValue().length, s.encryptionData()));
            }
            for (Map.Entry<String, byte[]> b : bombs.entrySet()) {
                Sealed s = seal(b.getValue(), scheme, password);
                sealed.put(b.getKey(), s.data());
                manifest.append(entry(b.getKey(), 1, s.encryptionData()));
            }
        }
        manifest.append("</manifest:manifest>");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            stored(zip, "mimetype", parts.get("mimetype"));
            for (Map.Entry<String, byte[]> p : sealed.entrySet()) {
                stored(zip, p.getKey(), p.getValue());
            }
            zip.putNextEntry(new ZipEntry("META-INF/manifest.xml"));
            zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String entry(String path, int size, String encryptionData) {
        return "<manifest:file-entry manifest:full-path=\"" + path + "\" manifest:media-type=\"text/xml\""
                + " manifest:size=\"" + size + "\">" + encryptionData + "</manifest:file-entry>";
    }

    private static void stored(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        e.setCrc(crc.getValue());
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }

    private static Map<String, byte[]> rewrite(Path source, String password) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OdfPackage.write(source, out, null, password);
        return parts(out.toByteArray());
    }

    private String pdfText(Path in, String password) throws IOException {
        Path pdf = dir.resolve(in.getFileName() + ".pdf");
        OfficeToPdf.convert(in, pdf, OfficeToPdf.Options.defaults().password(password));
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void everySchemeOpensWithItsPasswordAndMatchesThePlainDocument() throws Exception {
        byte[] plain = textPackage("Quarterly figures stay private");
        Path open = OdfFixtures.write(dir, "plain.odt", plain);
        Map<String, byte[]> expected = rewrite(open, null);
        for (Scheme scheme : Scheme.values()) {
            Path sealed = OdfFixtures.write(dir, scheme + ".odt", encrypt(plain, scheme, PASSWORD));
            Map<String, byte[]> actual = rewrite(sealed, PASSWORD);
            assertEquals(expected.keySet(), actual.keySet(), scheme.name());
            for (String name : expected.keySet()) {
                assertArrayEquals(expected.get(name), actual.get(name), scheme + " " + name);
            }
        }
    }

    @Test
    void textSpreadsheetAndPresentationConvertToTheSamePdfText() throws Exception {
        Map<String, byte[]> kinds = new LinkedHashMap<>();
        kinds.put("doc.odt", textPackage("A protected letter"));
        kinds.put("book.ods", OdfFixtures.odf(OdfFixtures.SPREADSHEET, OdfFixtures.content("", "<office:spreadsheet>"
                + "<table:table table:name=\"Budget\"><table:table-row><table:table-cell office:value-type=\"string\">"
                + "<text:p>Ink</text:p></table:table-cell><table:table-cell office:value-type=\"float\""
                + " office:value=\"40\"><text:p>40</text:p></table:table-cell></table:table-row></table:table>"
                + "</office:spreadsheet>"), null));
        kinds.put("deck.odp", OdfFixtures.odf(OdfFixtures.PRESENTATION, OdfFixtures.content("", "<office:presentation>"
                + "<draw:page draw:name=\"One\"><draw:frame svg:x=\"2cm\" svg:y=\"2cm\" svg:width=\"20cm\""
                + " svg:height=\"3cm\"><draw:text-box><text:p>Confidential slide</text:p></draw:text-box></draw:frame>"
                + "</draw:page></office:presentation>"), null));
        for (Map.Entry<String, byte[]> k : kinds.entrySet()) {
            String plain = pdfText(OdfFixtures.write(dir, "plain-" + k.getKey(), k.getValue()), null);
            Path sealed = OdfFixtures.write(dir, "sealed-" + k.getKey(), encrypt(k.getValue(), Scheme.AES_CBC,
                    PASSWORD));
            assertFalse(plain.isBlank(), k.getKey());
            assertEquals(plain, pdfText(sealed, PASSWORD), k.getKey());
        }
    }

    @Test
    void aMissingPasswordAndAWrongOneFailWithDifferentReasons() throws Exception {
        byte[] plain = textPackage("Nothing to see");
        for (Scheme scheme : Scheme.values()) {
            Path sealed = OdfFixtures.write(dir, scheme + ".odt", encrypt(plain, scheme, PASSWORD));
            Passwords.Refused none = assertThrows(Passwords.Refused.class,
                    () -> OdfPackage.write(sealed, new ByteArrayOutputStream(), null, null));
            assertEquals(Passwords.PROTECTED, none.getMessage(), scheme.name());
            Passwords.Refused wrong = assertThrows(Passwords.Refused.class,
                    () -> OdfPackage.write(sealed, new ByteArrayOutputStream(), null, "not it"));
            assertEquals(Passwords.WRONG, wrong.getMessage(), scheme.name());
            Passwords.Refused viaPdf = assertThrows(Passwords.Refused.class, () -> pdfText(sealed, "not it"));
            assertFalse(viaPdf.getMessage().contains("not it"));
        }
    }

    @Test
    void anEncryptedPartIsStillCappedWhenItInflates() throws Exception {
        String content = OdfFixtures.content("", OdfFixtures.text("<text:p>picture below</text:p>"));
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("content.xml", content.getBytes(StandardCharsets.UTF_8));
        parts.put("Pictures/small.png", new byte[] {1, 2, 3});
        Path sealed = OdfFixtures.write(dir, "bomb.odt", encrypt(OdfFixtures.zip(OdfFixtures.TEXT, parts),
                Scheme.AES_CBC, PASSWORD, Map.of("Pictures/bomb.png", deflatedZeros(1L << 30))));
        byte[][] pictures = new byte[2][];
        Allocation.Measured m = Allocation.measure(() -> {
            try (OdfDocument doc = OdfDocument.open(sealed, PASSWORD)) {
                pictures[0] = doc.picture("Pictures/small.png");
                pictures[1] = doc.picture("Pictures/bomb.png");
            }
        });
        assertNull(m.failure(), String.valueOf(m.failure()));
        assertArrayEquals(new byte[] {1, 2, 3}, pictures[0]);
        assertNull(pictures[1]);
        assertTrue(m.bytes() < 3L * OdfDocument.MAX_PICTURE_BYTES, "allocated " + m.megabytes() + " MB");
    }

    @Test
    void aPictureReadManyTimesStretchesItsKeyOnce() throws Exception {
        int iterations = 5_000_000;
        byte[] salt = random(16);
        byte[] key = pbkdf2(MessageDigest.getInstance("SHA-256").digest(PASSWORD.getBytes(StandardCharsets.UTF_8)),
                salt, iterations, 16);
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("content.xml", OdfFixtures.content("", OdfFixtures.text("<text:p>logo</text:p>"))
                .getBytes(StandardCharsets.UTF_8));
        parts.put("Pictures/logo.png", new byte[] {7, 8, 9});
        StringBuilder manifest = new StringBuilder("<manifest:manifest xmlns:manifest=\"" + MANIFEST_NS + "\">"
                + "<manifest:file-entry manifest:full-path=\"/\" manifest:media-type=\"" + OdfFixtures.TEXT + "\"/>");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            stored(zip, "mimetype", OdfFixtures.TEXT.getBytes(StandardCharsets.US_ASCII));
            for (Map.Entry<String, byte[]> p : parts.entrySet()) {
                byte[] iv = random(16);
                byte[] deflated = deflate(p.getValue());
                Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
                stored(zip, p.getKey(), c.doFinal(deflated));
                manifest.append(entry(p.getKey(), p.getValue().length, "<manifest:encryption-data"
                        + " manifest:checksum-type=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0#sha256-1k\""
                        + " manifest:checksum=\"" + b64(checksum("SHA-256", deflated)) + "\"><manifest:algorithm"
                        + " manifest:algorithm-name=\"http://www.w3.org/2001/04/xmlenc#aes128-cbc\""
                        + " manifest:initialisation-vector=\"" + b64(iv) + "\"/><manifest:start-key-generation"
                        + " manifest:start-key-generation-name=\"http://www.w3.org/2000/09/xmldsig#sha256\"/>"
                        + "<manifest:key-derivation manifest:key-derivation-name=\"PBKDF2\" manifest:iteration-count=\""
                        + iterations + "\" manifest:salt=\"" + b64(salt) + "\" manifest:key-size=\"16\"/>"
                        + "</manifest:encryption-data>"));
            }
            zip.putNextEntry(new ZipEntry("META-INF/manifest.xml"));
            zip.write((manifest + "</manifest:manifest>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path sealed = OdfFixtures.write(dir, "logo.odt", bytes.toByteArray());
        try (OdfDocument doc = OdfDocument.open(sealed, PASSWORD)) {
            for (int slide = 0; slide < 25; slide++) {
                assertArrayEquals(new byte[] {7, 8, 9}, doc.picture("Pictures/logo.png"), "slide " + slide);
            }
        }
    }

    @Test
    void argon2WorkIsCappedAcrossTheWholeDocument() {
        String heavy = "<manifest:encryption-data><manifest:algorithm manifest:algorithm-name=\"http://www.w3.org/"
                + "2009/xmlenc11#aes256-gcm\" manifest:initialisation-vector=\"" + b64(random(12)) + "\"/>"
                + "<manifest:start-key-generation manifest:start-key-generation-name=\"http://www.w3.org/2001/04/"
                + "xmlenc#sha256\"/><manifest:key-derivation manifest:key-derivation-name=\"urn:org:"
                + "documentfoundation:names:experimental:office:manifest:argon2id\" loext:argon2-iterations=\""
                + OdfEncryption.MAX_ARGON2_PASSES + "\" loext:argon2-memory=\"" + OdfEncryption.MAX_ARGON2_KIB
                + "\" loext:argon2-lanes=\"4\" manifest:salt=\"%s\" manifest:key-size=\"32\"/>"
                + "</manifest:encryption-data>";
        String manifest = "<manifest:manifest xmlns:manifest=\"" + MANIFEST_NS + "\" xmlns:loext=\"urn:org:"
                + "documentfoundation:names:experimental:office:xmlns:loext:1.0\">"
                + entry("content.xml", 1, heavy.formatted(b64(random(16))))
                + entry("styles.xml", 1, heavy.formatted(b64(random(16)))) + "</manifest:manifest>";
        IOException e = assertThrows(IOException.class,
                () -> OdfEncryption.read(manifest.getBytes(StandardCharsets.UTF_8), PASSWORD));
        assertTrue(e.getMessage().contains("more key stretching"), e.getMessage());
    }

    @Test
    void theDecryptedPackageIsDeletedWhenTheOuterZipFailsToClose() throws Exception {
        Path sealed = OdfFixtures.write(dir, "whole.odt", encrypt(textPackage("Board minutes"), Scheme.WHOLE_PACKAGE,
                PASSWORD));
        OdfDocument doc = OdfDocument.open(sealed, PASSWORD);
        Path plaintext = doc.scratch;
        assertTrue(Files.exists(plaintext));
        IOException e = assertThrows(IOException.class, () -> OdfDocument.adopt(doc, () -> {
            throw new IOException("close failed");
        }));
        assertEquals("close failed", e.getMessage());
        assertFalse(Files.exists(plaintext));
    }

    @Test
    void anUnencryptedDocumentIgnoresAGivenPassword() throws Exception {
        Path open = OdfFixtures.write(dir, "plain.odt", textPackage("Open to all"));
        assertTrue(new String(rewrite(open, null).get("word/document.xml"), StandardCharsets.UTF_8)
                .contains("Open to all"));
        assertTrue(new String(rewrite(open, "unused").get("word/document.xml"), StandardCharsets.UTF_8)
                .contains("Open to all"));
    }
}
