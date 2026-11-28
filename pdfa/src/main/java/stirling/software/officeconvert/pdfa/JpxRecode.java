package stirling.software.officeconvert.pdfa;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import stirling.software.officeconvert.jpx.JpxEncoder;

final class JpxRecode {

    static final long MAX_SAMPLES = 1L << 22;

    private static final double FLAT_BITS = 1;

    private JpxRecode() {}

    static PDImageXObject smaller(PDDocument doc, BufferedImage image, PDImageXObject current, boolean lossy)
            throws IOException {
        try {
            return attempt(doc, image, current, lossy);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            return current;
        }
    }

    static COSStream smaller(PDDocument doc, JpxEncoder.Planes planes, COSStream current, COSBase colourSpace)
            throws IOException {
        try {
            return attempt(doc, planes, current, colourSpace);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            return current;
        }
    }

    private static PDImageXObject attempt(PDDocument doc, BufferedImage image, PDImageXObject current, boolean lossy)
            throws IOException {
        long samples = (long) image.getWidth() * image.getHeight() * image.getRaster().getNumBands();
        if (samples > MAX_SAMPLES) {
            return current;
        }
        byte[] encoded = encoded(current.getCOSObject());
        JpxEncoder.Planes planes = JpxEncoder.planes(image);
        int expected = current.getColorSpace().getNumberOfComponents();
        if (planes.depths().length != expected || !lossy && encoded.length * 8L < samples * FLAT_BITS) {
            return current;
        }
        byte[] jpx = lossy ? matching(planes, target(current.getCOSObject(), planes), encoded.length)
                : JpxEncoder.encode(planes, JpxEncoder.Options.exact());
        if (jpx == null || jpx.length >= encoded.length) {
            return current;
        }
        return stream(doc, jpx, planes, current.getCOSObject().getDictionaryObject(COSName.COLORSPACE));
    }

    private static COSStream attempt(PDDocument doc, JpxEncoder.Planes planes, COSStream current, COSBase colourSpace)
            throws IOException {
        long samples = (long) planes.width() * planes.height() * planes.depths().length;
        if (samples > MAX_SAMPLES || current.getLength() * 8 < samples * FLAT_BITS) {
            return current;
        }
        byte[] jpx = JpxEncoder.encode(planes, JpxEncoder.Options.exact());
        if (jpx.length >= current.getLength()) {
            return current;
        }
        return stream(doc, jpx, planes, colourSpace).getCOSObject();
    }

    private static byte[] matching(JpxEncoder.Planes planes, JpxEncoder.Target target, long budget)
            throws IOException {
        return target == null ? null : JpxEncoder.encodeAsClose(planes, target, budget);
    }

    private static JpxEncoder.Target target(COSStream jpeg, JpxEncoder.Planes planes) throws IOException {
        JpxEncoder.Planes reference = decoded(jpeg, planes);
        return reference == null ? null : JpxEncoder.target(planes, reference);
    }

    private static JpxEncoder.Planes decoded(COSStream jpeg, JpxEncoder.Planes like) throws IOException {
        int n = like.depths().length;
        int pixels = like.width() * like.height();
        byte[] raw;
        try (InputStream in = jpeg.createInputStream()) {
            raw = in.readNBytes(pixels * n + 1);
        }
        if (raw.length != pixels * n) {
            return null;
        }
        int[][] s = new int[n][pixels];
        for (int i = 0; i < pixels; i++) {
            for (int c = 0; c < n; c++) {
                s[c][i] = raw[i * n + c] & 0xFF;
            }
        }
        return new JpxEncoder.Planes(like.width(), like.height(), s, like.depths(), null, JpxEncoder.Colour.GREY, -1);
    }

    private static PDImageXObject stream(PDDocument doc, byte[] jpx, JpxEncoder.Planes planes, COSBase colourSpace)
            throws IOException {
        COSStream s = doc.getDocument().createCOSStream();
        try (OutputStream out = s.createRawOutputStream()) {
            out.write(jpx);
        }
        s.setItem(COSName.FILTER, COSName.JPX_DECODE);
        s.setItem(COSName.TYPE, COSName.XOBJECT);
        s.setItem(COSName.SUBTYPE, COSName.IMAGE);
        s.setInt(COSName.WIDTH, planes.width());
        s.setInt(COSName.HEIGHT, planes.height());
        s.setInt(COSName.BITS_PER_COMPONENT, planes.depths()[0]);
        if (colourSpace != null) {
            s.setItem(COSName.COLORSPACE, colourSpace);
        }
        return new PDImageXObject(new PDStream(s), null);
    }

    private static byte[] encoded(COSStream s) throws IOException {
        try (InputStream in = s.createRawInputStream()) {
            return in.readAllBytes();
        }
    }
}
