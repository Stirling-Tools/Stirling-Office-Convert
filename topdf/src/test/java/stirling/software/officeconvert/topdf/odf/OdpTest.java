package stirling.software.officeconvert.topdf.odf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;

class OdpTest {

    @TempDir
    Path dir;

    private static final String STYLES = OdfFixtures.styles("<style:style style:name=\"Default-outline1\""
            + " style:family=\"presentation\"><style:graphic-properties><text:list-style style:name=\"Default-outline1\">"
            + "<text:list-level-style-bullet text:level=\"1\" text:bullet-char=\"●\"><style:list-level-properties"
            + " text:min-label-width=\"0.6cm\"/></text:list-level-style-bullet><text:list-level-style-bullet"
            + " text:level=\"2\" text:bullet-char=\"–\"><style:list-level-properties text:space-before=\"1.2cm\""
            + " text:min-label-width=\"0.6cm\"/></text:list-level-style-bullet></text:list-style></style:graphic-properties>"
            + "<style:text-properties fo:font-size=\"32pt\"/></style:style><style:style style:name=\"Default-outline2\""
            + " style:family=\"presentation\" style:parent-style-name=\"Default-outline1\"><style:text-properties"
            + " fo:font-size=\"28pt\"/></style:style>",
            "<style:page-layout style:name=\"PM1\"><style:page-layout-properties fo:page-width=\"28cm\""
            + " fo:page-height=\"15.75cm\"/></style:page-layout><style:style style:name=\"Mdp1\""
            + " style:family=\"drawing-page\"><style:drawing-page-properties draw:fill=\"solid\""
            + " draw:fill-color=\"#003366\"/></style:style>",
            "<style:master-page style:name=\"Default\" style:page-layout-name=\"PM1\" draw:style-name=\"Mdp1\">"
            + "<draw:frame presentation:class=\"title\" svg:x=\"1cm\" svg:y=\"1cm\" svg:width=\"20cm\" svg:height=\"2cm\">"
            + "<draw:text-box/></draw:frame><draw:frame presentation:class=\"page-number\" svg:x=\"24cm\" svg:y=\"14cm\""
            + " svg:width=\"3cm\" svg:height=\"1cm\"><draw:text-box><text:p><text:page-number>&lt;number&gt;"
            + "</text:page-number></text:p></draw:text-box></draw:frame><draw:custom-shape svg:x=\"0cm\" svg:y=\"0cm\""
            + " svg:width=\"1cm\" svg:height=\"1cm\"><draw:enhanced-geometry svg:viewBox=\"0 0 21600 21600\""
            + " draw:type=\"rectangle\"/></draw:custom-shape></style:master-page>");

    private Path odp(String automatic, String pages) throws IOException {
        return OdfFixtures.write(dir, "deck.odp", OdfFixtures.odf(OdfFixtures.PRESENTATION,
                OdfFixtures.content(automatic, "<office:presentation>" + pages + "</office:presentation>"), STYLES));
    }

    private String pdfText(Path in) throws IOException {
        Path pdf = dir.resolve("out.pdf");
        OfficeToPdf.convert(in, pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void slidesCarryTheirTextOutlinesAndMasterBackground() throws IOException {
        String auto = "<style:style style:name=\"pr1\" style:family=\"presentation\""
                + " style:parent-style-name=\"Default-outline1\"/><style:style style:name=\"dp1\""
                + " style:family=\"drawing-page\"><style:drawing-page-properties presentation:display-page-number=\"true\"/>"
                + "</style:style>";
        String page = "<draw:page draw:name=\"One\" draw:master-page-name=\"Default\" draw:style-name=\"dp1\">"
                + "<draw:frame presentation:class=\"title\" svg:x=\"1cm\" svg:y=\"1cm\" svg:width=\"20cm\""
                + " svg:height=\"2cm\"><draw:text-box><text:p>Quarterly review</text:p></draw:text-box></draw:frame>"
                + "<draw:frame presentation:class=\"outline\" presentation:style-name=\"pr1\" svg:x=\"1cm\" svg:y=\"4cm\""
                + " svg:width=\"20cm\" svg:height=\"8cm\"><draw:text-box><text:list><text:list-item><text:p>Growth</text:p>"
                + "<text:list><text:list-item><text:p>Europe</text:p></text:list-item></text:list></text:list-item>"
                + "</text:list></draw:text-box></draw:frame><draw:frame presentation:class=\"subtitle\""
                + " presentation:placeholder=\"true\" svg:x=\"1cm\" svg:y=\"13cm\" svg:width=\"5cm\" svg:height=\"1cm\">"
                + "<draw:text-box/></draw:frame></draw:page>";
        Path p = odp(auto, page);
        Map<String, String> parts = OdfFixtures.rewrite(p);
        String pres = parts.get("ppt/presentation.xml");
        assertTrue(pres.contains("<p:sldSz cx=\"10080000\" cy=\"5670000\"/>"), pres);
        String slide = parts.get("ppt/slides/slide1.xml");
        assertTrue(slide.contains("<p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"003366\"/>"), slide);
        assertTrue(slide.contains("<a:buChar char=\"●\"/>") && slide.contains("<a:buChar char=\"–\"/>"), slide);
        assertTrue(slide.contains("sz=\"3200\"") && slide.contains("sz=\"2800\""), slide);
        assertTrue(slide.contains("marL=\"216000\" indent=\"-216000\""), slide);
        assertTrue(slide.contains("type=\"slidenum\""), slide);
        assertEquals(1, slide.split("Quarterly review").length - 1, slide);
        String text = pdfText(p);
        assertTrue(text.contains("Quarterly review") && text.contains("Europe") && text.contains("1"), text);
    }

    @Test
    void customShapesAreDrawnFromTheirEnhancedPath() throws IOException {
        String page = "<draw:page draw:name=\"S\" draw:master-page-name=\"Default\"><draw:custom-shape svg:x=\"2cm\""
                + " svg:y=\"2cm\" svg:width=\"6cm\" svg:height=\"3cm\"><text:p>Inside</text:p><draw:enhanced-geometry"
                + " svg:viewBox=\"0 0 0 0\" draw:type=\"ooxml-roundRect\" draw:modifiers=\"16667\""
                + " draw:enhanced-path=\"M 0 0 Z N\" xmlns:drawooo=\"http://openoffice.org/2010/draw\""
                + " drawooo:enhanced-path=\"M 0 ?f2 G ?f2 ?f2 ?f12 ?f13 L ?f3 0 Z N\"><draw:equation draw:name=\"f1\""
                + " draw:formula=\"min(logwidth,logheight)\"/><draw:equation draw:name=\"f2\""
                + " draw:formula=\"?f1 *$0 /100000\"/><draw:equation draw:name=\"f3\" draw:formula=\"logwidth-?f2 \"/>"
                + "<draw:equation draw:name=\"f12\" draw:formula=\"(10800000)/60000.0\"/><draw:equation"
                + " draw:name=\"f13\" draw:formula=\"(5400000)/60000.0\"/></draw:enhanced-geometry></draw:custom-shape>"
                + "</draw:page>";
        String slide = OdfFixtures.rewrite(odp("", page)).get("ppt/slides/slide1.xml");
        assertTrue(slide.contains("<a:moveTo><a:pt x=\"0\" y=\"8334\"/></a:moveTo><a:cubicBezTo><a:pt x=\"0\""), slide);
        assertTrue(slide.contains("<a:pt x=\"8333\" y=\"0\"/></a:cubicBezTo>"), slide);
        assertTrue(slide.contains("Inside"), slide);
    }

    @Test
    void hiddenSlidesAreNotPrinted() throws IOException {
        String auto = "<style:style style:name=\"dp2\" style:family=\"drawing-page\"><style:drawing-page-properties"
                + " presentation:visibility=\"hidden\"/></style:style>";
        String pages = "<draw:page draw:name=\"A\" draw:master-page-name=\"Default\"><draw:frame svg:x=\"1cm\""
                + " svg:y=\"1cm\" svg:width=\"10cm\" svg:height=\"2cm\"><draw:text-box><text:p>Shown slide</text:p>"
                + "</draw:text-box></draw:frame></draw:page><draw:page draw:name=\"B\" draw:style-name=\"dp2\""
                + " draw:master-page-name=\"Default\"><draw:frame svg:x=\"1cm\" svg:y=\"1cm\" svg:width=\"10cm\""
                + " svg:height=\"2cm\"><draw:text-box><text:p>Secret slide</text:p></draw:text-box></draw:frame></draw:page>";
        String text = pdfText(odp(auto, pages));
        assertTrue(text.contains("Shown slide"), text);
        assertFalse(text.contains("Secret slide"), text);
    }

    @Test
    void slideTablesKeepCellFillsAndSpans() throws IOException {
        String auto = "<style:style style:name=\"ce1\" style:family=\"table-cell\"><loext:graphic-properties"
                + " draw:fill=\"solid\" draw:fill-color=\"#156082\"/></style:style>";
        String page = "<draw:page draw:name=\"T\" draw:master-page-name=\"Default\"><draw:frame svg:x=\"1cm\" svg:y=\"1cm\""
                + " svg:width=\"10cm\" svg:height=\"4cm\"><table:table><table:table-column"
                + " table:number-columns-repeated=\"2\"/><table:table-row><table:table-cell table:style-name=\"ce1\""
                + " table:number-columns-spanned=\"2\"><text:p>Head</text:p></table:table-cell><table:covered-table-cell/>"
                + "</table:table-row><table:table-row><table:table-cell><text:p>a</text:p></table:table-cell>"
                + "<table:table-cell><text:p>b</text:p></table:table-cell></table:table-row></table:table></draw:frame>"
                + "</draw:page>";
        String slide = OdfFixtures.rewrite(odp(auto, page)).get("ppt/slides/slide1.xml");
        assertTrue(slide.contains("<a:tc gridSpan=\"2\">") && slide.contains("hMerge=\"1\""), slide);
        assertTrue(slide.contains("<a:srgbClr val=\"156082\"/>"), slide);
    }

    @Test
    void shrinkToFitTextGetsTheScaleItNeeds() throws IOException {
        String auto = "<style:style style:name=\"gr1\" style:family=\"graphic\"><style:graphic-properties"
                + " style:shrink-to-fit=\"true\"/><style:text-properties fo:font-size=\"40pt\"/></style:style>";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            text.append("<text:p>A line of text that needs room number ").append(i).append("</text:p>");
        }
        String page = "<draw:page draw:name=\"S\" draw:master-page-name=\"Default\"><draw:frame draw:style-name=\"gr1\""
                + " svg:x=\"1cm\" svg:y=\"1cm\" svg:width=\"20cm\" svg:height=\"8cm\"><draw:text-box>" + text
                + "</draw:text-box></draw:frame></draw:page>";
        Path p = odp(auto, page);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        OdfPackage.write(p, out, stirling.software.officeconvert.topdf.font.FontLibrary.system());
        String slide = new String(out.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1);
        Map<String, String> parts = OdfFixtures.rewrite(p);
        assertTrue(parts.get("ppt/slides/slide1.xml").contains("<a:normAutofit/>"));
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(out.toByteArray()))) {
            for (java.util.zip.ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.getName().equals("ppt/slides/slide1.xml")) {
                    slide = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        assertTrue(slide.contains("<a:normAutofit fontScale=\""), slide);
    }

    @Test
    void aVerticallyMirroredShapeTurnsTheOtherWay() throws IOException {
        String page = "<draw:page draw:name=\"S\" draw:master-page-name=\"Default\"><draw:custom-shape svg:width=\"5cm\""
                + " svg:height=\"2cm\" draw:transform=\"rotate (-0.785398163397449) translate (12cm 12cm)\">"
                + "<draw:enhanced-geometry svg:viewBox=\"0 0 21600 21600\" draw:type=\"right-arrow\""
                + " draw:mirror-vertical=\"true\" draw:enhanced-path=\"M 0 0 L 21600 10800 0 21600 Z N\"/>"
                + "</draw:custom-shape></draw:page>";
        String slide = OdfFixtures.rewrite(odp("", page)).get("ppt/slides/slide1.xml");
        assertTrue(slide.contains("rot=\"18900000\" flipV=\"1\""), slide);
    }
}
