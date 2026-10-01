package stirling.software.officeconvert.topdf.crypt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hssf.record.FilePassRecord;
import org.apache.poi.hssf.record.RecordInputStream;
import org.apache.poi.hssf.record.crypto.Biff8DecryptingStream;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.util.LittleEndian;

/** An Excel 97-2003 workbook whose records are encrypted (RC4, CryptoAPI or XOR, announced by a FILEPASS record):
 * the Workbook stream is decrypted record by record into a new file system, FILEPASS left out and the sheet offsets
 * moved to match. A workbook protected only by Excel's built-in password opens without one. */
public final class EncryptedWorkbook {

    private static final int BOF = 0x0809;

    private static final int FILEPASS = 0x002F;

    private static final int BOUNDSHEET = 0x0085;

    private static final int MAX_LEADING_RECORDS = 64;

    private EncryptedWorkbook() {}

    /** The workbook decrypted, or null when its records are not encrypted. */
    public static POIFSFileSystem decrypt(DirectoryNode root, String password) throws IOException {
        String name = root.hasEntryCaseInsensitive("Workbook") ? "Workbook" : null;
        if (name == null) {
            return null;
        }
        byte[] stream;
        try (InputStream in = root.createDocumentInputStream(root.getEntryCaseInsensitive(name))) {
            stream = in.readAllBytes();
        }
        int at = filePass(stream);
        if (at < 0) {
            return null;
        }
        int length = LittleEndian.getUShort(stream, at + 2);
        EncryptionInfo info;
        try {
            RecordInputStream rin = new RecordInputStream(new ByteArrayInputStream(stream, at, 4 + length));
            rin.nextRecord();
            info = new FilePassRecord(rin).getEncryptionInfo();
            Passwords.unlock(info, password);
        } catch (EncryptedDocumentException e) {
            throw new Passwords.Refused(e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IOException("The workbook's encryption could not be read: " + EncryptedPackage.reason(e), e);
        }
        byte[] plain = decrypt(stream, info, at, 4 + length);
        POIFSFileSystem fs = new POIFSFileSystem();
        Streams.copyExcept(root, fs.getRoot(), name);
        fs.createDocument(new ByteArrayInputStream(plain), "Workbook");
        return fs;
    }

    private static int filePass(byte[] s) {
        int at = 0;
        for (int i = 0; i < MAX_LEADING_RECORDS && at + 4 <= s.length; i++) {
            int sid = LittleEndian.getUShort(s, at);
            int len = LittleEndian.getUShort(s, at + 2);
            if (sid == FILEPASS) {
                return at + 4 + len <= s.length ? at : -1;
            }
            if (sid == BOUNDSHEET || i > 0 && sid == BOF) {
                return -1;
            }
            at += 4 + len;
        }
        return -1;
    }

    private static byte[] decrypt(byte[] stream, EncryptionInfo info, int filePassAt, int filePassBytes)
            throws IOException {
        Biff8DecryptingStream in;
        try {
            in = new Biff8DecryptingStream(new ByteArrayInputStream(stream), 0, info);
        } catch (RuntimeException e) {
            throw new IOException("The workbook could not be decrypted: " + EncryptedPackage.reason(e), e);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(stream.length);
        long pos = 0;
        try {
            while (pos + 4 <= stream.length) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Conversion interrupted");
                }
                int sid = in.readRecordSID();
                int len = in.readDataSize();
                if (pos + 4 + len > stream.length) {
                    break;
                }
                byte[] data = new byte[len];
                if (Biff8DecryptingStream.isNeverEncryptedRecord(sid)) {
                    in.readPlain(data, 0, len);
                } else if (sid == BOUNDSHEET && len >= 4) {
                    in.readPlain(data, 0, 4);
                    in.readFully(data, 4, len - 4);
                    long offset = LittleEndian.getUInt(data, 0);
                    if (offset > filePassAt) {
                        LittleEndian.putInt(data, 0, (int) (offset - filePassBytes));
                    }
                } else {
                    in.readFully(data, 0, len);
                }
                pos += 4 + len;
                if (sid == FILEPASS) {
                    continue;
                }
                byte[] head = new byte[4];
                LittleEndian.putUShort(head, 0, sid);
                LittleEndian.putUShort(head, 2, len);
                out.write(head);
                out.write(data);
            }
        } catch (RuntimeException e) {
            throw new IOException("The workbook could not be decrypted: " + EncryptedPackage.reason(e), e);
        }
        return out.toByteArray();
    }
}
