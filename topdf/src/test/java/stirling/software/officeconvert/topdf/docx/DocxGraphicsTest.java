package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.Fixtures;

class DocxGraphicsTest {

    private static final String STYLES = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Liberation Sans\""
            + " w:hAnsi=\"Liberation Sans\"/><w:sz w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>"
            + "<w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";

    @TempDir
    Path dir;

    private static long emu(float pt) {
        return Math.round(pt * 12700);
    }

    private static String pic(String id, float w, float h) {
        return "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic>"
                + "<pic:nvPicPr><pic:cNvPr id=\"1\" name=\"p\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill>"
                + "<a:blip r:embed=\"" + id + "\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr>"
                + "<a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + emu(w) + "\" cy=\"" + emu(h) + "\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"/></pic:spPr></pic:pic></a:graphicData></a:graphic>";
    }

    private DocxDoc doc() {
        return new DocxDoc().styles(STYLES).media("blue.png", Fixtures.png(4, 4, Color.BLUE), "rIdImg");
    }

    @Test
    void relativeSizesFollowThePageNotTheSavedExtent() throws IOException {
        String box = "<w:p><w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\""
                + " relativeHeight=\"2\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">"
                + "<wp:simplePos x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"page\"><wp:posOffset>0</wp:posOffset>"
                + "</wp:positionH><wp:positionV relativeFrom=\"page\"><wp:posOffset>" + emu(400) + "</wp:posOffset>"
                + "</wp:positionV><wp:extent cx=\"" + emu(100) + "\" cy=\"" + emu(100) + "\"/><wp:wrapNone/>"
                + "<wp:docPr id=\"3\" name=\"g\"/><a:graphic><a:graphicData"
                + " uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingGroup\"><wpg:wgp"
                + " xmlns:wpg=\"http://schemas.microsoft.com/office/word/2010/wordprocessingGroup\"><wpg:grpSpPr><a:xfrm>"
                + "<a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + emu(100) + "\" cy=\"" + emu(100) + "\"/><a:chOff x=\"0\""
                + " y=\"0\"/><a:chExt cx=\"" + emu(100) + "\" cy=\"" + emu(100) + "\"/></a:xfrm></wpg:grpSpPr><wps:wsp>"
                + "<wps:spPr><a:xfrm><a:off x=\"" + emu(50) + "\" y=\"0\"/><a:ext cx=\"" + emu(50) + "\" cy=\""
                + emu(100) + "\"/></a:xfrm><a:prstGeom prst=\"rect\"/></wps:spPr><wps:txbx><w:txbxContent>"
                + DocxDoc.p("Marker") + "</w:txbxContent></wps:txbx><wps:bodyPr lIns=\"0\" tIns=\"0\" rIns=\"0\""
                + " bIns=\"0\"/></wps:wsp></wpg:wgp></a:graphicData></a:graphic><wp14:sizeRelH relativeFrom=\"page\""
                + " xmlns:wp14=\"http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing\"><wp14:pctWidth>"
                + "100000</wp14:pctWidth></wp14:sizeRelH></wp:anchor></w:drawing></w:r></w:p>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "relative", doc().body(box + DocxDoc.p("Body")).bytes());
        assertEquals(306, r.word("Marker").x(), 1.5f);
    }

    @Test
    void watermarkTextPathsAreDrawnOnEveryPage() throws IOException {
        String mark = "<w:p><w:r><w:pict><v:shape id=\"wm\" type=\"#_x0000_t136\" style=\"position:absolute;"
                + "margin-left:0;margin-top:0;width:400pt;height:150pt;rotation:315;z-index:-251655168;"
                + "mso-position-horizontal:center;mso-position-horizontal-relative:margin;"
                + "mso-position-vertical:center;mso-position-vertical-relative:margin\" fillcolor=\"silver\""
                + " stroked=\"f\"><v:fill opacity=\".5\"/><v:textpath style=\"font-family:&quot;Liberation Sans&quot;;"
                + "font-size:1pt\" string=\"DRAFT\"/></v:shape></w:pict></w:r></w:p>";
        String body = DocxDoc.p("First") + "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>" + DocxDoc.p("Second");
        String sect = "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rIdheaderxml\"/><w:pgSz w:w=\"12240\""
                + " w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\""
                + " w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/></w:sectPr>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "watermark",
                doc().header("header.xml", mark).section(sect).body(body).bytes());
        assertTrue(r.text(1).replaceAll("\\s", "").contains("DRAFT"), r.text(1));
        assertTrue(r.text(2).replaceAll("\\s", "").contains("DRAFT"), r.text(2));
        try (PDDocument d = r.open()) {
            assertTrue(minAlpha(d.getPage(0)) < 0.6f);
        }
    }

    @Test
    void wordArtIsSetAtTheShapeHeightAndStretchedAcrossItsWidth() throws IOException {
        String art = "<w:p><w:r><w:pict><v:shape id=\"wa\" type=\"#_x0000_t136\" style=\"position:absolute;"
                + "margin-left:100pt;margin-top:200pt;width:300pt;height:120pt;z-index:-1;"
                + "mso-position-horizontal-relative:page;mso-position-vertical-relative:page\" fillcolor=\"black\""
                + " stroked=\"f\"><v:textpath style=\"font-family:&quot;Liberation Sans&quot;;font-size:1pt\""
                + " string=\"HOLD\"/></v:shape></w:pict></w:r></w:p>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "wordart", doc().body(art + DocxDoc.p("Body")).bytes());
        java.util.List<org.apache.pdfbox.text.TextPosition> glyphs = new java.util.ArrayList<>();
        try (PDDocument d = r.open()) {
            new org.apache.pdfbox.text.PDFTextStripper() {
                @Override
                protected void writeString(String t, java.util.List<org.apache.pdfbox.text.TextPosition> ps) {
                    for (org.apache.pdfbox.text.TextPosition p : ps) {
                        if ("HOLD".contains(p.getUnicode())) {
                            glyphs.add(p);
                        }
                    }
                }
            }.getText(d);
        }
        assertEquals(4, glyphs.size(), glyphs.toString());
        org.apache.pdfbox.text.TextPosition h = glyphs.get(0);
        org.apache.pdfbox.text.TextPosition last = glyphs.get(3);
        assertEquals(120, h.getYScale(), 1.5f);
        assertTrue(h.getXDirAdj() < 100 && h.getXDirAdj() > 85, "H starts at " + h.getXDirAdj());
        assertEquals(h.getXDirAdj() + 300, last.getXDirAdj() + last.getWidthDirAdj(), 3);
        assertEquals(200 + 120 * 1854f / 2288, h.getYDirAdj(), 2);
    }

    @Test
    void drawingMlWordArtIsStretchedAcrossItsShape() throws IOException {
        String art = "<w:p><w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\""
                + " relativeHeight=\"1\" behindDoc=\"1\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">"
                + "<wp:simplePos x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"page\"><wp:posOffset>" + emu(100)
                + "</wp:posOffset></wp:positionH><wp:positionV relativeFrom=\"page\"><wp:posOffset>" + emu(200)
                + "</wp:posOffset></wp:positionV><wp:extent cx=\"" + emu(300) + "\" cy=\"" + emu(120) + "\"/>"
                + "<wp:wrapNone/><wp:docPr id=\"5\" name=\"Text Box 5\"/><a:graphic><a:graphicData"
                + " uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\"><wps:wsp><wps:cNvSpPr"
                + " txBox=\"1\"/><wps:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + emu(300) + "\" cy=\""
                + emu(120) + "\"/></a:xfrm><a:prstGeom prst=\"rect\"/></wps:spPr><wps:txbx><w:txbxContent><w:p><w:r>"
                + "<w:rPr xmlns:w14=\"http://schemas.microsoft.com/office/word/2010/wordml\"><w:rFonts"
                + " w:ascii=\"Liberation Sans\" w:hAnsi=\"Liberation Sans\"/><w:color w:val=\"C0C0C0\"/>"
                + "<w:sz w:val=\"2\"/><w14:textFill><w14:solidFill><w14:srgbClr w14:val=\"C0C0C0\"><w14:alpha"
                + " w14:val=\"50000\"/></w14:srgbClr></w14:solidFill></w14:textFill></w:rPr><w:t>HOLD</w:t></w:r>"
                + "</w:p></w:txbxContent></wps:txbx><wps:bodyPr fromWordArt=\"1\" wrap=\"square\"><a:prstTxWarp"
                + " prst=\"textPlain\"><a:avLst/></a:prstTxWarp><a:spAutoFit/></wps:bodyPr></wps:wsp></a:graphicData>"
                + "</a:graphic></wp:anchor>"
                + "</w:drawing></w:r></w:p>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "dmlwordart", doc().body(art + DocxDoc.p("Body")).bytes());
        java.util.List<org.apache.pdfbox.text.TextPosition> glyphs = new java.util.ArrayList<>();
        try (PDDocument d = r.open()) {
            new org.apache.pdfbox.text.PDFTextStripper() {
                @Override
                protected void writeString(String t, java.util.List<org.apache.pdfbox.text.TextPosition> ps) {
                    for (org.apache.pdfbox.text.TextPosition p : ps) {
                        if ("HOLD".contains(p.getUnicode())) {
                            glyphs.add(p);
                        }
                    }
                }
            }.getText(d);
            assertTrue(minAlpha(d.getPage(0)) < 0.6f);
        }
        assertEquals(4, glyphs.size(), glyphs.toString());
        assertEquals(120, glyphs.get(0).getYScale(), 1.5f);
        assertEquals(glyphs.get(0).getXDirAdj() + 300, glyphs.get(3).getXDirAdj() + glyphs.get(3).getWidthDirAdj(), 3);
    }

    @Test
    void slantedWordArtKeepsItsTextAtItsOwnSize() throws IOException {
        String art = "<w:p><w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\""
                + " relativeHeight=\"1\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">"
                + "<wp:simplePos x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"page\"><wp:posOffset>" + emu(100)
                + "</wp:posOffset></wp:positionH><wp:positionV relativeFrom=\"page\"><wp:posOffset>" + emu(200)
                + "</wp:posOffset></wp:positionV><wp:extent cx=\"" + emu(120) + "\" cy=\"" + emu(150) + "\"/>"
                + "<wp:wrapNone/><wp:docPr id=\"5\" name=\"WordArt 5\"/><a:graphic><a:graphicData"
                + " uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\"><wps:wsp><wps:cNvSpPr"
                + " txBox=\"1\"/><wps:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + emu(120) + "\" cy=\""
                + emu(150) + "\"/></a:xfrm><a:prstGeom prst=\"rect\"/></wps:spPr><wps:txbx><w:txbxContent><w:p><w:r>"
                + "<w:t>SKIPS FOR HIRE TODAY</w:t></w:r></w:p></w:txbxContent></wps:txbx><wps:bodyPr fromWordArt=\"1\""
                + " wrap=\"square\"><a:prstTxWarp prst=\"textSlantUp\"><a:avLst/></a:prstTxWarp><a:noAutofit/>"
                + "</wps:bodyPr></wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r></w:p>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "slantart", doc().body(art + DocxDoc.p("Body")).bytes());
        java.util.List<Float> sizes = new java.util.ArrayList<>();
        try (PDDocument d = r.open()) {
            new org.apache.pdfbox.text.PDFTextStripper() {
                @Override
                protected void writeString(String t, java.util.List<org.apache.pdfbox.text.TextPosition> ps) {
                    for (org.apache.pdfbox.text.TextPosition p : ps) {
                        if ("S".equals(p.getUnicode())) {
                            sizes.add(p.getYScale());
                        }
                    }
                }
            }.getText(d);
        }
        assertTrue(!sizes.isEmpty() && sizes.get(0) < 12, sizes.toString());
        DocxDoc.Word first = r.word("SKIPS");
        DocxDoc.Word last = r.word("TODAY");
        assertTrue(last.y() > first.y() + 5, "the text wraps inside its box: " + first + " " + last);
    }

    @Test
    void anObjectWiderThanTheColumnStartsBesideASideWrappedFloat() throws IOException {
        String body = "<w:p><w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\""
                + " relativeHeight=\"2\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">"
                + "<wp:simplePos x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"column\"><wp:posOffset>" + emu(400)
                + "</wp:posOffset></wp:positionH><wp:positionV relativeFrom=\"paragraph\"><wp:posOffset>0"
                + "</wp:posOffset></wp:positionV><wp:extent cx=\"" + emu(60) + "\" cy=\"" + emu(30) + "\"/>"
                + "<wp:wrapSquare wrapText=\"bothSides\"/><wp:docPr id=\"3\" name=\"f\"/>" + pic("rIdImg", 60, 30)
                + "</wp:anchor></w:drawing></w:r><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\""
                + " distR=\"0\"><wp:extent cx=\"" + emu(480) + "\" cy=\"" + emu(100) + "\"/><wp:docPr id=\"4\""
                + " name=\"wide\"/>" + pic("rIdImg", 480, 100) + "</wp:inline></w:drawing></w:r></w:p>"
                + DocxDoc.p("After");
        DocxDoc.Rendered r = DocxDoc.render(dir, "overwide", doc().body(body).bytes());
        assertTrue(r.word("After").y() < 72 + 100 + 20, r.word("After").toString());
    }

    @Test
    void textFollowsAThroughWrapPolygonLineByLine() throws IOException {
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            words.append("a ");
        }
        String body = "<w:p><w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\""
                + " relativeHeight=\"2\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">"
                + "<wp:simplePos x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"column\"><wp:posOffset>0"
                + "</wp:posOffset></wp:positionH><wp:positionV relativeFrom=\"paragraph\"><wp:posOffset>0"
                + "</wp:posOffset></wp:positionV><wp:extent cx=\"" + emu(200) + "\" cy=\"" + emu(100) + "\"/>"
                + "<wp:wrapThrough wrapText=\"bothSides\"><wp:wrapPolygon edited=\"1\"><wp:start x=\"0\" y=\"0\"/>"
                + "<wp:lineTo x=\"21600\" y=\"0\"/><wp:lineTo x=\"0\" y=\"21600\"/><wp:lineTo x=\"0\" y=\"0\"/>"
                + "</wp:wrapPolygon></wp:wrapThrough><wp:docPr id=\"3\" name=\"f\"/>" + pic("rIdImg", 200, 100)
                + "</wp:anchor></w:drawing></w:r><w:r><w:t xml:space=\"preserve\">" + words + "</w:t></w:r></w:p>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "polywrap", doc().body(body).bytes());
        float firstLine = 999;
        float nearBottom = 999;
        for (DocxDoc.Word w : r.words()) {
            if (w.y() < 72 + 12) {
                firstLine = Math.min(firstLine, w.x());
            } else if (w.y() > 72 + 70 && w.y() < 72 + 95) {
                nearBottom = Math.min(nearBottom, w.x());
            }
        }
        assertTrue(firstLine > 72 + 180, "the first line starts past the wide top: " + firstLine);
        assertTrue(nearBottom < 72 + 80, "lines near the narrow bottom start close to the margin: " + nearBottom);
    }

    @Test
    void textBeforeAPageAnchoredPolygonKeepsClearOfAllOfIt() throws IOException {
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            words.append("a ");
        }
        String body = "<w:p><w:r><w:t xml:space=\"preserve\">" + words + "</w:t></w:r></w:p><w:p><w:r><w:drawing>"
                + "<wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\" relativeHeight=\"2\""
                + " behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\"><wp:simplePos x=\"0\" y=\"0\"/>"
                + "<wp:positionH relativeFrom=\"page\"><wp:posOffset>" + emu(72) + "</wp:posOffset></wp:positionH>"
                + "<wp:positionV relativeFrom=\"page\"><wp:posOffset>" + emu(72) + "</wp:posOffset></wp:positionV>"
                + "<wp:extent cx=\"" + emu(200) + "\" cy=\"" + emu(100) + "\"/><wp:wrapThrough wrapText=\"bothSides\">"
                + "<wp:wrapPolygon edited=\"1\"><wp:start x=\"0\" y=\"0\"/><wp:lineTo x=\"0\" y=\"21600\"/>"
                + "<wp:lineTo x=\"21600\" y=\"21600\"/><wp:lineTo x=\"0\" y=\"0\"/></wp:wrapPolygon></wp:wrapThrough>"
                + "<wp:docPr id=\"3\" name=\"f\"/>" + pic("rIdImg", 200, 100) + "</wp:anchor></w:drawing></w:r></w:p>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "latepoly", doc().body(body).bytes());
        float nearTop = 999;
        float nearBottom = 999;
        for (DocxDoc.Word w : r.words()) {
            if (w.page() != 1) {
                continue;
            }
            if (w.y() < 72 + 12) {
                nearTop = Math.min(nearTop, w.x());
            } else if (w.y() > 72 + 80 && w.y() < 72 + 98) {
                nearBottom = Math.min(nearBottom, w.x());
            }
        }
        assertTrue(nearTop < 72 + 60, "lines beside the narrow top start close to the margin: " + nearTop);
        assertTrue(nearBottom > 72 + 160, "lines beside the wide bottom start past it: " + nearBottom);
    }

    @Test
    void lightGrayHighlightIsTheLightGrayWordPrints() throws IOException {
        String body = "<w:p><w:r><w:rPr><w:highlight w:val=\"lightGray\"/><w:sz w:val=\"40\"/></w:rPr>"
                + "<w:t>………………</w:t></w:r></w:p>";
        BufferedImage page = raster(DocxDoc.render(dir, "highlight", doc().body(body).bytes()));
        assertEquals(new Color(0xD3D3D3), pixel(page, 75, 75));
    }

    @Test
    void washedOutPicturesAreFaded() throws IOException {
        String body = "<w:p><w:r><w:pict><v:shape id=\"p\" style=\"width:100pt;height:100pt\"><v:imagedata"
                + " r:id=\"rIdImg\" gain=\"19661f\" blacklevel=\"22938f\"/></v:shape></w:pict></w:r></w:p>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "washout", doc().body(body).bytes());
        try (PDDocument d = r.open()) {
            float a = minAlpha(d.getPage(0));
            assertTrue(a > 0.2f && a < 0.4f, "alpha " + a);
        }
    }

    @Test
    void smartArtIsDrawnFromItsCachedShapes() throws IOException {
        String graphic = "<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">"
                + "<wp:extent cx=\"" + emu(300) + "\" cy=\"" + emu(100) + "\"/><wp:docPr id=\"5\" name=\"d\"/>"
                + "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/diagram\"><dgm:relIds"
                + " xmlns:dgm=\"http://schemas.openxmlformats.org/drawingml/2006/diagram\" r:dm=\"rIdDm\" r:lo=\"rIdLo\""
                + " r:qs=\"rIdQs\" r:cs=\"rIdCs\"/></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>";
        String data = "<dgm:dataModel xmlns:dgm=\"http://schemas.openxmlformats.org/drawingml/2006/diagram\""
                + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><dgm:ptLst/><dgm:extLst><a:ext"
                + " uri=\"http://schemas.microsoft.com/office/drawing/2008/diagram\"><dsp:dataModelExt"
                + " xmlns:dsp=\"http://schemas.microsoft.com/office/drawing/2008/diagram\" relId=\"rIdDsp\"/></a:ext>"
                + "</dgm:extLst></dgm:dataModel>";
        String drawing = "<dsp:drawing xmlns:dsp=\"http://schemas.microsoft.com/office/drawing/2008/diagram\""
                + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><dsp:spTree><dsp:nvGrpSpPr>"
                + "<dsp:cNvPr id=\"0\" name=\"\"/><dsp:cNvGrpSpPr/></dsp:nvGrpSpPr><dsp:grpSpPr/>" + step(0, "Alpha")
                + step(150, "Omega") + "</dsp:spTree></dsp:drawing>";
        DocxDoc d = doc().body(graphic + DocxDoc.p("Below"));
        d.zip().put("word/diagrams/data1.xml", data);
        d.zip().override("/word/diagrams/data1.xml",
                "application/vnd.openxmlformats-officedocument.drawingml.diagramData+xml");
        d.zip().put("word/diagrams/drawing1.xml", drawing);
        d.zip().override("/word/diagrams/drawing1.xml", "application/vnd.ms-office.drawingml.diagramDrawing+xml");
        d.relationship("rIdDm", Fixtures.REL + "diagramData", "diagrams/data1.xml", false);
        d.relationship("rIdDsp", "http://schemas.microsoft.com/office/2007/relationships/diagramDrawing",
                "diagrams/drawing1.xml", false);
        DocxDoc.Rendered r = DocxDoc.render(dir, "smartart", d.bytes());
        DocxDoc.Word alpha = r.word("Alpha");
        DocxDoc.Word omega = r.word("Omega");
        assertTrue(omega.x() - alpha.x() > 120, alpha + " " + omega);
        assertTrue(alpha.y() > 72 && alpha.y() < 72 + 100, alpha.toString());
    }

    @Test
    void objectsAnchoredInsideATextBoxAreDrawnAgainstIt() throws IOException {
        String inner = textBox("column", 50, "paragraph", 20, 100, 30, DocxDoc.p("Inner"));
        String outer = textBox("page", 100, "page", 100, 300, 200, "<w:p>" + inner.substring(5));
        DocxDoc.Rendered r = DocxDoc.render(dir, "nested", doc().body(outer + DocxDoc.p("Body")).bytes());
        assertEquals(150, r.word("Inner").x(), 1.5f);
        assertTrue(r.word("Inner").y() > 120 && r.word("Inner").y() < 135, r.word("Inner").toString());
    }

    private static String textBox(String hRel, float x, String vRel, float y, float w, float h, String content) {
        return "<w:p><w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\""
                + " relativeHeight=\"4\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\"><wp:simplePos"
                + " x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"" + hRel + "\"><wp:posOffset>" + emu(x)
                + "</wp:posOffset></wp:positionH><wp:positionV relativeFrom=\"" + vRel + "\"><wp:posOffset>" + emu(y)
                + "</wp:posOffset></wp:positionV><wp:extent cx=\"" + emu(w) + "\" cy=\"" + emu(h) + "\"/><wp:wrapNone/>"
                + "<wp:docPr id=\"11\" name=\"tb\"/><a:graphic><a:graphicData"
                + " uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\"><wps:wsp><wps:spPr>"
                + "<a:prstGeom prst=\"rect\"/></wps:spPr><wps:txbx><w:txbxContent>" + content + "</w:txbxContent></wps:txbx>"
                + "<wps:bodyPr lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\"/></wps:wsp></a:graphicData></a:graphic>"
                + "</wp:anchor></w:drawing></w:r></w:p>";
    }

    @Test
    void vmlShapeTypesAndPathsKeepTheirOutline() throws IOException {
        String arrow = "<w:p><w:r><w:pict><v:shape id=\"a\" type=\"#_x0000_t13\" style=\"position:absolute;"
                + "margin-left:0;margin-top:0;width:200pt;height:100pt;z-index:1;mso-position-horizontal-relative:page;"
                + "mso-position-vertical-relative:page\" fillcolor=\"red\" stroked=\"f\"/></w:pict></w:r></w:p>";
        String triangle = "<w:p><w:r><w:pict><v:shape id=\"t\" coordsize=\"1000,1000\" path=\"m0,0l1000,1000,0,1000xe\""
                + " style=\"position:absolute;margin-left:300pt;margin-top:0;width:200pt;height:200pt;z-index:2;"
                + "mso-position-horizontal-relative:page;mso-position-vertical-relative:page\" fillcolor=\"blue\""
                + " stroked=\"f\"/></w:pict></w:r></w:p>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "vmlshapes", doc().body(arrow + triangle).bytes());
        BufferedImage page = raster(r);
        assertEquals(Color.RED, pixel(page, 100, 50));
        assertEquals(Color.WHITE, pixel(page, 195, 5));
        assertEquals(Color.BLUE, pixel(page, 310, 190));
        assertEquals(Color.WHITE, pixel(page, 490, 10));
    }

    @Test
    void vmlWrapAnchorsPlaceShapesAgainstTheMargin() throws IOException {
        String box = "<w:p><w:r><w:pict><v:shape id=\"b\" type=\"#_x0000_t202\" style=\"position:absolute;"
                + "margin-left:0;margin-top:300pt;width:200pt;height:40pt;z-index:3\" stroked=\"f\"><v:textbox"
                + " inset=\"0,0,0,0\"><w:txbxContent>" + DocxDoc.p("Anchored") + "</w:txbxContent></v:textbox>"
                + "<w10:wrap xmlns:w10=\"urn:schemas-microsoft-com:office:word\" anchory=\"margin\" type=\"square\"/>"
                + "</v:shape></w:pict></w:r></w:p>";
        String body = DocxDoc.p("One") + DocxDoc.p("Two") + DocxDoc.p("Three") + box;
        DocxDoc.Rendered r = DocxDoc.render(dir, "vmlanchor", doc().body(body).bytes());
        assertEquals(72 + 300 + 9, r.word("Anchored").y(), 3);
    }

    @Test
    void aQuarterTurnedChildTakesTheGroupScaleAlongItsTurnedAxes() throws IOException {
        String child = "<wps:wsp><wps:spPr><a:xfrm rot=\"5400000\"><a:off x=\"" + emu(50) + "\" y=\"" + emu(0)
                + "\"/><a:ext cx=\"" + emu(100) + "\" cy=\"" + emu(200) + "\"/></a:xfrm><a:prstGeom prst=\"rect\"/>"
                + "<a:solidFill><a:srgbClr val=\"FF0000\"/></a:solidFill></wps:spPr><wps:bodyPr/></wps:wsp>";
        String group = "<w:p><w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\""
                + " relativeHeight=\"2\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\"><wp:simplePos"
                + " x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"page\"><wp:posOffset>0</wp:posOffset></wp:positionH>"
                + "<wp:positionV relativeFrom=\"page\"><wp:posOffset>0</wp:posOffset></wp:positionV><wp:extent cx=\""
                + emu(400) + "\" cy=\"" + emu(100) + "\"/><wp:wrapNone/><wp:docPr id=\"3\" name=\"g\"/><a:graphic>"
                + "<a:graphicData uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingGroup\"><wpg:wgp"
                + " xmlns:wpg=\"http://schemas.microsoft.com/office/word/2010/wordprocessingGroup\"><wpg:grpSpPr><a:xfrm>"
                + "<a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + emu(400) + "\" cy=\"" + emu(100) + "\"/><a:chOff x=\"0\""
                + " y=\"0\"/><a:chExt cx=\"" + emu(200) + "\" cy=\"" + emu(200) + "\"/></a:xfrm></wpg:grpSpPr>" + child
                + "</wpg:wgp></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r></w:p>";
        BufferedImage page = raster(DocxDoc.render(dir, "turned", doc().body(group).bytes()));
        assertEquals(Color.RED, pixel(page, 10, 50));
        assertEquals(Color.RED, pixel(page, 390, 50));
        assertEquals(Color.WHITE, pixel(page, 200, 80));
    }

    @Test
    void linesGetTheirArrowheadsAndVmlLinesTheirEndPoints() throws IOException {
        String arrow = "<w:p><w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\""
                + " relativeHeight=\"2\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\"><wp:simplePos"
                + " x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"page\"><wp:posOffset>" + emu(100) + "</wp:posOffset>"
                + "</wp:positionH><wp:positionV relativeFrom=\"page\"><wp:posOffset>" + emu(100) + "</wp:posOffset>"
                + "</wp:positionV><wp:extent cx=\"" + emu(200) + "\" cy=\"0\"/><wp:wrapNone/><wp:docPr id=\"4\""
                + " name=\"a\"/><a:graphic><a:graphicData uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\">"
                + "<wps:wsp><wps:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + emu(200) + "\" cy=\"0\"/></a:xfrm>"
                + "<a:prstGeom prst=\"straightConnector1\"/><a:ln w=\"38100\"><a:solidFill><a:srgbClr val=\"0000FF\"/>"
                + "</a:solidFill><a:tailEnd type=\"triangle\"/></a:ln></wps:spPr><wps:bodyPr/></wps:wsp></a:graphicData>"
                + "</a:graphic></wp:anchor></w:drawing></w:r></w:p>";
        String line = "<w:p><w:r><w:pict><v:line id=\"l\" style=\"position:absolute;z-index:5;"
                + "mso-position-horizontal-relative:page;mso-position-vertical-relative:page\" from=\"100pt,300pt\""
                + " to=\"300pt,300pt\" strokecolor=\"red\" strokeweight=\"3pt\"/></w:pict></w:r></w:p>";
        BufferedImage page = raster(DocxDoc.render(dir, "arrows", doc().body(arrow + line).bytes()));
        assertEquals(Color.BLUE, pixel(page, 292, 102));
        assertEquals(Color.WHITE, pixel(page, 150, 103));
        assertEquals(Color.RED, pixel(page, 200, 300));
        assertEquals(Color.WHITE, pixel(page, 50, 300));
    }

    @Test
    void thinLinesGetArrowheadsSizedFromTwoPointsAndStopInsideThem() throws IOException {
        String arrow = "<w:p><w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\""
                + " relativeHeight=\"2\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\"><wp:simplePos"
                + " x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"page\"><wp:posOffset>" + emu(100) + "</wp:posOffset>"
                + "</wp:positionH><wp:positionV relativeFrom=\"page\"><wp:posOffset>" + emu(100) + "</wp:posOffset>"
                + "</wp:positionV><wp:extent cx=\"0\" cy=\"" + emu(100) + "\"/><wp:wrapNone/><wp:docPr id=\"4\""
                + " name=\"a\"/><a:graphic><a:graphicData uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\">"
                + "<wps:wsp><wps:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"" + emu(100) + "\"/></a:xfrm>"
                + "<a:prstGeom prst=\"line\"/><a:ln w=\"9525\"><a:solidFill><a:srgbClr val=\"0000FF\"/>"
                + "</a:solidFill><a:tailEnd type=\"triangle\" w=\"med\" len=\"med\"/></a:ln></wps:spPr><wps:bodyPr/>"
                + "</wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r></w:p>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "thinArrow", doc().body(arrow).bytes());
        try (PDDocument d = r.open()) {
            BufferedImage page = new PDFRenderer(d).renderImage(0, 4);
            int headRow = Math.round(197 * 4);
            int blue = 0;
            for (int x = 380; x < 420; x++) {
                Color c = new Color(page.getRGB(x, headRow));
                blue += c.getBlue() > 200 && c.getRed() < 80 ? 1 : 0;
            }
            assertTrue(blue >= 12 && blue <= 26, "arrowhead three quarters of the way down is " + blue / 4f + " pt wide");
        }
        java.awt.Shape trimmed = ShapePath.trimmed(new java.awt.geom.Line2D.Float(0, 0, 0, 100),
                new Drawing.LineEnds("none", "med", "med", "triangle", "med", "med"),
                stirling.software.officeconvert.topdf.pdf.Stroke.solid(0.75f, Color.BLUE));
        assertEquals(95, ((java.awt.geom.Line2D) trimmed).getY2(), 0.01);
    }

    @Test
    void picturesCastTheirOuterShadow() throws IOException {
        String framed = "<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent"
                + " cx=\"" + emu(100) + "\" cy=\"" + emu(100) + "\"/><wp:docPr id=\"8\" name=\"p\"/><a:graphic>"
                + "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:nvPicPr>"
                + "<pic:cNvPr id=\"1\" name=\"p\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"rIdImg\"/>"
                + "<a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext"
                + " cx=\"" + emu(100) + "\" cy=\"" + emu(100) + "\"/></a:xfrm><a:prstGeom prst=\"rect\"/><a:ln w=\"88900\">"
                + "<a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill></a:ln><a:effectLst><a:outerShdw blurRad=\"55000\""
                + " dist=\"18000\" dir=\"5400000\" algn=\"tl\" rotWithShape=\"0\"><a:srgbClr val=\"000000\"><a:alpha"
                + " val=\"40000\"/></a:srgbClr></a:outerShdw></a:effectLst></pic:spPr></pic:pic></a:graphicData></a:graphic>"
                + "</wp:inline></w:drawing></w:r></w:p>";
        BufferedImage page = raster(DocxDoc.render(dir, "shadow", doc().body(framed).bytes()));
        int top = -1;
        for (int y = 0; y < 300 && top < 0; y++) {
            if (pixel(page, 122, y).equals(Color.BLUE)) {
                top = y;
            }
        }
        assertTrue(top > 0, "picture drawn");
        Color below = pixel(page, 122, top + 101);
        assertTrue(below.getRed() < 230 && below.getRed() == below.getBlue(), "shadow under the frame " + below);
        assertEquals(Color.WHITE, pixel(page, 122, top + 97));
        assertEquals(Color.WHITE, pixel(page, 122, top + 108));
    }

    @Test
    void gradientFillsRunAcrossTheShape() throws IOException {
        String shape = "<w:p><w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\""
                + " relativeHeight=\"2\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\"><wp:simplePos"
                + " x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"page\"><wp:posOffset>" + emu(100) + "</wp:posOffset>"
                + "</wp:positionH><wp:positionV relativeFrom=\"page\"><wp:posOffset>" + emu(100) + "</wp:posOffset>"
                + "</wp:positionV><wp:extent cx=\"" + emu(200) + "\" cy=\"" + emu(100) + "\"/><wp:wrapNone/><wp:docPr"
                + " id=\"6\" name=\"g\"/><a:graphic><a:graphicData"
                + " uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\"><wps:wsp><wps:spPr><a:xfrm>"
                + "<a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + emu(200) + "\" cy=\"" + emu(100) + "\"/></a:xfrm><a:prstGeom"
                + " prst=\"rect\"/><a:gradFill><a:gsLst><a:gs pos=\"0\"><a:srgbClr val=\"FF0000\"/></a:gs><a:gs"
                + " pos=\"100000\"><a:srgbClr val=\"0000FF\"/></a:gs></a:gsLst><a:lin ang=\"0\" scaled=\"1\"/>"
                + "</a:gradFill></wps:spPr><wps:bodyPr/></wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing>"
                + "</w:r></w:p>";
        BufferedImage page = raster(DocxDoc.render(dir, "gradient", doc().body(shape).bytes()));
        Color left = pixel(page, 102, 150);
        Color right = pixel(page, 298, 150);
        assertTrue(left.getRed() > 230 && left.getBlue() < 30, left.toString());
        assertTrue(right.getBlue() > 230 && right.getRed() < 30, right.toString());
    }

    @Test
    void horizontalRulesSpanTheLine() throws IOException {
        String rule = "<w:p><w:r><w:pict><v:rect id=\"hr\" style=\"width:0;height:3pt\" o:hralign=\"center\""
                + " o:hrstd=\"t\" o:hr=\"t\" fillcolor=\"#a0a0a0\" stroked=\"f\"/></w:pict></w:r></w:p>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "rule", doc().body(DocxDoc.p("Above") + rule + DocxDoc.p("Below"))
                .bytes());
        BufferedImage page = raster(r);
        int top = Math.round(r.word("Above").y()) + 2;
        int bottom = Math.round(r.word("Below").y()) - 8;
        boolean left = false;
        boolean right = false;
        for (int y = top; y <= bottom; y++) {
            left |= pixel(page, 80, y).equals(new Color(0xA0A0A0));
            right |= pixel(page, 530, y).equals(new Color(0xA0A0A0));
        }
        assertTrue(left && right, "rule between " + top + " and " + bottom);
    }

    private static BufferedImage raster(DocxDoc.Rendered r) throws IOException {
        try (PDDocument d = r.open()) {
            return new PDFRenderer(d).renderImage(0, 1);
        }
    }

    @Test
    void aVmlWrapWithoutATypeLeavesTheTextAlone() throws IOException {
        String mark = "<w:p xmlns:w10=\"urn:schemas-microsoft-com:office:word\"><w:r><w:pict><v:shape id=\"w\""
                + " type=\"#_x0000_t75\" style=\"position:absolute;margin-left:0;margin-top:0;width:400pt;"
                + "height:200pt\"><v:imagedata r:id=\"rIdImg\"/><w10:wrap anchorx=\"margin\" anchory=\"margin\"/>"
                + "</v:shape></w:pict></w:r><w:r><w:t>First</w:t></w:r></w:p>" + DocxDoc.p("Second");
        DocxDoc.Rendered r = DocxDoc.render(dir, "vmlnowrap", doc().body(mark).bytes());
        assertEquals(72, r.word("First").x(), 1);
        assertTrue(r.word("Second").y() < 72 + 40, r.word("Second").toString());
    }

    @Test
    void textKeepsNinePointsFromASquareWrappedVmlPicture() throws IOException {
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            words.append("a ");
        }
        String body = "<w:p xmlns:w10=\"urn:schemas-microsoft-com:office:word\"><w:r><w:pict><v:shape id=\"p\""
                + " type=\"#_x0000_t75\" style=\"position:absolute;margin-left:300pt;margin-top:0;width:100pt;"
                + "height:100pt\"><v:imagedata r:id=\"rIdImg\"/><w10:wrap type=\"square\"/></v:shape></w:pict></w:r>"
                + "<w:r><w:t xml:space=\"preserve\">" + words + "</w:t></w:r></w:p>";
        DocxDoc.Rendered out = DocxDoc.render(dir, "vmlwrap", doc().body(body).bytes());
        float right = 0;
        float after = 999;
        for (DocxDoc.Word w : out.words()) {
            if (w.y() < 72 + 90 && w.x() < 72 + 300) {
                right = Math.max(right, w.x());
            } else if (w.y() < 72 + 90) {
                after = Math.min(after, w.x());
            }
        }
        assertTrue(right > 340 && right < 72 + 300 - 9 - 4, "last word before the picture starts at " + right);
        assertTrue(after >= 72 + 400 + 9 - 0.5f && after < 72 + 400 + 12, "first word after it starts at " + after);
    }

    private static Color pixel(BufferedImage page, int x, int y) {
        return new Color(page.getRGB(x, y));
    }

    private static String step(float x, String text) {
        return "<dsp:sp modelId=\"{" + text + "}\"><dsp:nvSpPr><dsp:cNvPr id=\"0\" name=\"\"/><dsp:cNvSpPr/>"
                + "</dsp:nvSpPr><dsp:spPr><a:xfrm><a:off x=\"" + emu(x) + "\" y=\"0\"/><a:ext cx=\"" + emu(150)
                + "\" cy=\"" + emu(100) + "\"/></a:xfrm><a:prstGeom prst=\"roundRect\"><a:avLst/></a:prstGeom>"
                + "<a:solidFill><a:srgbClr val=\"4472C4\"/></a:solidFill></dsp:spPr><dsp:txBody><a:bodyPr"
                + " anchor=\"ctr\"/><a:lstStyle/><a:p><a:pPr algn=\"ctr\"/><a:r><a:rPr lang=\"en-US\" sz=\"2000\">"
                + "<a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill></a:rPr><a:t>" + text + "</a:t></a:r></a:p>"
                + "</dsp:txBody><dsp:txXfrm><a:off x=\"" + emu(x) + "\" y=\"0\"/><a:ext cx=\"" + emu(150) + "\" cy=\""
                + emu(100) + "\"/></dsp:txXfrm></dsp:sp>";
    }

    private static float minAlpha(PDPage page) {
        float min = 1;
        PDResources res = page.getResources();
        for (COSName n : res.getExtGStateNames()) {
            PDExtendedGraphicsState gs = res.getExtGState(n);
            Float ca = gs.getNonStrokingAlphaConstant();
            if (ca != null) {
                min = Math.min(min, ca);
            }
        }
        return min;
    }
}
