package stirling.software.officeconvert.topdf.pptx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.poi.sl.usermodel.PictureData.PictureType;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.testing.Fixtures;
import stirling.software.officeconvert.topdf.testing.HostileImagePlugin;
import stirling.software.officeconvert.topdf.testing.NoNetwork;

class PptxNoNetworkTest {

    private static final String SLIDE = "ppt/slides/slide1.xml";

    private static final String SLIDE_PART = "/" + SLIDE;

    @TempDir
    Path dir;

    @Test
    void everyKindOfActiveContentIsSkippedWithoutTouchingTheNetwork() throws IOException {
        try (NoNetwork net = NoNetwork.start(); HostileImagePlugin plugin = HostileImagePlugin.register()) {
            Decks.Converted c = Decks.convert(dir, "hostile.pptx", hostileDeck(net));
            net.assertNothingConnected();
            plugin.assertNeverUsed();
            String text = c.text();
            assertTrue(text.contains("Visible text survives"), text);
            assertTrue(text.contains("Cached field text"), text);
            assertTrue(c.result().pages() >= 1);
            assertFalse(c.result().warnings().isEmpty());
            try (PDDocument d = c.open()) {
                for (int i = 0; i < d.getNumberOfPages(); i++) {
                    for (PDAnnotation a : d.getPage(i).getAnnotations()) {
                        if (a instanceof PDAnnotationLink link && link.getAction() instanceof PDActionURI uri) {
                            String u = uri.getURI();
                            assertTrue(u.startsWith("http://") || u.startsWith("https://") || u.startsWith("mailto:"), u);
                        }
                    }
                }
            }
            BufferedImage img = c.render(0, 72);
            assertEquals(Color.RED.getRGB(), img.getRGB(450, 150), "the OLE preview picture is drawn");
        }
    }

    @Test
    void theSharedHostileFixtureConverts() throws IOException {
        try (NoNetwork net = NoNetwork.start()) {
            Decks.Converted c = Decks.convert(dir, "shared.pptx", Fixtures.hostilePptx(net));
            net.assertNothingConnected();
            assertEquals(2, c.result().pages());
        }
    }

    @Test
    void aDoctypeInASlideIsRefusedWithoutFetchingIt() throws IOException {
        try (NoNetwork net = NoNetwork.start()) {
            Fixtures.Zip z = Fixtures.edit(Fixtures.pptx("Doctype slide"));
            String doctype = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><!DOCTYPE x SYSTEM \"" + net.url("evil.dtd")
                    + "\" [<!ENTITY xxe SYSTEM \"" + net.url("xxe") + "\">]>";
            String slide = z.text(SLIDE).replaceFirst("^<\\?xml[^>]*\\?>", "");
            z.put(SLIDE, doctype + slide.replace("Doctype slide", "&xxe;"));
            Path in = Fixtures.write(dir, "doctype.pptx", z.bytes());
            try {
                OfficeToPdf.convert(in, dir.resolve("doctype.pdf"));
            } catch (IOException expected) {
                assertFalse(expected instanceof OfficeToPdf.TimedOut);
            }
            net.assertNothingConnected();
        }
    }

    private static byte[] hostileDeck(NoNetwork net) {
        byte[] base = Decks.deck(new Dimension(720, 540), ppt -> {
            XSLFSlide s = ppt.createSlide();
            XSLFTextBox box = s.createTextBox();
            box.setAnchor(new Rectangle2D.Double(20, 20, 400, 40));
            box.setText("Visible text survives");
            XSLFPictureData png = ppt.addPicture(Fixtures.png(4, 4, Color.RED), PictureType.PNG);
            s.createPicture(png).setAnchor(new Rectangle2D.Double(600, 450, 20, 20));
        });
        Fixtures.Zip z = Fixtures.edit(base);
        List<String> shapes = new ArrayList<>();
        int id = 100;
        int n = 0;
        for (String target : net.hostileTargets("x")) {
            n++;
            String link = "rIdL" + n;
            String media = "rIdM" + n;
            String ole = "rIdO" + n;
            String href = "rIdH" + n;
            z.relationship(SLIDE_PART, link, Fixtures.REL + "image", target + ".png", true);
            z.relationship(SLIDE_PART, media, Fixtures.REL + "video", target + ".mp4", true);
            z.relationship(SLIDE_PART, ole, Fixtures.REL + "oleObject", target + ".xlsx", true);
            z.relationship(SLIDE_PART, href, Fixtures.REL + "hyperlink", target, true);
            shapes.add(picture(id++, "r:link=\"" + link + "\"", "<a:videoFile r:link=\"" + media + "\"/>", href));
            shapes.add(filledShape(id++, "<a:blipFill><a:blip r:link=\"" + link + "\"/><a:stretch><a:fillRect/>"
                    + "</a:stretch></a:blipFill>"));
            shapes.add(Decks.textBox(id++, 914400, 3657600, 914400, 457200, "<a:bodyPr/>",
                    "<a:p><a:pPr><a:buBlip><a:blip r:link=\"" + link + "\"/></a:buBlip></a:pPr><a:r><a:rPr lang=\"en-US\">"
                            + "<a:hlinkClick r:id=\"" + href + "\"/></a:rPr><a:t>link " + n + "</a:t></a:r></a:p>"));
            shapes.add(oleFrame(id++, ole));
        }
        z.relationship(SLIDE_PART, "rIdAct", Fixtures.REL + "hyperlink", "ppaction://program?cmd.exe", true);
        shapes.add(Decks.textBox(id++, 914400, 4572000, 2743200, 457200, "<a:bodyPr/>",
                "<a:p><a:r><a:rPr lang=\"en-US\"><a:hlinkClick r:id=\"rIdAct\" action=\"ppaction://program\"/></a:rPr>"
                        + "<a:t>action</a:t></a:r><a:fld id=\"{11111111-2222-3333-4444-555555555555}\" type=\"datetime1\">"
                        + "<a:rPr lang=\"en-US\"/><a:t>Cached field text</a:t></a:fld></a:p>"));
        z.put("ppt/embeddings/oleObject1.bin", Fixtures.encryptedOle2());
        z.defaultType("bin", "application/vnd.openxmlformats-officedocument.oleObject");
        z.relationship(SLIDE_PART, "rIdEmb", Fixtures.REL + "oleObject", "../embeddings/oleObject1.bin", false);
        z.put("ppt/media/preview9.png", Fixtures.png(4, 4, Color.RED));
        z.defaultType("png", "image/png");
        z.relationship(SLIDE_PART, "rIdPrev", Fixtures.REL + "image", "../media/preview9.png", false);
        shapes.add(embeddedOle(id++));
        z.put("ppt/activeX/activeX1.xml", "<ax:ocx xmlns:ax=\"http://schemas.microsoft.com/office/2006/activeX\""
                + " ax:classid=\"{8BD21D10-EC42-11CE-9E0D-00AA006002F3}\" ax:persistence=\"persistPropertyBag\"/>");
        z.override("/ppt/activeX/activeX1.xml", "application/vnd.ms-office.activeX+xml");
        z.relationship(SLIDE_PART, "rIdAx", Fixtures.REL + "control", "../activeX/activeX1.xml", false);
        z.put("ppt/media/evil.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\">"
                + "<script>fetch('" + net.url("svg-script") + "')</script><image xlink:href=\"" + net.url("svg.png")
                + "\" width=\"8\" height=\"8\"/></svg>");
        z.defaultType("svg", "image/svg+xml");
        z.relationship(SLIDE_PART, "rIdSvg", Fixtures.REL + "image", "../media/evil.svg", false);
        shapes.add(svgPicture(id++));
        z.put("ppt/media/media1.mp4", new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p'});
        z.defaultType("mp4", "video/mp4");
        z.relationship(SLIDE_PART, "rIdMp4", Fixtures.MS_REL_2007 + "media", "../media/media1.mp4", false);
        z.put("ppt/charts/chart1.xml", "<c:chartSpace xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\""
                + " xmlns:r=\"" + Decks.R + "\"><c:chart><c:plotArea><c:layout/></c:plotArea></c:chart>"
                + "<c:externalData r:id=\"rIdWb\"/></c:chartSpace>");
        z.override("/ppt/charts/chart1.xml", "application/vnd.openxmlformats-officedocument.drawingml.chart+xml");
        z.relationship("/ppt/charts/chart1.xml", "rIdWb", Fixtures.REL + "oleObject", net.uncPath("book.xlsx"), true);
        z.relationship(SLIDE_PART, "rIdChart", Fixtures.REL + "chart", "../charts/chart1.xml", false);
        shapes.add(chartFrame(id++));
        z.relationship("/ppt/slides/slide1.xml", "rIdBg", Fixtures.REL + "image", net.url("background.png"), true);
        z.insertBefore(SLIDE, "<p:spTree>", "<p:bg><p:bgPr><a:blipFill><a:blip r:link=\"rIdBg\"/><a:stretch><a:fillRect/>"
                + "</a:stretch></a:blipFill><a:effectLst/></p:bgPr></p:bg>");
        z.relationship("/ppt/presentation.xml", "rIdUpd", Fixtures.REL + "slideUpdateUrl", net.url("library"), true);
        z.put("ppt/vbaProject.bin", new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0});
        z.override("/ppt/vbaProject.bin", "application/vnd.ms-office.vbaProject");
        z.relationship("/ppt/presentation.xml", "rIdVba", Fixtures.MS_REL + "vbaProject", "vbaProject.bin", false);
        z.put("customUI/customUI14.xml", "<customUI xmlns=\"http://schemas.microsoft.com/office/2009/07/customui\""
                + " onLoad=\"AutoOpen\"><ribbon/></customUI>");
        z.relationship("/", "rIdUi", Fixtures.MS_REL_2007 + "ui/extensibility", "customUI/customUI14.xml", false);
        z.relationship("/ppt/presentation.xml", "rIdFont", Fixtures.REL + "font", net.url("font.fntdata"), true);
        String theme = z.text("ppt/theme/theme1.xml");
        z.relationship("/ppt/theme/theme1.xml", "rIdThemePic", Fixtures.REL + "image", net.canaryUrl("theme.png"), true);
        z.put("ppt/theme/theme1.xml", theme.replaceFirst("(<a:bgFillStyleLst>)", "$1<a:blipFill><a:blip xmlns:r=\""
                + Decks.R + "\" r:link=\"rIdThemePic\"/><a:stretch/></a:blipFill>"));
        z.insertBefore(SLIDE, "</p:spTree>", String.join("", shapes));
        return z.bytes();
    }

    private static String picture(int id, String blip, String media, String href) {
        return "<p:pic " + Decks.NS + "><p:nvPicPr><p:cNvPr id=\"" + id + "\" name=\"Linked " + id + "\">"
                + "<a:hlinkClick r:id=\"" + href + "\"/></p:cNvPr><p:cNvPicPr/><p:nvPr>" + media + "</p:nvPr></p:nvPicPr>"
                + "<p:blipFill><a:blip " + blip + "/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm>"
                + "<a:off x=\"914400\" y=\"1828800\"/><a:ext cx=\"914400\" cy=\"914400\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>";
    }

    private static String filledShape(int id, String fill) {
        return "<p:sp " + Decks.NS + "><p:nvSpPr><p:cNvPr id=\"" + id + "\" name=\"Fill " + id + "\"/><p:cNvSpPr/>"
                + "<p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"2743200\" y=\"1828800\"/><a:ext cx=\"914400\""
                + " cy=\"914400\"/></a:xfrm><a:prstGeom prst=\"ellipse\"><a:avLst/></a:prstGeom>" + fill + "</p:spPr></p:sp>";
    }

    private static String oleFrame(int id, String rel) {
        return "<p:graphicFrame " + Decks.NS + "><p:nvGraphicFramePr><p:cNvPr id=\"" + id + "\" name=\"Linked OLE " + id
                + "\"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off x=\"4572000\" y=\"3657600\"/>"
                + "<a:ext cx=\"914400\" cy=\"914400\"/></p:xfrm><a:graphic><a:graphicData uri=\""
                + "http://schemas.openxmlformats.org/presentationml/2006/ole\"><p:oleObj name=\"Worksheet\" r:id=\"" + rel
                + "\" progId=\"Excel.Sheet.12\" updateAutomatic=\"1\"><p:link updateAutomatic=\"1\"/></p:oleObj>"
                + "</a:graphicData></a:graphic></p:graphicFrame>";
    }

    private static String embeddedOle(int id) {
        return "<p:graphicFrame " + Decks.NS + "><p:nvGraphicFramePr><p:cNvPr id=\"" + id + "\" name=\"Object\"/>"
                + "<p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off x=\"5486400\" y=\"1371600\"/>"
                + "<a:ext cx=\"914400\" cy=\"914400\"/></p:xfrm><a:graphic><a:graphicData uri=\""
                + "http://schemas.openxmlformats.org/presentationml/2006/ole\"><p:oleObj name=\"Packager\" r:id=\"rIdEmb\""
                + " progId=\"Package\"><p:embed/><p:pic><p:nvPicPr><p:cNvPr id=\"0\" name=\"\"/><p:cNvPicPr/><p:nvPr/>"
                + "</p:nvPicPr><p:blipFill><a:blip r:embed=\"rIdPrev\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>"
                + "<p:spPr><a:xfrm><a:off x=\"5486400\" y=\"1371600\"/><a:ext cx=\"914400\" cy=\"914400\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic></p:oleObj></a:graphicData></a:graphic>"
                + "</p:graphicFrame>";
    }

    private static String svgPicture(int id) {
        return "<p:pic " + Decks.NS + "><p:nvPicPr><p:cNvPr id=\"" + id + "\" name=\"Svg\"/><p:cNvPicPr/><p:nvPr/>"
                + "</p:nvPicPr><p:blipFill><a:blip r:embed=\"rIdSvg\"><a:extLst><a:ext uri=\"{96DAC541-7B7A-43D3-8B79-"
                + "37D633B846F1}\"><asvg:svgBlip xmlns:asvg=\"http://schemas.microsoft.com/office/drawing/2016/SVG/main\""
                + " r:embed=\"rIdSvg\"/></a:ext></a:extLst></a:blip><a:stretch><a:fillRect/></a:stretch></p:blipFill>"
                + "<p:spPr><a:xfrm><a:off x=\"6400800\" y=\"3657600\"/><a:ext cx=\"914400\" cy=\"914400\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>";
    }

    private static String chartFrame(int id) {
        return "<p:graphicFrame " + Decks.NS + "><p:nvGraphicFramePr><p:cNvPr id=\"" + id + "\" name=\"Chart\"/>"
                + "<p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off x=\"457200\" y=\"5029200\"/>"
                + "<a:ext cx=\"1828800\" cy=\"914400\"/></p:xfrm><a:graphic><a:graphicData uri=\""
                + "http://schemas.openxmlformats.org/drawingml/2006/chart\"><c:chart xmlns:c=\""
                + "http://schemas.openxmlformats.org/drawingml/2006/chart\" r:id=\"rIdChart\"/></a:graphicData></a:graphic>"
                + "</p:graphicFrame>";
    }

    @Test
    void aSmartArtDrawingWithADoctypeFetchesNothing() throws IOException {
        try (NoNetwork net = NoNetwork.start()) {
            String doctype = "<?xml version=\"1.0\"?><!DOCTYPE dsp:drawing SYSTEM \"" + net.url("evil.dtd")
                    + "\" [<!ENTITY xxe SYSTEM \"" + net.url("xxe") + "\">]>";
            Path in = Fixtures.write(dir, "smartart-doctype.pptx", PptxRendererTest.smartArt(doctype));
            try {
                OfficeToPdf.convert(in, dir.resolve("smartart-doctype.pdf"));
            } catch (IOException expected) {
                assertFalse(expected instanceof OfficeToPdf.TimedOut);
            }
            net.assertNothingConnected();
        }
    }
}
