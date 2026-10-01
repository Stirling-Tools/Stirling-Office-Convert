package stirling.software.officeconvert.topdf.odf;

import java.util.Locale;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** A sheet's page style written as SpreadsheetML print options, margins, page setup and header and footer. */
final class SheetPage {

    private static final double[][] PAPERS = {{612, 792, 1}, {612, 1008, 5}, {595.3, 841.9, 9}, {841.9, 1190.6, 8},
        {419.5, 595.3, 11}, {728.5, 1031.8, 12}, {515.9, 728.5, 13}, {522, 756, 7}, {792, 1224, 3}, {708.7, 1000.6, 34}};

    private final Element master;

    private final Props page;

    private final Props header;

    private final Props footer;

    SheetPage(Styles styles, String masterName) {
        Element m = styles.master(masterName);
        if (m == null) {
            m = styles.master("Default");
        }
        if (m == null) {
            m = styles.firstMaster();
        }
        master = m;
        Element layout = styles.pageLayout(Dom.attr(m, Ns.STYLE, "page-layout-name"));
        page = new Props();
        page.merge(Dom.kid(layout, Ns.STYLE, "page-layout-properties"));
        header = new Props();
        header.merge(Dom.kid(Dom.kid(layout, Ns.STYLE, "header-style"), Ns.STYLE, "header-footer-properties"));
        footer = new Props();
        footer.merge(Dom.kid(Dom.kid(layout, Ns.STYLE, "footer-style"), Ns.STYLE, "header-footer-properties"));
    }

    boolean fitToPage() {
        return page.has("style:scale-to-X") || page.has("style:scale-to-Y") || page.has("style:scale-to-pages");
    }

    private static boolean shown(Element e) {
        return e != null && !"false".equals(Dom.attr(e, Ns.STYLE, "display"));
    }

    String xml() {
        StringBuilder b = new StringBuilder();
        String print = page.get("style:print", "");
        String centering = page.get("style:table-centering", "none");
        boolean grid = print.contains("grid");
        boolean headings = print.contains("headers");
        boolean hc = centering.equals("both") || centering.equals("horizontal");
        boolean vc = centering.equals("both") || centering.equals("vertical");
        if (grid || headings || hc || vc) {
            b.append("<printOptions").append(hc ? " horizontalCentered=\"1\"" : "").append(vc ? " verticalCentered=\"1\"" : "")
                    .append(headings ? " headings=\"1\"" : "").append(grid ? " gridLines=\"1\"" : "").append("/>");
        }
        double top = page.pt("fo:margin-top", 56.7);
        double bottom = page.pt("fo:margin-bottom", 56.7);
        double left = page.pt("fo:margin-left", 56.7);
        double right = page.pt("fo:margin-right", 56.7);
        double headerDist = 21.6;
        double footerDist = 21.6;
        boolean hasHeader = master != null && shown(Dom.kid(master, Ns.STYLE, "header"));
        boolean hasFooter = master != null && shown(Dom.kid(master, Ns.STYLE, "footer"));
        if (hasHeader) {
            headerDist = top;
            top += header.pt("fo:min-height", 0) + ("true".equals(header.get("style:dynamic-spacing")) ? 0
                    : header.pt("fo:margin-bottom", 0));
        }
        if (hasFooter) {
            footerDist = bottom;
            bottom += footer.pt("fo:min-height", 0) + ("true".equals(footer.get("style:dynamic-spacing")) ? 0
                    : footer.pt("fo:margin-top", 0));
        }
        b.append("<pageMargins left=\"").append(in(left)).append("\" right=\"").append(in(right)).append("\" top=\"")
                .append(in(top)).append("\" bottom=\"").append(in(bottom)).append("\" header=\"").append(in(headerDist))
                .append("\" footer=\"").append(in(footerDist)).append("\"/>");
        double w = page.pt("fo:page-width", 595.3);
        double h = page.pt("fo:page-height", 841.9);
        boolean landscape = "landscape".equals(page.get("style:print-orientation")) || w > h;
        b.append("<pageSetup paperSize=\"").append(paper(Math.min(w, h), Math.max(w, h))).append('"');
        String scale = page.get("style:scale-to");
        if (scale != null) {
            long s = Math.round(Length.percent(scale, 100));
            b.append(" scale=\"").append(Math.max(10, Math.min(400, s))).append('"');
        }
        if (fitToPage()) {
            int x = parse(page.get("style:scale-to-X"), 0);
            int y = parse(page.get("style:scale-to-Y"), 0);
            int pages = parse(page.get("style:scale-to-pages"), 0);
            if (pages > 0 && x == 0 && y == 0) {
                x = 1;
                y = pages;
            }
            b.append(" fitToWidth=\"").append(x).append("\" fitToHeight=\"").append(y).append('"');
        }
        if ("ltr".equals(page.get("style:print-page-order"))) {
            b.append(" pageOrder=\"overThenDown\"");
        }
        b.append(" orientation=\"").append(landscape ? "landscape" : "portrait").append('"');
        int first = parse(page.get("style:first-page-number"), 0);
        if (first > 0) {
            b.append(" firstPageNumber=\"").append(first).append("\" useFirstPageNumber=\"1\"");
        }
        b.append("/>");
        String head = hasHeader ? text(Dom.kid(master, Ns.STYLE, "header")) : "";
        String foot = hasFooter ? text(Dom.kid(master, Ns.STYLE, "footer")) : "";
        if (!head.isEmpty() || !foot.isEmpty()) {
            b.append("<headerFooter>");
            if (!head.isEmpty()) {
                b.append("<oddHeader>").append(Xml.esc(head)).append("</oddHeader>");
            }
            if (!foot.isEmpty()) {
                b.append("<oddFooter>").append(Xml.esc(foot)).append("</oddFooter>");
            }
            b.append("</headerFooter>");
        }
        return b.toString();
    }

    private static int parse(String v, int fallback) {
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String in(double pt) {
        return String.valueOf(Math.round(pt / 72 * 10000) / 10000.0);
    }

    private static int paper(double w, double h) {
        for (double[] p : PAPERS) {
            if (Math.abs(p[0] - w) < 4 && Math.abs(p[1] - h) < 4) {
                return (int) p[2];
            }
        }
        return w < 600 ? 9 : 1;
    }

    static String text(Element hf) {
        if (hf == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        boolean regions = false;
        for (String[] r : new String[][] {{"region-left", "&L"}, {"region-center", "&C"}, {"region-right", "&R"}}) {
            Element region = Dom.kid(hf, Ns.STYLE, r[0]);
            if (region != null) {
                regions = true;
                String t = paragraphs(region);
                if (!t.isEmpty()) {
                    b.append(r[1]).append(t);
                }
            }
        }
        if (!regions) {
            String t = paragraphs(hf);
            if (!t.isEmpty()) {
                b.append("&C").append(t);
            }
        }
        return b.length() > 255 ? b.substring(0, 255) : b.toString();
    }

    private static String paragraphs(Element e) {
        StringBuilder b = new StringBuilder();
        boolean first = true;
        for (Element p : Dom.kids(e, Ns.TEXT, "p")) {
            if (!first) {
                b.append('\n');
            }
            first = false;
            fields(p, b, 0);
        }
        return b.toString().strip().isEmpty() ? "" : b.toString();
    }

    private static void fields(Element e, StringBuilder b, int depth) {
        if (depth > 16) {
            return;
        }
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.TEXT_NODE) {
                b.append(n.getNodeValue().replace("&", "&&"));
            } else if (n instanceof Element k && Ns.TEXT.equals(k.getNamespaceURI())) {
                switch (Dom.local(k).toLowerCase(Locale.ROOT)) {
                    case "page-number" -> b.append("&P");
                    case "page-count" -> b.append("&N");
                    case "sheet-name" -> b.append("&A");
                    case "title", "file-name" -> b.append("&F");
                    case "date" -> b.append("&D");
                    case "time" -> b.append("&T");
                    case "s" -> b.append(" ".repeat(Math.max(1, Math.min(100, Dom.integer(k, Ns.TEXT, "c", 1)))));
                    case "tab" -> b.append(' ');
                    case "line-break" -> b.append('\n');
                    default -> fields(k, b, depth + 1);
                }
            }
        }
    }
}
