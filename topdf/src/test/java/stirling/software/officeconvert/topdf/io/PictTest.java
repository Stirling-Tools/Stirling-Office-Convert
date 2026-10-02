package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.topdf.testing.Allocation;

class PictTest {

    private static final class Pict2 {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Pict2(int bottom, int right) {
            out.writeBytes(new byte[512]);
            w16(0);
            rect(0, 0, bottom, right);
            w16(0x0011);
            w16(0x02FF);
            w16(0x0C00);
            w16(0xFFFF);
            out.writeBytes(new byte[22]);
        }

        Pict2 w16(int v) {
            out.write(v >> 8 & 0xFF);
            out.write(v & 0xFF);
            return this;
        }

        Pict2 w32(long v) {
            w16((int) (v >> 16));
            return w16((int) v);
        }

        Pict2 bytes(int... b) {
            for (int x : b) {
                out.write(x);
            }
            return this;
        }

        Pict2 rect(int top, int left, int bottom, int right) {
            return w16(top).w16(left).w16(bottom).w16(right);
        }

        Pict2 pixmap(int packType, int pixelType, int pixelSize, int cmpCount, int cmpSize) {
            return w16(0).w16(packType).w32(0).w32(0x00480000).w32(0x00480000).w16(pixelType).w16(pixelSize)
                    .w16(cmpCount).w16(cmpSize).w32(0).w32(0).w32(0);
        }

        Pict2 align() {
            if (out.size() % 2 != 0) {
                out.write(0);
            }
            return this;
        }

        byte[] end() {
            align();
            w16(0x00FF);
            return out.toByteArray();
        }
    }

    @Test
    void packedAndDirectBitmapsKeepTheirPixels() throws Exception {
        Pict2 p = new Pict2(3, 8);
        p.w16(0x0098).w16(0x8000 | 8).rect(0, 0, 2, 8).pixmap(0, 0, 8, 1, 8);
        p.w32(0).w16(0).w16(1).w16(0).w16(0xFFFF).w16(0xFFFF).w16(0xFFFF).w16(1).w16(0xFFFF).w16(0).w16(0);
        p.rect(0, 0, 2, 8).rect(0, 0, 2, 8).w16(0);
        p.bytes(2, 0xF9, 1).bytes(9, 7, 0, 1, 0, 1, 0, 1, 0, 1).align();
        p.w16(0x009A).w32(0xFF).w16(0x8000 | 32).rect(0, 0, 1, 8).pixmap(4, 16, 32, 3, 8);
        p.rect(0, 0, 1, 8).rect(2, 0, 3, 8).w16(0);
        p.bytes(6, 0xF9, 0x00, 0xF9, 0x80, 0xF9, 0xFF);
        byte[] pict = p.end();
        assertEquals(PictureDecoder.Kind.PICT, PictureDecoder.sniff(pict));
        assertEquals(PictureDecoder.Kind.PICT, PictureDecoder.sniff(Arrays.copyOfRange(pict, 512, pict.length)));
        try (PDDocument doc = new PDDocument()) {
            DecodedPicture d = PictureDecoder.decode(doc, pict);
            assertTrue(d.vector());
            assertEquals(8, d.naturalWidth(), 0.01);
            assertEquals(3, d.naturalHeight(), 0.01);
            List<BufferedImage> images = images(d.form().getResources());
            assertEquals(2, images.size());
            BufferedImage indexed = images.stream().filter(i -> i.getHeight() == 2).findFirst().orElseThrow();
            assertEquals(0xFF0000, indexed.getRGB(0, 0) & 0xFFFFFF);
            assertEquals(0xFFFFFF, indexed.getRGB(0, 1) & 0xFFFFFF);
            assertEquals(0xFF0000, indexed.getRGB(1, 1) & 0xFFFFFF);
            BufferedImage direct = images.stream().filter(i -> i.getHeight() == 1).findFirst().orElseThrow();
            assertEquals(0x0080FF, direct.getRGB(5, 0) & 0xFFFFFF);
        }
    }

    @Test
    void shapesAndQuickTimeJpegsAreDrawn() throws Exception {
        BufferedImage src = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                src.setRGB(x, y, 0x00C000);
            }
        }
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(src, "jpeg", jpeg);
        Pict2 p = new Pict2(40, 40);
        p.w16(0x001A).w16(0xFFFF).w16(0).w16(0);
        p.w16(0x0031).rect(0, 0, 20, 40);
        p.w16(0x8200).w32(68 + jpeg.size()).w16(0);
        p.w32(0x10000).w32(0).w32(0).w32(0).w32(0x10000).w32(0).w32(0).w32(20L << 16).w32(0x40000000);
        p.w32(0).rect(0, 0, 0, 0).w16(0).rect(0, 0, 16, 16).w32(0).w32(0);
        p.out.writeBytes(jpeg.toByteArray());
        byte[] pict = p.end();
        try (PDDocument doc = new PDDocument()) {
            DecodedPicture d = PictureDecoder.decode(doc, pict);
            assertEquals(1, images(d.form().getResources()).size());
            PDPage page = new PDPage(new PDRectangle(40, 40));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawForm(d.form());
            }
            BufferedImage shown = new PDFRenderer(doc).renderImageWithDPI(0, 72);
            int top = shown.getRGB(20, 5);
            int bottom = shown.getRGB(8, 28);
            assertTrue((top >> 16 & 0xFF) > 200 && (top >> 8 & 0xFF) < 60, Integer.toHexString(top));
            assertTrue((bottom >> 8 & 0xFF) > 150 && (bottom >> 16 & 0xFF) < 80, Integer.toHexString(bottom));
        }
    }

    @Test
    void aBrokenPictureDrawsWhatCameBeforeTheDamage() throws Exception {
        Pict2 p = new Pict2(10, 10);
        p.w16(0x0031).rect(0, 0, 10, 10);
        p.w16(0x0098).w16(0x8000 | 8).rect(0, 0, 30000, 30000);
        byte[] pict = p.end();
        try (PDDocument doc = new PDDocument()) {
            assertTrue(PictureDecoder.decode(doc, pict).vector());
            assertThrows(IOException.class, () -> PictureDecoder.decode(doc, Arrays.copyOf(pict, 520)));
        }
    }

    @Test
    void aQuickTimeSizePastTheEndAllocatesNothing() throws Exception {
        Pict2 p = new Pict2(40, 40);
        p.w16(0x0031).rect(0, 0, 20, 40);
        p.w16(0x8200).w32(0x7FFFFF00L).w16(0);
        p.w32(0x10000).w32(0).w32(0).w32(0).w32(0x10000).w32(0).w32(0).w32(0).w32(0x40000000);
        p.w32(0).rect(0, 0, 0, 0).w16(0).rect(0, 0, 16, 16).w32(0).w32(0);
        byte[] pict = p.end();
        try (PDDocument doc = new PDDocument()) {
            Allocation.Measured m = Allocation.measure(() -> assertTrue(PictureDecoder.decode(doc, pict).vector()));
            assertEquals(null, m.failure());
            assertTrue(m.bytes() < 64L << 20, "allocated " + m.megabytes() + " MB");
        }
    }

    private static byte[] packedBitmaps(int count, int rowBytes, int width, int height) {
        Pict2 p = new Pict2(100, 100);
        for (int i = 0; i < count; i++) {
            p.w16(0x009A).w32(0xFF).w16(0x8000 | rowBytes).rect(0, 0, height, width).pixmap(4, 16, 32, 3, 8);
            p.rect(0, 0, height, width).rect(0, 0, 100, 100).w16(0);
            for (int y = 0; y < height; y++) {
                if (rowBytes > 250) {
                    p.w16(0);
                } else {
                    p.bytes(0);
                }
            }
            p.align();
        }
        return p.end();
    }

    @Test
    void directBitmapRowsMustHoldTheirPixels() throws Exception {
        byte[] pict = packedBitmaps(10, 200, 5000, 6400);
        try (PDDocument doc = new PDDocument()) {
            Allocation.Measured m = Allocation.measure(() -> PictureDecoder.decode(doc, pict));
            assertTrue(m.failure() == null || m.failure() instanceof IOException, String.valueOf(m.failure()));
            assertTrue(m.bytes() < 256L << 20, "allocated " + m.megabytes() + " MB");
        }
    }

    @Test
    void oneSmallPictureDecodesABoundedNumberOfPixels() throws Exception {
        byte[] pict = packedBitmaps(8, 16_000, 4000, 4000);
        try (PDDocument doc = new PDDocument()) {
            Allocation.Measured m = Allocation.measure(() -> PictureDecoder.decode(doc, pict));
            assertTrue(m.failure() == null || m.failure() instanceof IOException, String.valueOf(m.failure()));
            assertTrue(m.bytes() < 1536L << 20, "allocated " + m.megabytes() + " MB");
        }
    }

    private static List<BufferedImage> images(PDResources res) throws IOException {
        List<BufferedImage> out = new ArrayList<>();
        for (COSName n : res.getXObjectNames()) {
            if (res.getXObject(n) instanceof PDImageXObject x) {
                out.add(x.getOpaqueImage());
            }
        }
        return out;
    }
}
