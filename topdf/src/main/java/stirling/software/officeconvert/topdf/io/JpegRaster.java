package stirling.software.officeconvert.topdf.io;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

final class JpegRaster extends BufferedImage {

    private final byte[] jpeg;

    private final PictureDecoder.JpegFrame frame;

    private JpegRaster(BufferedImage decoded, byte[] jpeg, PictureDecoder.JpegFrame frame) {
        super(decoded.getColorModel(), decoded.getRaster(), decoded.isAlphaPremultiplied(), null);
        this.jpeg = jpeg;
        this.frame = frame;
    }

    static BufferedImage of(BufferedImage decoded, byte[] jpeg) {
        PictureDecoder.JpegFrame frame = PictureDecoder.JpegFrame.of(jpeg);
        if (frame == null || !frame.passesThrough() || frame.width() != decoded.getWidth()
                || frame.height() != decoded.getHeight() || frame.components() == 4
                || frame.components() != decoded.getColorModel().getNumColorComponents()
                || decoded.getColorModel().hasAlpha() || !plain(decoded.getColorModel().getColorSpace())) {
            return decoded;
        }
        return new JpegRaster(decoded, jpeg, frame);
    }

    private static boolean plain(ColorSpace cs) {
        return cs.isCS_sRGB() || cs.getType() == ColorSpace.TYPE_GRAY && cs.getNumComponents() == 1;
    }

    PDImageXObject encode(PDDocument doc) throws IOException {
        return PictureDecoder.dct(doc, jpeg, frame);
    }
}
