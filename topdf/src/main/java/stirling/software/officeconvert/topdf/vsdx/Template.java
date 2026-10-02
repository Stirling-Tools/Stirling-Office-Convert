package stirling.software.officeconvert.topdf.vsdx;

import java.io.IOException;

import stirling.software.officeconvert.topdf.xls.Parts;
import stirling.software.officeconvert.topdf.xls.Xml;

final class Template {

    private static final String TREE = "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/>"
            + "<p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld>";

    private Template() {}

    static void master(Parts parts, String ns, String font) throws IOException {
        parts.put("ppt/slideMasters/slideMaster1.xml", Xml.HEAD + "<p:sldMaster" + ns + ">" + TREE
                + "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\""
                + " accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\""
                + " folHlink=\"folHlink\"/><p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/>"
                + "</p:sldLayoutIdLst><p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles>"
                + "</p:sldMaster>");
        parts.put("ppt/slideMasters/_rels/slideMaster1.xml.rels", VsdxPackage.rels(VsdxPackage.rel("rId1",
                "slideLayout", "../slideLayouts/slideLayout1.xml") + VsdxPackage.rel("rId2", "theme",
                        "../theme/theme1.xml")));
        parts.put("ppt/slideLayouts/slideLayout1.xml", Xml.HEAD + "<p:sldLayout" + ns + " type=\"blank\">" + TREE
                + "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>");
        parts.put("ppt/slideLayouts/_rels/slideLayout1.xml.rels", VsdxPackage.rels(VsdxPackage.rel("rId1",
                "slideMaster", "../slideMasters/slideMaster1.xml")));
        parts.put("ppt/theme/theme1.xml", theme(font == null ? "Calibri" : font));
    }

    private static String theme(String font) {
        StringBuilder c = new StringBuilder();
        String[][] colors = {{"dk1", "000000"}, {"lt1", "FFFFFF"}, {"dk2", "44546A"}, {"lt2", "E7E6E6"},
            {"accent1", "5B9BD5"}, {"accent2", "ED7D31"}, {"accent3", "A5A5A5"}, {"accent4", "FFC000"},
            {"accent5", "4472C4"}, {"accent6", "70AD47"}, {"hlink", "0563C1"}, {"folHlink", "954F72"}};
        for (String[] k : colors) {
            c.append("<a:").append(k[0]).append("><a:srgbClr val=\"").append(k[1]).append("\"/></a:").append(k[0])
                    .append('>');
        }
        String fill = "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>";
        String face = "<a:latin typeface=\"" + Xml.attr(font) + "\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/>";
        return Xml.HEAD + "<a:theme xmlns:a=\"" + VsdxPackage.A + "\" name=\"Office\"><a:themeElements>"
                + "<a:clrScheme name=\"Office\">" + c + "</a:clrScheme><a:fontScheme name=\"Office\"><a:majorFont>"
                + face + "</a:majorFont><a:minorFont>" + face + "</a:minorFont></a:fontScheme><a:fmtScheme"
                + " name=\"Office\"><a:fillStyleLst>" + fill + fill + fill + "</a:fillStyleLst><a:lnStyleLst><a:ln"
                + " w=\"6350\">" + fill + "</a:ln><a:ln w=\"12700\">" + fill + "</a:ln><a:ln w=\"19050\">" + fill
                + "</a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle>"
                + "<a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle>"
                + "</a:effectStyleLst><a:bgFillStyleLst>" + fill + fill + fill + "</a:bgFillStyleLst></a:fmtScheme>"
                + "</a:themeElements></a:theme>";
    }
}
