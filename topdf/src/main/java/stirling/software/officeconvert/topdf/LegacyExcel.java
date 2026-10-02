package stirling.software.officeconvert.topdf;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.OfficeToPdf.Result;
import stirling.software.officeconvert.topdf.biff5.Biff4Upgrade;
import stirling.software.officeconvert.topdf.biff5.Biff5Package;
import stirling.software.officeconvert.topdf.crypt.EncryptedWorkbook;
import stirling.software.officeconvert.topdf.io.LegacyOffice;

/** Excel workbooks older than Excel 97: Excel 5.0/95 (BIFF5, in an OLE2 file or bare) is rewritten as SpreadsheetML,
 * Excel 2.x to 4.0 worksheets first as BIFF5; a bare BIFF8 stream is wrapped as the OLE2 file Excel 97 writes. */
final class LegacyExcel {

    static final String UNKNOWN = "The file is an Excel binary workbook in a form that is not supported; save it as"
            + " .xlsx";

    static final String OLDER = "The file is an Excel 4.0 or older workbook, which is not supported; save it as .xlsx";

    private static final int MAX_STREAM_BYTES = 256 << 20;

    private LegacyExcel() {}

    /** The workbook stream of an OLE2 Excel file or a bare BIFF file; null for anything else. */
    static byte[] stream(Path source) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(source)) {
            head = in.readNBytes(8);
        }
        if (bareBof(head)) {
            if (Files.size(source) > MAX_STREAM_BYTES) {
                return null;
            }
            return Files.readAllBytes(source);
        }
        if (!LegacyOffice.ole2(source)) {
            return null;
        }
        try (POIFSFileSystem fs = new POIFSFileSystem(source.toFile(), true)) {
            DirectoryNode root = fs.getRoot();
            String name = root.hasEntryCaseInsensitive("Book") && !root.hasEntryCaseInsensitive("Workbook") ? "Book"
                    : root.hasEntryCaseInsensitive("Workbook") ? "Workbook" : null;
            if (name == null) {
                return null;
            }
            try (InputStream in = root.createDocumentInputStream(root.getEntryCaseInsensitive(name))) {
                byte[] start = in.readNBytes(8);
                if (Biff5Package.is(start) || Biff5Package.older(start)) {
                    return concat(start, in.readNBytes(MAX_STREAM_BYTES));
                }
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean bareBof(byte[] h) {
        if (h.length < 8) {
            return false;
        }
        int type = (h[0] & 0xFF) | (h[1] & 0xFF) << 8;
        int size = (h[2] & 0xFF) | (h[3] & 0xFF) << 8;
        int version = (h[4] & 0xFF) | (h[5] & 0xFF) << 8;
        if (type == 0x0809) {
            return size >= 4 && size <= 20;
        }
        return (type == 0x0009 || type == 0x0209 || type == 0x0409) && size >= 4 && size <= 8;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static Long estimate(Path source) throws IOException {
        byte[] stream = stream(source);
        return stream == null ? null : Biff5Package.estimate(stream.length) + 2 * Admission.BASE_BYTES;
    }

    static Result render(Path source, OutputStream sink, Options options, OfficeToPdf.Renderer renderer)
            throws IOException {
        byte[] stream = stream(source);
        if (stream == null) {
            return null;
        }
        if (Biff5Package.older(stream)) {
            stream = Biff4Upgrade.upgrade(stream);
        }
        if (!Biff5Package.is(stream)) {
            if (!Biff5Package.globals8(stream)) {
                throw new IOException(UNKNOWN);
            }
            return bareBiff8(stream, sink, options, renderer);
        }
        byte[] plain = EncryptedWorkbook.decrypt(stream, options.password());
        if (plain != null) {
            stream = plain;
        }
        Path xlsx = Files.createTempFile("office-to-pdf-", ".xlsx");
        try {
            Biff5Package.Outcome outcome;
            Admission.Ticket ticket = Admission.jvm().enter(Biff5Package.estimate(stream.length));
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(xlsx), 1 << 16)) {
                outcome = Biff5Package.write(stream, os);
            } finally {
                ticket.close();
            }
            OfficeToPdf.stopIfInterrupted();
            Result r = OfficeToPdf.render(xlsx, OfficeToPdf.Format.XLSX, sink, options,
                    (s, job) -> renderer.render(source, job), OfficeToPdf.REWRITTEN);
            List<String> warnings = new ArrayList<>(r.warnings());
            for (String w : outcome.warnings()) {
                String c = RenderJob.clean(w);
                if (c != null && !warnings.contains(c)) {
                    warnings.add(c);
                }
            }
            return new Result(r.pages(), r.truncated() || outcome.lost(), warnings, r.pageLimitReached());
        } finally {
            OfficeToPdf.deleteQuietly(xlsx);
        }
    }

    private static Result bareBiff8(byte[] stream, OutputStream sink, Options options, OfficeToPdf.Renderer renderer)
            throws IOException {
        Path ole = Files.createTempFile("office-to-pdf-", ".xls");
        try {
            try (POIFSFileSystem fs = new POIFSFileSystem(); OutputStream os = Files.newOutputStream(ole)) {
                fs.createDocument(new ByteArrayInputStream(stream), "Workbook");
                fs.writeFilesystem(os);
            }
            return OfficeToPdf.render(ole, OfficeToPdf.Format.XLSX, sink, options, renderer);
        } finally {
            OfficeToPdf.deleteQuietly(ole);
        }
    }
}
