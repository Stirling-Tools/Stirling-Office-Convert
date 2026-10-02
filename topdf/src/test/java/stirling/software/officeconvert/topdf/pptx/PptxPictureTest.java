package stirling.software.officeconvert.topdf.pptx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.Fixtures;

class PptxPictureTest {

    @TempDir
    Path dir;

    @Test
    void duotonePicturesAreRecolouredWithAnyColourModel() throws IOException {
        String pic = "<p:pic " + Decks.NS + "><p:nvPicPr><p:cNvPr id=\"7\" name=\"Picture\"/><p:cNvPicPr/><p:nvPr/>"
                + "</p:nvPicPr><p:blipFill><a:blip r:embed=\"rIdP\"><a:duotone><a:prstClr val=\"black\"/>"
                + "<a:srgbClr val=\"FF0000\"/></a:duotone></a:blip><a:stretch><a:fillRect/></a:stretch></p:blipFill>"
                + "<p:spPr><a:xfrm><a:off x=\"1270000\" y=\"1270000\"/><a:ext cx=\"1270000\" cy=\"1270000\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>";
        Fixtures.Zip z = Fixtures.edit(Decks.slideXml(pic));
        z.put("ppt/media/white.png", Fixtures.png(4, 4, Color.WHITE));
        z.defaultType("png", "image/png");
        z.relationship("/ppt/slides/slide1.xml", "rIdP", Fixtures.REL + "image", "../media/white.png", false);
        BufferedImage img = Decks.convert(dir, "duotone.pptx", z.bytes()).render(0, 72);
        assertEquals(Color.RED.getRGB(), img.getRGB(150, 150));
    }

    @Test
    void duotoneBackgroundPicturesAreRecolouredWithAnyColourModel() throws IOException {
        Fixtures.Zip z = Fixtures.edit(Decks.slideXml(""));
        z.insertAfter("ppt/slides/slide1.xml", "<p:cSld>", "<p:bg><p:bgPr><a:blipFill><a:blip xmlns:r=\"" + Decks.R + "\" r:embed=\"rIdP\">"
                + "<a:duotone><a:schemeClr val=\"accent2\"><a:shade val=\"45000\"/></a:schemeClr><a:prstClr "
                + "val=\"red\"/></a:duotone></a:blip><a:stretch><a:fillRect/></a:stretch></a:blipFill><a:effectLst/>"
                + "</p:bgPr></p:bg>");
        z.put("ppt/media/white.png", Fixtures.png(4, 4, Color.WHITE));
        z.defaultType("png", "image/png");
        z.relationship("/ppt/slides/slide1.xml", "rIdP", Fixtures.REL + "image", "../media/white.png", false);
        Decks.Converted c = Decks.convert(dir, "duotonebg.pptx", z.bytes());
        assertEquals(Color.RED.getRGB(), c.render(0, 72).getRGB(150, 150), c.result().warnings().toString());
    }

    @Test
    void theStandInForAnSvgHidesNoBlackBehindItsTransparentPixels() throws IOException {
        String pic = "<p:pic " + Decks.NS + "><p:nvPicPr><p:cNvPr id=\"7\" name=\"Graphic\"/><p:cNvPicPr/><p:nvPr/>"
                + "</p:nvPicPr><p:blipFill><a:blip r:embed=\"rIdP\"><a:extLst><a:ext "
                + "uri=\"{96DAC541-7B7A-43D3-8B79-37D633B846F1}\"><asvg:svgBlip xmlns:asvg=\"http://schemas.microsoft.com/"
                + "office/drawing/2016/SVG/main\" r:embed=\"rIdS\"/></a:ext></a:extLst></a:blip><a:stretch><a:fillRect/>"
                + "</a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x=\"1270000\" y=\"1270000\"/><a:ext cx=\"1270000\" "
                + "cy=\"1270000\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>";
        BufferedImage line = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) {
            line.setRGB(3, y, 0xFFFF0000);
        }
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(line, "png", png);
        Fixtures.Zip z = Fixtures.edit(Decks.slideXml(pic));
        z.put("ppt/media/line.png", png.toByteArray());
        z.defaultType("png", "image/png");
        z.relationship("/ppt/slides/slide1.xml", "rIdP", Fixtures.REL + "image", "../media/line.png", false);
        Decks.Converted c = Decks.convert(dir, "svgstandin.pptx", z.bytes());
        try (PDDocument d = c.open()) {
            var res = d.getPage(0).getResources();
            BufferedImage back = null;
            for (var n : res.getXObjectNames()) {
                if (res.getXObject(n) instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject x) {
                    back = x.getOpaqueImage();
                }
            }
            assertTrue(back != null);
            assertEquals(0xFF0000, back.getRGB(0, 0) & 0xFFFFFF);
            assertEquals(0xFF0000, back.getRGB(back.getWidth() - 1, back.getHeight() - 1) & 0xFFFFFF);
        }
    }

    @Test
    void coloursAreWrittenWithThreeSignificantDigitsLikeOffice() throws IOException {
        String sp = "<p:sp " + Decks.NS + "><p:nvSpPr><p:cNvPr id=\"8\" name=\"Teal\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>"
                + "<p:spPr><a:xfrm><a:off x=\"1270000\" y=\"1270000\"/><a:ext cx=\"1270000\" cy=\"1270000\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val=\"00800F\"/></a:solidFill>"
                + "<a:ln><a:noFill/></a:ln></p:spPr></p:sp>";
        Decks.Converted c = Decks.convert(dir, "teal.pptx", Decks.slideXml(sp));
        try (PDDocument d = c.open()) {
            String content = new String(d.getPage(0).getContents().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(content.contains("0 0.502 0.0588 sc"), content);
        }
    }

    @Test
    void aHueOffsetIsInSixtiethThousandthsOfADegree() throws IOException {
        String sp = "<p:sp " + Decks.NS + "><p:nvSpPr><p:cNvPr id=\"8\" name=\"Red\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>"
                + "<p:spPr><a:xfrm><a:off x=\"1270000\" y=\"1270000\"/><a:ext cx=\"1270000\" cy=\"1270000\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val=\"FF0000\"><a:hueOff "
                + "val=\"-99754\"/></a:srgbClr></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr></p:sp>";
        Color c = new Color(Decks.convert(dir, "hue.pptx", Decks.slideXml(sp)).render(0, 72).getRGB(150, 150));
        assertTrue(c.getRed() > 240 && c.getGreen() < 20 && c.getBlue() < 20, c.toString());
    }

    @Test
    void shapesThatUseTheBackgroundFillShowTheSlideBackground() throws IOException {
        String sp = "<p:sp " + Decks.NS + " useBgFill=\"1\"><p:nvSpPr><p:cNvPr id=\"8\" name=\"Cover\"/><p:cNvSpPr/>"
                + "<p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"1270000\" y=\"1270000\"/><a:ext cx=\"1270000\""
                + " cy=\"1270000\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:ln><a:noFill/></a:ln>"
                + "</p:spPr><p:style><a:lnRef idx=\"2\"><a:schemeClr val=\"accent1\"/></a:lnRef><a:fillRef idx=\"1\">"
                + "<a:schemeClr val=\"accent1\"/></a:fillRef><a:effectRef idx=\"0\"><a:schemeClr val=\"accent1\"/>"
                + "</a:effectRef><a:fontRef idx=\"minor\"><a:schemeClr val=\"lt1\"/></a:fontRef></p:style></p:sp>";
        Fixtures.Zip z = Fixtures.edit(Decks.slideXml(sp));
        z.insertAfter("ppt/slides/slide1.xml", "<p:cSld>", "<p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"00FF00\"/>"
                + "</a:solidFill><a:effectLst/></p:bgPr></p:bg>");
        BufferedImage img = Decks.convert(dir, "usebg.pptx", z.bytes()).render(0, 72);
        assertEquals(Color.GREEN.getRGB(), img.getRGB(150, 150));
        assertEquals(Color.GREEN.getRGB(), img.getRGB(20, 20));
    }

    @Test
    void placeholderTextWithoutAFontTakesTheThemeMinorFont() throws IOException {
        byte[] base = Decks.deck(ppt -> {
            var layout = ppt.getSlideMasters().get(0).getLayout(org.apache.poi.xslf.usermodel.SlideLayout.TITLE_ONLY);
            ppt.createSlide(layout).getPlaceholder(0).setText("Themed title");
        });
        Fixtures.Zip z = Fixtures.edit(base);
        String master = "ppt/slideMasters/slideMaster1.xml";
        z.put(master, z.text(master).replace("<a:latin typeface=\"+mj-lt\"/>", ""));
        String theme = "ppt/theme/theme1.xml";
        z.put(theme, z.text(theme).replaceFirst("(<a:minorFont>\\s*<a:latin typeface=\")[^\"]*", "$1Courier New"));
        Decks.Converted c = Decks.convert(dir, "themefont.pptx", z.bytes());
        assertTrue(c.text().contains("Themed title"), c.text());
        String expected = stirling.software.officeconvert.topdf.font.FontLibrary.system()
                .find("Courier New", false, false).postScriptName();
        try (PDDocument d = c.open()) {
            boolean found = false;
            for (var name : d.getPage(0).getResources().getFontNames()) {
                found |= d.getPage(0).getResources().getFont(name).getName().endsWith(expected);
            }
            assertTrue(found, expected);
        }
    }

    @Test
    void aLayoutThemeOverrideRecoloursItsSlides() throws IOException {
        String sp = "<p:sp " + Decks.NS + "><p:nvSpPr><p:cNvPr id=\"8\" name=\"Light\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>"
                + "<p:spPr><a:xfrm><a:off x=\"1270000\" y=\"1270000\"/><a:ext cx=\"1270000\" cy=\"1270000\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:solidFill><a:schemeClr val=\"bg1\"/></a:solidFill>"
                + "<a:ln><a:noFill/></a:ln></p:spPr></p:sp>";
        Fixtures.Zip z = Fixtures.edit(Decks.slideXml(sp));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("slideLayouts/(slideLayout[0-9]+[.]xml)")
                .matcher(z.text("ppt/slides/_rels/slide1.xml.rels"));
        assertTrue(m.find());
        String layout = "/ppt/slideLayouts/" + m.group(1);
        String scheme = "<a:clrScheme name=\"Override\"><a:dk1><a:srgbClr val=\"000000\"/></a:dk1><a:lt1>"
                + "<a:srgbClr val=\"FF0000\"/></a:lt1><a:dk2><a:srgbClr val=\"000000\"/></a:dk2><a:lt2><a:srgbClr"
                + " val=\"FFFFFF\"/></a:lt2><a:accent1><a:srgbClr val=\"000000\"/></a:accent1><a:accent2><a:srgbClr"
                + " val=\"000000\"/></a:accent2><a:accent3><a:srgbClr val=\"000000\"/></a:accent3><a:accent4><a:srgbClr"
                + " val=\"000000\"/></a:accent4><a:accent5><a:srgbClr val=\"000000\"/></a:accent5><a:accent6><a:srgbClr"
                + " val=\"000000\"/></a:accent6><a:hlink><a:srgbClr val=\"0000FF\"/></a:hlink><a:folHlink><a:srgbClr"
                + " val=\"800080\"/></a:folHlink></a:clrScheme>";
        z.put("ppt/theme/themeOverride1.xml", "<a:themeOverride xmlns:a=\"" + Decks.A + "\">" + scheme
                + "</a:themeOverride>");
        z.override("/ppt/theme/themeOverride1.xml", "application/vnd.openxmlformats-officedocument.themeOverride+xml");
        z.relationship(layout, "rIdTo", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/themeOverride",
                "../theme/themeOverride1.xml", false);
        BufferedImage img = Decks.convert(dir, "override.pptx", z.bytes()).render(0, 72);
        assertEquals(Color.RED.getRGB(), img.getRGB(150, 150));
    }

    @Test
    void aPicturePlaceholderTakesTheShapeOfItsLayoutPlaceholder() throws IOException {
        String xfrm = "<a:xfrm><a:off x=\"1270000\" y=\"1270000\"/><a:ext cx=\"2540000\" cy=\"2540000\"/></a:xfrm>";
        String pic = "<p:pic " + Decks.NS + "><p:nvPicPr><p:cNvPr id=\"7\" name=\"Picture\"/><p:cNvPicPr/><p:nvPr>"
                + "<p:ph type=\"pic\" idx=\"27\"/></p:nvPr></p:nvPicPr><p:blipFill><a:blip r:embed=\"rIdP\"/>"
                + "<a:stretch/></p:blipFill><p:spPr>" + xfrm + "</p:spPr></p:pic>";
        Fixtures.Zip z = Fixtures.edit(Decks.slideXml(pic));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("slideLayouts/(slideLayout[0-9]+[.]xml)")
                .matcher(z.text("ppt/slides/_rels/slide1.xml.rels"));
        assertTrue(m.find());
        z.insertBefore("ppt/slideLayouts/" + m.group(1), "</p:spTree>", "<p:sp " + Decks.NS + "><p:nvSpPr><p:cNvPr "
                + "id=\"31\" name=\"Picture Placeholder\"/><p:cNvSpPr/><p:nvPr><p:ph type=\"pic\" idx=\"27\"/></p:nvPr>"
                + "</p:nvSpPr><p:spPr>" + xfrm + "<a:prstGeom prst=\"ellipse\"><a:avLst/></a:prstGeom></p:spPr></p:sp>");
        z.put("ppt/media/red.png", Fixtures.png(4, 4, Color.RED));
        z.defaultType("png", "image/png");
        z.relationship("/ppt/slides/slide1.xml", "rIdP", Fixtures.REL + "image", "../media/red.png", false);
        BufferedImage img = Decks.convert(dir, "phgeom.pptx", z.bytes()).render(0, 72);
        assertEquals(Color.RED.getRGB(), img.getRGB(200, 200));
        assertEquals(Color.WHITE.getRGB(), img.getRGB(104, 104));
    }

    @Test
    void anEmptyPlaceholderStillShowsTheFillItTakesFromItsLayout() throws IOException {
        String xfrm = "<a:xfrm><a:off x=\"1270000\" y=\"1270000\"/><a:ext cx=\"2540000\" cy=\"2540000\"/></a:xfrm>";
        String sp = "<p:sp " + Decks.NS + "><p:nvSpPr><p:cNvPr id=\"7\" name=\"Media\"/><p:cNvSpPr/><p:nvPr>"
                + "<p:ph type=\"media\" idx=\"14\"/></p:nvPr></p:nvSpPr><p:spPr/></p:sp>";
        Fixtures.Zip z = Fixtures.edit(Decks.slideXml(sp));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("slideLayouts/(slideLayout[0-9]+[.]xml)")
                .matcher(z.text("ppt/slides/_rels/slide1.xml.rels"));
        assertTrue(m.find());
        z.insertBefore("ppt/slideLayouts/" + m.group(1), "</p:spTree>", "<p:sp " + Decks.NS + "><p:nvSpPr><p:cNvPr "
                + "id=\"31\" name=\"Media Placeholder\"/><p:cNvSpPr/><p:nvPr><p:ph type=\"media\" idx=\"14\"/></p:nvPr>"
                + "</p:nvSpPr><p:spPr>" + xfrm + "<a:solidFill><a:srgbClr val=\"FF0000\"/></a:solidFill></p:spPr>"
                + "<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang=\"en-US\"/><a:t>Click icon to add media</a:t>"
                + "</a:r></a:p></p:txBody></p:sp>");
        Decks.Converted c = Decks.convert(dir, "emptyph.pptx", z.bytes());
        assertEquals(Color.RED.getRGB(), c.render(0, 72).getRGB(200, 200));
        assertTrue(!c.text().contains("Click icon"), c.text());
    }

    @Test
    void brightnessAndContrastWashPicturesOut() throws IOException {
        String pic = "<p:pic " + Decks.NS + "><p:nvPicPr><p:cNvPr id=\"7\" name=\"Picture\"/><p:cNvPicPr/><p:nvPr/>"
                + "</p:nvPicPr><p:blipFill><a:blip r:embed=\"rIdP\"><a:lum bright=\"50000\" contrast=\"-60000\"/>"
                + "</a:blip><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x=\"1270000\""
                + " y=\"1270000\"/><a:ext cx=\"1270000\" cy=\"1270000\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/>"
                + "</a:prstGeom></p:spPr></p:pic>";
        Fixtures.Zip z = Fixtures.edit(Decks.slideXml(pic));
        z.put("ppt/media/green.png", Fixtures.png(4, 4, new Color(0, 128, 0)));
        z.defaultType("png", "image/png");
        z.relationship("/ppt/slides/slide1.xml", "rIdP", Fixtures.REL + "image", "../media/green.png", false);
        Color c = new Color(Decks.convert(dir, "lum.pptx", z.bytes()).render(0, 72).getRGB(150, 150));
        assertEquals(204, c.getRed(), 3);
        assertEquals(255, c.getGreen(), 3);
        assertEquals(204, c.getBlue(), 3);
    }

    @Test
    void greyPicturesAreRecolouredLikeTheSameColourPicture() throws IOException {
        String lum = "<a:lum bright=\"20000\"/>";
        BufferedImage grey = new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY);
        java.awt.Graphics2D g = grey.createGraphics();
        g.setColor(new Color(30, 30, 30));
        g.fillRect(0, 0, 8, 8);
        g.dispose();
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(grey, "png", png);
        Color fromGrey = new Color(Decks.convert(dir, "greylum.pptx",
                framedPicture(lum, png.toByteArray(), "grey.png", "png")).render(0, 72).getRGB(150, 150));
        Color fromRgb = new Color(Decks.convert(dir, "rgblum.pptx",
                framedPicture(lum, Fixtures.png(8, 8, new Color(30, 30, 30)), "rgb.png", "png")).render(0, 72)
                .getRGB(150, 150));
        assertEquals(fromRgb.getRed(), fromGrey.getRed(), 2, fromGrey + " vs " + fromRgb);
    }

    private static byte[] framedPicture(String effects, byte[] picture, String name, String type) {
        String pic = "<p:pic " + Decks.NS + "><p:nvPicPr><p:cNvPr id=\"7\" name=\"Picture\"/><p:cNvPicPr/><p:nvPr/>"
                + "</p:nvPicPr><p:blipFill><a:blip r:embed=\"rIdP\">" + effects + "</a:blip><a:stretch><a:fillRect/>"
                + "</a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x=\"1270000\" y=\"1270000\"/><a:ext cx=\"1270000\""
                + " cy=\"1270000\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>";
        Fixtures.Zip z = Fixtures.edit(Decks.slideXml(pic));
        z.insertAfter("ppt/slides/slide1.xml", "<p:cSld>", "<p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"00FF00\"/>"
                + "</a:solidFill><a:effectLst/></p:bgPr></p:bg>");
        z.put("ppt/media/" + name, picture);
        z.defaultType(type, "image/" + (type.equals("jpg") ? "jpeg" : type));
        z.relationship("/ppt/slides/slide1.xml", "rIdP", Fixtures.REL + "image", "../media/" + name, false);
        return z.bytes();
    }

    private static byte[] jpeg(int w, int h, Color color) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    @Test
    void aTransparentColourLetsTheBackgroundShowThrough() throws IOException {
        String change = "<a:clrChange><a:clrFrom><a:srgbClr val=\"FDFDFD\"/></a:clrFrom><a:clrTo><a:srgbClr"
                + " val=\"FDFDFD\"><a:alpha val=\"0\"/></a:srgbClr></a:clrTo></a:clrChange>";
        BufferedImage img = Decks.convert(dir, "clrchange.pptx",
                framedPicture(change, jpeg(8, 8, new Color(0xF6F8FA)), "white.jpg", "jpg")).render(0, 72);
        assertEquals(Color.GREEN.getRGB(), img.getRGB(150, 150));
        BufferedImage kept = Decks.convert(dir, "kept.pptx",
                framedPicture(change, Fixtures.png(8, 8, new Color(0xF6F8FA)), "white.png", "png")).render(0, 72);
        assertTrue((kept.getRGB(150, 150) & 0xFFFFFF) != 0x00FF00, Integer.toHexString(kept.getRGB(150, 150)));
    }

    @Test
    void grayscalePicturesLoseTheirColour() throws IOException {
        BufferedImage img = Decks.convert(dir, "gray.pptx",
                framedPicture("<a:grayscl/>", Fixtures.png(8, 8, Color.RED), "red.png", "png")).render(0, 72);
        Color c = new Color(img.getRGB(150, 150));
        assertTrue(c.getRed() == c.getGreen() && c.getGreen() == c.getBlue() && c.getRed() > 40 && c.getRed() < 120,
                c.toString());
    }

    @Test
    void softEdgesFadeThePictureIntoTheBackground() throws IOException {
        byte[] pptx = framedPicture("", Fixtures.png(20, 20, Color.RED), "red.png", "png");
        Fixtures.Zip z = Fixtures.edit(pptx);
        z.put("ppt/slides/slide1.xml", z.text("ppt/slides/slide1.xml").replace("</a:prstGeom></p:spPr></p:pic>",
                "</a:prstGeom><a:effectLst><a:softEdge rad=\"254000\"/></a:effectLst></p:spPr></p:pic>"));
        BufferedImage img = Decks.convert(dir, "soft.pptx", z.bytes()).render(0, 72);
        assertEquals(Color.RED.getRGB(), img.getRGB(150, 150));
        Color edge = new Color(img.getRGB(101, 150));
        assertTrue(edge.getGreen() > 200 && edge.getRed() < 60, edge.toString());
        Color between = new Color(img.getRGB(110, 150));
        assertTrue(between.getRed() > 40 && between.getGreen() > 40, between.toString());
    }

    @Test
    void seeThroughBackgroundGradientsFadeIntoWhite() throws IOException {
        Fixtures.Zip z = Fixtures.edit(Decks.slideXml(""));
        z.insertAfter("ppt/slides/slide1.xml", "<p:cSld>", "<p:bg><p:bgPr><a:gradFill><a:gsLst><a:gs pos=\"0\">"
                + "<a:srgbClr val=\"FF0000\"><a:alpha val=\"0\"/></a:srgbClr></a:gs><a:gs pos=\"100000\">"
                + "<a:srgbClr val=\"FF0000\"/></a:gs></a:gsLst><a:lin ang=\"0\" scaled=\"1\"/></a:gradFill>"
                + "<a:effectLst/></p:bgPr></p:bg>");
        BufferedImage img = Decks.convert(dir, "alphabg.pptx", z.bytes()).render(0, 72);
        Color left = new Color(img.getRGB(3, 270));
        Color right = new Color(img.getRGB(716, 270));
        assertTrue(left.getGreen() > 240 && left.getRed() > 240, left.toString());
        assertTrue(right.getGreen() < 20 && right.getRed() > 240, right.toString());
    }

    @Test
    void shapeGradientsFadeWhereTheirStopsAreSeeThrough() throws IOException {
        String sp = "<p:sp " + Decks.NS + "><p:nvSpPr><p:cNvPr id=\"8\" name=\"Fade\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>"
                + "<p:spPr><a:xfrm><a:off x=\"1270000\" y=\"1270000\"/><a:ext cx=\"5080000\" cy=\"1270000\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:gradFill><a:gsLst><a:gs pos=\"0\"><a:srgbClr"
                + " val=\"FF0000\"><a:alpha val=\"0\"/></a:srgbClr></a:gs><a:gs pos=\"100000\"><a:srgbClr val=\"FF0000\"/>"
                + "</a:gs></a:gsLst><a:lin ang=\"0\" scaled=\"1\"/></a:gradFill><a:ln><a:noFill/></a:ln></p:spPr></p:sp>";
        Fixtures.Zip z = Fixtures.edit(Decks.slideXml(sp));
        z.insertAfter("ppt/slides/slide1.xml", "<p:cSld>", "<p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"00FF00\"/>"
                + "</a:solidFill><a:effectLst/></p:bgPr></p:bg>");
        BufferedImage img = Decks.convert(dir, "fade.pptx", z.bytes()).render(0, 72);
        Color left = new Color(img.getRGB(103, 125));
        Color right = new Color(img.getRGB(496, 125));
        assertTrue(left.getGreen() > 230 && left.getRed() < 30, left.toString());
        assertTrue(right.getRed() > 230 && right.getGreen() < 30, right.toString());
    }
}
