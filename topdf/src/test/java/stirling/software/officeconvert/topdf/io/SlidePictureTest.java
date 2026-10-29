package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.hslf.usermodel.HSLFPictureShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.sl.usermodel.PictureData.PictureType;
import org.junit.jupiter.api.Test;

class SlidePictureTest {

    @Test
    void aLegacySlidePictureKeepsItsPixels() throws Exception {
        BufferedImage source = new BufferedImage(40, 20, BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                source.setRGB(x, y, (x * 6) << 16 | (y * 12) << 8 | (x + y) * 4);
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(source, "png", png);
        try (HSLFSlideShow ppt = new HSLFSlideShow(); PDDocument doc = new PDDocument()) {
            ppt.setPageSize(new Dimension(80, 40));
            HSLFSlide slide = ppt.createSlide();
            HSLFPictureShape picture = slide.createPicture(ppt.addPicture(png.toByteArray(), PictureType.PNG));
            picture.setAnchor(new Rectangle2D.Double(0, 0, 80, 40));
            PDFormXObject form = SafeImageRenderer.drawForm(doc, slide, 80, 40, null);
            List<PDImageXObject> images = new ArrayList<>();
            collect(form.getResources(), images);
            assertEquals(1, images.size());
            BufferedImage drawn = images.get(0).getImage();
            assertEquals(source.getWidth(), drawn.getWidth());
            assertEquals(source.getHeight(), drawn.getHeight());
            assertArrayEquals(pixels(source), pixels(drawn));
        }
    }

    private static void collect(PDResources resources, List<PDImageXObject> out) throws Exception {
        assertNotNull(resources);
        for (COSName name : resources.getXObjectNames()) {
            PDXObject x = resources.getXObject(name);
            if (x instanceof PDImageXObject image) {
                out.add(image);
            } else if (x instanceof PDFormXObject inner && inner.getResources() != null) {
                collect(inner.getResources(), out);
            }
        }
    }

    private static int[] pixels(BufferedImage image) {
        int[] rgb = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        for (int i = 0; i < rgb.length; i++) {
            rgb[i] &= 0xFFFFFF;
        }
        return rgb;
    }
}
