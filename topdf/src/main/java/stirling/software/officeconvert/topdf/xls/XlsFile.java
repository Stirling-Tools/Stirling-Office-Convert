package stirling.software.officeconvert.topdf.xls;

import java.io.ByteArrayInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Locale;

import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import stirling.software.officeconvert.topdf.crypt.EncryptedWorkbook;
import stirling.software.officeconvert.topdf.io.LegacyOffice;

public final class XlsFile {

    private static final String WORKBOOK = "Workbook";

    private XlsFile() {}

    public static boolean is(Path source) {
        try {
            if (!LegacyOffice.ole2(source)) {
                return false;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        try (POIFSFileSystem fs = LegacyOffice.open(source)) {
            return XlsPackage.isWorkbook(fs.getRoot());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public static XlsPackage.Outcome rewrite(Path source, String password, OutputStream out) throws IOException {
        SheetPart.stopIfInterrupted();
        Counted counted = new Counted(out);
        try (POIFSFileSystem fs = LegacyOffice.open(source);
                POIFSFileSystem plain = decrypt(fs.getRoot(), password)) {
            return XlsPackage.write(plain == null ? fs.getRoot() : plain.getRoot(), counted);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            SheetPart.stopIfInterrupted();
            boolean locked = e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT)
                    .contains("password");
            byte[] stream = locked || counted.written > 0 ? null : Repair.salvage(source);
            if (stream == null) {
                throw e;
            }
            return XlsPackage.writeStream(stream, counted);
        }
    }

    private static final class Counted extends FilterOutputStream {
        long written;

        Counted(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            written++;
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            written += len;
            out.write(b, off, len);
        }
    }

    private static POIFSFileSystem decrypt(DirectoryNode root, String password) throws IOException {
        if (!root.hasEntryCaseInsensitive(WORKBOOK)) {
            return null;
        }
        byte[] stream;
        try (InputStream in = root.createDocumentInputStream(root.getEntryCaseInsensitive(WORKBOOK))) {
            stream = in.readAllBytes();
        }
        byte[] plain = EncryptedWorkbook.decrypt(stream, password);
        if (plain == null) {
            return null;
        }
        POIFSFileSystem fs = new POIFSFileSystem();
        Streams.copyExcept(root, fs.getRoot(), WORKBOOK);
        fs.createDocument(new ByteArrayInputStream(plain), WORKBOOK);
        return fs;
    }
}
