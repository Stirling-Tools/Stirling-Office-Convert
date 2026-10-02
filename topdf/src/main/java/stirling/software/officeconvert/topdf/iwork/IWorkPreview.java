package stirling.software.officeconvert.topdf.iwork;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
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

    private static final String[] MARKERS = {"index.xml", "index.xml.gz", "index.apxl", "index.apxl.gz",
        "Index/Document.iwa", "Index.zip", "QuickLook/Preview.pdf"};

    private static final long MAX_PREVIEW_BYTES = 256L << 20;

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
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public static Drawn draw(Path file, PdfOutput out, int maxPages) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            for (String p : PDFS) {
                ZipEntry e = zip.getEntry(p);
                if (e != null && e.getSize() <= MAX_PREVIEW_BYTES) {
                    byte[] data = read(zip, e);
                    if (data != null) {
                        return pdf(data, out, maxPages);
                    }
                }
            }
            for (String p : PICTURES) {
                ZipEntry e = zip.getEntry(p);
                if (e != null && e.getSize() <= MAX_PREVIEW_BYTES) {
                    byte[] data = read(zip, e);
                    if (data != null) {
                        picture(data, out);
                        return new Drawn(1, true, 1);
                    }
                }
            }
        }
        throw new IOException("The Apple iWork document holds no preview, and its own format cannot be read; export"
                + " it from Pages, Numbers or Keynote as PDF or as an Office document");
    }

    private static byte[] read(ZipFile zip, ZipEntry e) throws IOException {
        try (InputStream in = zip.getInputStream(e)) {
            byte[] b = in.readNBytes((int) Math.min(MAX_PREVIEW_BYTES, Integer.MAX_VALUE - 16));
            return b.length == 0 ? null : b;
        }
    }

    private static Drawn pdf(byte[] data, PdfOutput out, int maxPages) throws IOException {
        try (PDDocument src = Loader.loadPDF(data)) {
            if (src.isEncrypted()) {
                throw new IOException("The Apple iWork document's preview is encrypted");
            }
            int total = src.getNumberOfPages();
            int n = maxPages > 0 ? Math.min(total, maxPages) : total;
            LayerUtility layers = new LayerUtility(out.document());
            for (int i = 0; i < n; i++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.io.InterruptedIOException("Conversion interrupted");
                }
                PDPage page = src.getPage(i);
                PDRectangle box = page.getCropBox();
                PDFormXObject form = layers.importPageAsForm(src, i);
                float w = Math.max(1, Math.min(14_400, box.getWidth()));
                float h = Math.max(1, Math.min(14_400, box.getHeight()));
                try (PdfCanvas c = out.newPage(w, h)) {
                    c.form(form, 0, 0, w, h);
                }
            }
            return new Drawn(n, false, total);
        }
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
