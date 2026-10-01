package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.officeconvert.topdf.testing.TestFonts;

class ChartDefaultsTest {

    private static final String C = "xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\""
            + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"";

    @TempDir
    Path dir;

    private static String points(Object... values) {
        StringBuilder b = new StringBuilder("<c:ptCount val=\"" + values.length + "\"/>");
        for (int i = 0; i < values.length; i++) {
            b.append("<c:pt idx=\"").append(i).append("\"><c:v>").append(values[i]).append("</c:v></c:pt>");
        }
        return b.toString();
    }

    private static String scatter(String xVal) {
        return "<c:chartSpace " + C + "><c:chart><c:title><c:overlay val=\"0\"/></c:title><c:plotArea><c:layout/>"
                + "<c:scatterChart><c:scatterStyle val=\"lineMarker\"/><c:ser><c:idx val=\"0\"/><c:order val=\"0\"/>"
                + "<c:tx><c:strRef><c:f>S!$B$1</c:f><c:strCache>" + points("Sales") + "</c:strCache></c:strRef></c:tx>"
                + "<c:spPr><a:ln><a:noFill/></a:ln></c:spPr>" + xVal + "<c:yVal><c:numRef><c:f>S!$B$2:$B$5</c:f>"
                + "<c:numCache><c:formatCode>General</c:formatCode>" + points(8, 3, 1, 1) + "</c:numCache></c:numRef>"
                + "</c:yVal></c:ser><c:axId val=\"1\"/><c:axId val=\"2\"/></c:scatterChart><c:valAx><c:axId val=\"2\"/>"
                + "<c:delete val=\"1\"/><c:axPos val=\"l\"/><c:crossAx val=\"1\"/></c:valAx><c:valAx><c:axId val=\"1\"/>"
                + "<c:delete val=\"0\"/><c:axPos val=\"b\"/><c:crossAx val=\"2\"/></c:valAx></c:plotArea></c:chart>"
                + "<c:txPr><a:bodyPr/><a:lstStyle/><a:p><a:pPr><a:defRPr sz=\"1800\"/></a:pPr><a:endParaRPr/></a:p>"
                + "</c:txPr></c:chartSpace>";
    }

    @Test
    void textXValuesPlotAtOneTwoThreeOnAValueAxis() throws IOException {
        String x = "<c:xVal><c:strRef><c:f>S!$A$2:$A$5</c:f><c:strCache>" + points("Q1", "Q2", "Q3", "Q4")
                + "</c:strCache></c:strRef></c:xVal>";
        String text = render("scatter", scatter(x)).text();
        assertTrue(text.contains("0") && text.contains("5"), text);
        assertTrue(!text.contains("Q1"), text);
    }

    @Test
    void anUnstyledTitleIsBoldAtASizeAboveTheChartText() throws IOException {
        TestFonts.assumeInstalled("Calibri", true, TestFonts.CALIBRI);
        String x = "<c:xVal><c:numRef><c:f>S!$A$2:$A$5</c:f><c:numCache><c:formatCode>General</c:formatCode>"
                + points(1, 2, 3, 4) + "</c:numCache></c:numRef></c:xVal>";
        DocxDoc.Rendered r = render("title", scatter(x));
        List<TextPosition> title = new ArrayList<>();
        try (PDDocument d = r.open()) {
            new PDFTextStripper() {
                @Override
                protected void writeString(String t, List<TextPosition> ps) {
                    if (t.contains("Sales") && title.isEmpty()) {
                        title.addAll(ps);
                    }
                }
            }.getText(d);
        }
        assertTrue(!title.isEmpty());
        TextPosition p = title.get(0);
        assertTrue(p.getFont().getName().contains("Bold"), p.getFont().getName());
        assertTrue(p.getFontSizeInPt() > 20, "size " + p.getFontSizeInPt());
    }

    private DocxDoc.Rendered render(String name, String chart) throws IOException {
        DocxDoc doc = new DocxDoc().chart("chart1.xml", "rIdChart", chart)
                .body("<w:p>" + DocxDoc.chartRun("rIdChart") + "</w:p>");
        return DocxDoc.render(dir, name, doc.bytes());
    }
}
