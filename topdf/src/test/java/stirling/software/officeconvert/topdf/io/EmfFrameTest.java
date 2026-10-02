package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

class EmfFrameTest {

    private static byte[] blackSquareInALargerFrame() {
        ByteBuffer b = ByteBuffer.allocate(88 + 24 + 12 + 24 + 20).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(1).putInt(88);
        b.putInt(0).putInt(0).putInt(99).putInt(99);
        b.putInt(0).putInt(0).putInt(1080).putInt(1080);
        b.putInt(0x464D4520).putInt(0x10000).putInt(b.capacity()).putInt(5).putShort((short) 1).putShort((short) 0);
        b.putInt(0).putInt(0).putInt(0).putInt(1000).putInt(1000).putInt(100).putInt(100);
        b.putInt(39).putInt(24).putInt(1).putInt(0).putInt(0).putInt(0);
        b.putInt(37).putInt(12).putInt(1);
        b.putInt(43).putInt(24).putInt(0).putInt(0).putInt(100).putInt(100);
        b.putInt(14).putInt(20).putInt(0).putInt(16).putInt(20);
        return b.array();
    }

    @Test
    void anEmfFillsItsFrameNotJustTheBoundsOfWhatItDraws() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            DecodedPicture p = PictureDecoder.decode(doc, blackSquareInALargerFrame());
            assertTrue(p.vector());
            float w = p.naturalWidth();
            float h = p.naturalHeight();
            assertEquals(1, w / h, 0.01);
            PDPage page = new PDPage(new PDRectangle(w, h));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawForm(p.form());
            }
            BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(0, 72);
            assertTrue((img.getRGB(img.getWidth() / 2, img.getHeight() / 2) & 0xFFFFFF) < 0x404040);
            assertEquals(0xFFFFFF, img.getRGB(img.getWidth() * 97 / 100, img.getHeight() * 97 / 100) & 0xFFFFFF);
        }
    }
}
