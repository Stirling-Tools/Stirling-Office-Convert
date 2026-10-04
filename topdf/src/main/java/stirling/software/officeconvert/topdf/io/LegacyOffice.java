package stirling.software.officeconvert.topdf.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.apache.poi.hslf.blip.PICT;
import org.apache.poi.hslf.exceptions.EncryptedPowerPointFileException;
import org.apache.poi.hslf.exceptions.OldPowerPointFormatException;
import org.apache.poi.hslf.usermodel.HSLFPictureData;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.sl.usermodel.PictureData.PictureType;

import stirling.software.officeconvert.topdf.crypt.Passwords;

/** Legacy binary Office files (OLE2): the one way for tracks to open them, with their pictures bounded. */
public final class LegacyOffice {

    public static final String POWERPOINT_STREAM = "PowerPoint Document";

    private static final byte[] OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A,
        (byte) 0xE1};

    private static final int METAFILE_HEADER = 16 + 34;

    private static final int PICT_TWO_IDS = 0x5430;

    private static final byte[] EMPTY_DEFLATE = {0x78, (byte) 0x9C, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01};

    private LegacyOffice() {}

    public static POIFSFileSystem open(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        OfficeZip.checkNotInterrupted();
        return new POIFSFileSystem(file.toFile(), true);
    }

    public static boolean ole2(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        try (InputStream in = Files.newInputStream(file)) {
            return Arrays.equals(in.readNBytes(OLE2.length), OLE2);
        }
    }

    /** Whether the OLE2 file holds a PowerPoint 97-2003 presentation; false for any other file. */
    public static boolean powerPoint(Path file) throws IOException {
        if (!ole2(file)) {
            return false;
        }
        try (POIFSFileSystem fs = new POIFSFileSystem(file.toFile(), true)) {
            return fs.getRoot().hasEntryCaseInsensitive(POWERPOINT_STREAM);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The kind of legacy file by its main stream: xls, ppt, doc, or encrypted (a password protected OOXML package);
     * null for any other file. */
    public static String kind(Path file) throws IOException {
        if (!ole2(file)) {
            return null;
        }
        try (POIFSFileSystem fs = new POIFSFileSystem(file.toFile(), true)) {
            DirectoryNode root = fs.getRoot();
            if (root.hasEntryCaseInsensitive("Workbook") || root.hasEntryCaseInsensitive("Book")) {
                return "xls";
            }
            if (root.hasEntryCaseInsensitive(POWERPOINT_STREAM)) {
                return "ppt";
            }
            if (root.hasEntryCaseInsensitive("WordDocument")) {
                return "doc";
            }
            return root.hasEntryCaseInsensitive("EncryptedPackage") ? "encrypted" : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Opens a PowerPoint 97-2003 file read-only; its metafile and PICT pictures are checked before anything draws. */
    public static HSLFSlideShow slideShow(Path file) throws IOException {
        return slideShow(file, null);
    }

    /** As {@link #slideShow(Path)}, decrypting with {@code password}, which must stay set in
     * {@link Passwords#legacy(String)} for as long as the slide show is read. */
    public static HSLFSlideShow slideShow(Path file, String password) throws IOException {
        Objects.requireNonNull(file, "file");
        POIFSFileSystem fs;
        try {
            fs = new POIFSFileSystem(file.toFile(), true);
        } catch (RuntimeException e) {
            throw new IOException("The presentation cannot be read: " + PoiPackages.reason(e), e);
        }
        try {
            return new HSLFSlideShow(fs);
        } catch (EncryptedPowerPointFileException e) {
            fs.close();
            throw new Passwords.Refused(Passwords.refusal(password), e);
        } catch (OldPowerPointFormatException e) {
            fs.close();
            throw new IOException("PowerPoint 95 and older files are not supported; save the file as .pptx", e);
        } catch (IOException | RuntimeException | StackOverflowError e) {
            fs.close();
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Conversion interrupted");
            }
            if (e instanceof StackOverflowError) {
                throw new IOException("The presentation nests too deeply to convert", e);
            }
            throw new IOException("The presentation cannot be read: " + PoiPackages.reason(e), e);
        }
    }

    /** POI inflates metafile and PICT pictures without a bound; one that inflates past {@code maxBytes}, or does
     * not inflate, is replaced by an empty picture, which draws nothing. Returns whether the picture was kept. */
    @SuppressWarnings("deprecation")
    public static boolean boundPicture(HSLFPictureData picture, int maxBytes) {
        PictureType type = picture.getType();
        if (type != PictureType.EMF && type != PictureType.WMF && type != PictureType.PICT) {
            return true;
        }
        byte[] raw = picture.getRawData();
        int ids = picture instanceof PICT p && p.getSignature() == PICT_TWO_IDS ? 2 : 1;
        if (raw != null && inflatesWithin(raw, METAFILE_HEADER + 16 * (ids - 1), maxBytes)) {
            return true;
        }
        picture.setRawData(neutral(raw, type == PictureType.PICT ? 2 : 1));
        return false;
    }

    // The picture's id kept, a zeroed header (size 0) and an empty deflate stream at each place POI may look for it
    private static byte[] neutral(byte[] raw, int ids) {
        byte[] out = new byte[16 * ids + 34 + EMPTY_DEFLATE.length];
        if (raw != null) {
            System.arraycopy(raw, 0, out, 0, Math.min(16, raw.length));
        }
        System.arraycopy(EMPTY_DEFLATE, 0, out, out.length - EMPTY_DEFLATE.length, EMPTY_DEFLATE.length);
        return out;
    }

    static boolean inflatesWithin(byte[] data, int offset, int maxBytes) {
        if (data.length <= offset) {
            return false;
        }
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data, offset, data.length - offset);
            byte[] buffer = new byte[64 << 10];
            long total = 0;
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                total += n;
                if (total > maxBytes || Thread.currentThread().isInterrupted()) {
                    return false;
                }
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    return false;
                }
            }
            return true;
        } catch (DataFormatException e) {
            return false;
        } finally {
            inflater.end();
        }
    }
}
