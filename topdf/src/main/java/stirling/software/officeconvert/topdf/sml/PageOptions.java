package stirling.software.officeconvert.topdf.sml;

import stirling.software.officeconvert.topdf.xls.Xml;

/** A Worksheet's WorksheetOptions and PageBreaks as SpreadsheetML print settings. */
final class PageOptions {

    private static final int MAX_BREAKS = 1026;

    private PageOptions() {}

    static String xml(Node options, Node breaks) {
        StringBuilder b = new StringBuilder();
        Node setup = options == null ? null : options.kid("PageSetup");
        Node print = options == null ? null : options.kid("Print");
        Node layout = setup == null ? null : setup.kid("Layout");
        boolean hCenter = layout != null && layout.flag("CenterHorizontal");
        boolean vCenter = layout != null && layout.flag("CenterVertical");
        boolean grid = print != null && print.kid("Gridlines") != null;
        boolean headings = print != null && print.kid("RowColHeadings") != null;
        if (hCenter || vCenter || grid || headings) {
            b.append("<printOptions").append(hCenter ? " horizontalCentered=\"1\"" : "")
                    .append(vCenter ? " verticalCentered=\"1\"" : "").append(headings ? " headings=\"1\"" : "")
                    .append(grid ? " gridLines=\"1\"" : "").append("/>");
        }
        Node margins = setup == null ? null : setup.kid("PageMargins");
        Node header = setup == null ? null : setup.kid("Header");
        Node footer = setup == null ? null : setup.kid("Footer");
        b.append("<pageMargins left=\"").append(inches(margins, "Left", 0.75)).append("\" right=\"")
                .append(inches(margins, "Right", 0.75)).append("\" top=\"").append(inches(margins, "Top", 1))
                .append("\" bottom=\"").append(inches(margins, "Bottom", 1)).append("\" header=\"")
                .append(inches(header, "Margin", 0.5)).append("\" footer=\"").append(inches(footer, "Margin", 0.5))
                .append("\"/>");
        b.append("<pageSetup");
        int paper = number(print, "PaperSizeIndex", -1);
        if (paper > 0 && paper < 256) {
            b.append(" paperSize=\"").append(paper).append('"');
        }
        int scale = number(print, "Scale", -1);
        if (scale >= 10 && scale <= 400) {
            b.append(" scale=\"").append(scale).append('"');
        }
        int start = layout == null ? -1 : layout.integer("StartPageNumber", -1);
        if (start > 0) {
            b.append(" firstPageNumber=\"").append(start).append("\" useFirstPageNumber=\"1\"");
        }
        b.append(" fitToWidth=\"").append(Math.max(0, number(print, "FitWidth", 1))).append("\" fitToHeight=\"")
                .append(Math.max(0, number(print, "FitHeight", 1))).append('"');
        if (print != null && print.kid("LeftToRight") != null) {
            b.append(" pageOrder=\"overThenDown\"");
        }
        String orientation = layout == null ? null : layout.attr("Orientation");
        if ("Landscape".equalsIgnoreCase(orientation)) {
            b.append(" orientation=\"landscape\"");
        } else if ("Portrait".equalsIgnoreCase(orientation)) {
            b.append(" orientation=\"portrait\"");
        }
        if (print != null && print.kid("BlackAndWhite") != null) {
            b.append(" blackAndWhite=\"1\"");
        }
        b.append("/>");
        String head = header == null ? null : header.attr("Data");
        String foot = footer == null ? null : footer.attr("Data");
        if (head != null && !head.isEmpty() || foot != null && !foot.isEmpty()) {
            b.append("<headerFooter>");
            if (head != null && !head.isEmpty()) {
                b.append("<oddHeader>").append(Xml.attr(head)).append("</oddHeader>");
            }
            if (foot != null && !foot.isEmpty()) {
                b.append("<oddFooter>").append(Xml.attr(foot)).append("</oddFooter>");
            }
            b.append("</headerFooter>");
        }
        b.append(breaks(breaks, "RowBreaks", "RowBreak", "Row", "rowBreaks", 16383));
        b.append(breaks(breaks, "ColBreaks", "ColBreak", "Column", "colBreaks", 1048575));
        return b.toString();
    }

    private static String breaks(Node all, String group, String item, String field, String element, int max) {
        Node g = all == null ? null : all.kid(group);
        if (g == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (Node k : g.all(item)) {
            Node f = k.kid(field);
            int at = f == null ? -1 : (int) Styles.parse(f.text(), -1);
            if (at > 0 && n < MAX_BREAKS) {
                n++;
                b.append("<brk id=\"").append(at).append("\" max=\"").append(max).append("\" man=\"1\"/>");
            }
        }
        return n == 0 ? "" : "<" + element + " count=\"" + n + "\" manualBreakCount=\"" + n + "\">" + b + "</"
                + element + ">";
    }

    private static double inches(Node n, String attr, double fallback) {
        double v = n == null ? fallback : n.number(attr, fallback);
        return v >= 0 && v < 50 ? v : fallback;
    }

    private static int number(Node n, String kid, int fallback) {
        Node k = n == null ? null : n.kid(kid);
        return k == null ? fallback : (int) Styles.parse(k.text(), fallback);
    }
}
