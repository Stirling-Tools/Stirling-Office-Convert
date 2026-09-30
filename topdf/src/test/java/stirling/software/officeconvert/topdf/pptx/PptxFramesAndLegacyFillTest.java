package stirling.software.officeconvert.topdf.pptx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.Fixtures;

class PptxFramesAndLegacyFillTest {

    private static final String OFD = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";

    @TempDir
    Path dir;

    @Test
    void aTableThatNamesNoStyleGetsTheTableGrid() throws IOException {
        String table = "<p:graphicFrame " + Decks.NS + "><p:nvGraphicFramePr><p:cNvPr id=\"4\" name=\"Table\"/>"
                + "<p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off x=\"1270000\" y=\"1270000\"/>"
                + "<a:ext cx=\"2540000\" cy=\"1270000\"/></p:xfrm><a:graphic><a:graphicData "
                + "uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\"><a:tbl><a:tblPr firstRow=\"1\"/>"
                + "<a:tblGrid><a:gridCol w=\"2540000\"/></a:tblGrid><a:tr h=\"1270000\"><a:tc><a:txBody><a:bodyPr/>"
                + "<a:lstStyle/><a:p><a:endParaRPr lang=\"en-US\"/></a:p></a:txBody><a:tcPr/></a:tc></a:tr></a:tbl>"
                + "</a:graphicData></a:graphic></p:graphicFrame>";
        Decks.Converted c = Decks.convert(dir, "grid.pptx", Decks.slideXml(table));
        BufferedImage page = c.render(0, 72);
        assertEquals(Color.WHITE.getRGB(), page.getRGB(200, 150));
        assertNotEquals(Color.WHITE.getRGB(), darkest(page, 100, 150, 104, 150), c.result().warnings().toString());
    }

    @Test
    void aSmartArtDrawingInAScaledGroupKeepsItsOwnSize() throws IOException {
        byte[] pptx = PptxSmartArtTest.smartArt(PptxSmartArtTest.shape(1, "rect", 0, 0, 2743200, 1371600, null), true,
                "drawing1.xml");
        Fixtures.Zip z = Fixtures.edit(pptx);
        String slide = "ppt/slides/slide1.xml";
        String group = "<p:grpSp " + Decks.NS + "><p:nvGrpSpPr><p:cNvPr id=\"9\" name=\"Group\"/><p:cNvGrpSpPr/><p:nvPr/>"
                + "</p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"4572000\" cy=\"3429000\"/>"
                + "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"9144000\" cy=\"6858000\"/></a:xfrm></p:grpSpPr>";
        z.put(slide, z.text(slide).replace("<p:graphicFrame", group + "<p:graphicFrame")
                .replace("</p:graphicFrame>", "</p:graphicFrame></p:grpSp>"));
        Decks.Converted c = Decks.convert(dir, "scaledart.pptx", z.bytes());
        BufferedImage page = c.render(0, 72);
        assertEquals(Color.RED.getRGB(), page.getRGB(200, 80), c.result().warnings().toString());
    }

    @Test
    void aLegacyPreviewShowsItsOwnFillAndOutline() throws IOException {
        String vml = "<v:shape id=\"_x0000_s2001\" style='position:absolute;left:100pt;top:100pt;width:200pt;"
                + "height:100pt'><v:fill color=\"yellow\" on=\"t\" type=\"solid\"/><v:stroke color=\"#0000FF\" "
                + "weight=\"4pt\" on=\"t\"/><v:imagedata o:relid=\"rId1\" o:title=\"\"/></v:shape>";
        Fixtures.Zip z = Fixtures.edit(Decks.slideXml(""));
        z.put("ppt/drawings/vmlDrawing1.vml", "<xml xmlns:v=\"urn:schemas-microsoft-com:vml\" "
                + "xmlns:o=\"urn:schemas-microsoft-com:office:office\">" + vml + "</xml>");
        z.put("ppt/media/clear.png", Fixtures.png(20, 10, new Color(0, 0, 0, 0)));
        z.defaultType("vml", "application/vnd.openxmlformats-officedocument.vmlDrawing");
        z.defaultType("png", "image/png");
        z.relationship("ppt/slides/slide1.xml", "rId90", OFD + "vmlDrawing", "../drawings/vmlDrawing1.vml", false);
        z.relationship("ppt/drawings/vmlDrawing1.vml", "rId1", OFD + "image", "../media/clear.png", false);
        Decks.Converted c = Decks.convert(dir, "vmlfill.pptx", z.bytes());
        BufferedImage page = c.render(0, 72);
        assertEquals(Color.YELLOW.getRGB(), page.getRGB(200, 150), c.result().warnings().toString());
        assertEquals(Color.BLUE.getRGB(), page.getRGB(100, 150));
    }

    private static int darkest(BufferedImage page, int x0, int y0, int x1, int y1) {
        int best = Color.WHITE.getRGB();
        int level = 255 * 3;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int rgb = page.getRGB(x, y);
                int sum = ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
                if (sum < level) {
                    level = sum;
                    best = rgb;
                }
            }
        }
        return best;
    }
}
