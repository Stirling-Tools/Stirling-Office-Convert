package stirling.software.officeconvert;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import stirling.software.officeconvert.extract.PdfFiles;
import stirling.software.officeconvert.html.EpubWriter;
import stirling.software.officeconvert.memory.Admission;

final class PdfBook {

    private static final Log LOG = LogFactory.getLog(PdfBook.class);

    static final int COVER_SIDE = 800;

    private PdfBook() {}

    static EpubWriter.Book of(PDDocument doc, PdfToDocx.Options options, EbookOptions book) {
        String language = null;
        try {
            language = doc.getDocumentCatalog().getLanguage();
        } catch (RuntimeException e) {
            LOG.debug("No language for the book", e);
        }
        return new EpubWriter.Book(cover(doc, options), language, book);
    }

    static byte[] cover(PDDocument doc, PdfToDocx.Options options) {
        try {
            int page = PdfFiles.pageRange(doc.getNumberOfPages(), options.firstPage(), options.lastPage())[0];
            return render(doc, page);
        } catch (IOException | RuntimeException e) {
            LOG.debug("No cover picture for the book", e);
            return null;
        }
    }

    private static byte[] render(PDDocument doc, int index) throws IOException {
        PdfFiles.stopIfInterrupted();
        PDPage page = doc.getPage(index);
        PDRectangle box = page.getCropBox();
        int rotation = ((page.getRotation() % 360) + 360) % 360;
        float width = rotation == 90 || rotation == 270 ? box.getHeight() : box.getWidth();
        float height = rotation == 90 || rotation == 270 ? box.getWidth() : box.getHeight();
        if (!(width >= 1 && height >= 1)) {
            return null;
        }
        float scale = COVER_SIDE / Math.max(width, height);
        long pixels = (long) Math.ceil(width * scale) * (long) Math.ceil(height * scale);
        Admission.Ticket ticket = Admission.jvm().enter(pixels * 4L * 3L);
        try {
            PDFRenderer renderer = new PDFRenderer(doc);
            renderer.setSubsamplingAllowed(true);
            BufferedImage image = renderer.renderImage(index, scale, ImageType.RGB);
            return blank(image) ? null : jpeg(image);
        } finally {
            ticket.close();
        }
    }

    private static boolean blank(BufferedImage image) {
        int first = image.getRGB(0, 0);
        for (int y = 0; y < image.getHeight(); y += 4) {
            for (int x = 0; x < image.getWidth(); x += 4) {
                if (image.getRGB(x, y) != first) {
                    return false;
                }
            }
        }
        return true;
    }

    static byte[] jpeg(BufferedImage image) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return null;
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.75f);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
