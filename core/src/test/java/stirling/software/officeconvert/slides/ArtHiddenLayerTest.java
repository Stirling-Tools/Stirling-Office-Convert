package stirling.software.officeconvert.slides;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.build.PlacedContent;
import stirling.software.officeconvert.extract.HiddenContent;
import stirling.software.officeconvert.model.Picture;

class ArtHiddenLayerTest {

    @Test
    void visibleArtAfterAHiddenFillIsStillDrawn() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(720, 405));
            doc.addPage(page);
            PDOptionalContentProperties layers = new PDOptionalContentProperties();
            PDOptionalContentGroup group = new PDOptionalContentGroup("Layer");
            layers.addGroup(group);
            layers.setGroupEnabled(group, false);
            doc.getDocumentCatalog().setOCProperties(layers);
            PDResources resources = new PDResources();
            COSDictionary named = new COSDictionary();
            named.setItem(COSName.getPDFName("L1"), group);
            resources.getCOSObject().setItem(COSName.PROPERTIES, named);
            page.setResources(resources);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginMarkedContent(COSName.OC, group);
                cs.setNonStrokingColor(1f, 0f, 0f);
                cs.addRect(400, 100, 200, 200);
                cs.fill();
                cs.endMarkedContent();
                cs.setStrokingColor(0f, 0f, 1f);
                cs.setLineWidth(6);
                cs.moveTo(0, 50);
                cs.curveTo(100, 300, 260, 300, 360, 50);
                cs.stroke();
            }
            AffineTransform toDisplay = new AffineTransform(1, 0, 0, -1, 0, 405);
            PaintOrder paint = PaintOrder.read(page, toDisplay, 720, 405, HiddenContent.of(doc));
            Store store = new Store();
            List<Art.Piece> pieces = new Art(doc, store, 150f, false, g -> false)
                    .pieces(paint, Set.of(), 0, toDisplay, 720, 405);
            assertEquals(1, pieces.size());
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(store.bytes.getFirst()));
            int blue = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int argb = image.getRGB(x, y);
                    if ((argb >>> 24) > 128 && (argb & 0xFF) > 200 && (argb >> 16 & 0xFF) < 80) {
                        blue++;
                    }
                }
            }
            assertTrue(blue > 100, "the visible curve must be drawn, found " + blue + " blue pixels");
        }
    }

    private static final class Store implements PlacedContent.MediaStore {
        final List<byte[]> bytes = new ArrayList<>();

        @Override
        public Picture.MediaRef media(Object key) {
            return null;
        }

        @Override
        public Picture.MediaRef media(byte[] data, String ext, int pixelWidth, int pixelHeight, Object key) {
            bytes.add(data);
            return new Picture.MediaRef("m" + bytes.size(), ext, pixelWidth, pixelHeight);
        }
    }
}
