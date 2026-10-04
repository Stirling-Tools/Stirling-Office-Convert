package stirling.software.officeconvert.topdf.pptx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.xslf.usermodel.SlideLayout;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFSlideLayout;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.ChartXml;
import stirling.software.officeconvert.topdf.testing.Fixtures;

class PptxContentTest {

    @TempDir
    Path dir;

    @Test
    void chartsAreDrawnFromTheirCachedValues() throws IOException {
        Fixtures.Zip z = Fixtures.edit(Decks.deck(ppt -> ppt.createSlide()));
        z.put("ppt/charts/chart1.xml", ChartXml.bar("ChartTitleXYZ"));
        z.override("/ppt/charts/chart1.xml", "application/vnd.openxmlformats-officedocument.drawingml.chart+xml");
        z.relationship("/ppt/slides/slide1.xml", "rIdChart", Fixtures.REL + "chart", "../charts/chart1.xml", false);
        z.insertBefore("ppt/slides/slide1.xml", "</p:spTree>", "<p:graphicFrame " + Decks.NS + "><p:nvGraphicFramePr>"
                + "<p:cNvPr id=\"9\" name=\"Chart\"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off"
                + " x=\"635000\" y=\"635000\"/><a:ext cx=\"6350000\" cy=\"4064000\"/></p:xfrm><a:graphic><a:graphicData"
                + " uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"><c:chart"
                + " xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" r:id=\"rIdChart\"/>"
                + "</a:graphicData></a:graphic></p:graphicFrame>");
        String text = Decks.convert(dir, "chart.pptx", z.bytes()).text();
        assertTrue(text.contains("ChartTitleXYZ") && text.contains("CatAlpha") && text.contains("CatBeta"), text);
    }

    @Test
    void hiddenShapesAreLeftOut() throws IOException {
        String visible = Decks.textBox(5, 635000, 635000, 3000000, 500000, "<a:bodyPr/>",
                "<a:p>" + Decks.run("VisibleShape", "") + "</a:p>");
        String hidden = Decks.textBox(6, 635000, 1635000, 3000000, 500000, "<a:bodyPr/>",
                "<a:p>" + Decks.run("HiddenShape", "") + "</a:p>").replace("name=\"Box 6\"", "name=\"Box 6\" hidden=\"1\"");
        String text = Decks.convert(dir, "hidden.pptx", Decks.slideXml(visible + hidden)).text();
        assertTrue(text.contains("VisibleShape"), text);
        assertFalse(text.contains("HiddenShape"), text);
    }

    @Test
    void placeholdersInheritVerticalTextFromTheirLayout() throws IOException {
        byte[] pptx = Decks.deck(ppt -> {
            XSLFSlideLayout layout = ppt.getSlideMasters().get(0).getLayout(SlideLayout.VERT_TX);
            XSLFSlide slide = ppt.createSlide(layout);
            for (XSLFTextShape t : slide.getPlaceholders()) {
                if (t.getPlaceholderDetails().getPlaceholder() == org.apache.poi.sl.usermodel.Placeholder.BODY) {
                    t.setText("Vertical body text");
                } else {
                    t.setText("");
                }
            }
        });
        Fixtures.Zip z = Fixtures.edit(pptx);
        String slide = z.text("ppt/slides/slide1.xml").replaceAll(" vert=\"[A-Za-z0-9]+\"", "");
        z.put("ppt/slides/slide1.xml", slide);
        assertTrue(z.text("ppt/slideLayouts/slideLayout" + layoutIndex(z) + ".xml").contains("vert=\""));
        Decks.Converted c = Decks.convert(dir, "vert.pptx", z.bytes());
        List<TextPosition> pos = c.positions(0);
        boolean vertical = pos.stream().filter(p -> p.getUnicode().equals("V")).anyMatch(p -> p.getDir() != 0);
        assertTrue(vertical, "the body placeholder's text is horizontal");
    }

    private static int layoutIndex(Fixtures.Zip z) {
        String rels = z.text("ppt/slides/_rels/slide1.xml.rels");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("slideLayout(\\d+)\\.xml").matcher(rels);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }
}
