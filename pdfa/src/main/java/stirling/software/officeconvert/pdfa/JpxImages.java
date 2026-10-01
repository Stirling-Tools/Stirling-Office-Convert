package stirling.software.officeconvert.pdfa;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

final class JpxImages {

    static final long MAX_PIXELS = 100_000_000L;

    private static final COSName SMASK_IN_DATA = COSName.getPDFName("SMaskInData");

    private static final List<COSName> REPLACED = List.of(COSName.FILTER, COSName.DECODE_PARMS, COSName.COLORSPACE,
            COSName.BITS_PER_COMPONENT, COSName.DECODE, SMASK_IN_DATA, COSName.WIDTH, COSName.HEIGHT,
            COSName.LENGTH);

    private final PDDocument doc;

    private final PdfALevel level;

    private final Report report;

    private JpxImages(PDDocument doc, PdfALevel level, Report report) {
        this.doc = doc;
        this.level = level;
        this.report = report;
    }

    static void run(PDDocument doc, PdfALevel level, Report report) throws IOException {
        JpxImages j = new JpxImages(doc, level, report);
        List<COSStream> images = new ArrayList<>();
        CosWalk.walk(doc, b -> {
            if (b instanceof COSStream s && COSName.IMAGE.equals(s.getCOSName(COSName.SUBTYPE)) && jpx(s)) {
                images.add(s);
            }
        });
        for (COSStream s : images) {
            j.fix(s);
        }
    }

    static boolean decoderAvailable() {
        return ImageIO.getImageReadersByFormatName("JPEG2000").hasNext();
    }

    private static boolean jpx(COSStream s) {
        COSBase f = s.getDictionaryObject(COSName.FILTER);
        if (f instanceof COSArray a) {
            return a.size() > 0 && COSName.JPX_DECODE.equals(a.getObject(a.size() - 1));
        }
        return COSName.JPX_DECODE.equals(f);
    }

    private void fix(COSStream s) throws IOException {
        if (level.part() > 1) {
            JpxHeader h = JpxHeader.parse(encoded(s));
            if (h != null && h.allowedInPdfA(s.getDictionaryObject(COSName.COLORSPACE) != null)) {
                return;
            }
        }
        if (s.getBoolean(COSName.IMAGE_MASK, false)) {
            throw new IOException("The PDF has a JPEG 2000 image mask, which " + level.label() + " does not allow");
        }
        if (!decoderAvailable()) {
            throw new IOException("The PDF has JPEG 2000 images that " + level.label() + " does not allow, and no "
                    + "JPEG 2000 decoder is installed to convert them (add com.github.jai-imageio:jai-imageio-jpeg2000)"
                    + (level.part() == 1 ? "; PDF/A-2 and 3 keep them as they are" : ""));
        }
        long pixels = (long) s.getInt(COSName.WIDTH, 0) * s.getInt(COSName.HEIGHT, 0);
        if (pixels > MAX_PIXELS) {
            throw new IOException("A JPEG 2000 image has " + pixels + " pixels, more than " + MAX_PIXELS
                    + " can be converted for " + level.label());
        }
        PDImageXObject original = new PDImageXObject(new PDStream(s), null);
        BufferedImage opaque = original.getOpaqueImage();
        BufferedImage alpha = alpha(s);
        PDImageXObject replacement = encode(opaque);
        PDImageXObject mask = alpha == null ? null : LosslessFactory.createFromImage(doc, alpha);
        COSStream from = replacement.getCOSObject();
        byte[] data;
        try (InputStream in = from.createRawInputStream()) {
            data = in.readAllBytes();
        }
        for (COSName k : REPLACED) {
            s.removeItem(k);
        }
        for (Map.Entry<COSName, COSBase> e : from.entrySet()) {
            if (!COSName.LENGTH.equals(e.getKey())) {
                s.setItem(e.getKey(), e.getValue());
            }
        }
        try (OutputStream out = s.createRawOutputStream()) {
            out.write(data);
        }
        if (mask != null) {
            s.setItem(COSName.SMASK, mask.getCOSObject());
        }
        report.warn("Converted JPEG 2000 images, which " + level.label() + " does not allow as they were");
    }

    private PDImageXObject encode(BufferedImage image) throws IOException {
        BufferedImage img = image;
        if (img.getType() != BufferedImage.TYPE_BYTE_GRAY && img.getType() != BufferedImage.TYPE_INT_RGB) {
            BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(),
                    img.getColorModel().getNumColorComponents() == 1 ? BufferedImage.TYPE_BYTE_GRAY
                            : BufferedImage.TYPE_INT_RGB);
            rgb.createGraphics().drawImage(img, 0, 0, null);
            img = rgb;
        }
        return Transparency.photographic(img) ? JPEGFactory.createFromImage(doc, img, 0.92f)
                : LosslessFactory.createFromImage(doc, img);
    }

    private static BufferedImage alpha(COSStream s) throws IOException {
        if (s.getInt(SMASK_IN_DATA, 0) == 0) {
            return null;
        }
        ImageReader reader = ImageIO.getImageReadersByFormatName("JPEG2000").next();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(encoded(s)))) {
            reader.setInput(in, true, true);
            BufferedImage img = reader.read(0);
            Raster a = img.getAlphaRaster();
            if (a == null) {
                return null;
            }
            BufferedImage mask = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
            mask.setData(a.createTranslatedChild(0, 0));
            return mask;
        } catch (RuntimeException e) {
            throw new IOException("A JPEG 2000 image could not be read: " + e.getMessage(), e);
        } finally {
            reader.dispose();
        }
    }

    private static byte[] encoded(COSStream s) throws IOException {
        try (InputStream in = new PDStream(s).createInputStream(List.of(COSName.JPX_DECODE.getName()))) {
            return in.readAllBytes();
        }
    }
}
