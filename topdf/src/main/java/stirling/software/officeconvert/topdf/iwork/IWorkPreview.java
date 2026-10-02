package stirling.software.officeconvert.topdf.iwork;

import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

import stirling.software.officeconvert.topdf.io.DecodedPicture;
import stirling.software.officeconvert.topdf.io.PictureDecoder;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.PdfOutput;

/** Apple Pages, Numbers and Keynote files. Their own formats (XML in iWork '09, protocol buffers since) are not read:
 * what is drawn is the preview the file holds, the whole document as PDF when there is one, else its preview
 * picture of the first page. */
public final class IWorkPreview {

    /** What was drawn: the pages, and whether only a picture of the first page was available. */
    public record Drawn(int pages, boolean pictureOnly, int totalPages) {}

    private static final String[] PDFS = {"QuickLook/Preview.pdf", "preview.pdf"};

    private static final String[] PICTURES = {"preview.jpg", "preview-web.jpg", "QuickLook/Thumbnail.jpg",
        "QuickLook/Thumbnail.png", "preview-micro.jpg"};

    private static final String[] MARKERS = {"Index/Document.iwa", "index.apxl", "index.apxl.gz",
        "QuickLook/Preview.pdf", "buildVersionHistory.plist", "Metadata/BuildVersionHistory.plist"};

    private static final String[] INDEXES = {"index.xml", "index.xml.gz"};

    private static final String APPLE_NAMESPACE = "developer.apple.com/namespaces/";

    private static final long MAX_PREVIEW_BYTES = 64L << 20;

    private static final long MIN_COMPRESSED_PER_BYTE = 100;

    private static final long GRACE_BYTES = 100L << 10;

    private static final float WIDTH = 612;

    private IWorkPreview() {}

    /** Whether the file is a zipped iWork document: by its name, or by the index parts only iWork writes. */
    public static boolean is(Path file) {
        Path name = file.getFileName();
        String n = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        boolean named = n.endsWith(".pages") || n.endsWith(".numbers") || n.endsWith(".key");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(2);
            if (head.length < 2 || head[0] != 'P' || head[1] != 'K') {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        try (ZipFile zip = new ZipFile(file.toFile())) {
            if (zip.getEntry("[Content_Types].xml") != null || zip.getEntry("mimetype") != null) {
                return false;
            }
            if (named) {
                return true;
            }
            for (String m : MARKERS) {
                if (zip.getEntry(m) != null) {
                    return true;
                }
            }
            for (String m : INDEXES) {
                ZipEntry e = zip.getEntry(m);
                if (e != null && appleIndex(zip, e)) {
                    return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean appleIndex(ZipFile zip, ZipEntry e) throws IOException {
        try (InputStream raw = zip.getInputStream(e);
                InputStream in = e.getName().endsWith(".gz") ? new GZIPInputStream(raw) : raw) {
            String head = new String(in.readNBytes(4096), StandardCharsets.ISO_8859_1);
            return head.contains(APPLE_NAMESPACE);
        }
    }

    public static long memoryBound(Path file) throws IOException {
        long most = 0;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            for (String p : PDFS) {
                most = Math.max(most, bound(zip.getEntry(p)));
            }
            for (String p : PICTURES) {
                most = Math.max(most, bound(zip.getEntry(p)));
            }
        }
        return most;
    }

    private static long bound(ZipEntry e) {
        if (e == null) {
            return 0;
        }
        long compressed = Math.max(0, e.getCompressedSize());
        long ratio = compressed > (MAX_PREVIEW_BYTES - GRACE_BYTES) / MIN_COMPRESSED_PER_BYTE ? MAX_PREVIEW_BYTES
                : compressed * MIN_COMPRESSED_PER_BYTE + GRACE_BYTES;
        long declared = e.getSize() >= 0 ? e.getSize() : MAX_PREVIEW_BYTES;
        return Math.min(Math.min(MAX_PREVIEW_BYTES, ratio), Math.max(declared, 0));
    }

    public static Drawn draw(Path file, PdfOutput out, int maxPages) throws IOException {
        String encrypted = null;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            for (String p : PDFS) {
                byte[] data = read(zip, zip.getEntry(p));
                PDDocument src = data == null ? null : load(data);
                if (src == null) {
                    continue;
                }
                try (src) {
                    if (src.isEncrypted()) {
                        encrypted = "The Apple iWork document's preview is encrypted";
                    } else if (src.getNumberOfPages() > 0) {
                        return pdf(src, out, maxPages);
                    }
                }
            }
            for (String p : PICTURES) {
                byte[] data = read(zip, zip.getEntry(p));
                if (data != null) {
                    picture(data, out);
                    return new Drawn(1, true, 1);
                }
            }
        }
        if (encrypted != null) {
            throw new IOException(encrypted);
        }
        throw new IOException("The Apple iWork document holds no preview, and its own format cannot be read; export"
                + " it from Pages, Numbers or Keynote as PDF or as an Office document");
    }

    private static PDDocument load(byte[] data) throws IOException {
        try {
            return Loader.loadPDF(data);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] read(ZipFile zip, ZipEntry e) throws IOException {
        if (e == null) {
            return null;
        }
        long limit = bound(e);
        if (limit <= 0 || e.getSize() > limit) {
            return null;
        }
        try (InputStream in = zip.getInputStream(e)) {
            byte[] b = in.readNBytes((int) limit + 1);
            return b.length == 0 || b.length > limit ? null : b;
        }
    }

    private static Drawn pdf(PDDocument src, PdfOutput out, int maxPages) throws IOException {
        int total = src.getNumberOfPages();
        int n = maxPages > 0 ? Math.min(total, maxPages) : total;
        LayerUtility layers = new LayerUtility(out.document());
        for (int i = 0; i < n; i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Conversion interrupted");
            }
            PDPage page = src.getPage(i);
            PDRectangle box = page.getCropBox();
            PDFormXObject form = layers.importPageAsForm(src, i);
            form.setMatrix(new AffineTransform());
            form.setBBox(box);
            float w = Math.max(1, Math.min(14_400, box.getWidth()));
            float h = Math.max(1, Math.min(14_400, box.getHeight()));
            int turn = Math.floorMod(page.getRotation(), 360) / 90 * 90;
            boolean sideways = turn == 90 || turn == 270;
            try (PdfCanvas c = out.newPage(sideways ? h : w, sideways ? w : h)) {
                c.save();
                c.transform(switch (turn) {
                    case 90 -> new AffineTransform(0, 1, -1, 0, h, 0);
                    case 180 -> new AffineTransform(-1, 0, 0, -1, w, h);
                    case 270 -> new AffineTransform(0, -1, 1, 0, 0, w);
                    default -> new AffineTransform();
                });
                c.form(form, 0, 0, w, h);
                c.restore();
            }
        }
        return new Drawn(n, false, total);
    }

    private static void picture(byte[] data, PdfOutput out) throws IOException {
        DecodedPicture p = PictureDecoder.decode(out.document(), data);
        float w = WIDTH;
        float h = Math.max(1, Math.min(14_400, WIDTH * p.pixelHeight() / Math.max(1f, p.pixelWidth())));
        try (PdfCanvas c = out.newPage(w, h)) {
            c.image(p, 0, 0, w, h);
        }
    }
}
