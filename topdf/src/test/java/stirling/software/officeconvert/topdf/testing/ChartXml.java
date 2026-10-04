package stirling.software.officeconvert.topdf.testing;

public final class ChartXml {

    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";

    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private ChartXml() {}

    public static String bar(String title) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><c:chartSpace"
                + " xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" xmlns:a=\"" + A + "\""
                + " xmlns:r=\"" + R + "\"><c:chart><c:title><c:tx><c:rich><a:bodyPr/><a:p><a:r><a:t>" + title
                + "</a:t></a:r></a:p></c:rich></c:tx></c:title><c:plotArea><c:barChart><c:barDir val=\"col\"/>"
                + "<c:grouping val=\"clustered\"/><c:ser><c:idx val=\"0\"/><c:tx><c:strRef><c:f>Sheet1!$B$1</c:f>"
                + "<c:strCache><c:ptCount val=\"1\"/><c:pt idx=\"0\"><c:v>Series</c:v></c:pt></c:strCache></c:strRef>"
                + "</c:tx><c:cat><c:strRef><c:f>Sheet1!$A$2:$A$3</c:f><c:strCache><c:ptCount val=\"2\"/><c:pt"
                + " idx=\"0\"><c:v>CatAlpha</c:v></c:pt><c:pt idx=\"1\"><c:v>CatBeta</c:v></c:pt></c:strCache>"
                + "</c:strRef></c:cat><c:val><c:numRef><c:f>Sheet1!$B$2:$B$3</c:f><c:numCache><c:formatCode>General"
                + "</c:formatCode><c:ptCount val=\"2\"/><c:pt idx=\"0\"><c:v>10</c:v></c:pt><c:pt idx=\"1\"><c:v>20"
                + "</c:v></c:pt></c:numCache></c:numRef></c:val></c:ser><c:axId val=\"1\"/><c:axId val=\"2\"/>"
                + "</c:barChart><c:catAx><c:axId val=\"1\"/><c:crossAx val=\"2\"/></c:catAx><c:valAx><c:axId"
                + " val=\"2\"/><c:crossAx val=\"1\"/></c:valAx></c:plotArea></c:chart></c:chartSpace>";
    }
}
