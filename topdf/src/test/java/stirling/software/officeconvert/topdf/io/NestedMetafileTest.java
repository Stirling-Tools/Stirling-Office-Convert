package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.topdf.testing.Emf;
import stirling.software.officeconvert.topdf.testing.HostileImagePlugin;

class NestedMetafileTest {

    @Test
    void metafilesUsedAsBrushesNeverReachThirdPartyImagePlugins() throws Exception {
        for (byte[] emf : new byte[][] {Emf.brushedSvg(), Emf.brushedWmfMagic()}) {
            try (HostileImagePlugin plugin = HostileImagePlugin.register(); PDDocument doc = new PDDocument()) {
                try {
                    PictureDecoder.decode(doc, emf);
                } catch (IOException refused) {
                    assertTrue(refused.getMessage().contains("markup"), refused.getMessage());
                }
                assertEquals(0, plugin.readersCreated());
            }
        }
    }

    @Test
    void theGuardLooksInsideTextureBrushes() {
        IOException e = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> MetafileGuard.check(Emf.brushedSvg(), true, PictureDecoder.DECODE_PIXELS, PictureDecoder.DECODE_PIXELS));
        assertTrue(e.getMessage().contains("markup"), e.getMessage());
    }

    @Test
    void aBitmapInsideAMetafileKeepsItsPixels() throws Exception {
        java.awt.image.BufferedImage src = new java.awt.image.BufferedImage(8, 6, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 8; x++) {
                src.setRGB(x, y, (x * 30) << 16 | (y * 40) << 8 | 0x80);
            }
        }
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(src, "png", png);
        byte[] emf = Emf.of(100, 100).image(0, Emf.bitmapImage(8, 6, png.toByteArray())).drawImage(0, 8, 6, 0, 0, 90, 90)
                .bytes();
        try (PDDocument doc = new PDDocument()) {
            DecodedPicture p = PictureDecoder.decode(doc, emf);
            assertTrue(p.vector());
            org.apache.pdfbox.pdmodel.PDResources res = p.form().getResources();
            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject img = null;
            for (org.apache.pdfbox.cos.COSName n : res.getXObjectNames()) {
                if (res.getXObject(n) instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject x) {
                    img = x;
                }
            }
            assertTrue(img != null, "no picture in the metafile's form");
            java.awt.image.BufferedImage back = img.getOpaqueImage();
            for (int y = 0; y < 6; y++) {
                for (int x = 0; x < 8; x++) {
                    assertEquals(src.getRGB(x, y) & 0xFFFFFF, back.getRGB(x, y) & 0xFFFFFF, x + "," + y);
                }
            }
        }
    }

    @Test
    void aBrushMetafileWithAHugeHeaderIsDrawnSmall() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            DecodedPicture p = PictureDecoder.decode(doc, Emf.brushedHuge(46_000));
            assertTrue(p.vector());
        }
        SafeImageRenderer r = new SafeImageRenderer();
        r.loadImage(Emf.of(46_000, 46_000).fillRect(0, 0, 0, 90, 90).bytes(), "image/x-emf");
        java.awt.image.BufferedImage img = r.getImage();
        assertTrue((long) img.getWidth() * img.getHeight() <= SafeImageRenderer.MAX_IMAGE_PIXELS,
                img.getWidth() + "x" + img.getHeight());
    }
}
