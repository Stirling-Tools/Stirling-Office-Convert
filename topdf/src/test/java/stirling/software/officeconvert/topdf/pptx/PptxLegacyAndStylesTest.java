package stirling.software.officeconvert.topdf.pptx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.Fixtures;

class PptxLegacyAndStylesTest {

    private static final String OFD = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";

    private static final String VML_TYPE = "application/vnd.openxmlformats-officedocument.vmlDrawing";

    @TempDir
    Path dir;

    private static String vmlShape(String id, String style, String relId) {
        return "<v:shape id=\"" + id + "\" o:spid=\"" + id + "\" style='position:absolute;" + style + "'>"
                + "<![if gte mso 9]><v:imagedata o:relid=\"" + relId + "\" o:title=\"\"/><![endif]></v:shape>";
    }

    private static byte[] legacy(String slideShapes, String vmlShapes) {
        Fixtures.Zip z = Fixtures.edit(Decks.slideXml(slideShapes));
        z.put("ppt/drawings/vmlDrawing1.vml", "<xml xmlns:v=\"urn:schemas-microsoft-com:vml\" "
                + "xmlns:o=\"urn:schemas-microsoft-com:office:office\">" + vmlShapes + "</xml>");
        z.put("ppt/media/red.png", Fixtures.png(20, 10, Color.RED));
        z.put("ppt/media/blue.png", Fixtures.png(20, 10, Color.BLUE));
        z.defaultType("vml", VML_TYPE);
        z.defaultType("png", "image/png");
        z.relationship("ppt/slides/slide1.xml", "rId90", OFD + "vmlDrawing", "../drawings/vmlDrawing1.vml", false);
        z.relationship("ppt/drawings/vmlDrawing1.vml", "rId1", OFD + "image", "../media/red.png", false);
        z.relationship("ppt/drawings/vmlDrawing1.vml", "rId2", OFD + "image", "../media/blue.png", false);
        return z.bytes();
    }

    @Test
    void aControlKeptOnlyInTheLegacyDrawingShowsItsPreview() throws IOException {
        byte[] pptx = legacy("", vmlShape("_x0000_s1025", "left:100pt;top:100pt;width:200pt;height:100pt", "rId1")
                + vmlShape("_x0000_s1026", "left:400pt;top:300pt;width:200pt;height:100pt;visibility:hidden", "rId2"));
        Decks.Converted c = Decks.convert(dir, "legacy.pptx", pptx);
        BufferedImage page = c.render(0, 72);
        assertEquals(Color.RED.getRGB(), page.getRGB(200, 150), c.result().warnings().toString());
        assertEquals(Color.WHITE.getRGB(), page.getRGB(500, 350));
    }

    @Test
    void aControlWithItsOwnPictureOnTheSlideIsNotDrawnAgain() throws IOException {
        String control = "<mc:AlternateContent xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\">"
                + "<mc:Choice xmlns:v=\"urn:schemas-microsoft-com:vml\" Requires=\"v\"><p:control " + Decks.NS
                + " spid=\"_x0000_s1025\" name=\"C\" r:id=\"rId91\"/></mc:Choice><mc:Fallback><p:control " + Decks.NS
                + " spid=\"_x0000_s1025\" name=\"C\" r:id=\"rId91\"><p:pic><p:nvPicPr><p:cNvPr id=\"9\" name=\"C\"/><p:cNvPicPr/><p:nvPr/>"
                + "</p:nvPicPr><p:blipFill><a:blip r:embed=\"rId92\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>"
                + "<p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"12700\" cy=\"12700\"/></a:xfrm><a:prstGeom "
                + "prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic></p:control></mc:Fallback></mc:AlternateContent>";
        byte[] pptx = legacy("", vmlShape("_x0000_s1025", "left:100pt;top:100pt;width:200pt;height:100pt", "rId1"));
        Fixtures.Zip z = Fixtures.edit(pptx);
        z.insertBefore("ppt/slides/slide1.xml", "</p:cSld>", "<p:controls>" + control + "</p:controls>");
        Decks.Converted c = Decks.convert(dir, "pictured.pptx", z.bytes());
        assertEquals(Color.WHITE.getRGB(), c.render(0, 72).getRGB(200, 150));
    }

    @Test
    void legacyShapeBoxesReadTheirStyleInPoints() {
        assertArrayEquals(new float[] {109, 110, 132, 33},
                LegacyShapes.box("position:absolute;left:109pt;top:110pt;width:132pt;height:33pt"));
        assertArrayEquals(new float[] {72, 36, 75, 15},
                LegacyShapes.box("position:absolute;margin-left:1in;margin-top:0.5in;width:100px;height:20px"), 0.001f);
        assertNull(LegacyShapes.box("left:1pt;top:1pt;width:1pt;height:1pt"));
        assertNull(LegacyShapes.box("position:absolute;left:1pt;top:1pt;width:1pt;height:1pt;visibility:hidden"));
    }

    @Test
    void aStackedListLaysOutItsItemsWithoutADrawing() throws IOException {
        Fixtures.Zip z = Fixtures.edit(PptxSmartArtTest.smartArt("", false, "drawing1.xml"));
        z.remove("ppt/diagrams/drawing1.xml");
        z.put("ppt/diagrams/layout1.xml", z.text("ppt/diagrams/layout1.xml").replace("layout/radial1",
                "layout/lProcess2"));
        z.put("ppt/diagrams/data1.xml", "<dgm:dataModel xmlns:dgm=\"" + PptxSmartArtTest.DGM + "\" xmlns:a=\""
                + Decks.A + "\"><dgm:ptLst><dgm:pt modelId=\"{00000000-0000-0000-0000-000000000001}\" type=\"doc\">"
                + "<dgm:prSet/><dgm:spPr/></dgm:pt>" + node(2, "Heading") + node(3, "Child") + "</dgm:ptLst><dgm:cxnLst>"
                + link(1, 2) + link(2, 3) + "</dgm:cxnLst></dgm:dataModel>");
        Decks.Converted c = Decks.convert(dir, "stacked.pptx", z.bytes());
        String text = c.text();
        assertTrue(text.contains("Heading") && text.contains("Child"), text + " " + c.result().warnings());
        assertEquals(SmartArtLayout.Kind.STACKED,
                SmartArtLayout.kind("urn:microsoft.com/office/officeart/2005/8/layout/lProcess2"));
    }

    @Test
    void aTitleWithoutAnIndexTakesTheLayoutsFirstTitle() throws IOException {
        String title = "<p:sp " + Decks.NS + "><p:nvSpPr><p:cNvPr id=\"5\" name=\"T\"/><p:cNvSpPr/><p:nvPr>"
                + "<p:ph type=\"title\"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"914400\" y=\"914400\"/>"
                + "<a:ext cx=\"6400800\" cy=\"1828800\"/></a:xfrm></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p>"
                + "<a:r><a:rPr lang=\"en-US\"/><a:t>Title text</a:t></a:r></a:p></p:txBody></p:sp>";
        Fixtures.Zip z = Fixtures.edit(Decks.slideXml(title));
        String rels = z.text("ppt/slides/_rels/slide1.xml.rels");
        int at = rels.indexOf("slideLayouts/");
        String layout = "ppt/slideLayouts/" + rels.substring(at + 13, rels.indexOf('"', at));
        String xml = z.text(layout).replaceAll("type=\"title\"", "type=\"body\"").replaceAll("type=\"ctrTitle\"",
                "type=\"body\"");
        z.put(layout, xml);
        z.insertBefore(layout, "</p:spTree>", layoutTitle(20, "", "1800") + layoutTitle(21, " idx=\"2\"", "8000"));
        Decks.Converted c = Decks.convert(dir, "titles.pptx", z.bytes());
        List<TextPosition> p = c.positions(0);
        assertTrue(!p.isEmpty(), c.text());
        assertEquals(18, p.get(0).getFontSizeInPt(), 0.5);
    }

    @Test
    void aTableStyleBackgroundFillsUnderTheCells() throws IOException {
        String style = "{11111111-2222-3333-4444-555555555555}";
        String table = "<p:graphicFrame " + Decks.NS + "><p:nvGraphicFramePr><p:cNvPr id=\"4\" name=\"Table\"/>"
                + "<p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off x=\"1270000\" y=\"1270000\"/>"
                + "<a:ext cx=\"2540000\" cy=\"1270000\"/></p:xfrm><a:graphic><a:graphicData "
                + "uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\"><a:tbl><a:tblPr><a:tableStyleId>" + style
                + "</a:tableStyleId></a:tblPr><a:tblGrid><a:gridCol w=\"2540000\"/></a:tblGrid><a:tr h=\"1270000\"><a:tc>"
                + "<a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang=\"en-US\"/></a:p></a:txBody><a:tcPr/></a:tc>"
                + "</a:tr></a:tbl></a:graphicData></a:graphic></p:graphicFrame>";
        Fixtures.Zip z = Fixtures.edit(Decks.slideXml(table));
        z.put("ppt/tableStyles.xml", "<a:tblStyleLst xmlns:a=\"" + Decks.A + "\" def=\"" + style + "\"><a:tblStyle "
                + "styleId=\"" + style + "\" styleName=\"Background\"><a:tblBg><a:fillRef idx=\"1\"><a:srgbClr "
                + "val=\"00FF00\"/></a:fillRef></a:tblBg><a:wholeTbl><a:tcStyle><a:tcBdr/><a:fill><a:noFill/></a:fill>"
                + "</a:tcStyle></a:wholeTbl></a:tblStyle></a:tblStyleLst>");
        Decks.Converted c = Decks.convert(dir, "tablebg.pptx", z.bytes());
        assertEquals(new Color(0, 255, 0).getRGB(), c.render(0, 72).getRGB(200, 150), c.result().warnings().toString());
    }

    @Test
    void aCameraTurnsTheTextBody() throws IOException {
        String body = "<a:bodyPr wrap=\"none\"><a:scene3d><a:camera prst=\"orthographicFront\"><a:rot lat=\"0\" "
                + "lon=\"0\" rev=\"16200000\"/></a:camera><a:lightRig rig=\"threePt\" dir=\"t\"/></a:scene3d></a:bodyPr>";
        byte[] pptx = Decks.slideXml(Decks.textBox(7, 1270000, 1270000, 2540000, 2540000, body,
                "<a:p>" + Decks.run("Turned", "sz=\"1800\"") + "</a:p>"));
        Decks.Converted c = Decks.convert(dir, "turned.pptx", pptx);
        List<TextPosition> p = c.positions(0);
        assertTrue(!p.isEmpty(), c.text());
        float turn = p.get(0).getDir();
        assertTrue(turn == 90 || turn == 270, "direction " + turn);
    }

    private static String node(int id, String text) {
        return "<dgm:pt modelId=\"{00000000-0000-0000-0000-00000000000" + id + "}\"><dgm:prSet/><dgm:spPr/><dgm:t>"
                + "<a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang=\"en-US\"/><a:t>" + text + "</a:t></a:r></a:p>"
                + "</dgm:t></dgm:pt>";
    }

    private static String link(int from, int to) {
        return "<dgm:cxn modelId=\"{10000000-0000-0000-0000-00000000000" + to + "}\" srcId=\"{00000000-0000-0000-0000-"
                + "00000000000" + from + "}\" destId=\"{00000000-0000-0000-0000-00000000000" + to + "}\" srcOrd=\"0\" "
                + "destOrd=\"0\"/>";
    }

    private static String layoutTitle(int id, String idx, String size) {
        return "<p:sp><p:nvSpPr><p:cNvPr id=\"" + id + "\" name=\"Title " + id + "\"/><p:cNvSpPr/><p:nvPr><p:ph "
                + "type=\"title\"" + idx + "/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"914400\" y=\"914400\"/>"
                + "<a:ext cx=\"6400800\" cy=\"1828800\"/></a:xfrm></p:spPr><p:txBody><a:bodyPr/><a:lstStyle><a:lvl1pPr>"
                + "<a:defRPr sz=\"" + size + "\"/></a:lvl1pPr></a:lstStyle><a:p><a:endParaRPr lang=\"en-US\"/></a:p>"
                + "</p:txBody></p:sp>";
    }
}
