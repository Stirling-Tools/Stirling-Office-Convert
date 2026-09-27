package stirling.software.officeconvert.pptx;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

final class PptxParts {

    private static final String RELS_NS = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String CT = "application/vnd.openxmlformats-officedocument.presentationml.";

    private PptxParts() {}

    static String presentation(int slides, long cx, long cy) {
        StringBuilder sb = new StringBuilder(Ooxml.HEADER).append("<p:presentation").append(Ooxml.NAMESPACES)
                .append(" saveSubsetFonts=\"1\"><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/>")
                .append("</p:sldMasterIdLst><p:sldIdLst>");
        for (int i = 0; i < slides; i++) {
            sb.append("<p:sldId id=\"").append(256 + i).append("\" r:id=\"rId").append(10 + i).append("\"/>");
        }
        sb.append("</p:sldIdLst><p:sldSz cx=\"").append(cx).append("\" cy=\"").append(cy)
                .append("\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/><p:defaultTextStyle>")
                .append("<a:defPPr><a:defRPr lang=\"en-US\"/></a:defPPr>");
        for (int level = 1; level <= 9; level++) {
            sb.append("<a:lvl").append(level).append("pPr marL=\"").append((level - 1) * 457200L)
                    .append("\" algn=\"l\" defTabSz=\"914400\" rtl=\"0\" eaLnBrk=\"1\" latinLnBrk=\"0\" hangingPunct=\"1\">")
                    .append(defRPr(1800, "mn")).append("</a:lvl").append(level).append("pPr>");
        }
        return sb.append("</p:defaultTextStyle></p:presentation>").toString();
    }

    private static String defRPr(int size, String font) {
        return "<a:defRPr sz=\"" + size + "\" kern=\"1200\"><a:solidFill><a:schemeClr val=\"tx1\"/></a:solidFill>"
                + "<a:latin typeface=\"+" + font + "-lt\"/><a:ea typeface=\"+" + font + "-ea\"/><a:cs typeface=\"+" + font
                + "-cs\"/></a:defRPr>";
    }

    static String presentationRels(int slides) {
        StringBuilder sb = new StringBuilder(Ooxml.HEADER).append("<Relationships xmlns=\"").append(RELS_NS).append("\">");
        rel(sb, "rId1", "slideMaster", "slideMasters/slideMaster1.xml");
        rel(sb, "rId2", "theme", "theme/theme1.xml");
        rel(sb, "rId3", "presProps", "presProps.xml");
        rel(sb, "rId4", "viewProps", "viewProps.xml");
        rel(sb, "rId5", "tableStyles", "tableStyles.xml");
        for (int i = 0; i < slides; i++) {
            rel(sb, "rId" + (10 + i), "slide", "slides/slide" + (i + 1) + ".xml");
        }
        return sb.append("</Relationships>").toString();
    }

    private static void rel(StringBuilder sb, String id, String type, String target) {
        sb.append("<Relationship Id=\"").append(id).append("\" Type=\"").append(Ooxml.REL).append(type)
                .append("\" Target=\"").append(target).append("\"/>");
    }

    private static String group() {
        return "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm>"
                + "<a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/>"
                + "</a:xfrm></p:grpSpPr>";
    }

    private static String placeholder(int id, String name, String ph, long x, long y, long cx, long cy, String anchor,
            String prompt) {
        return "<p:sp><p:nvSpPr><p:cNvPr id=\"" + id + "\" name=\"" + name + "\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/>"
                + "</p:cNvSpPr><p:nvPr>" + ph + "</p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"" + x + "\" y=\"" + y
                + "\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>"
                + "</p:spPr><p:txBody><a:bodyPr vert=\"horz\" lIns=\"91440\" tIns=\"45720\" rIns=\"91440\" bIns=\"45720\""
                + " rtlCol=\"0\" anchor=\"" + anchor + "\"><a:normAutofit/></a:bodyPr><a:lstStyle/><a:p><a:r><a:rPr lang=\"en-US\"/>"
                + "<a:t>" + prompt + "</a:t></a:r></a:p></p:txBody></p:sp>";
    }

    static String master(long cx, long cy) {
        long x = cx / 20;
        long w = cx - 2 * x;
        StringBuilder sb = new StringBuilder(Ooxml.HEADER).append("<p:sldMaster").append(Ooxml.NAMESPACES)
                .append("><p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg><p:spTree>")
                .append(group())
                .append(placeholder(2, "Title Placeholder 1", "<p:ph type=\"title\"/>", x, cy / 25, w, cy * 18 / 100, "ctr",
                        "Click to edit Master title style"))
                .append(placeholder(3, "Text Placeholder 2", "<p:ph type=\"body\" idx=\"1\"/>", x, cy / 4, w, cy * 65 / 100,
                        "t", "Click to edit Master text styles"))
                .append("</p:spTree></p:cSld><p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\"")
                .append(" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\"")
                .append(" hlink=\"hlink\" folHlink=\"folHlink\"/><p:sldLayoutIdLst>")
                .append("<p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/><p:sldLayoutId id=\"2147483650\" r:id=\"rId2\"/>")
                .append("</p:sldLayoutIdLst><p:txStyles><p:titleStyle><a:lvl1pPr algn=\"l\" defTabSz=\"914400\" rtl=\"0\"")
                .append(" eaLnBrk=\"1\" latinLnBrk=\"0\" hangingPunct=\"1\"><a:lnSpc><a:spcPct val=\"90000\"/></a:lnSpc>")
                .append("<a:spcBef><a:spcPct val=\"0\"/></a:spcBef><a:buNone/>").append(defRPr(4400, "mj"))
                .append("</a:lvl1pPr></p:titleStyle><p:bodyStyle>");
        for (int level = 1; level <= 9; level++) {
            long marL = 228600L + (level - 1) * 457200L;
            int size = Math.max(1400, 2800 - (level - 1) * 400);
            sb.append("<a:lvl").append(level).append("pPr marL=\"").append(marL).append("\" indent=\"-228600\" algn=\"l\"")
                    .append(" defTabSz=\"914400\" rtl=\"0\" eaLnBrk=\"1\" latinLnBrk=\"0\" hangingPunct=\"1\"><a:lnSpc>")
                    .append("<a:spcPct val=\"90000\"/></a:lnSpc><a:spcBef><a:spcPts val=\"").append(level == 1 ? 1000 : 500)
                    .append("\"/></a:spcBef><a:buFont typeface=\"Arial\"/><a:buChar char=\"&#8226;\"/>").append(defRPr(size, "mn"))
                    .append("</a:lvl").append(level).append("pPr>");
        }
        sb.append("</p:bodyStyle><p:otherStyle><a:defPPr><a:defRPr lang=\"en-US\"/></a:defPPr>");
        for (int level = 1; level <= 9; level++) {
            sb.append("<a:lvl").append(level).append("pPr marL=\"").append((level - 1) * 457200L)
                    .append("\" algn=\"l\" defTabSz=\"914400\" rtl=\"0\" eaLnBrk=\"1\" latinLnBrk=\"0\" hangingPunct=\"1\">")
                    .append(defRPr(1800, "mn")).append("</a:lvl").append(level).append("pPr>");
        }
        return sb.append("</p:otherStyle></p:txStyles></p:sldMaster>").toString();
    }

    static String masterRels() {
        StringBuilder sb = new StringBuilder(Ooxml.HEADER).append("<Relationships xmlns=\"").append(RELS_NS).append("\">");
        rel(sb, "rId1", "slideLayout", "../slideLayouts/" + SlideXml.TITLE_LAYOUT);
        rel(sb, "rId2", "slideLayout", "../slideLayouts/" + SlideXml.BLANK_LAYOUT);
        rel(sb, "rId3", "theme", "../theme/theme1.xml");
        return sb.append("</Relationships>").toString();
    }

    static String titleLayout() {
        return Ooxml.HEADER + "<p:sldLayout" + Ooxml.NAMESPACES + " type=\"titleOnly\" preserve=\"1\"><p:cSld name=\"Title Only\">"
                + "<p:spTree>" + group() + "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Title 1\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/>"
                + "</p:cNvSpPr><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/>"
                + "<a:p><a:r><a:rPr lang=\"en-US\"/><a:t>Click to edit Master title style</a:t></a:r></a:p></p:txBody></p:sp>"
                + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>";
    }

    static String blankLayout() {
        return Ooxml.HEADER + "<p:sldLayout" + Ooxml.NAMESPACES + " type=\"blank\" preserve=\"1\"><p:cSld name=\"Blank\">"
                + "<p:spTree>" + group() + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>";
    }

    static String layoutRels() {
        StringBuilder sb = new StringBuilder(Ooxml.HEADER).append("<Relationships xmlns=\"").append(RELS_NS).append("\">");
        rel(sb, "rId1", "slideMaster", "../slideMasters/slideMaster1.xml");
        return sb.append("</Relationships>").toString();
    }

    static String theme(String headingFont, String bodyFont) {
        StringBuilder sb = new StringBuilder(Ooxml.HEADER).append("<a:theme xmlns:a=\"").append(Ooxml.NS_A)
                .append("\" name=\"Office Theme\"><a:themeElements><a:clrScheme name=\"Office\">")
                .append("<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1>")
                .append("<a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>");
        String[][] colours = {{"dk2", "44546A"}, {"lt2", "E7E6E6"}, {"accent1", "4472C4"}, {"accent2", "ED7D31"},
            {"accent3", "A5A5A5"}, {"accent4", "FFC000"}, {"accent5", "5B9BD5"}, {"accent6", "70AD47"},
            {"hlink", "0563C1"}, {"folHlink", "954F72"}};
        for (String[] c : colours) {
            sb.append("<a:").append(c[0]).append("><a:srgbClr val=\"").append(c[1]).append("\"/></a:").append(c[0]).append('>');
        }
        sb.append("</a:clrScheme><a:fontScheme name=\"Office\"><a:majorFont><a:latin typeface=\"")
                .append(Ooxml.esc(headingFont)).append("\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>")
                .append("<a:minorFont><a:latin typeface=\"").append(Ooxml.esc(bodyFont))
                .append("\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme><a:fmtScheme name=\"Office\">")
                .append("<a:fillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>")
                .append("<a:solidFill><a:schemeClr val=\"phClr\"><a:tint val=\"50000\"/></a:schemeClr></a:solidFill>")
                .append("<a:solidFill><a:schemeClr val=\"phClr\"><a:shade val=\"80000\"/></a:schemeClr></a:solidFill>")
                .append("</a:fillStyleLst><a:lnStyleLst>");
        for (int w : new int[] {6350, 12700, 19050}) {
            sb.append("<a:ln w=\"").append(w).append("\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\"><a:solidFill>")
                    .append("<a:schemeClr val=\"phClr\"/></a:solidFill><a:prstDash val=\"solid\"/><a:miter lim=\"800000\"/></a:ln>");
        }
        sb.append("</a:lnStyleLst><a:effectStyleLst>");
        for (int i = 0; i < 3; i++) {
            sb.append("<a:effectStyle><a:effectLst/></a:effectStyle>");
        }
        return sb.append("</a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>")
                .append("<a:solidFill><a:schemeClr val=\"phClr\"><a:tint val=\"95000\"/></a:schemeClr></a:solidFill>")
                .append("<a:solidFill><a:schemeClr val=\"phClr\"><a:shade val=\"80000\"/></a:schemeClr></a:solidFill>")
                .append("</a:bgFillStyleLst></a:fmtScheme></a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>")
                .toString();
    }

    static String presProps() {
        return Ooxml.HEADER + "<p:presentationPr" + Ooxml.NAMESPACES + "/>";
    }

    static String viewProps() {
        return Ooxml.HEADER + "<p:viewPr" + Ooxml.NAMESPACES + "><p:normalViewPr><p:restoredLeft sz=\"15620\"/>"
                + "<p:restoredTop sz=\"94660\"/></p:normalViewPr><p:gridSpacing cx=\"76200\" cy=\"76200\"/></p:viewPr>";
    }

    static String tableStyles() {
        return Ooxml.HEADER + "<a:tblStyleLst xmlns:a=\"" + Ooxml.NS_A + "\" def=\"{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}\"/>";
    }

    static String contentTypes(int slides) {
        StringBuilder sb = new StringBuilder(Ooxml.HEADER)
                .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                .append("<Default Extension=\"png\" ContentType=\"image/png\"/>")
                .append("<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>");
        override(sb, "/ppt/presentation.xml", CT + "presentation.main+xml");
        override(sb, "/ppt/slideMasters/slideMaster1.xml", CT + "slideMaster+xml");
        override(sb, "/ppt/slideLayouts/" + SlideXml.TITLE_LAYOUT, CT + "slideLayout+xml");
        override(sb, "/ppt/slideLayouts/" + SlideXml.BLANK_LAYOUT, CT + "slideLayout+xml");
        override(sb, "/ppt/theme/theme1.xml", "application/vnd.openxmlformats-officedocument.theme+xml");
        override(sb, "/ppt/presProps.xml", CT + "presProps+xml");
        override(sb, "/ppt/viewProps.xml", CT + "viewProps+xml");
        override(sb, "/ppt/tableStyles.xml", CT + "tableStyles+xml");
        override(sb, "/docProps/core.xml", "application/vnd.openxmlformats-package.core-properties+xml");
        override(sb, "/docProps/app.xml", "application/vnd.openxmlformats-officedocument.extended-properties+xml");
        for (int i = 1; i <= slides; i++) {
            override(sb, "/ppt/slides/slide" + i + ".xml", CT + "slide+xml");
        }
        return sb.append("</Types>").toString();
    }

    private static void override(StringBuilder sb, String part, String type) {
        sb.append("<Override PartName=\"").append(part).append("\" ContentType=\"").append(type).append("\"/>");
    }

    static String packageRels() {
        return Ooxml.HEADER + "<Relationships xmlns=\"" + RELS_NS + "\">"
                + "<Relationship Id=\"rId1\" Type=\"" + Ooxml.REL + "officeDocument\" Target=\"ppt/presentation.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\""
                + " Target=\"docProps/core.xml\"/>"
                + "<Relationship Id=\"rId3\" Type=\"" + Ooxml.REL + "extended-properties\" Target=\"docProps/app.xml\"/>"
                + "</Relationships>";
    }

    static String app(int slides) {
        return Ooxml.HEADER + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">"
                + "<Application>Stirling-PDF</Application><Slides>" + slides + "</Slides></Properties>";
    }

    static String core(String title, String author) {
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        return Ooxml.HEADER
                + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\""
                + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + (title == null || title.isBlank() ? "" : "<dc:title>" + Ooxml.esc(title) + "</dc:title>")
                + (author == null || author.isBlank() ? "" : "<dc:creator>" + Ooxml.esc(author) + "</dc:creator>")
                + "<dcterms:created xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:created>"
                + "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:modified>"
                + "</cp:coreProperties>";
    }
}
