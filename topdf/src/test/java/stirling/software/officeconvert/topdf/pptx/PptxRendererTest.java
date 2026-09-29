package stirling.software.officeconvert.topdf.pptx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import javax.imageio.ImageIO;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.sl.usermodel.PictureData.PictureType;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.xslf.usermodel.SlideLayout;
import org.apache.poi.xslf.usermodel.XSLFAutoShape;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.Fixtures;

class PptxRendererTest {

    @TempDir
    Path dir;

    @Test
    void onePagePerVisibleSlideAtTheSlideSize() throws IOException {
        byte[] pptx = Decks.deck(new Dimension(720, 405), ppt -> {
            for (String t : new String[] {"First", "Hidden", "Third"}) {
                XSLFSlide s = ppt.createSlide();
                XSLFTextBox box = s.createTextBox();
                box.setAnchor(new Rectangle2D.Double(40, 40, 400, 60));
                box.setText(t);
                s.setHidden("Hidden".equals(t));
            }
        });
        Decks.Converted c = Decks.convert(dir, "pages.pptx", pptx);
        assertEquals(2, c.result().pages());
        try (PDDocument d = c.open()) {
            PDPage p = d.getPage(0);
            assertEquals(720, p.getMediaBox().getWidth(), 0.01);
            assertEquals(405, p.getMediaBox().getHeight(), 0.01);
        }
        String text = c.text();
        assertTrue(text.contains("First") && text.contains("Third"), text);
        assertFalse(text.contains("Hidden"), text);
    }

    @Test
    void textIsRealEmbeddedText() throws IOException {
        Decks.Converted c = Decks.convert(dir, "text.pptx", Fixtures.pptx("Hello Office"));
        assertTrue(c.text().contains("Hello Office"), c.text());
        try (PDDocument d = c.open()) {
            PDPage p = d.getPage(0);
            List<PDFont> fonts = new ArrayList<>();
            for (COSName n : p.getResources().getFontNames()) {
                fonts.add(p.getResources().getFont(n));
            }
            assertFalse(fonts.isEmpty());
            for (PDFont f : fonts) {
                assertTrue(f instanceof PDType0Font, f.getName());
                assertTrue(f.isEmbedded(), f.getName());
            }
        }
    }

    @Test
    void singleSpacedLinesAreOnePointTwoTimesTheSize() throws IOException {
        String p = "<a:p><a:pPr><a:lnSpc><a:spcPct val=\"100000\"/></a:lnSpc></a:pPr>" + Decks.run("Alpha", "sz=\"2000\"")
                + "</a:p><a:p><a:pPr><a:lnSpc><a:spcPct val=\"100000\"/></a:lnSpc></a:pPr>" + Decks.run("Beta", "sz=\"2000\"")
                + "</a:p><a:p><a:pPr><a:lnSpc><a:spcPct val=\"150000\"/></a:lnSpc></a:pPr>" + Decks.run("Gamma", "sz=\"2000\"")
                + "</a:p><a:p><a:pPr><a:lnSpc><a:spcPct val=\"150000\"/></a:lnSpc></a:pPr>" + Decks.run("Delta", "sz=\"2000\"")
                + "</a:p>";
        byte[] pptx = Decks.slideXml(Decks.textBox(10, 914400, 914400, 5486400, 1828800,
                "<a:bodyPr wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\"/>", p));
        List<TextPosition> pos = Decks.convert(dir, "spacing.pptx", pptx).positions(0);
        float alpha = baseline(pos, 'A');
        float beta = baseline(pos, 'B');
        float gamma = baseline(pos, 'G');
        float delta = baseline(pos, 'D');
        assertEquals(24, beta - alpha, 0.05);
        assertEquals(36, delta - gamma, 0.05);
        assertTrue(gamma - beta > 24 && gamma - beta < 36, "the first 150% line is taller: " + (gamma - beta));
    }

    private static float baseline(List<TextPosition> pos, char c) {
        for (TextPosition t : pos) {
            if (t.getUnicode().charAt(0) == c) {
                return t.getYDirAdj();
            }
        }
        throw new AssertionError("no " + c);
    }

    @Test
    void slideNumberFieldsShowTheSlideNumber() throws IOException {
        String p = "<a:p><a:fld id=\"{B6F15528-21DE-4FAA-801E-634DDDAF4B2B}\" type=\"slidenum\"><a:rPr lang=\"en-US\"/>"
                + "<a:t>99</a:t></a:fld></a:p>";
        Fixtures.Zip z = Fixtures.edit(Decks.deck(ppt -> {
            ppt.createSlide();
            ppt.createSlide();
        }));
        String box = Decks.textBox(10, 914400, 914400, 1828800, 914400, "<a:bodyPr/>", p);
        z.insertBefore("ppt/slides/slide2.xml", "</p:spTree>", box);
        String text = Decks.convert(dir, "number.pptx", z.bytes()).text();
        assertTrue(text.contains("2"), text);
        assertFalse(text.contains("99"), text);
    }

    @Test
    void autoNumberedParagraphsCount() throws IOException {
        StringBuilder p = new StringBuilder();
        for (String t : new String[] {"One", "Two", "Three"}) {
            p.append("<a:p><a:pPr marL=\"342900\" indent=\"-342900\"><a:buFont typeface=\"+mj-lt\"/>")
                    .append("<a:buAutoNum type=\"arabicPeriod\"/></a:pPr>").append(Decks.run(t, "sz=\"1800\""))
                    .append("</a:p>");
        }
        byte[] pptx = Decks.slideXml(Decks.textBox(10, 914400, 914400, 5486400, 2743200, "<a:bodyPr/>", p.toString()));
        String text = Decks.convert(dir, "numbers.pptx", pptx).text().replaceAll("\\s+", " ");
        assertTrue(text.contains("1. One") && text.contains("2. Two") && text.contains("3. Three"), text);
    }

    @Test
    void tableRowsGrowToFitTheirText() throws IOException {
        byte[] pptx = Decks.deck(ppt -> {
            XSLFSlide s = ppt.createSlide();
            XSLFTable t = s.createTable();
            t.setAnchor(new Rectangle2D.Double(50, 50, 300, 40));
            for (int r = 0; r < 2; r++) {
                XSLFTableRow row = t.addRow();
                row.setHeight(10);
                for (int c = 0; c < 2; c++) {
                    row.addCell().setText(r == 0 && c == 0 ? "Top left cell text that wraps over several lines here"
                            : "R" + r + "C" + c);
                }
            }
            t.setColumnWidth(0, 100);
            t.setColumnWidth(1, 100);
        });
        Decks.Converted c = Decks.convert(dir, "table.pptx", pptx);
        String text = c.text();
        assertTrue(text.contains("R1C1") && text.contains("R0C1"), text);
        List<TextPosition> pos = c.positions(0);
        float top = Float.MAX_VALUE;
        float bottomOfWrapped = 0;
        float secondRow = 0;
        String all = "";
        for (TextPosition t : pos) {
            all += t.getUnicode();
            if (all.endsWith("R1C")) {
                secondRow = t.getYDirAdj();
            }
            top = Math.min(top, t.getYDirAdj());
        }
        for (TextPosition t : pos) {
            if (t.getXDirAdj() < 150 && t.getYDirAdj() < secondRow) {
                bottomOfWrapped = Math.max(bottomOfWrapped, t.getYDirAdj());
            }
        }
        assertTrue(bottomOfWrapped - top > 30, "the first cell wraps: " + top + " " + bottomOfWrapped);
        assertTrue(secondRow > bottomOfWrapped + 10, "row 2 starts below the wrapped text: " + secondRow);
    }

    @Test
    void solidBackgroundsFillTheSlide() throws IOException {
        byte[] pptx = Decks.deck(ppt -> ppt.createSlide().getBackground().setFillColor(new Color(0, 128, 0)));
        BufferedImage img = Decks.convert(dir, "bg.pptx", pptx).render(0, 20);
        assertEquals(new Color(0, 128, 0).getRGB(), img.getRGB(img.getWidth() / 2, img.getHeight() / 2));
    }

    @Test
    void groupsScaleTheirChildren() throws IOException {
        byte[] pptx = Decks.deck(ppt -> {
            XSLFSlide s = ppt.createSlide();
            XSLFGroupShape g = s.createGroup();
            g.setAnchor(new Rectangle2D.Double(360, 0, 360, 270));
            g.setInteriorAnchor(new Rectangle2D.Double(0, 0, 720, 540));
            XSLFAutoShape r = g.createAutoShape();
            r.setShapeType(ShapeType.RECT);
            r.setAnchor(new Rectangle2D.Double(360, 270, 360, 270));
            r.setFillColor(Color.BLUE);
            r.setLineColor(null);
        });
        BufferedImage img = Decks.convert(dir, "group.pptx", pptx).render(0, 72);
        assertEquals(Color.BLUE.getRGB(), img.getRGB(630, 200));
        assertEquals(Color.WHITE.getRGB(), img.getRGB(450, 100));
        assertEquals(Color.WHITE.getRGB(), img.getRGB(630, 400));
    }

    @Test
    void picturesAreDrawnAndCropped() throws IOException {
        byte[] pptx = Decks.deck(ppt -> {
            XSLFPictureData data = ppt.addPicture(twoColours(), PictureType.PNG);
            XSLFPictureShape pic = ppt.createSlide().createPicture(data);
            pic.setAnchor(new Rectangle2D.Double(100, 100, 200, 100));
        });
        Fixtures.Zip z = Fixtures.edit(pptx);
        String slide = z.text("ppt/slides/slide1.xml");
        z.put("ppt/slides/slide1.xml", slide.replace("<a:stretch>", "<a:srcRect l=\"50000\"/><a:stretch>"));
        BufferedImage img = Decks.convert(dir, "picture.pptx", z.bytes()).render(0, 72);
        assertEquals(Color.RED.getRGB(), img.getRGB(120, 150));
        assertEquals(Color.RED.getRGB(), img.getRGB(280, 150));
    }

    private static byte[] twoColours() {
        BufferedImage b = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 20; x++) {
            for (int y = 0; y < 10; y++) {
                b.setRGB(x, y, x < 10 ? Color.BLUE.getRGB() : Color.RED.getRGB());
            }
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(b, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void svgPicturesDrawTheirPngFallback() throws IOException {
        byte[] pptx = Decks.deck(ppt -> {
            XSLFPictureData data = ppt.addPicture(Fixtures.png(8, 8, Color.RED), PictureType.PNG);
            ppt.createSlide().createPicture(data).setAnchor(new Rectangle2D.Double(100, 100, 100, 100));
        });
        Fixtures.Zip z = Fixtures.edit(pptx);
        z.put("ppt/media/image9.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"8\" height=\"8\">"
                + "<rect width=\"8\" height=\"8\" fill=\"blue\"/></svg>");
        z.defaultType("svg", "image/svg+xml");
        z.relationship("/ppt/slides/slide1.xml", "rIdSvg", Fixtures.REL + "image", "../media/image9.svg", false);
        String slide = z.text("ppt/slides/slide1.xml");
        z.put("ppt/slides/slide1.xml", slide.replaceFirst("<a:blip ([^>]*?)/>", "<a:blip $1><a:extLst><a:ext uri="
                + "\"{96DAC541-7B7A-43D3-8B79-37D633B846F1}\"><asvg:svgBlip xmlns:asvg=\"http://schemas.microsoft.com/"
                + "office/drawing/2016/SVG/main\" r:embed=\"rIdSvg\"/></a:ext></a:extLst></a:blip>"));
        BufferedImage img = Decks.convert(dir, "svg.pptx", z.bytes()).render(0, 72);
        assertEquals(Color.RED.getRGB(), img.getRGB(150, 150));
    }

    @Test
    void rightToLeftParagraphsKeepTheirLetters() throws IOException {
        String arabic = "\u0645\u0631\u062D\u0628\u0627";
        String p = "<a:p><a:pPr algn=\"r\" rtl=\"1\"/>" + Decks.run(arabic, "sz=\"2400\"") + "</a:p>";
        byte[] pptx = Decks.slideXml(Decks.textBox(10, 914400, 914400, 5486400, 914400, "<a:bodyPr/>", p));
        String text = Decks.convert(dir, "rtl.pptx", pptx).text();
        TreeSet<Integer> want = new TreeSet<>();
        arabic.codePoints().forEach(want::add);
        TreeSet<Integer> got = new TreeSet<>();
        text.codePoints().forEach(got::add);
        assertTrue(got.containsAll(want), text);
    }

    @Test
    void emptyPlaceholdersAreNotDrawn() throws IOException {
        byte[] pptx = Decks.deck(ppt -> {
            XSLFSlide s = ppt.createSlide(ppt.getSlideMasters().get(0).getLayout(
                    SlideLayout.TITLE_AND_CONTENT));
            s.getPlaceholder(0).setText("Only the title");
            s.getPlaceholder(1).clearText();
            s.getPlaceholder(1).addNewTextParagraph();
            s.getPlaceholder(1).setFillColor(null);
        });
        String text = Decks.convert(dir, "placeholders.pptx", pptx).text();
        assertTrue(text.contains("Only the title"), text);
        assertFalse(text.contains("Click to edit"), text);
    }

    @Test
    void alternateContentDrawsItsFallbackPicture() throws IOException {
        byte[] base = Decks.deck(ppt -> ppt.createSlide());
        Fixtures.Zip z = Fixtures.edit(base);
        z.put("ppt/media/fallback1.png", Fixtures.png(4, 4, Color.RED));
        z.defaultType("png", "image/png");
        z.relationship("/ppt/slides/slide1.xml", "rIdFb", Fixtures.REL + "image", "../media/fallback1.png", false);
        String alt = "<mc:AlternateContent xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\" "
                + Decks.NS + "><mc:Choice xmlns:a14=\"http://schemas.microsoft.com/office/drawing/2010/main\""
                + " Requires=\"a14\"><p:sp><p:nvSpPr><p:cNvPr id=\"5\" name=\"Equation\"/><p:cNvSpPr txBox=\"1\"/>"
                + "<p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"1270000\" y=\"1270000\"/><a:ext cx=\"1270000\""
                + " cy=\"1270000\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr><p:txBody>"
                + "<a:bodyPr/><a:lstStyle/><a:p><a14:m/></a:p></p:txBody></p:sp></mc:Choice><mc:Fallback><p:sp><p:nvSpPr>"
                + "<p:cNvPr id=\"5\" name=\"Equation\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm>"
                + "<a:off x=\"1270000\" y=\"1270000\"/><a:ext cx=\"1270000\" cy=\"1270000\"/></a:xfrm><a:prstGeom"
                + " prst=\"rect\"><a:avLst/></a:prstGeom><a:blipFill><a:blip r:embed=\"rIdFb\"/><a:stretch><a:fillRect/>"
                + "</a:stretch></a:blipFill></p:spPr></p:sp></mc:Fallback></mc:AlternateContent>";
        z.insertBefore("ppt/slides/slide1.xml", "</p:spTree>", alt);
        BufferedImage img = Decks.convert(dir, "alternate.pptx", z.bytes()).render(0, 72);
        assertEquals(Color.RED.getRGB(), img.getRGB(150, 150));
        assertEquals(Color.WHITE.getRGB(), img.getRGB(50, 50));
    }

    @Test
    void groupFillIsInheritedByChildren() throws IOException {
        byte[] pptx = Decks.deck(ppt -> {
            XSLFSlide s = ppt.createSlide();
            XSLFGroupShape g = s.createGroup();
            g.setAnchor(new Rectangle2D.Double(100, 100, 200, 200));
            g.setInteriorAnchor(new Rectangle2D.Double(100, 100, 200, 200));
            XSLFAutoShape r = g.createAutoShape();
            r.setShapeType(ShapeType.RECT);
            r.setAnchor(new Rectangle2D.Double(100, 100, 200, 200));
            r.setLineColor(null);
        });
        Fixtures.Zip z = Fixtures.edit(pptx);
        String slide = z.text("ppt/slides/slide1.xml");
        slide = slide.replaceFirst("(<p:grpSp>.*?<p:grpSpPr>.*?</a:xfrm>)", "$1<a:solidFill><a:srgbClr val=\"00FF00\"/></a:solidFill>");
        slide = slide.replaceFirst("(<p:sp>.*?<a:prstGeom prst=\"rect\">\\s*<a:avLst/>\\s*</a:prstGeom>)", "$1<a:grpFill/>");
        z.put("ppt/slides/slide1.xml", slide);
        BufferedImage img = Decks.convert(dir, "grpfill.pptx", z.bytes()).render(0, 72);
        assertEquals(Color.GREEN.getRGB(), img.getRGB(200, 200));
    }

    static byte[] smartArt(String drawingPrefix) {
        byte[] base = Decks.deck(ppt -> ppt.createSlide());
        Fixtures.Zip z = Fixtures.edit(base);
        String slide = "/ppt/slides/slide1.xml";
        z.put("ppt/diagrams/data1.xml", "<dgm:dataModel xmlns:dgm=\"http://schemas.openxmlformats.org/drawingml/2006/diagram\""
                + " xmlns:a=\"" + Decks.A + "\"><dgm:ptLst/><dgm:cxnLst/><dgm:bg/><dgm:whole/></dgm:dataModel>");
        z.override("/ppt/diagrams/data1.xml", "application/vnd.openxmlformats-officedocument.drawingml.diagramData+xml");
        z.relationship(slide, "rIdDm", Fixtures.REL + "diagramData", "../diagrams/data1.xml", false);
        z.put("ppt/diagrams/drawing1.xml", drawingPrefix + "<dsp:drawing xmlns:dsp=\"http://schemas.microsoft.com/office/drawing/2008/"
                + "diagram\" xmlns:dgm=\"http://schemas.openxmlformats.org/drawingml/2006/diagram\" xmlns:a=\"" + Decks.A
                + "\"><dsp:spTree><dsp:nvGrpSpPr><dsp:cNvPr id=\"0\" name=\"\"/><dsp:cNvGrpSpPr/></dsp:nvGrpSpPr><dsp:grpSpPr/>"
                + "<dsp:sp modelId=\"{00000000-0000-0000-0000-000000000001}\"><dsp:nvSpPr><dsp:cNvPr id=\"0\" name=\"\"/>"
                + "<dsp:cNvSpPr/></dsp:nvSpPr><dsp:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"2540000\" cy=\"1270000\"/>"
                + "</a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val=\"0000FF\"/>"
                + "</a:solidFill></dsp:spPr><dsp:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang=\"en-US\" sz=\"2000\"/>"
                + "<a:t>Smart text</a:t></a:r></a:p></dsp:txBody><dsp:txXfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"2540000\""
                + " cy=\"1270000\"/></dsp:txXfrm></dsp:sp></dsp:spTree></dsp:drawing>");
        z.override("/ppt/diagrams/drawing1.xml", "application/vnd.ms-office.drawingml.diagramDrawing+xml");
        z.relationship(slide, "rIdDr", Fixtures.MS_REL_2007 + "diagramDrawing", "../diagrams/drawing1.xml", false);
        String frame = "<p:graphicFrame " + Decks.NS + "><p:nvGraphicFramePr><p:cNvPr id=\"4\" name=\"Diagram\"/>"
                + "<p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off x=\"1270000\" y=\"1270000\"/>"
                + "<a:ext cx=\"2540000\" cy=\"1270000\"/></p:xfrm><a:graphic><a:graphicData uri=\""
                + "http://schemas.openxmlformats.org/drawingml/2006/diagram\"><dgm:relIds xmlns:dgm=\""
                + "http://schemas.openxmlformats.org/drawingml/2006/diagram\" r:dm=\"rIdDm\" r:lo=\"rIdDm\" r:qs=\"rIdDm\""
                + " r:cs=\"rIdDm\"/></a:graphicData></a:graphic></p:graphicFrame>";
        z.insertBefore("ppt/slides/slide1.xml", "</p:spTree>", frame);
        return z.bytes();
    }

    @Test
    void smartArtDrawsItsCachedDrawing() throws IOException {
        Decks.Converted c = Decks.convert(dir, "smartart.pptx", smartArt(""));
        assertTrue(c.text().contains("Smart text"), c.text());
        BufferedImage img = c.render(0, 72);
        assertEquals(Color.BLUE.getRGB(), img.getRGB(280, 180));
    }

    @Test
    void hyperlinksBecomeLinkAnnotationsOnlyForWebAddresses() throws IOException {
        byte[] pptx = Decks.deck(ppt -> {
            XSLFSlide s = ppt.createSlide();
            XSLFAutoShape a = s.createAutoShape();
            a.setShapeType(ShapeType.RECT);
            a.setAnchor(new Rectangle2D.Double(50, 50, 100, 50));
            a.setFillColor(Color.GRAY);
            a.createHyperlink().setAddress("https://example.com/page");
            XSLFAutoShape b = s.createAutoShape();
            b.setShapeType(ShapeType.RECT);
            b.setAnchor(new Rectangle2D.Double(200, 50, 100, 50));
            b.setFillColor(Color.GRAY);
            b.createHyperlink().setAddress("file:///C:/Windows/system.ini");
            XSLFTextBox t = s.createTextBox();
            t.setAnchor(new Rectangle2D.Double(50, 200, 300, 50));
            t.setText("Visit us").createHyperlink().setAddress("mailto:someone@example.com");
        });
        List<String> uris = new ArrayList<>();
        try (PDDocument d = Decks.convert(dir, "links.pptx", pptx).open()) {
            for (PDAnnotation a : d.getPage(0).getAnnotations()) {
                if (a instanceof PDAnnotationLink l
                        && l.getAction() instanceof PDActionURI u) {
                    uris.add(u.getURI());
                }
            }
        }
        assertTrue(uris.contains("https://example.com/page"), uris.toString());
        assertTrue(uris.contains("mailto:someone@example.com"), uris.toString());
        assertFalse(uris.stream().anyMatch(u -> u.startsWith("file")), uris.toString());
    }
}
