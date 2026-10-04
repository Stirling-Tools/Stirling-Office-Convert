package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

class LegacyDrawingTest {

    private static final String VML = "urn:schemas-microsoft-com:vml";

    @Test
    void vmlWithHtmlLeftoversParses() throws IOException {
        String vml = "<?xml version=\"1.0\"?><xml xmlns:v=\"" + VML + "\" xmlns:o=\"urn:schemas-microsoft-com:office:office\">"
                + "<v:shape id=\"a\" style='position:absolute'><![if gte mso 9]><v:imagedata o:relid=\"rId1\"/>"
                + "<![endif]><v:textbox><div>One<br>Two&nbsp;three</div></v:textbox></v:shape></xml>";
        Document d = VmlXml.parse(vml.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, d.getElementsByTagNameNS(VML, "imagedata").getLength());
        assertEquals("OneTwo three", d.getElementsByTagNameNS(VML, "textbox").item(0).getTextContent());
    }

    @Test
    void vmlStillRefusesADoctypeAndOversizedParts() {
        String doctype = "<!DOCTYPE x [<!ENTITY e SYSTEM \"http://example.invalid/\">]><xml>&e;</xml>";
        assertThrows(IOException.class, () -> VmlXml.parse(doctype.getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> VmlXml.parse(new byte[VmlXml.MAX_BYTES + 1]));
    }

    @Test
    void aPatternCopyFillsItsRectangleWithTheBrush() throws IOException {
        byte[] wmf = wmf(true);
        try (PDDocument doc = new PDDocument()) {
            DecodedPicture p = PictureDecoder.decode(doc, wmf);
            assertTrue(p.vector(), p.toString());
            String content = content(p);
            assertTrue(content.contains(" f") || content.contains("\nf"), content);
            assertTrue(content.contains("0.50196 0 0 sc") || content.contains("0.50196 0 0 rg")
                    || content.contains("0.502 0 0"), content);
        }
    }

    @Test
    void anOpaqueTextRecordFillsItsRectangleWithTheBackground() throws IOException {
        byte[] wmf = wmf(false);
        try (PDDocument doc = new PDDocument()) {
            DecodedPicture p = PictureDecoder.decode(doc, wmf);
            String content = content(p);
            assertTrue(content.contains(" f") || content.contains("\nf"), content);
        }
    }

    private static String content(DecodedPicture p) throws IOException {
        try (InputStream in = p.form().getContents()) {
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    // A dark red rectangle drawn by a pattern copy, or by an opaque text record with no text
    private static byte[] wmf(boolean patBlt) {
        ByteArrayOutputStream records = new ByteArrayOutputStream();
        record(records, 0x020B, 0, 0);
        record(records, 0x020C, 40, 100);
        if (patBlt) {
            record(records, 0x02FC, 0, 0x0080, 0, 0);
            record(records, 0x012D, 0);
            record(records, 0x061D, 0x0021, 0x00F0, 40, 100, 0, 0);
        } else {
            record(records, 0x0201, 0x0080, 0);
            record(records, 0x0A32, 0, 0, 0, 0x0002, 0, 0, 100, 40);
        }
        record(records, 0x0000);
        byte[] body = records.toByteArray();
        ByteBuffer b = ByteBuffer.allocate(18 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 1).putShort((short) 9).putShort((short) 0x0300).putInt((18 + body.length) / 2)
                .putShort((short) 1).putInt(9).putShort((short) 0).put(body);
        return b.array();
    }

    private static void record(ByteArrayOutputStream out, int function, int... words) {
        ByteBuffer b = ByteBuffer.allocate(6 + 2 * words.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(3 + words.length).putShort((short) function);
        for (int w : words) {
            b.putShort((short) w);
        }
        out.writeBytes(b.array());
    }
}
