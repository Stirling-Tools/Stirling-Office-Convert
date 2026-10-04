package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.common.usermodel.fonts.FontInfo;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.topdf.font.FontLibrary;

class EmfTextSpacingTest {

    private static final int WIDTH = 2000;

    private static final int HEIGHT = 100;

    @Test
    void eachWordStartsWhereTheSpacingArrayPutsIt() throws IOException {
        BufferedImage page = render(text("x W", 1500, 10, 40));
        int[] ink = inkColumns(page);
        assertTrue(ink[1] > page.getWidth() * 0.7, "second word drawn at " + ink[1] + " of " + page.getWidth());
        assertTrue(ink[0] < page.getWidth() * 0.1);
    }

    @Test
    void aMissingFamilyIsDrawnWithAnInstalledStandIn() {
        String name = "No Such Family Anywhere";
        String expected = FontLibrary.standIns(name).stream().filter(EmfTextSpacingTest::installed).findFirst()
                .orElse(null);
        assumeTrue(expected != null, "no stand-in font installed");
        FontInfo missing = () -> name;
        assertEquals(expected, MetafileFonts.INSTANCE.getMappedFont(null, missing).getTypeface());
        FontInfo symbol = () -> "Symbol";
        assertEquals("Symbol", MetafileFonts.INSTANCE.getMappedFont(null, symbol).getTypeface());
    }

    private static boolean installed(String family) {
        return family.equalsIgnoreCase(new Font(family, Font.PLAIN, 10).getFamily(Locale.ROOT));
    }

    private static BufferedImage render(byte[] emf) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            DecodedPicture p = Metafiles.render(doc, emf, PictureDecoder.Kind.EMF);
            PDPage page = new PDPage(new PDRectangle(p.naturalWidth(), p.naturalHeight()));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                if (p.vector()) {
                    cs.drawForm(p.form());
                } else {
                    cs.drawImage(p.image(), 0, 0, p.naturalWidth(), p.naturalHeight());
                }
            }
            return new PDFRenderer(doc).renderImage(0, 2);
        }
    }

    private static int[] inkColumns(BufferedImage img) {
        int first = -1;
        int last = -1;
        for (int x = 0; x < img.getWidth(); x++) {
            for (int y = 0; y < img.getHeight(); y++) {
                if ((img.getRGB(x, y) & 0xFF) < 128) {
                    first = first < 0 ? x : first;
                    last = x;
                    break;
                }
            }
        }
        return new int[] {first, last};
    }

    private static byte[] text(String s, int... dx) {
        return text(s, dx, "Arial");
    }

    private static byte[] text(String s, int[] dx, String family) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ByteBuffer font = le(104).putInt(82).putInt(104).putInt(1).putInt(-40).putInt(0).putInt(0).putInt(0)
                .putInt(400).putInt(0).putInt(0);
        byte[] face = family.getBytes(StandardCharsets.UTF_16LE);
        font.put(face, 0, Math.min(face.length, 62));
        write(body, font.array());
        write(body, le(12).putInt(37).putInt(12).putInt(1).array());
        byte[] chars = s.getBytes(StandardCharsets.UTF_16LE);
        int padded = (chars.length + 3) & ~3;
        int size = 76 + padded + 4 * dx.length;
        ByteBuffer t = le(size).putInt(84).putInt(size).putInt(0).putInt(0).putInt(WIDTH).putInt(HEIGHT).putInt(1)
                .putFloat(1).putFloat(1).putInt(0).putInt(60).putInt(s.length()).putInt(76).putInt(4).putInt(0)
                .putInt(0).putInt(WIDTH).putInt(HEIGHT).putInt(76 + padded);
        t.put(chars).position(76 + padded);
        for (int d : dx) {
            t.putInt(d);
        }
        write(body, t.array());
        write(body, le(20).putInt(14).putInt(20).putInt(0).putInt(16).putInt(20).array());
        byte[] records = body.toByteArray();
        ByteBuffer h = le(88).putInt(1).putInt(88).putInt(0).putInt(0).putInt(WIDTH - 1).putInt(HEIGHT - 1)
                .putInt(0).putInt(0).putInt(WIDTH * 2645 / 100).putInt(HEIGHT * 2645 / 100).putInt(0x464D4520)
                .putInt(0x10000).putInt(88 + records.length).putInt(5).putShort((short) 2).putShort((short) 0)
                .putInt(0).putInt(0).putInt(0).putInt(1920).putInt(1080).putInt(508).putInt(285);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, h.array());
        write(out, records);
        return out.toByteArray();
    }

    private static ByteBuffer le(int n) {
        return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void write(ByteArrayOutputStream out, byte[] b) {
        out.write(b, 0, b.length);
    }
}
