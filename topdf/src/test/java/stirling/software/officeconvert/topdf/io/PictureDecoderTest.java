package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.topdf.testing.Fixtures;
import stirling.software.officeconvert.topdf.testing.NoNetwork;

class PictureDecoderTest {

    @Test
    void decodesPngIntoAnImage() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            DecodedPicture p = PictureDecoder.decode(doc, Fixtures.png(96, 48, Color.GREEN));
            assertEquals(PictureDecoder.Kind.PNG, p.kind());
            assertFalse(p.vector());
            assertEquals(96, p.pixelWidth());
            assertEquals(72f, p.naturalWidth(), 0.01f);
            assertEquals(36f, p.naturalHeight(), 0.01f);
        }
    }

    @Test
    void refusesHugePicturesFromTheirHeader() throws Exception {
        byte[] png = pngHeader(20_000, 20_000);
        assertEquals(PictureDecoder.Kind.PNG, PictureDecoder.sniff(png));
        assertEquals(20_000, PictureDecoder.pixelSize(png).width);
        try (PDDocument doc = new PDDocument()) {
            IOException e = assertThrows(IOException.class, () -> PictureDecoder.decode(doc, png));
            assertTrue(e.getMessage().contains("too large"), e.getMessage());
        }
    }

    @Test
    void neverRendersSvg() throws Exception {
        try (NoNetwork net = NoNetwork.start(); PDDocument doc = new PDDocument()) {
            byte[] svg = ("<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\">"
                    + "<image xlink:href=\"" + net.url("x.png") + "\"/></svg>").getBytes(StandardCharsets.UTF_8);
            assertEquals(PictureDecoder.Kind.SVG, PictureDecoder.sniff(svg));
            assertThrows(IOException.class, () -> PictureDecoder.decode(doc, svg));
            assertThrows(IOException.class, () -> PictureDecoder.pixelSize(svg));
            SafeImageRenderer r = new SafeImageRenderer();
            assertTrue(r.canRender("image/svg+xml"));
            assertThrows(IOException.class, () -> r.loadImage(svg, "image/svg+xml"));
            assertEquals(PictureDecoder.Kind.SVG, r.kind());
            assertFalse(r.drawImage(null, null));
            assertThrows(IOException.class, () -> r.loadImage(svg, "image/png"));
            assertThrows(IOException.class, () -> r.loadImage("just text".getBytes(StandardCharsets.UTF_8), "image/png"));
            net.assertNothingConnected();
        }
    }

    @Test
    void refusesUnknownData() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            assertThrows(IOException.class, () -> PictureDecoder.decode(doc, "just text".getBytes(StandardCharsets.UTF_8)));
            assertThrows(IOException.class, () -> PictureDecoder.decode(doc, new byte[0]));
        }
    }

    @Test
    void refusesMarkupHiddenInMetafiles() {
        assertTrue(MetafileGuard.markup("  <svg/>".getBytes(StandardCharsets.UTF_8)));
        assertTrue(MetafileGuard.markup("﻿<?xml version=\"1.0\"?><svg/>".getBytes(StandardCharsets.UTF_8)));
        assertTrue(MetafileGuard.markup("<svg/>".getBytes(StandardCharsets.UTF_16)));
        assertFalse(MetafileGuard.markup(Fixtures.png(2, 2, Color.RED)));
    }

    @Test
    void safeRendererDecodesBitmapsWithinTheBudget() throws Exception {
        SafeImageRenderer r = new SafeImageRenderer();
        r.loadImage(Fixtures.png(10, 20, Color.RED), "image/png");
        assertEquals(PictureDecoder.Kind.PNG, r.kind());
        assertEquals(10, r.getImage().getWidth());
        assertThrows(IOException.class, () -> r.loadImage(pngHeader(20_000, 20_000), "image/png"));
    }

    static byte[] pngHeader(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) 8).put((byte) 2)
                .put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(out, "IHDR", ihdr.array());
        chunk(out, "IDAT", new byte[] {0x78, (byte) 0x9C, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01});
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        out.write(ByteBuffer.allocate(4).putInt(data.length).array());
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.write(t);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
