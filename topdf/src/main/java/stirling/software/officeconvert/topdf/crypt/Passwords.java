package stirling.software.officeconvert.topdf.crypt;

import java.io.IOException;
import java.security.GeneralSecurityException;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hssf.record.crypto.Biff8EncryptionKey;
import org.apache.poi.poifs.crypt.Decryptor;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionVerifier;

/** The two plain reasons a protected document is not converted, and POI's thread-wide password for the legacy
 * readers that look it up themselves. */
public final class Passwords {

    public static final String PROTECTED = "The document is password protected; remove the password and try again";

    public static final String WRONG = "The password given for the document is not correct";

    static final int MAX_SPIN_COUNT = 10_000_000;

    private Passwords() {}

    /** Which message a protected document fails with: the plain one without a password, the wrong one with. */
    public static String refusal(String password) {
        return password == null ? PROTECTED : WRONG;
    }

    /** POI's legacy readers (.xls, .doc, .ppt) read the password from a thread-local; it is set until close. */
    public static Scope legacy(String password) {
        String before = Biff8EncryptionKey.getCurrentUserPassword();
        Biff8EncryptionKey.setCurrentUserPassword(password);
        return () -> Biff8EncryptionKey.setCurrentUserPassword(before);
    }

    /** A protected document that was not opened: no password, a wrong one, or an encryption that is refused. */
    public static final class Refused extends IOException {
        public Refused(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /** A decryptor that accepts the password or Office's built-in read-only one, or an
     * EncryptedDocumentException with the plain reason. Key stretching past the format's limit is refused unread. */
    static Decryptor unlock(EncryptionInfo info, String password) {
        EncryptionVerifier v = info.getVerifier();
        if (v != null && v.getSpinCount() > MAX_SPIN_COUNT) {
            throw new EncryptedDocumentException("The document's encryption asks for more key stretching than Office"
                    + " allows, so it was not opened");
        }
        Decryptor d = info.getDecryptor();
        if (d == null) {
            throw new EncryptedDocumentException("The document uses an encryption that is not supported");
        }
        try {
            if (password != null && d.verifyPassword(password)) {
                return d;
            }
            if (d.verifyPassword(Decryptor.DEFAULT_PASSWORD)) {
                return d;
            }
        } catch (GeneralSecurityException e) {
            throw new EncryptedDocumentException(refusal(password), e);
        }
        throw new EncryptedDocumentException(refusal(password));
    }
}
