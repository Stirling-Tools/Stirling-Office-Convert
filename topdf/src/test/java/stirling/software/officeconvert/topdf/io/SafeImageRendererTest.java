package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.sl.usermodel.PictureData.PictureType;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.topdf.testing.Fixtures;
import stirling.software.officeconvert.topdf.testing.HostileImagePlugin;
import stirling.software.officeconvert.topdf.testing.NoNetwork;

class SafeImageRendererTest {

    private static byte[] svg(NoNetwork net) {
        return ("<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\""
                + " xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\"10\" height=\"10\">"
                + "<rect width=\"10\" height=\"10\" fill=\"#00ff00\"/><image xlink:href=\"" + net.url("svg.png")
                + "\" width=\"10\" height=\"10\"/><script>fetch('" + net.canaryUrl("js") + "')</script></svg>")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void poiDrawsThePngOfficeKeepsBesideAnSvg() throws Exception {
        try (NoNetwork net = NoNetwork.start(); XMLSlideShow ppt = new XMLSlideShow()) {
            ppt.setPageSize(new Dimension(200, 100));
            XSLFSlide slide = ppt.createSlide();
            XSLFPictureShape both = slide.createPicture(ppt.addPicture(Fixtures.png(10, 10, Color.RED), PictureType.PNG));
            both.setAnchor(new Rectangle2D.Double(10, 10, 80, 80));
            XSLFPictureData svg = ppt.addPicture(svg(net), PictureType.SVG);
            both.setSvgImage(svg);
            XSLFPictureShape plain = slide.createPicture(ppt.addPicture(Fixtures.png(10, 10, Color.BLUE), PictureType.PNG));
            plain.setAnchor(new Rectangle2D.Double(110, 10, 80, 80));
            try (HostileImagePlugin plugin = HostileImagePlugin.register()) {
                BufferedImage page = page();
                Graphics2D g = page.createGraphics();
                try {
                    SafeImageRenderer.draw(g, slide);
                } finally {
                    g.dispose();
                }
                assertEquals(Color.RED.getRGB(), page.getRGB(50, 50));
                assertEquals(Color.BLUE.getRGB(), page.getRGB(150, 50));

                BufferedImage one = page();
                Graphics2D g2 = one.createGraphics();
                try {
                    SafeImageRenderer.draw(g2, both);
                } finally {
                    g2.dispose();
                }
                assertEquals(Color.RED.getRGB(), one.getRGB(50, 50));
                assertEquals(Color.WHITE.getRGB(), one.getRGB(150, 50));
                plugin.assertNeverUsed();
            }
            net.assertNothingConnected();
        }
    }

    @Test
    void noPictureEverReachesAThirdPartyImagePlugin() throws Exception {
        Map<String, byte[]> rasters = new LinkedHashMap<>();
        BufferedImage rgb = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);
        for (String format : new String[] {"png", "jpeg", "gif", "bmp", "tiff"}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(rgb, format, out);
            rasters.put(format, out.toByteArray());
        }
        try (NoNetwork net = NoNetwork.start(); HostileImagePlugin plugin = HostileImagePlugin.register();
                PDDocument doc = new PDDocument()) {
            for (Map.Entry<String, byte[]> r : rasters.entrySet()) {
                assertEquals(8, PictureDecoder.decode(doc, r.getValue()).pixelWidth(), r.getKey());
                SafeImageRenderer renderer = new SafeImageRenderer();
                renderer.loadImage(r.getValue(), "image/svg+xml");
                assertEquals(6, renderer.getImage().getHeight(), r.getKey());
            }
            byte[] svg = svg(net);
            byte[] padded = ("\n  " + new String(svg, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            for (byte[] markup : new byte[][] {svg, padded}) {
                assertThrows(IOException.class, () -> PictureDecoder.decode(doc, markup));
                assertThrows(IOException.class, () -> PictureDecoder.pixelSize(markup));
                assertThrows(IOException.class, () -> PictureDecoder.readRaster(markup, 1000));
                assertThrows(IOException.class, () -> new SafeImageRenderer().loadImage(markup, "image/png"));
            }
            plugin.assertNeverUsed();
            net.assertNothingConnected();
        }
    }

    private static BufferedImage page() {
        BufferedImage img = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 200, 100);
        g.dispose();
        return img;
    }
}
