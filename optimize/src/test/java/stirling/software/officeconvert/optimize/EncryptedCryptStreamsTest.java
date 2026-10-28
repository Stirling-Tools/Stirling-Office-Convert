package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.memory.Admission;

class EncryptedCryptStreamsTest {

    private static final String XML = "<?xpacket begin=''?><x:xmpmeta xmlns:x='adobe:ns:meta/'>"
            + "<value>Private metadata</value></x:xmpmeta><?xpacket end='w'?>";

    private static final byte[] CONTENT = "0 0 1 rg 10 10 50 50 re f".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path dir;

    private static String syntax(COSBase value) throws IOException {
        Bytes bytes = new Bytes(1024);
        new Syntax((ref, writer) -> writer.value(ref.getObject())).into(bytes).value(value);
        return new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private Path fixture(String role, COSName filter, boolean metadataEncrypted, boolean reachable) throws Exception {
        ByteArrayOutputStream template = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            AccessPermission permissions = new AccessPermission();
            permissions.setCanPrint(false);
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", "user", permissions);
            policy.setEncryptionKeyLength(128);
            policy.setPreferAES(true);
            document.protect(policy);
            document.save(template);
        }
        String name = role + "-" + (filter == null ? "default" : filter.getName()) + "-" + metadataEncrypted + reachable;
        try (PDDocument templateDocument = Loader.loadPDF(template.toByteArray(), "owner")) {
            if (!metadataEncrypted) {
                var dictionary = templateDocument.getEncryption();
                byte[] id = ((org.apache.pdfbox.cos.COSString) templateDocument.getDocument().getDocumentID()
                        .getObject(0)).getBytes();
                byte[] user = new org.apache.pdfbox.pdmodel.encryption.StandardSecurityHandler().computeUserPassword(
                        "user".getBytes(StandardCharsets.ISO_8859_1), dictionary.getOwnerKey(),
                        dictionary.getPermissions(), id, dictionary.getRevision(), dictionary.getLength() / 8, false);
                dictionary.setUserKey(user);
                dictionary.getCOSObject().setBoolean(COSName.ENCRYPT_META_DATA, false);
            }
            Encryption encryption = Encryption.of(templateDocument, "owner");
            assertEquals(metadataEncrypted, templateDocument.getEncryption().isEncryptMetaData());
            if (filter != null && !filter.equals(COSName.IDENTITY)) {
                COSDictionary cf = encryption.dictionary.getCOSDictionary(COSName.CF);
                cf.setItem(filter, cf.getItem(COSName.STD_CF));
            }
            RawPdf raw = RawPdf.page("", new String(CONTENT, StandardCharsets.US_ASCII));
            raw.header = "%PDF-1.7\n";
            raw.objects.set(3, RawPdf.stream("", encryption.encrypt(CONTENT, 4)));
            String crypt = filter == null ? "" : "/Filter/Crypt/DecodeParms<</Name/" + filter.getName() + ">>";
            byte[] plain = role.equals("metadata") ? XML.getBytes(StandardCharsets.UTF_8)
                    : role.equals("content") ? CONTENT : new byte[16];
            byte[] encoded = !metadataEncrypted && role.equals("metadata") ? plain
                    : COSName.IDENTITY.equals(filter) ? role.equals("metadata") ? plain : encryption.encrypt(plain, 5)
                    : encryption.encrypt(plain, 5);
            String dictionary = crypt;
            if (role.equals("metadata")) {
                dictionary += "/Type/Metadata/Subtype/XML";
            } else if (role.equals("image")) {
                int width = COSName.IDENTITY.equals(filter) ? encoded.length / 3 : 4;
                dictionary += "/Type/XObject/Subtype/Image/Width " + width
                        + "/Height 1/ColorSpace/DeviceRGB/BitsPerComponent 8";
                if (!COSName.IDENTITY.equals(filter)) {
                    plain = new byte[12];
                    encoded = encryption.encrypt(plain, 5);
                }
                raw.set(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Resources<</XObject<</Im 5 0 R>>>>"
                        + "/Contents 4 0 R>>");
                byte[] content = "q 80 0 0 20 10 100 cm /Im Do Q".getBytes(StandardCharsets.US_ASCII);
                raw.objects.set(3, RawPdf.stream("", encryption.encrypt(content, 4)));
            }
            if (role.equals("content")) {
                raw.set(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Contents 5 0 R>>");
            }
            raw.add(RawPdf.stream(dictionary, encoded));
            if (reachable && !role.equals("image")) {
                raw.set(1, "<</Type/Catalog/Pages 2 0 R/" + (role.equals("metadata") ? "Metadata" : "Payload")
                        + " 5 0 R>>");
            }
            int encrypt = raw.add(syntax(encryption.dictionary));
            raw.trailerExtra = "/Encrypt " + encrypt + " 0 R/ID"
                    + syntax(templateDocument.getDocument().getDocumentID());
            Files.write(dir.resolve(name + ".expected.bin"), plain);
            if (COSName.IDENTITY.equals(filter) && !role.equals("metadata")) {
                Files.write(dir.resolve(name + ".identity.bin"), encoded);
            }
            return raw.write(dir, name);
        }
    }

    private void refuses(Path input) throws Exception {
        for (String password : List.of("owner", "user")) {
            Path output = dir.resolve(input.getFileName() + "-" + password + ".pdf");
            byte[] sentinel = "existing output".getBytes(StandardCharsets.UTF_8);
            Files.write(output, sentinel);
            int running = Admission.jvm().running();
            IOException failure = assertThrows(IOException.class,
                    () -> PdfOptimizer.optimize(input, output,
                            PdfOptimizer.Options.defaults().password(password).alwaysRewrite(true)));
            assertTrue(failure.getMessage().contains("crypt filters"), failure.toString());
            assertArrayEquals(sentinel, Files.readAllBytes(output));
            assertEquals(running, Admission.jvm().running());
        }
        try (var files = Files.list(dir)) {
            assertFalse(files.anyMatch(file -> file.getFileName().toString().endsWith(".part")));
        }
    }

    @Test
    void identityFiltersOnNonContentStreamsAreRefused() throws Exception {
        for (String role : List.of("auxiliary", "image", "metadata")) {
            refuses(fixture(role, COSName.IDENTITY, true, true));
        }
    }

    @Test
    void namedFiltersOnNonContentStreamsAreRefused() throws Exception {
        for (String role : List.of("auxiliary", "image", "metadata")) {
            refuses(fixture(role, COSName.getPDFName("NamedCF"), true, true));
        }
    }

    @Test
    void namedContentFiltersRemainRejected() throws Exception {
        refuses(fixture("content", COSName.getPDFName("NamedCF"), true, true));
    }

    @Test
    void unencryptedIdentityMetadataStillRoundTrips() throws Exception {
        Path input = dir.resolve("unencrypted-identity.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            PDMetadata metadata = new PDMetadata(document);
            metadata.importXMPMetadata(XML.getBytes(StandardCharsets.UTF_8));
            metadata.getCOSObject().setItem(COSName.FILTER, COSName.CRYPT);
            COSDictionary parameters = new COSDictionary();
            parameters.setItem(COSName.NAME, COSName.IDENTITY);
            metadata.getCOSObject().setItem(COSName.DECODE_PARMS, parameters);
            document.getDocumentCatalog().setMetadata(metadata);
            document.save(input.toFile());
        }
        Path output = dir.resolve("unencrypted-identity-optimized.pdf");
        PdfOptimizer.optimize(input, output, PdfOptimizer.Options.defaults().alwaysRewrite(true));
        try (PDDocument document = Loader.loadPDF(output.toFile())) {
            assertFalse(document.isEncrypted());
            assertEquals(XML, new String(document.getDocumentCatalog().getMetadata().toByteArray(),
                    StandardCharsets.UTF_8));
        }
        assertSameAppearance(input, output, "");
    }

    @Test
    void plaintextMetadataWithIdentityFilterRemainsPlaintext() throws Exception {
        Path input = fixture("metadata", COSName.IDENTITY, false, true);
        for (String password : List.of("owner", "user")) {
            Path output = dir.resolve("plain-metadata-" + password + ".pdf");
            PdfOptimizer.optimize(input, output, PdfOptimizer.Options.defaults().password(password).alwaysRewrite(true));
            try (PDDocument document = Loader.loadPDF(output.toFile(), password)) {
                assertTrue(document.isEncrypted());
                assertFalse(document.getEncryption().isEncryptMetaData());
                assertEquals(XML, new String(document.getDocumentCatalog().getMetadata().toByteArray(),
                        StandardCharsets.UTF_8));
            }
            assertSameAppearance(input, output, password);
        }
    }

    @Test
    void unreachableCryptStreamsDoNotPreventARewrite() throws Exception {
        Path input = fixture("auxiliary", COSName.IDENTITY, true, false);
        Path output = dir.resolve("unreachable-optimized.pdf");
        PdfOptimizer.optimize(input, output, PdfOptimizer.Options.defaults().password("user").alwaysRewrite(true));
        assertSameAppearance(input, output, "user");
    }

    @Test
    void ordinaryEncryptedStreamsStillRoundTrip() throws Exception {
        for (String role : List.of("auxiliary", "image", "metadata")) {
            Path input = fixture(role, null, true, true);
            for (String password : List.of("owner", "user")) {
                Path output = dir.resolve("ordinary-" + role + "-" + password + ".pdf");
                PdfOptimizer.optimize(input, output,
                        PdfOptimizer.Options.defaults().password(password).alwaysRewrite(true));
                try (PDDocument document = Loader.loadPDF(output.toFile(), "user")) {
                    assertTrue(document.isEncrypted());
                    assertFalse(document.getCurrentAccessPermission().canPrint());
                    if (role.equals("metadata")) {
                        assertEquals(XML, new String(document.getDocumentCatalog().getMetadata().toByteArray(),
                                StandardCharsets.UTF_8));
                    }
                }
                assertSameAppearance(input, output, password);
            }
        }
    }

    @Test
    void anEncryptedBorrowedDocumentRemainsOpenAfterRefusal() throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            PDMetadata metadata = new PDMetadata(document);
            metadata.importXMPMetadata(XML.getBytes(StandardCharsets.UTF_8));
            metadata.getCOSObject().setItem(COSName.FILTER, COSName.CRYPT);
            COSDictionary parameters = new COSDictionary();
            parameters.setItem(COSName.NAME, COSName.IDENTITY);
            metadata.getCOSObject().setItem(COSName.DECODE_PARMS, parameters);
            document.getDocumentCatalog().setMetadata(metadata);
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", "user", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            policy.setPreferAES(true);
            document.protect(policy);
            IOException failure = assertThrows(IOException.class,
                    () -> PdfOptimizer.optimize(document, new ByteArrayOutputStream(),
                            PdfOptimizer.Options.defaults().password("owner")));
            assertTrue(failure.getMessage().contains("crypt filters"), failure.toString());
            assertFalse(document.getDocument().isClosed());
            assertEquals(COSName.CRYPT, metadata.getCOSObject().getCOSName(COSName.FILTER));
            assertEquals(1, document.getNumberOfPages());
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder("<");
        for (byte x : bytes) {
            b.append(String.format("%02X", x & 0xFF));
        }
        return b.append('>').toString();
    }

    @Test
    void stringsThatJoinTheSameStaySeparate() throws Exception {
        ByteArrayOutputStream template = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", "user", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            policy.setPreferAES(true);
            document.protect(policy);
            document.save(template);
        }
        Path input;
        try (PDDocument templateDocument = Loader.loadPDF(template.toByteArray(), "owner")) {
            Encryption encryption = Encryption.of(templateDocument, "owner");
            RawPdf raw = RawPdf.page("", "");
            raw.header = "%PDF-1.7\n";
            raw.objects.set(3, RawPdf.stream("", encryption.encrypt(new byte[0], 4)));
            raw.set(1, "<</Type/Catalog/Pages 2 0 R/First 5 0 R/Second 6 0 R>>");
            raw.add("[" + hex(encryption.string(new byte[] {'a', 0}, 5)) + " "
                    + hex(encryption.string(new byte[] {'b'}, 5)) + "]");
            raw.add("[" + hex(encryption.string(new byte[] {'a'}, 6)) + " "
                    + hex(encryption.string(new byte[] {0, 'b'}, 6)) + "]");
            int encrypt = raw.add(syntax(encryption.dictionary));
            raw.trailerExtra = "/Encrypt " + encrypt + " 0 R/ID"
                    + syntax(templateDocument.getDocument().getDocumentID());
            input = raw.write(dir, "joined-strings");
        }
        Path output = dir.resolve("joined-strings-optimized.pdf");
        PdfOptimizer.optimize(input, output, PdfOptimizer.Options.defaults().password("owner").alwaysRewrite(true));
        try (PDDocument document = Loader.loadPDF(output.toFile(), "user")) {
            COSDictionary catalog = document.getDocumentCatalog().getCOSObject();
            var first = catalog.getCOSArray(COSName.getPDFName("First"));
            var second = catalog.getCOSArray(COSName.getPDFName("Second"));
            assertArrayEquals(new byte[] {'a', 0}, ((org.apache.pdfbox.cos.COSString) first.getObject(0)).getBytes());
            assertArrayEquals(new byte[] {'a'}, ((org.apache.pdfbox.cos.COSString) second.getObject(0)).getBytes());
            assertArrayEquals(new byte[] {0, 'b'}, ((org.apache.pdfbox.cos.COSString) second.getObject(1)).getBytes());
        }
    }

    private static void assertSameAppearance(Path input, Path output, String password) throws Exception {
        try (PDDocument before = Loader.loadPDF(input.toFile(), password);
                PDDocument after = Loader.loadPDF(output.toFile(), password)) {
            assertEquals(new PDFTextStripper().getText(before), new PDFTextStripper().getText(after));
            var a = new PDFRenderer(before).renderImageWithDPI(0, 72);
            var b = new PDFRenderer(after).renderImageWithDPI(0, 72);
            assertArrayEquals(a.getRGB(0, 0, a.getWidth(), a.getHeight(), null, 0, a.getWidth()),
                    b.getRGB(0, 0, b.getWidth(), b.getHeight(), null, 0, b.getWidth()));
        }
    }
}
