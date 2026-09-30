package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocxChartTest {

    private static final String C = "xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\""
            + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
            + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"";

    @TempDir
    Path dir;

    private static String cache(String kind, String format, Object... values) {
        StringBuilder pts = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            pts.append("<c:pt idx=\"").append(i).append("\"><c:v>").append(values[i]).append("</c:v></c:pt>");
        }
        if (kind.equals("str")) {
            return "<c:strRef><c:f>S!$A$1</c:f><c:strCache><c:ptCount val=\"" + values.length + "\"/>" + pts
                    + "</c:strCache></c:strRef>";
        }
        return "<c:numRef><c:f>S!$A$1</c:f><c:numCache><c:formatCode>" + format + "</c:formatCode><c:ptCount val=\""
                + values.length + "\"/>" + pts + "</c:numCache></c:numRef>";
    }

    private static String lineChart(String cat, Object[] values, String catAx, String valAx) {
        return "<c:chartSpace " + C + "><c:chart><c:autoTitleDeleted val=\"1\"/><c:plotArea><c:layout/>"
                + "<c:lineChart><c:grouping val=\"standard\"/><c:ser><c:idx val=\"0\"/><c:order val=\"0\"/><c:tx>"
                + cache("str", null, "Serie") + "</c:tx><c:marker><c:symbol val=\"none\"/></c:marker><c:cat>" + cat
                + "</c:cat><c:val>" + cache("num", "General", values) + "</c:val><c:smooth val=\"1\"/></c:ser>"
                + "<c:axId val=\"1\"/><c:axId val=\"2\"/></c:lineChart>" + catAx + valAx
                + "</c:plotArea><c:legend><c:legendPos val=\"b\"/></c:legend></c:chart></c:chartSpace>";
    }

    private DocxDoc.Rendered render(String name, String chart) throws IOException {
        DocxDoc doc = new DocxDoc().chart("chart1.xml", "rIdChart", chart)
                .body("<w:p>" + DocxDoc.chartRun("rIdChart") + "</w:p>");
        return DocxDoc.render(dir, name, doc.bytes());
    }

    @Test
    void numberFormatsCoverDatesPercentagesAndLiteralText() {
        assertEquals("Jan-18", ChartFormat.format(43101, "mmm\\-yy", false));
        assertEquals("01/02/2018", ChartFormat.format(43132, "dd/mm/yyyy", false));
        assertEquals("25.6%", ChartFormat.format(0.256, "0.0%", false));
        assertEquals("1,234.50 k", ChartFormat.format(1234.5, "#,##0.00 \"k\"", false));
        assertEquals("(5)", ChartFormat.format(-5, "0;(0)", false));
        assertEquals("€1,200", ChartFormat.format(1200, "[$€-407]#,##0", false));
        assertEquals("12:30 PM", ChartFormat.format(0.5208333, "h:mm AM/PM", false));
        assertEquals("2023", ChartFormat.format(44927, "yyyy", false));
        assertEquals("Jan-00", ChartFormat.format(35065, "mmm-yy", true));
        assertEquals("1.5E+03", ChartFormat.format(1500, "0.0E+00", false));
        assertEquals("3", ChartFormat.format(3, "General", false));
    }

    @Test
    void dateCategoriesShowAsDatesAndCrowdedLabelsAreThinnedOut() throws IOException {
        Object[] dates = new Object[48];
        Object[] values = new Object[48];
        for (int i = 0; i < dates.length; i++) {
            dates[i] = 43101 + Math.round(30.44 * i);
            values[i] = 2 + i % 7;
        }
        String catAx = "<c:dateAx><c:axId val=\"1\"/><c:delete val=\"0\"/><c:axPos val=\"b\"/><c:numFmt"
                + " formatCode=\"mmm\\-yy\" sourceLinked=\"1\"/><c:crossAx val=\"2\"/></c:dateAx>";
        String valAx = "<c:valAx><c:axId val=\"2\"/><c:delete val=\"0\"/><c:axPos val=\"l\"/><c:majorGridlines/>"
                + "<c:crossAx val=\"1\"/></c:valAx>";
        String text = render("dates", lineChart(cache("num", "mmm\\-yy", dates), values, catAx, valAx)).text()
                .replaceAll("\\s", "");
        assertTrue(text.contains("Jan-18"), text);
        assertFalse(text.contains("43101"), text);
        assertFalse(text.contains("Feb-18"), "labels too crowded to fit are skipped: " + text);
    }

    @Test
    void theValueAxisPicksARoundUnitThatKeepsItsLabelsApart() throws IOException {
        String catAx = "<c:catAx><c:axId val=\"1\"/><c:delete val=\"0\"/><c:axPos val=\"b\"/><c:crossAx"
                + " val=\"2\"/></c:catAx>";
        String valAx = "<c:valAx><c:axId val=\"2\"/><c:scaling><c:orientation val=\"minMax\"/><c:max val=\"500\"/>"
                + "<c:min val=\"300\"/></c:scaling><c:delete val=\"0\"/><c:axPos val=\"l\"/><c:majorGridlines/>"
                + "<c:crossAx val=\"1\"/></c:valAx>";
        String text = render("unit", lineChart(cache("str", null, "One", "Two", "Three"), new Object[] {320, 410, 470},
                catAx, valAx)).text();
        assertTrue(text.contains("320") && text.contains("480") && text.contains("500"), text);
        assertFalse(text.contains("350"), text);
    }

    @Test
    void automaticGridlinesKeepWellClearOfEachOther() throws IOException {
        String catAx = "<c:catAx><c:axId val=\"1\"/><c:delete val=\"0\"/><c:axPos val=\"b\"/><c:crossAx"
                + " val=\"2\"/></c:catAx>";
        String valAx = "<c:valAx><c:axId val=\"2\"/><c:delete val=\"0\"/><c:axPos val=\"l\"/><c:majorGridlines/>"
                + "<c:crossAx val=\"1\"/></c:valAx>";
        String text = render("gridgap", lineChart(cache("str", null, "One", "Two", "Three"), new Object[] {1, 6, 3},
                catAx, valAx)).text();
        assertTrue(text.contains("7"), text);
        assertFalse(text.contains("6.5") || text.contains("5.5") || text.contains("0.5"), text);
    }

    @Test
    void aSetMaximumOffTheUnitEndsTheAxisBetweenGridlines() throws IOException {
        String catAx = "<c:catAx><c:axId val=\"1\"/><c:delete val=\"0\"/><c:axPos val=\"b\"/><c:crossAx"
                + " val=\"2\"/></c:catAx>";
        String valAx = "<c:valAx><c:axId val=\"2\"/><c:scaling><c:orientation val=\"minMax\"/><c:max val=\"55\"/>"
                + "<c:min val=\"0\"/></c:scaling><c:delete val=\"0\"/><c:axPos val=\"l\"/><c:majorGridlines/>"
                + "<c:majorUnit val=\"10\"/><c:crossAx val=\"1\"/></c:valAx>";
        String text = render("offunit", lineChart(cache("str", null, "One", "Two", "Three"), new Object[] {12, 31, 44},
                catAx, valAx)).text();
        assertTrue(text.contains("50"), text);
        assertFalse(text.contains("60"), text);
    }

    @Test
    void axisLabelsUseTheTypefaceTheirAxisSets() throws IOException {
        String catAx = "<c:catAx><c:axId val=\"1\"/><c:delete val=\"0\"/><c:axPos val=\"b\"/><c:txPr><a:bodyPr/>"
                + "<a:p><a:pPr><a:defRPr sz=\"900\"><a:latin typeface=\"Liberation Serif\"/></a:defRPr></a:pPr>"
                + "</a:p></c:txPr><c:crossAx val=\"2\"/></c:catAx>";
        String valAx = "<c:valAx><c:axId val=\"2\"/><c:delete val=\"0\"/><c:axPos val=\"l\"/><c:majorGridlines/>"
                + "<c:crossAx val=\"1\"/></c:valAx>";
        DocxDoc.Rendered r = render("axisfont", lineChart(cache("str", null, "Alpha", "Beta", "Gamma"),
                new Object[] {1, 6, 3}, catAx, valAx));
        java.util.Map<String, String> fonts = new java.util.HashMap<>();
        try (org.apache.pdfbox.pdmodel.PDDocument d = r.open()) {
            new org.apache.pdfbox.text.PDFTextStripper() {
                @Override
                protected void writeString(String t, java.util.List<org.apache.pdfbox.text.TextPosition> ps) {
                    fonts.put(t.strip(), ps.get(0).getFont().getName());
                }
            }.getText(d);
        }
        assertTrue(fonts.get("Beta").contains("Serif"), fonts.toString());
        assertFalse(fonts.get("Serie").contains("Serif"), fonts.toString());
    }

    @Test
    void categoryLabelsWrapAfterTheHyphenOfALongWord() throws IOException {
        String catAx = "<c:catAx><c:axId val=\"1\"/><c:delete val=\"0\"/><c:axPos val=\"b\"/><c:txPr><a:bodyPr/>"
                + "<a:p><a:pPr><a:defRPr sz=\"800\"/></a:pPr></a:p></c:txPr><c:crossAx val=\"2\"/></c:catAx>";
        String valAx = "<c:valAx><c:axId val=\"2\"/><c:delete val=\"0\"/><c:axPos val=\"l\"/><c:majorGridlines/>"
                + "<c:crossAx val=\"1\"/></c:valAx>";
        Object[] cats = new Object[8];
        Object[] values = new Object[8];
        for (int i = 0; i < cats.length; i++) {
            cats[i] = "Self-Awareness " + i;
            values[i] = i + 1;
        }
        DocxDoc.Rendered r = render("hyphen", lineChart(cache("str", null, cats), values, catAx, valAx));
        java.util.List<String> lines = new java.util.ArrayList<>();
        try (org.apache.pdfbox.pdmodel.PDDocument d = r.open()) {
            new org.apache.pdfbox.text.PDFTextStripper() {
                @Override
                protected void writeString(String t, java.util.List<org.apache.pdfbox.text.TextPosition> ps) {
                    lines.add(ps.get(0).getDir() + " " + t.strip());
                }
            }.getText(d);
        }
        assertTrue(lines.contains("0.0 Self-"), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("0.0 Awareness")), lines.toString());
    }

    @Test
    void axisTitlesAndDataLabelsAreDrawn() throws IOException {
        String chart = "<c:chartSpace " + C + "><c:chart><c:autoTitleDeleted val=\"1\"/><c:plotArea><c:barChart>"
                + "<c:barDir val=\"col\"/><c:grouping val=\"clustered\"/><c:ser><c:idx val=\"0\"/><c:tx>"
                + cache("str", null, "Revenue") + "</c:tx><c:dLbls><c:numFmt formatCode=\"0.0\" sourceLinked=\"0\"/>"
                + "<c:showVal val=\"1\"/></c:dLbls><c:cat>" + cache("str", null, "North", "South") + "</c:cat><c:val>"
                + cache("num", "General", 12.25, 30) + "</c:val></c:ser><c:axId val=\"1\"/><c:axId val=\"2\"/>"
                + "</c:barChart><c:catAx><c:axId val=\"1\"/><c:title><c:tx><c:rich><a:bodyPr/><a:p><a:r><a:t>"
                + "RegionAxisTitle</a:t></a:r></a:p></c:rich></c:tx></c:title><c:crossAx val=\"2\"/></c:catAx><c:valAx>"
                + "<c:axId val=\"2\"/><c:title><c:tx><c:rich><a:bodyPr rot=\"-5400000\"/><a:p><a:r><a:t>AmountTitle"
                + "</a:t></a:r></a:p></c:rich></c:tx></c:title><c:crossAx val=\"1\"/></c:valAx></c:plotArea></c:chart>"
                + "</c:chartSpace>";
        String text = render("titles", chart).text().replaceAll("\\s", "");
        assertTrue(text.contains("RegionAxisTitle") && text.contains("AmountTitle"), text);
        assertTrue(text.contains("12.3") && text.contains("30.0"), text);
    }
    private static String plainChart(Object[] cats, Object[] values) {
        return "<c:chartSpace " + C + "><c:chart><c:plotArea><c:layout/><c:barChart><c:barDir val=\"col\"/>"
                + "<c:grouping val=\"clustered\"/><c:ser><c:idx val=\"0\"/><c:order val=\"0\"/><c:tx>"
                + cache("str", null, "Risk") + "</c:tx><c:cat>" + cache("str", null, cats) + "</c:cat><c:val>"
                + cache("num", "General", values) + "</c:val></c:ser><c:axId val=\"1\"/><c:axId val=\"2\"/>"
                + "</c:barChart><c:catAx><c:axId val=\"1\"/><c:axPos val=\"b\"/><c:crossAx val=\"2\"/></c:catAx>"
                + "<c:valAx><c:axId val=\"2\"/><c:axPos val=\"l\"/><c:majorGridlines/><c:crossAx val=\"1\"/>"
                + "</c:valAx></c:plotArea><c:legend><c:legendPos val=\"r\"/></c:legend></c:chart></c:chartSpace>";
    }

    @Test
    void anUnformattedChartAreaFollowsTheApplication() throws IOException {
        XEl root = XTree.parse(new java.io.ByteArrayInputStream(plainChart(new Object[] {"A", "B"},
                new Object[] {1, 2}).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Chart sheet = ChartReader.read(root, Theme.DEFAULT);
        Chart slide = ChartReader.readSlide(root, Theme.DEFAULT);
        assertEquals(java.awt.Color.BLACK, sheet.textColor());
        assertEquals(java.awt.Color.WHITE, sheet.fill());
        assertTrue(sheet.border() != null);
        assertTrue(slide.fill() == null && slide.border() == null, "a slide chart area stays clear");
    }

    @Test
    void wordFramesAnUnformattedChartInTheAutomaticLineColour() throws IOException {
        DocxDoc.Rendered r = render("plain", plainChart(new Object[] {"North", "South"}, new Object[] {1, 2}));
        try (org.apache.pdfbox.pdmodel.PDDocument d = r.open()) {
            java.awt.image.BufferedImage page = new org.apache.pdfbox.rendering.PDFRenderer(d).renderImage(0, 2);
            int x = 2 * 72 + 2 * 200;
            int top = -1;
            for (int y = 0; y < page.getHeight() && top < 0; y++) {
                if ((page.getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) {
                    top = y;
                }
            }
            int darkest = 255;
            for (int y = top; y < top + 4; y++) {
                darkest = Math.min(darkest, new java.awt.Color(page.getRGB(x, y)).getRed());
            }
            assertTrue(darkest < 170 && darkest > 100, "chart frame " + darkest);
        }
    }

    @Test
    void categoriesPastTheLabelCacheStayBlank() throws IOException {
        DocxDoc.Rendered r = render("blank", plainChart(new Object[] {"North", "South"},
                new Object[] {0.1, 0.2, 0.3}));
        java.util.List<String> lines = new java.util.ArrayList<>();
        try (org.apache.pdfbox.pdmodel.PDDocument d = r.open()) {
            new org.apache.pdfbox.text.PDFTextStripper() {
                @Override
                protected void writeString(String t, java.util.List<org.apache.pdfbox.text.TextPosition> ps) {
                    lines.add(t.strip());
                }
            }.getText(d);
        }
        assertTrue(lines.contains("North") && lines.contains("South"), lines.toString());
        assertFalse(lines.contains("3"), lines.toString());
    }

    @Test
    void aChartShownTwiceIsDrawnOnceAndLooksTheSame() throws IOException {
        String catAx = "<c:catAx><c:axId val=\"1\"/><c:delete val=\"0\"/><c:axPos val=\"b\"/><c:crossAx"
                + " val=\"2\"/></c:catAx>";
        String valAx = "<c:valAx><c:axId val=\"2\"/><c:delete val=\"0\"/><c:axPos val=\"l\"/><c:majorGridlines/>"
                + "<c:crossAx val=\"1\"/></c:valAx>";
        String chart = lineChart(cache("str", null, "Alpha", "Beta", "Gamma"), new Object[] {1, 6, 3}, catAx, valAx);
        String brk = "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>";
        String twice = "<w:p>" + DocxDoc.chartRun("rIdChart") + "</w:p>" + brk + "<w:p>" + DocxDoc.chartRun("rIdChart")
                + "</w:p>";
        String once = "<w:p>" + DocxDoc.chartRun("rIdChart") + "</w:p>" + brk + "<w:p/>";
        DocxDoc.Rendered shared = DocxDoc.render(dir, "twice", new DocxDoc().chart("chart1.xml", "rIdChart", chart)
                .body(twice).bytes());
        DocxDoc.Rendered inline = DocxDoc.render(dir, "once", new DocxDoc().chart("chart1.xml", "rIdChart", chart)
                .body(once).bytes());
        try (org.apache.pdfbox.pdmodel.PDDocument a = shared.open();
                org.apache.pdfbox.pdmodel.PDDocument b = inline.open()) {
            assertEquals(2, a.getNumberOfPages());
            java.util.List<Object> forms = new java.util.ArrayList<>();
            for (org.apache.pdfbox.pdmodel.PDPage page : a.getPages()) {
                for (org.apache.pdfbox.cos.COSName n : page.getResources().getXObjectNames()) {
                    forms.add(page.getResources().getXObject(n).getCOSObject());
                }
            }
            assertEquals(2, forms.size(), "each page places the chart once");
            assertTrue(forms.get(0) == forms.get(1), "both pages place the same form XObject");
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(a).replaceAll("\s", "");
            assertEquals(2, text.split("Beta", -1).length - 1, text);
            java.awt.image.BufferedImage x = new org.apache.pdfbox.rendering.PDFRenderer(a).renderImage(0, 1);
            java.awt.image.BufferedImage y = new org.apache.pdfbox.rendering.PDFRenderer(b).renderImage(0, 1);
            long differ = 0;
            for (int j = 0; j < x.getHeight(); j++) {
                for (int i = 0; i < x.getWidth(); i++) {
                    differ += x.getRGB(i, j) != y.getRGB(i, j) ? 1 : 0;
                }
            }
            assertTrue(differ <= x.getWidth() * x.getHeight() / 10_000, differ + " pixels differ; only rounding may");
        }
    }
}
