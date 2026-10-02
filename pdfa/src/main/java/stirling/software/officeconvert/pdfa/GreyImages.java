package stirling.software.officeconvert.pdfa;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.io.OutputStream;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

final class GreyImages {

    private GreyImages() {}

    static PDImageXObject lossless(PDDocument doc, BufferedImage image) throws IOException {
        if (image.getType() != BufferedImage.TYPE_BYTE_GRAY) {
            return LosslessFactory.createFromImage(doc, image);
        }
        int w = image.getWidth();
        int h = image.getHeight();
        COSStream s = doc.getDocument().createCOSStream();
        Raster raster = image.getRaster();
        byte[] row = new byte[w];
        try (OutputStream out = s.createOutputStream(COSName.FLATE_DECODE)) {
            for (int y = 0; y < h; y++) {
                raster.getDataElements(0, y, w, 1, row);
                out.write(row);
            }
        }
        s.setItem(COSName.TYPE, COSName.XOBJECT);
        s.setItem(COSName.SUBTYPE, COSName.IMAGE);
        s.setInt(COSName.WIDTH, w);
        s.setInt(COSName.HEIGHT, h);
        s.setInt(COSName.BITS_PER_COMPONENT, 8);
        s.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
        return new PDImageXObject(new PDStream(s), null);
    }
}
