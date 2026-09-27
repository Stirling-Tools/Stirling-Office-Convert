package stirling.software.officeconvert.odt;

import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.model.Section;

final class OdtPages {

    record Running(String header, String headerLeft, String footer, String footerLeft, boolean titlePage) {

        boolean hasHeader() {
            return header != null || headerLeft != null;
        }

        boolean hasFooter() {
            return footer != null || footerLeft != null;
        }
    }

    private record Master(String name, Section page, boolean first) {}

    private final List<Master> masters = new ArrayList<>();

    String master(Section s) {
        if (masters.isEmpty()) {
            masters.add(new Master("Standard", s, true));
            return "Standard";
        }
        for (Master m : masters) {
            if (!m.first() && sameGeometry(m.page(), s)) {
                return m.name();
            }
        }
        String name = "Page" + (masters.size() + 1);
        masters.add(new Master(name, s, false));
        return name;
    }

    static boolean sameGeometry(Section a, Section b) {
        return close(a.pageWidth, b.pageWidth) && close(a.pageHeight, b.pageHeight) && close(a.marginTop, b.marginTop)
                && close(a.marginBottom, b.marginBottom) && close(a.marginLeft, b.marginLeft)
                && close(a.marginRight, b.marginRight) && close(a.headerDistance, b.headerDistance)
                && close(a.footerDistance, b.footerDistance);
    }

    private static boolean close(float a, float b) {
        return Math.abs(a - b) < 0.05f;
    }

    void write(StringBuilder automatic, StringBuilder masterStyles, Running running) {
        if (masters.isEmpty()) {
            Section s = new Section();
            s.pageWidth = 612;
            s.pageHeight = 792;
            s.marginTop = s.marginBottom = s.marginLeft = s.marginRight = 72;
            s.headerDistance = s.footerDistance = 36;
            master(s);
        }
        for (int i = 0; i < masters.size(); i++) {
            Section s = masters.get(i).page();
            boolean header = running.hasHeader();
            boolean footer = running.hasFooter();
            float headerTop = Math.max(0, Math.min(s.headerDistance, s.marginTop));
            float footerBottom = Math.max(0, Math.min(s.footerDistance, s.marginBottom));
            automatic.append("<style:page-layout style:name=\"pm").append(i + 1).append("\"><style:page-layout-properties")
                    .append(" fo:page-width=\"").append(OdtXml.pt(s.pageWidth)).append("\" fo:page-height=\"")
                    .append(OdtXml.pt(s.pageHeight)).append("\" style:print-orientation=\"")
                    .append(s.pageWidth > s.pageHeight ? "landscape" : "portrait").append("\" fo:margin-top=\"")
                    .append(OdtXml.pt(header ? headerTop : s.marginTop)).append("\" fo:margin-bottom=\"")
                    .append(OdtXml.pt(footer ? footerBottom : s.marginBottom)).append("\" fo:margin-left=\"")
                    .append(OdtXml.pt(s.marginLeft)).append("\" fo:margin-right=\"").append(OdtXml.pt(s.marginRight))
                    .append("\" style:writing-mode=\"lr-tb\"/>");
            if (header) {
                automatic.append("<style:header-style><style:header-footer-properties fo:min-height=\"")
                        .append(OdtXml.pt(Math.max(0, s.marginTop - headerTop))).append("\" fo:margin-bottom=\"0pt\"")
                        .append(" style:dynamic-spacing=\"true\"/></style:header-style>");
            } else {
                automatic.append("<style:header-style/>");
            }
            if (footer) {
                automatic.append("<style:footer-style><style:header-footer-properties fo:min-height=\"")
                        .append(OdtXml.pt(Math.max(0, s.marginBottom - footerBottom))).append("\" fo:margin-top=\"0pt\"")
                        .append(" style:dynamic-spacing=\"true\"/></style:footer-style>");
            } else {
                automatic.append("<style:footer-style/>");
            }
            automatic.append("</style:page-layout>");

            Master m = masters.get(i);
            masterStyles.append("<style:master-page style:name=\"").append(m.name()).append("\" style:page-layout-name=\"pm")
                    .append(i + 1).append("\">");
            boolean first = m.first() && running.titlePage();
            part(masterStyles, "header", running.header());
            part(masterStyles, "header-left", running.headerLeft());
            if (first && header) {
                masterStyles.append("<style:header-first><text:p text:style-name=\"Standard\"/></style:header-first>");
            }
            part(masterStyles, "footer", running.footer());
            part(masterStyles, "footer-left", running.footerLeft());
            if (first && footer) {
                masterStyles.append("<style:footer-first><text:p text:style-name=\"Standard\"/></style:footer-first>");
            }
            masterStyles.append("</style:master-page>");
        }
    }

    private static void part(StringBuilder sb, String kind, String content) {
        if (content != null) {
            sb.append("<style:").append(kind).append('>').append(content).append("</style:").append(kind).append('>');
        }
    }
}
