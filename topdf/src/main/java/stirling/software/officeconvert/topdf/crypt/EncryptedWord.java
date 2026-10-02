package stirling.software.officeconvert.topdf.crypt;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.poifs.crypt.ChunkedCipherInputStream;
import org.apache.poi.poifs.crypt.Decryptor;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.util.LittleEndian;
import org.apache.poi.util.LittleEndianByteArrayInputStream;

/** A Word 97-2003 document encrypted with RC4, CryptoAPI or XOR obfuscation ([MS-DOC] 2.2.6): its WordDocument,
 * table and Data streams decrypted, with the FIB's encryption flags cleared. */
public final class EncryptedWord {

    private static final int FIB_BASE_BYTES = 68;

    private static final int FLAGS = 0x0A;

    private static final int ENCRYPTED = 0x0100;

    private static final int WHICH_TABLE = 0x0200;

    private static final int OBFUSCATED = 0x8000;

    private static final int LKEY = 0x0E;

    private static final int RC4_REKEYING_INTERVAL = 512;

    private EncryptedWord() {}

    public static boolean encrypted(byte[] wordDocument) {
        return wordDocument.length > FLAGS + 1 && (LittleEndian.getUShort(wordDocument, FLAGS) & ENCRYPTED) != 0;
    }

    public static String table(byte[] wordDocument) {
        int flags = wordDocument.length > FLAGS + 1 ? LittleEndian.getUShort(wordDocument, FLAGS) : 0;
        return (flags & WHICH_TABLE) != 0 ? "1Table" : "0Table";
    }

    /** The decrypted streams by name; an IOException with the plain reason when the password is missing or wrong. */
    public static Map<String, byte[]> decrypt(byte[] wordDocument, byte[] table, byte[] data, String password)
            throws IOException {
        if (wordDocument.length < FIB_BASE_BYTES) {
            throw new Passwords.Refused(Passwords.refusal(password), null);
        }
        int flags = LittleEndian.getUShort(wordDocument, FLAGS);
        int lKey = LittleEndian.getInt(wordDocument, LKEY);
        if (table == null || lKey < 0 || lKey > table.length) {
            throw new Passwords.Refused(Passwords.refusal(password), null);
        }
        EncryptionInfo info;
        Decryptor d;
        try {
            EncryptionMode mode = (flags & OBFUSCATED) != 0 ? EncryptionMode.xor : null;
            info = new EncryptionInfo(new LittleEndianByteArrayInputStream(table, 0, lKey), mode);
            d = Passwords.unlock(info, password);
        } catch (EncryptedDocumentException e) {
            throw new Passwords.Refused(e.getMessage(), e);
        } catch (IOException | RuntimeException e) {
            if (password == null) {
                throw new Passwords.Refused(Passwords.PROTECTED, e);
            }
            throw new IOException("The document's encryption could not be read: " + EncryptedPackage.reason(e), e);
        }
        d.setChunkSize(RC4_REKEYING_INTERVAL);
        Map<String, byte[]> out = new LinkedHashMap<>();
        byte[] main = decrypt(d, wordDocument, FIB_BASE_BYTES);
        LittleEndian.putUShort(main, FLAGS, flags & ~(ENCRYPTED | OBFUSCATED));
        out.put("WordDocument", main);
        out.put(table(wordDocument), decrypt(d, table, lKey));
        if (data != null) {
            out.put("Data", decrypt(d, data, 0));
        }
        return out;
    }

    private static byte[] decrypt(Decryptor d, byte[] data, int plainBytes) throws IOException {
        try (ChunkedCipherInputStream in = (ChunkedCipherInputStream) d.getDataStream(new ByteArrayInputStream(data),
                data.length, 0)) {
            byte[] out = new byte[data.length];
            int head = Math.min(plainBytes, data.length);
            if (head > 0) {
                in.readPlain(out, 0, head);
            }
            in.readFully(out, head, data.length - head);
            return out;
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IOException("The document could not be decrypted: " + EncryptedPackage.reason(e), e);
        }
    }
}
