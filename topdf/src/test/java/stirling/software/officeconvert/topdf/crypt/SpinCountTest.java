package stirling.software.officeconvert.topdf.crypt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.poifs.crypt.Decryptor;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.testing.Fixtures;

class SpinCountTest {

    @TempDir
    Path dir;

    private static final class Counting extends Decryptor {
        final List<String> tried = new ArrayList<>();

        @Override
        public boolean verifyPassword(String password) {
            tried.add(password);
            return Decryptor.DEFAULT_PASSWORD.equals(password);
        }

        @Override
        public InputStream getDataStream(DirectoryNode dir) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getLength() {
            return 0;
        }

        @Override
        public Decryptor copy() {
            return this;
        }
    }

    private static Counting install(EncryptionInfo info, int spin) {
        info.getVerifier().setSpinCount(spin);
        Counting c = new Counting();
        info.setDecryptor(c);
        return c;
    }

    @Test
    void theBuiltInPasswordIsTriedOnlyAtOfficesOwnSpinCount() {
        EncryptionInfo office = new EncryptionInfo(EncryptionMode.agile);
        Counting atOffice = install(office, Passwords.OFFICE_SPIN_COUNT);
        assertSame(atOffice, Passwords.unlock(office, null));
        assertEquals(List.of(Decryptor.DEFAULT_PASSWORD), atOffice.tried);

        EncryptionInfo costly = new EncryptionInfo(EncryptionMode.agile);
        Counting above = install(costly, Passwords.OFFICE_SPIN_COUNT + 1);
        EncryptedDocumentException none = assertThrows(EncryptedDocumentException.class,
                () -> Passwords.unlock(costly, null));
        assertEquals(Passwords.PROTECTED, none.getMessage());
        assertEquals(List.of(), above.tried);
        assertThrows(EncryptedDocumentException.class, () -> Passwords.unlock(costly, "guess"));
        assertEquals(List.of("guess"), above.tried);
    }

    @Test
    void stretchingPastTheCapIsRefusedWithoutDerivingAKey() {
        EncryptionInfo info = new EncryptionInfo(EncryptionMode.agile);
        Counting c = install(info, Passwords.MAX_SPIN_COUNT + 1);
        EncryptedDocumentException e = assertThrows(EncryptedDocumentException.class,
                () -> Passwords.unlock(info, "pw"));
        assertTrue(e.getMessage().contains("key stretching"), e.getMessage());
        assertEquals(List.of(), c.tried);
        assertEquals(1_000_000, Passwords.MAX_SPIN_COUNT);
    }

    @Test
    void aDocumentWithTheBuiltInPasswordAtAHigherSpinCountNeedsItsPasswordGiven() throws Exception {
        byte[] locked;
        try (POIFSFileSystem fs = new POIFSFileSystem(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            EncryptionInfo info = new EncryptionInfo(EncryptionMode.agile);
            info.getVerifier().setSpinCount(Passwords.OFFICE_SPIN_COUNT * 2);
            Encryptor enc = info.getEncryptor();
            enc.confirmPassword(Decryptor.DEFAULT_PASSWORD);
            try (OutputStream os = enc.getDataStream(fs)) {
                os.write(Fixtures.docx("Stretched"));
            }
            fs.writeFilesystem(out);
            locked = out.toByteArray();
        }
        Path in = Fixtures.write(dir, "stretched.docx", locked);
        OfficeToPdf.Options none = OfficeToPdf.Options.defaults();
        assertEquals(Passwords.PROTECTED, assertThrows(Passwords.Refused.class,
                () -> OfficeToPdf.convert(in, dir.resolve("none.pdf"), none)).getMessage());
        OfficeToPdf.convert(in, dir.resolve("given.pdf"), none.password(Decryptor.DEFAULT_PASSWORD));
    }
}
