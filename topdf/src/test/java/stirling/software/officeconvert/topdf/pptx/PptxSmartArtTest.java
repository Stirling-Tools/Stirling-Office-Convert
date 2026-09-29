package stirling.software.officeconvert.topdf.pptx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.font.CloudFonts;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.testing.Fixtures;

class PptxSmartArtTest {

    static final String DGM = "http://schemas.openxmlformats.org/drawingml/2006/diagram";

    static final String DSP = "http://schemas.microsoft.com/office/drawing/2008/diagram";

    static final String OFD = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";

    @TempDir
    Path dir;

    static String frame() {
        return "<p:graphicFrame " + Decks.NS + "><p:nvGraphicFramePr><p:cNvPr id=\"4\" name=\"Diagram 3\"/>"
                + "<p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off x=\"914400\" y=\"914400\"/>"
                + "<a:ext cx=\"5486400\" cy=\"3657600\"/></p:xfrm><a:graphic><a:graphicData uri=\"" + DGM + "\">"
                + "<dgm:relIds xmlns:dgm=\"" + DGM + "\" r:dm=\"rId10\" r:lo=\"rId11\" r:qs=\"rId12\" r:cs=\"rId13\"/>"
                + "</a:graphicData></a:graphic></p:graphicFrame>";
    }

    static String shape(int id, String geom, long x, long y, long cx, long cy, String text) {
        String tx = text == null ? "" : "<dsp:txBody><a:bodyPr anchor=\"ctr\"/><a:lstStyle/><a:p><a:pPr algn=\"ctr\"/>"
                + "<a:r><a:rPr lang=\"en-US\" sz=\"1800\"/><a:t>" + text + "</a:t></a:r></a:p></dsp:txBody>"
                + "<dsp:txXfrm><a:off x=\"" + x + "\" y=\"" + y + "\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy
                + "\"/></dsp:txXfrm>";
        return "<dsp:sp modelId=\"{0000000" + id + "-0000-0000-0000-000000000000}\"><dsp:nvSpPr><dsp:cNvPr id=\"0\" "
                + "name=\"\"/><dsp:cNvSpPr/></dsp:nvSpPr><dsp:spPr><a:xfrm><a:off x=\"" + x + "\" y=\"" + y + "\"/>"
                + "<a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm><a:prstGeom prst=\"" + geom + "\"><a:avLst/>"
                + "</a:prstGeom><a:solidFill><a:srgbClr val=\"FF0000\"/></a:solidFill><a:ln w=\"12700\"><a:solidFill>"
                + "<a:srgbClr val=\"0000FF\"/></a:solidFill></a:ln></dsp:spPr><dsp:style><a:lnRef idx=\"2\">"
                + "<a:scrgbClr r=\"0\" g=\"0\" b=\"0\"/></a:lnRef><a:fillRef idx=\"1\"><a:scrgbClr r=\"0\" g=\"0\" b=\"0\"/>"
                + "</a:fillRef><a:effectRef idx=\"0\"><a:scrgbClr r=\"0\" g=\"0\" b=\"0\"/></a:effectRef><a:fontRef idx=\"minor\">"
                + "<a:schemeClr val=\"lt1\"/></a:fontRef></dsp:style>" + tx + "</dsp:sp>";
    }

    static byte[] smartArt(String drawingTree, boolean dataModelExt, String drawingName) {
        byte[] base = Decks.slideXml(frame());
        Fixtures.Zip z = Fixtures.edit(base);
        String slide = "ppt/slides/slide1.xml";
        String ext = dataModelExt ? "<dgm:extLst><a:ext uri=\"http://schemas.microsoft.com/office/drawing/2008/diagram\">"
                + "<dsp:dataModelExt xmlns:dsp=\"" + DSP + "\" relId=\"rId14\" minVer=\"http://schemas.openxmlformats.org/"
                + "drawingml/2006/diagram\"/></a:ext></dgm:extLst>" : "";
        z.put("ppt/diagrams/data1.xml", "<dgm:dataModel xmlns:dgm=\"" + DGM + "\" xmlns:a=\"" + Decks.A + "\"><dgm:ptLst>"
                + "<dgm:pt modelId=\"{00000000-0000-0000-0000-000000000001}\" type=\"doc\"><dgm:prSet/><dgm:spPr/>"
                + "<dgm:t><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang=\"en-US\"/></a:p></dgm:t></dgm:pt></dgm:ptLst>"
                + "<dgm:cxnLst/><dgm:bg/><dgm:whole/>" + ext + "</dgm:dataModel>");
        z.put("ppt/diagrams/layout1.xml", "<dgm:layoutDef xmlns:dgm=\"" + DGM + "\" xmlns:a=\"" + Decks.A + "\" "
                + "uniqueId=\"urn:microsoft.com/office/officeart/2005/8/layout/radial1\"><dgm:layoutNode name=\"x\"/>"
                + "</dgm:layoutDef>");
        z.put("ppt/diagrams/quickStyle1.xml", "<dgm:styleDef xmlns:dgm=\"" + DGM + "\" xmlns:a=\"" + Decks.A + "\" "
                + "uniqueId=\"urn:microsoft.com/office/officeart/2005/8/quickstyle/simple1\"><dgm:scene3d><a:camera "
                + "prst=\"orthographicFront\"/><a:lightRig rig=\"threePt\" dir=\"t\"/></dgm:scene3d></dgm:styleDef>");
        z.put("ppt/diagrams/colors1.xml", "<dgm:colorsDef xmlns:dgm=\"" + DGM + "\" xmlns:a=\"" + Decks.A + "\" "
                + "uniqueId=\"urn:microsoft.com/office/officeart/2005/8/colors/accent1_2\"/>");
        z.put("ppt/diagrams/" + drawingName, "<dsp:drawing xmlns:dgm=\"" + DGM + "\" xmlns:dsp=\"" + DSP + "\" xmlns:a=\""
                + Decks.A + "\"><dsp:spTree><dsp:nvGrpSpPr><dsp:cNvPr id=\"0\" name=\"\"/><dsp:cNvGrpSpPr/>"
                + "</dsp:nvGrpSpPr><dsp:grpSpPr/>" + drawingTree + "</dsp:spTree></dsp:drawing>");
        z.override("/ppt/diagrams/data1.xml", "application/vnd.openxmlformats-officedocument.drawingml.diagramData+xml");
        z.override("/ppt/diagrams/layout1.xml", "application/vnd.openxmlformats-officedocument.drawingml.diagramLayout+xml");
        z.override("/ppt/diagrams/quickStyle1.xml",
                "application/vnd.openxmlformats-officedocument.drawingml.diagramStyle+xml");
        z.override("/ppt/diagrams/colors1.xml", "application/vnd.openxmlformats-officedocument.drawingml.diagramColors+xml");
        z.override("/ppt/diagrams/" + drawingName, "application/vnd.ms-office.drawingml.diagramDrawing+xml");
        z.relationship(slide, "rId10", OFD + "diagramData", "../diagrams/data1.xml", false);
        z.relationship(slide, "rId11", OFD + "diagramLayout", "../diagrams/layout1.xml", false);
        z.relationship(slide, "rId12", OFD + "diagramQuickStyle", "../diagrams/quickStyle1.xml", false);
        z.relationship(slide, "rId13", OFD + "diagramColors", "../diagrams/colors1.xml", false);
        z.relationship(slide, "rId14", "http://schemas.microsoft.com/office/2007/relationships/diagramDrawing",
                "../diagrams/" + drawingName, false);
        return z.bytes();
    }

    @Test
    void smartArtDrawsTheStoredDrawing() throws IOException {
        byte[] pptx = smartArt(shape(1, "ellipse", 0, 0, 2743200, 1371600, "Literacy outcome"), true, "drawing1.xml");
        Decks.Converted c = Decks.convert(dir, "smartart.pptx", pptx);
        assertTrue(c.text().contains("Literacy outcome"), c.text() + " " + c.result().warnings());
    }

    @Test
    void smartArtDrawsWithALowerCaseContentType() throws IOException {
        byte[] pptx = smartArt(shape(1, "ellipse", 0, 0, 2743200, 1371600, "Literacy outcome"), true, "drawing1.xml");
        Fixtures.Zip z = Fixtures.edit(pptx);
        z.put("[Content_Types].xml", z.text("[Content_Types].xml").replace("diagramDrawing+xml", "diagramdrawing+xml"));
        Decks.Converted c = Decks.convert(dir, "smartartlc.pptx", z.bytes());
        assertTrue(c.text().contains("Literacy outcome"), c.text() + " " + c.result().warnings());
    }

    private static String node(int id, String text) {
        return "<dgm:pt modelId=\"{00000000-0000-0000-0000-00000000000" + id + "}\"><dgm:prSet/><dgm:spPr/><dgm:t>"
                + "<a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang=\"en-US\"/><a:t>" + text + "</a:t></a:r></a:p>"
                + "</dgm:t></dgm:pt>";
    }

    private static String link(int from, int to, int order) {
        return "<dgm:cxn modelId=\"{10000000-0000-0000-0000-00000000000" + to + "}\" srcId=\"{00000000-0000-0000-0000-"
                + "00000000000" + from + "}\" destId=\"{00000000-0000-0000-0000-00000000000" + to + "}\" srcOrd=\""
                + order + "\" destOrd=\"0\"/>";
    }

    @Test
    void aNodeWhoseTextRunsFarOffTheSlideWritesOnlyWhatShows() throws IOException {
        Fixtures.Zip z = Fixtures.edit(smartArt("", true, "drawing1.xml"));
        String flood = "word ".repeat(20_000);
        z.put("ppt/diagrams/data1.xml", "<dgm:dataModel xmlns:dgm=\"" + DGM + "\" xmlns:a=\"" + Decks.A + "\"><dgm:ptLst>"
                + "<dgm:pt modelId=\"{00000000-0000-0000-0000-000000000001}\" type=\"doc\"><dgm:prSet/><dgm:spPr/></dgm:pt>"
                + node(2, "Hub") + node(3, flood) + "</dgm:ptLst><dgm:cxnLst>" + link(1, 2, 0) + link(2, 3, 0)
                + "</dgm:cxnLst></dgm:dataModel>");
        Decks.Converted c = Decks.convert(dir, "flood.pptx", z.bytes());
        int shown = c.text().replaceAll("\s", "").length();
        assertTrue(shown > 20 && shown < 20_000, "characters written: " + shown);
    }

    @Test
    void aSmartArtSavedWithoutItsDrawingIsLaidOutFromItsData() throws IOException {
        Fixtures.Zip z = Fixtures.edit(smartArt("", true, "drawing1.xml"));
        z.put("ppt/diagrams/data1.xml", "<dgm:dataModel xmlns:dgm=\"" + DGM + "\" xmlns:a=\"" + Decks.A + "\"><dgm:ptLst>"
                + "<dgm:pt modelId=\"{00000000-0000-0000-0000-000000000001}\" type=\"doc\"><dgm:prSet/><dgm:spPr/></dgm:pt>"
                + node(2, "Hub") + node(3, "North") + node(4, "East") + node(5, "South") + "</dgm:ptLst><dgm:cxnLst>"
                + link(1, 2, 0) + link(2, 3, 0) + link(2, 4, 1) + link(2, 5, 2) + "</dgm:cxnLst></dgm:dataModel>");
        Decks.Converted c = Decks.convert(dir, "stub.pptx", z.bytes());
        String text = c.text();
        for (String t : new String[] {"Hub", "North", "East", "South"}) {
            assertTrue(text.contains(t), text + " " + c.result().warnings());
        }
        java.awt.image.BufferedImage img = c.render(0, 72);
        int p = img.getRGB(72 + 216, 72 + 144);
        assertTrue((p & 0xFFFFFF) != 0xFFFFFF, "the hub is drawn");
        assertTrue((img.getRGB(398, 335) & 0xFFFFFF) != 0xFFFFFF, "equal circles 1.3 diameters from the hub");
        assertTrue((img.getRGB(458, 311) & 0xFFFFFF) == 0xFFFFFF, "nothing beyond the east circle");
        assertTrue((img.getRGB(343, 262) & 0xFFFFFF) == 0xFFFFFF, "a gap between the hub and the east circle");
    }

    @Test
    void layoutNamesPickAnArrangement() {
        String base = "urn:microsoft.com/office/officeart/2005/8/layout/";
        org.junit.jupiter.api.Assertions.assertEquals(SmartArtLayout.Kind.BASIC_RADIAL,
                SmartArtLayout.kind(base + "radial1"));
        org.junit.jupiter.api.Assertions.assertEquals(SmartArtLayout.Kind.RADIAL, SmartArtLayout.kind(base + "radial2"));
        org.junit.jupiter.api.Assertions.assertEquals(SmartArtLayout.Kind.CYCLE, SmartArtLayout.kind(base + "cycle2"));
        org.junit.jupiter.api.Assertions.assertEquals(SmartArtLayout.Kind.ROW, SmartArtLayout.kind(base + "process1"));
        org.junit.jupiter.api.Assertions.assertEquals(SmartArtLayout.Kind.COLUMN, SmartArtLayout.kind(base + "vList2"));
        org.junit.jupiter.api.Assertions.assertEquals(SmartArtLayout.Kind.GRID, SmartArtLayout.kind(base + "default#1"));
        org.junit.jupiter.api.Assertions.assertNull(SmartArtLayout.kind(base + "arrow2"));
    }

    private static List<SmartArtLayout.Point> points(float... widths) {
        List<SmartArtLayout.Point> out = new ArrayList<>();
        for (int i = 0; i < widths.length; i++) {
            SmartArtLayout.Point p = new SmartArtLayout.Point("p" + i);
            p.scaleX = widths[i];
            out.add(p);
        }
        return out;
    }

    @Test
    void blockListsFillCentredRowsAsLargeAsTheFrameAllows() {
        List<SmartArtLayout.Box> boxes = SmartArtLayout.snake(points(1, 1, 1, 1, 1), 0, 0, 1000, 420);
        assertEquals(5, boxes.size());
        float u = boxes.get(0).w();
        assertEquals(1000 / 3.2f, u, 0.5f, "three a row, a tenth of a box apart");
        assertEquals(0.6f * u, boxes.get(0).h(), 0.01f);
        assertEquals(boxes.get(0).y(), boxes.get(2).y(), 0.01f);
        assertTrue(boxes.get(3).y() > boxes.get(0).y() + boxes.get(0).h());
        float second = boxes.get(3).x() + (boxes.get(4).x() + boxes.get(4).w() - boxes.get(3).x()) / 2;
        assertEquals(500, second, 0.5f, "a short row is centred");
        assertEquals(SmartArtLayout.Form.SQUARE, boxes.get(0).form());
    }

    @Test
    void resizedAndDraggedBlocksKeepTheirShareOfTheRow() {
        List<SmartArtLayout.Point> nodes = points(1, 2, 1, 1);
        nodes.get(0).shiftY = -0.5f;
        nodes.get(1).scaleY = 0.5f;
        List<SmartArtLayout.Box> boxes = SmartArtLayout.snake(nodes, 0, 0, 1000, 300);
        float u = boxes.get(0).w();
        assertEquals(2 * u, boxes.get(1).w(), 0.01f);
        assertEquals(0.3f * u, boxes.get(1).h(), 0.01f);
        assertEquals(boxes.get(2).y() - 0.3f * u, boxes.get(0).y(), 0.01f, "moved up by half its height");
        assertTrue(boxes.get(1).y() > boxes.get(2).y(), "a shorter box sits in the middle of its row");
        assertTrue(boxes.get(3).y() > boxes.get(2).y() + boxes.get(2).h(), "the fourth wraps");
    }

    @Test
    void draggedShapesAreCentredInTheFrameAsAWhole() {
        List<SmartArtLayout.Box> boxes = List.of(new SmartArtLayout.Box(0, 0, 10, 10, SmartArtLayout.Form.ELLIPSE),
                new SmartArtLayout.Box(30, 0, 10, 10, SmartArtLayout.Form.ELLIPSE));
        float[] move = SmartArtLayout.recentre(boxes, 0, 0, 100, 100);
        assertEquals(30, move[0], 1e-4);
        assertEquals(45, move[1], 1e-4);
        List<SmartArtLayout.Point> nodes = points(1, 1, 1);
        nodes.get(0).shiftY = -1;
        List<SmartArtLayout.Box> row = SmartArtLayout.snake(nodes, 0, 0, 1000, 300);
        float top = row.get(0).y();
        float bottom = row.get(1).y() + row.get(1).h();
        assertEquals(150, (top + bottom) / 2, 0.01f, "a box dragged up moves the row down");
    }

    @Test
    void smartArtTextSitsInsideTheShapeLessItsMargins() {
        SmartArtLayout.Box circle = new SmartArtLayout.Box(0, 0, 100, 100, SmartArtLayout.Form.ELLIPSE);
        float[] in = SmartArtLayout.inner(circle, 20);
        assertEquals(100 * Math.sqrt(0.5) - 2, in[2], 0.01f);
        SmartArtLayout.Box box = new SmartArtLayout.Box(0, 0, 200, 120, SmartArtLayout.Form.SQUARE);
        assertEquals(200 - 12, SmartArtLayout.inner(box, 20)[2], 0.01f);
        FontLibrary fonts = FontLibrary.of(List.of(dir));
        Standins.Emulation liberation = new Standins(new CloudFonts(fonts)).emulate("Arial", false, false);
        assertEquals(0.9f * 1.149f, SmartArtLayout.lineMetrics(liberation)[0], 0.01f, "90 % of the font's line");
    }

    @Test
    void aBlockListWithoutItsDrawingHasSquareBoxesAndTheQuickStyleText() throws IOException {
        Fixtures.Zip z = Fixtures.edit(smartArt("", true, "drawing1.xml"));
        z.put("ppt/diagrams/data1.xml", "<dgm:dataModel xmlns:dgm=\"" + DGM + "\" xmlns:a=\"" + Decks.A + "\"><dgm:ptLst>"
                + "<dgm:pt modelId=\"{00000000-0000-0000-0000-000000000001}\" type=\"doc\"><dgm:prSet/><dgm:spPr/></dgm:pt>"
                + node(2, "One") + node(3, "Two") + node(4, "Three") + "</dgm:ptLst><dgm:cxnLst>" + link(1, 2, 0)
                + link(1, 3, 1) + link(1, 4, 2) + "</dgm:cxnLst></dgm:dataModel>");
        z.put("ppt/diagrams/layout1.xml", "<dgm:layoutDef xmlns:dgm=\"" + DGM + "\" xmlns:a=\"" + Decks.A + "\" "
                + "uniqueId=\"urn:microsoft.com/office/officeart/2005/8/layout/default\"><dgm:layoutNode name=\"x\"/>"
                + "</dgm:layoutDef>");
        z.put("ppt/diagrams/quickStyle1.xml", "<dgm:styleDef xmlns:dgm=\"" + DGM + "\" xmlns:a=\"" + Decks.A + "\" "
                + "uniqueId=\"urn:microsoft.com/office/officeart/2005/8/quickstyle/simple1\"><dgm:styleLbl name=\"node1\">"
                + "<dgm:style><a:lnRef idx=\"0\"><a:scrgbClr r=\"0\" g=\"0\" b=\"0\"/></a:lnRef><a:fillRef idx=\"3\">"
                + "<a:scrgbClr r=\"0\" g=\"0\" b=\"0\"/></a:fillRef><a:effectRef idx=\"0\"><a:scrgbClr r=\"0\" g=\"0\" "
                + "b=\"0\"/></a:effectRef><a:fontRef idx=\"minor\"><a:srgbClr val=\"00FF00\"/></a:fontRef></dgm:style>"
                + "</dgm:styleLbl></dgm:styleDef>");
        Decks.Converted c = Decks.convert(dir, "blocks.pptx", z.bytes());
        assertTrue(c.text().contains("Three"), c.text() + " " + c.result().warnings());
        java.awt.image.BufferedImage img = c.render(0, 72);
        List<SmartArtLayout.Box> boxes = SmartArtLayout.snake(points(1, 1, 1), 72, 72, 432, 288);
        SmartArtLayout.Box first = boxes.get(0);
        int corner = img.getRGB(Math.round(first.x() + 1.5f), Math.round(first.y() + 1.5f));
        assertTrue((corner & 0xFFFFFF) != 0xFFFFFF, "square corners, no outline");
        boolean green = false;
        for (int x = Math.round(first.x()); x < first.x() + first.w() && !green; x++) {
            for (int y = Math.round(first.y()); y < first.y() + first.h(); y++) {
                int p = img.getRGB(x, y);
                if (((p >> 8) & 0xFF) > 200 && ((p >> 16) & 0xFF) < 80 && (p & 0xFF) < 80) {
                    green = true;
                    break;
                }
            }
        }
        assertTrue(green, "the quick style's text colour");
        int top = img.getRGB(Math.round(first.x() + 3), Math.round(first.y() + 3));
        int bottom = img.getRGB(Math.round(first.x() + 3), Math.round(first.y() + first.h() - 3));
        assertTrue(top != bottom, "the theme's third fill style is a gradient");
    }
}
