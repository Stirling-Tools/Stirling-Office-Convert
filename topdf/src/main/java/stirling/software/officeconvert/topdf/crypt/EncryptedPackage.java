package stirling.software.officeconvert.topdf.crypt;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.poifs.crypt.Decryptor;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/** A password protected Office Open XML document (Agile or Standard encryption, [MS-OFFCRYPTO]): an OLE2 file whose
 * EncryptedPackage stream holds the zip package. */
public final class EncryptedPackage {

    private static final byte[] OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A,
        (byte) 0xE1};

    private EncryptedPackage() {}

    public static boolean is(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            if (!java.util.Arrays.equals(in.readNBytes(OLE2.length), OLE2)) {
                return false;
            }
        }
        try (POIFSFileSystem fs = new POIFSFileSystem(file.toFile(), true)) {
            return is(fs.getRoot());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static boolean is(DirectoryNode root) {
        return root.hasEntryCaseInsensitive("EncryptionInfo") && root.hasEntryCaseInsensitive("EncryptedPackage");
    }

    /** Decrypts the package into {@code target}, at most {@code maxBytes} of it; an IOException with the plain reason
     * when the password is missing or wrong. */
    public static void decrypt(Path source, String password, Path target, long maxBytes) throws IOException {
        try (POIFSFileSystem fs = new POIFSFileSystem(source.toFile(), true)) {
            Decryptor d;
            try {
                d = Passwords.unlock(new EncryptionInfo(fs), password);
            } catch (EncryptedDocumentException e) {
                throw new Passwords.Refused(e.getMessage(), e);
            } catch (IOException | RuntimeException e) {
                throw new IOException("The document's encryption could not be read: " + reason(e), e);
            }
            try (InputStream in = d.getDataStream(fs); OutputStream out = Files.newOutputStream(target)) {
                if (d.getLength() > maxBytes) {
                    throw new IOException("The document is too large: over " + (maxBytes >> 20) + " MB");
                }
                copy(in, out, maxBytes);
            } catch (GeneralSecurityException | RuntimeException e) {
                throw new IOException("The document could not be decrypted: " + reason(e), e);
            }
        }
    }

    private static void copy(InputStream in, OutputStream out, long maxBytes) throws IOException {
        byte[] buffer = new byte[1 << 16];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) >= 0) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Conversion interrupted");
            }
            total += n;
            if (total > maxBytes) {
                throw new IOException("The document is too large: over " + (maxBytes >> 20) + " MB");
            }
            out.write(buffer, 0, n);
        }
    }

    static String reason(Throwable e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }
}
