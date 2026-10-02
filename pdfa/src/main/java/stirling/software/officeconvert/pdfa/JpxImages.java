package stirling.software.officeconvert.pdfa;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import stirling.software.officeconvert.jpx.JpxDecoder;

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

    static CosWalk.Visitor visitor(PDDocument doc, PdfALevel level, Report report) {
        JpxImages j = new JpxImages(doc, level, report);
        return b -> {
            if (b instanceof COSStream s && COSName.IMAGE.equals(s.getCOSName(COSName.SUBTYPE)) && jpx(s)) {
                j.fix(s);
            }
        };
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
        long pixels = (long) s.getInt(COSName.WIDTH, 0) * s.getInt(COSName.HEIGHT, 0);
        if (pixels > MAX_PIXELS) {
            throw new IOException("A JPEG 2000 image has " + pixels + " pixels, more than " + MAX_PIXELS
                    + " can be converted for " + level.label());
        }
        BufferedImage decoded = s.getInt(SMASK_IN_DATA, 0) == 0 ? null : decoded(s);
        BufferedImage alpha = decoded == null ? null : alpha(decoded);
        BufferedImage opaque = alpha != null && s.getDictionaryObject(COSName.COLORSPACE) == null ? colours(decoded)
                : new PDImageXObject(new PDStream(s), null).getOpaqueImage();
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

    private static BufferedImage decoded(COSStream s) throws IOException {
        try {
            return JpxDecoder.decode(encoded(s)).toBufferedImage();
        } catch (RuntimeException e) {
            throw new IOException("A JPEG 2000 image could not be read: " + e.getMessage(), e);
        }
    }

    private static BufferedImage alpha(BufferedImage img) {
        Raster a = img.getAlphaRaster();
        if (a == null) {
            return null;
        }
        BufferedImage mask = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        mask.setData(a.createTranslatedChild(0, 0));
        return mask;
    }

    private static BufferedImage colours(BufferedImage img) {
        ColorModel cm = img.getColorModel();
        int[] bands = new int[cm.getNumColorComponents()];
        for (int i = 0; i < bands.length; i++) {
            bands[i] = i;
        }
        WritableRaster r = img.getRaster().createWritableChild(0, 0, img.getWidth(), img.getHeight(), 0, 0, bands);
        if (bands.length == 1 && r.getTransferType() == DataBuffer.TYPE_BYTE) {
            BufferedImage grey = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
            grey.setData(r);
            return grey;
        }
        ColorModel opaque = new ComponentColorModel(cm.getColorSpace(), false, false, ColorModel.OPAQUE,
                r.getTransferType());
        return new BufferedImage(opaque, r, false, null);
    }

    private static byte[] encoded(COSStream s) throws IOException {
        try (InputStream in = new PDStream(s).createInputStream(List.of(COSName.JPX_DECODE.getName()))) {
            return in.readAllBytes();
        }
    }
}
