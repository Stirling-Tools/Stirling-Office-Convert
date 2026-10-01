package stirling.software.officeconvert.topdf.odf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.font.FontLibrary;

/** An OpenDocument presentation rewritten as the PresentationML package the PPTX renderer draws. Every slide carries
 * its own shapes, with the master page's background objects drawn in, so no placeholder inheritance is needed. */
final class OdpWriter {

    static final int MAX_SLIDES = 5000;

    private static final String P = Xml.P;

    final OdfDocument doc;

    final Styles styles;

    final PackageOut out;

    final Map<String, String> footers = new HashMap<>();

    final Map<String, String> dates = new HashMap<>();

    final Map<String, String> headers = new HashMap<>();

    boolean externalSkipped;

    final FontLibrary fonts;

    OdpWriter(OdfDocument doc, PackageOut out, FontLibrary fonts) {
        this.doc = doc;
        this.fonts = fonts;
        this.out = out;
        this.styles = new Styles(doc);
    }

    List<String> write() throws IOException {
        Element body = Dom.kid(Dom.kid(doc.content(), Ns.OFFICE, "body"), Ns.OFFICE, "presentation");
        for (Element d : Dom.kids(body, Ns.PRESENTATION, "footer-decl")) {
            footers.put(Dom.attr(d, Ns.PRESENTATION, "name", ""), d.getTextContent());
        }
        for (Element d : Dom.kids(body, Ns.PRESENTATION, "header-decl")) {
            headers.put(Dom.attr(d, Ns.PRESENTATION, "name", ""), d.getTextContent());
        }
        for (Element d : Dom.kids(body, Ns.PRESENTATION, "date-time-decl")) {
            dates.put(Dom.attr(d, Ns.PRESENTATION, "name", ""), d.getTextContent());
        }
        List<Element> pages = Dom.kids(body, Ns.DRAW, "page");
        double[] size = slideSize(pages);
        long cx = Math.max(914_400, Math.min(51_206_400, Length.emu(size[0])));
        long cy = Math.max(914_400, Math.min(51_206_400, Length.emu(size[1])));
        Rels presRels = new Rels();
        String masterId = presRels.add("slideMaster", "slideMasters/slideMaster1.xml");
        StringBuilder ids = new StringBuilder();
        int n = 0;
        List<String> warnings = new ArrayList<>();
        for (Element page : pages) {
            if (n >= MAX_SLIDES) {
                warnings.add("Only the first " + MAX_SLIDES + " slides were converted");
                break;
            }
            n++;
            String part = "ppt/slides/slide" + n + ".xml";
            new SlideWriter(this, page, n, new Part(part)).write();
            String rid = presRels.add("slide", "slides/slide" + n + ".xml");
            ids.append("<p:sldId id=\"").append(255 + n).append("\" r:id=\"").append(rid).append("\"/>");
        }
        presRels.add("theme", "theme/theme1.xml");
        StringBuilder pres = new StringBuilder(Xml.HEAD).append("<p:presentation xmlns:a=\"").append(Xml.A)
                .append("\" xmlns:r=\"").append(Xml.R).append("\" xmlns:p=\"").append(P).append("\">")
                .append("<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"").append(masterId)
                .append("\"/></p:sldMasterIdLst>");
        if (n > 0) {
            pres.append("<p:sldIdLst>").append(ids).append("</p:sldIdLst>");
        }
        pres.append("<p:sldSz cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/><p:notesSz cx=\"6858000\"")
                .append(" cy=\"9144000\"/><p:defaultTextStyle><a:defPPr><a:defRPr lang=\"en-US\"/></a:defPPr>")
                .append("</p:defaultTextStyle></p:presentation>");
        out.xml("ppt/presentation.xml", Xml.CT + "presentationml.presentation.main+xml", pres);
        out.xml("ppt/_rels/presentation.xml.rels", null, presRels.xml());
        masterParts();
        out.xml("_rels/.rels", null, Xml.HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/"
                + "relationships\"><Relationship Id=\"rId1\" Type=\"" + Xml.REL_TYPE + "officeDocument\" Target=\""
                + "ppt/presentation.xml\"/></Relationships>");
        out.finish();
        if (externalSkipped) {
            warnings.add("Skipped active content: linked files and pictures (not fetched)");
        }
        return warnings;
    }

    private double[] slideSize(List<Element> pages) {
        Element master = pages.isEmpty() ? styles.firstMaster()
                : styles.master(Dom.attr(pages.get(0), Ns.DRAW, "master-page-name"));
        if (master == null) {
            master = styles.firstMaster();
        }
        Element layout = styles.pageLayout(Dom.attr(master, Ns.STYLE, "page-layout-name"));
        Props p = new Props();
        p.merge(Dom.kid(layout, Ns.STYLE, "page-layout-properties"));
        return new double[] {p.pt("fo:page-width", 720), p.pt("fo:page-height", 540)};
    }

    private void masterParts() throws IOException {
        String spTree = "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/>"
                + "</p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld>";
        out.xml("ppt/slideMasters/slideMaster1.xml", Xml.CT + "presentationml.slideMaster+xml", Xml.HEAD
                + "<p:sldMaster xmlns:a=\"" + Xml.A + "\" xmlns:r=\"" + Xml.R + "\" xmlns:p=\"" + P + "\">" + spTree
                + "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\""
                + " accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\""
                + " folHlink=\"folHlink\"/><p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/>"
                + "</p:sldLayoutIdLst><p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles>"
                + "</p:sldMaster>");
        Rels mr = new Rels();
        mr.add("slideLayout", "../slideLayouts/slideLayout1.xml");
        mr.add("theme", "../theme/theme1.xml");
        out.xml("ppt/slideMasters/_rels/slideMaster1.xml.rels", null, mr.xml());
        out.xml("ppt/slideLayouts/slideLayout1.xml", Xml.CT + "presentationml.slideLayout+xml", Xml.HEAD
                + "<p:sldLayout xmlns:a=\"" + Xml.A + "\" xmlns:r=\"" + Xml.R + "\" xmlns:p=\"" + P + "\" type=\"blank\">"
                + spTree + "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>");
        Rels lr = new Rels();
        lr.add("slideMaster", "../slideMasters/slideMaster1.xml");
        out.xml("ppt/slideLayouts/_rels/slideLayout1.xml.rels", null, lr.xml());
        out.xml("ppt/theme/theme1.xml", Xml.CT + "theme+xml", theme());
    }

    private static String theme() {
        StringBuilder c = new StringBuilder();
        String[][] colors = {{"dk1", "000000"}, {"lt1", "FFFFFF"}, {"dk2", "44546A"}, {"lt2", "E7E6E6"},
            {"accent1", "4472C4"}, {"accent2", "ED7D31"}, {"accent3", "A5A5A5"}, {"accent4", "FFC000"},
            {"accent5", "5B9BD5"}, {"accent6", "70AD47"}, {"hlink", "0563C1"}, {"folHlink", "954F72"}};
        for (String[] k : colors) {
            c.append("<a:").append(k[0]).append("><a:srgbClr val=\"").append(k[1]).append("\"/></a:").append(k[0])
                    .append('>');
        }
        String fill = "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>";
        return Xml.HEAD + "<a:theme xmlns:a=\"" + Xml.A + "\" name=\"Office\"><a:themeElements><a:clrScheme name=\"Office\">"
                + c + "</a:clrScheme><a:fontScheme name=\"Office\"><a:majorFont><a:latin typeface=\"Liberation Sans\"/>"
                + "<a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Liberation Sans\"/>"
                + "<a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme><a:fmtScheme name=\"Office\">"
                + "<a:fillStyleLst>" + fill + fill + fill + "</a:fillStyleLst><a:lnStyleLst><a:ln w=\"6350\">" + fill
                + "</a:ln><a:ln w=\"12700\">" + fill + "</a:ln><a:ln w=\"19050\">" + fill + "</a:ln></a:lnStyleLst>"
                + "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/>"
                + "</a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst>"
                + fill + fill + fill + "</a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>";
    }
}
