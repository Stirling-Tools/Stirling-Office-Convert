package stirling.software.officeconvert.topdf.uof;

import java.io.IOException;
import java.util.List;

final class UofPresentation {

    private static final String PML = "application/vnd.openxmlformats-officedocument.presentationml.";

    private static final String RELS = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">";

    private UofPresentation() {}

    static void write(UofParts parts, List<String> slides, long[] size) throws IOException {
        String ns = UofSlides.P_NS;
        StringBuilder pres = new StringBuilder(UofParts.XML);
        pres.append("<p:presentation ").append(ns).append("><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\""
                + " r:id=\"rM\"/></p:sldMasterIdLst><p:sldIdLst>");
        StringBuilder rels = new StringBuilder(UofParts.XML).append(RELS);
        rels.append("<Relationship Id=\"rM\" Type=\"").append(UofParts.REL)
                .append("slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>");
        rels.append("<Relationship Id=\"rTh\" Type=\"").append(UofParts.REL).append("theme\" Target=\"theme/theme1.xml\"/>");
        for (int i = 0; i < slides.size(); i++) {
            pres.append("<p:sldId id=\"").append(256 + i).append("\" r:id=\"rS").append(i + 1).append("\"/>");
            rels.append("<Relationship Id=\"rS").append(i + 1).append("\" Type=\"").append(UofParts.REL)
                    .append("slide\" Target=\"slides/").append(slides.get(i)).append(".xml\"/>");
        }
        pres.append("</p:sldIdLst><p:sldSz cx=\"").append(size[0]).append("\" cy=\"").append(size[1])
                .append("\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>");
        rels.append("</Relationships>");
        parts.add("ppt/presentation.xml", pres.toString());
        parts.add("ppt/_rels/presentation.xml.rels", rels.toString());
        String tree = "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/>"
                + "</p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld>";
        parts.add("ppt/slideMasters/slideMaster1.xml", UofParts.XML + "<p:sldMaster " + ns + ">" + tree
                + "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\""
                + " accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\""
                + " folHlink=\"folHlink\"/><p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rL\"/>"
                + "</p:sldLayoutIdLst></p:sldMaster>");
        parts.add("ppt/slideMasters/_rels/slideMaster1.xml.rels", UofParts.XML + RELS + "<Relationship Id=\"rL\" Type=\""
                + UofParts.REL + "slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/><Relationship Id=\"rT\""
                + " Type=\"" + UofParts.REL + "theme\" Target=\"../theme/theme1.xml\"/></Relationships>");
        parts.add("ppt/slideLayouts/slideLayout1.xml", UofParts.XML + "<p:sldLayout " + ns + " type=\"blank\">" + tree
                + "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>");
        parts.add("ppt/slideLayouts/_rels/slideLayout1.xml.rels", UofParts.XML + RELS + "<Relationship Id=\"rM\" Type=\""
                + UofParts.REL + "slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/></Relationships>");
        parts.add("ppt/theme/theme1.xml", theme());
        StringBuilder types = new StringBuilder(UofParts.XML);
        types.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                .append("<Default Extension=\"png\" ContentType=\"image/png\"/><Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>")
                .append("<Default Extension=\"gif\" ContentType=\"image/gif\"/><Default Extension=\"bmp\" ContentType=\"image/bmp\"/>")
                .append("<Default Extension=\"tiff\" ContentType=\"image/tiff\"/><Default Extension=\"emf\" ContentType=\"image/x-emf\"/>")
                .append("<Default Extension=\"wmf\" ContentType=\"image/x-wmf\"/>")
                .append("<Override PartName=\"/ppt/presentation.xml\" ContentType=\"").append(PML)
                .append("presentation.main+xml\"/>")
                .append("<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"").append(PML)
                .append("slideMaster+xml\"/>")
                .append("<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"").append(PML)
                .append("slideLayout+xml\"/>")
                .append("<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>");
        for (String s : slides) {
            types.append("<Override PartName=\"/ppt/slides/").append(s).append(".xml\" ContentType=\"").append(PML)
                    .append("slide+xml\"/>");
        }
        types.append("</Types>");
        parts.add("[Content_Types].xml", types.toString());
        parts.add("_rels/.rels", UofParts.XML + RELS + "<Relationship Id=\"rId1\" Type=\"" + UofParts.REL
                + "officeDocument\" Target=\"ppt/presentation.xml\"/></Relationships>");
    }

    static String theme() {
        StringBuilder c = new StringBuilder();
        String[][] colours = {{"dk1", "000000"}, {"lt1", "FFFFFF"}, {"dk2", "1F497D"}, {"lt2", "EEECE1"},
                {"accent1", "4F81BD"}, {"accent2", "C0504D"}, {"accent3", "9BBB59"}, {"accent4", "8064A2"},
                {"accent5", "4BACC6"}, {"accent6", "F79646"}, {"hlink", "0000FF"}, {"folHlink", "800080"}};
        for (String[] k : colours) {
            c.append("<a:").append(k[0]).append("><a:srgbClr val=\"").append(k[1]).append("\"/></a:").append(k[0])
                    .append('>');
        }
        String fill = "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>";
        String line = "<a:ln w=\"9525\">" + fill + "</a:ln>";
        return UofParts.XML + "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"UOF\">"
                + "<a:themeElements><a:clrScheme name=\"UOF\">" + c + "</a:clrScheme><a:fontScheme name=\"UOF\">"
                + "<a:majorFont><a:latin typeface=\"Arial\"/><a:ea typeface=\"黑体\"/><a:cs typeface=\"\"/></a:majorFont>"
                + "<a:minorFont><a:latin typeface=\"Arial\"/><a:ea typeface=\"宋体\"/><a:cs typeface=\"\"/></a:minorFont>"
                + "</a:fontScheme><a:fmtScheme name=\"UOF\"><a:fillStyleLst>" + fill + fill + fill + "</a:fillStyleLst>"
                + "<a:lnStyleLst>" + line + line + line + "</a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/>"
                + "</a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/>"
                + "</a:effectStyle></a:effectStyleLst><a:bgFillStyleLst>" + fill + fill + fill + "</a:bgFillStyleLst>"
                + "</a:fmtScheme></a:themeElements></a:theme>";
    }
}
