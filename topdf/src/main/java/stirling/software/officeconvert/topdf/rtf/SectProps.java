package stirling.software.officeconvert.topdf.rtf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SectProps {

    static final class Page {
        int width = 12240;
        int height = 15840;
        int left = 1800;
        int right = 1800;
        int top = 1440;
        int bottom = 1440;
        int gutter;
        boolean landscape;

        Page copy() {
            Page p = new Page();
            p.width = width;
            p.height = height;
            p.left = left;
            p.right = right;
            p.top = top;
            p.bottom = bottom;
            p.gutter = gutter;
            p.landscape = landscape;
            return p;
        }

        boolean apply(String w, int v) {
            switch (w) {
                case "paperw" -> width = v;
                case "paperh" -> height = v;
                case "margl" -> left = v;
                case "margr" -> right = v;
                case "margt" -> top = v;
                case "margb" -> bottom = v;
                case "gutter" -> gutter = v;
                case "landscape" -> landscape = true;
                default -> {
                    return false;
                }
            }
            return true;
        }
    }

    Page page;
    int columns = 1;
    int columnSpace = 720;
    boolean lineBetween;
    final List<int[]> columnWidths = new ArrayList<>();
    String breakType;
    boolean titlePage;
    int headerY = 720;
    int footerY = 720;
    Integer pageStart;
    boolean restart;
    String pageFormat;
    String valign;
    boolean rtl;
    final Map<String, String> headers = new LinkedHashMap<>();
    final Map<String, String> footers = new LinkedHashMap<>();

    private int column = -1;

    SectProps(Page defaults) {
        page = defaults.copy();
    }

    boolean apply(String w, int v, boolean has) {
        switch (w) {
            case "pgwsxn" -> page.width = v;
            case "pghsxn" -> page.height = v;
            case "marglsxn" -> page.left = v;
            case "margrsxn" -> page.right = v;
            case "margtsxn" -> page.top = v;
            case "margbsxn" -> page.bottom = v;
            case "guttersxn" -> page.gutter = v;
            case "lndscpsxn" -> page.landscape = true;
            case "cols" -> columns = Math.max(1, Math.min(45, v));
            case "colsx" -> columnSpace = Math.max(0, v);
            case "linebetcol" -> lineBetween = true;
            case "colno" -> {
                column = v - 1;
                while (columnWidths.size() <= column && columnWidths.size() < 45) {
                    columnWidths.add(new int[2]);
                }
            }
            case "colw" -> {
                if (column >= 0 && column < columnWidths.size()) {
                    columnWidths.get(column)[0] = v;
                }
            }
            case "colsr" -> {
                if (column >= 0 && column < columnWidths.size()) {
                    columnWidths.get(column)[1] = v;
                }
            }
            case "sbknone" -> breakType = "continuous";
            case "sbkcol" -> breakType = "nextColumn";
            case "sbkpage" -> breakType = "nextPage";
            case "sbkeven" -> breakType = "evenPage";
            case "sbkodd" -> breakType = "oddPage";
            case "titlepg" -> titlePage = !has || v != 0;
            case "headery" -> headerY = v;
            case "footery" -> footerY = v;
            case "pgnstarts" -> pageStart = v;
            case "pgnrestart" -> restart = true;
            case "pgncont" -> restart = false;
            case "pgndec" -> pageFormat = "decimal";
            case "pgnucrm" -> pageFormat = "upperRoman";
            case "pgnlcrm" -> pageFormat = "lowerRoman";
            case "pgnucltr" -> pageFormat = "upperLetter";
            case "pgnlcltr" -> pageFormat = "lowerLetter";
            case "vertalt" -> valign = "top";
            case "vertalc" -> valign = "center";
            case "vertalb" -> valign = "bottom";
            case "vertalj" -> valign = "both";
            case "rtlsect" -> rtl = true;
            case "ltrsect" -> rtl = false;
            default -> {
                return false;
            }
        }
        return true;
    }

    String xml() {
        StringBuilder b = new StringBuilder(512).append("<w:sectPr>");
        header(b, "headerReference", headers);
        header(b, "footerReference", footers);
        if (breakType != null) {
            b.append("<w:type w:val=\"").append(breakType).append("\"/>");
        }
        int w = Math.max(144, Math.min(31680, page.width));
        int h = Math.max(144, Math.min(31680, page.height));
        if (page.landscape && w < h) {
            int t = w;
            w = h;
            h = t;
        }
        b.append("<w:pgSz w:w=\"").append(w).append("\" w:h=\"").append(h).append('"');
        if (page.landscape) {
            b.append(" w:orient=\"landscape\"");
        }
        b.append("/><w:pgMar w:top=\"").append(page.top).append("\" w:right=\"").append(Math.max(0, page.right))
                .append("\" w:bottom=\"").append(page.bottom).append("\" w:left=\"").append(Math.max(0, page.left))
                .append("\" w:header=\"").append(Math.max(0, headerY)).append("\" w:footer=\"")
                .append(Math.max(0, footerY)).append("\" w:gutter=\"").append(Math.max(0, page.gutter)).append("\"/>");
        if (pageStart != null && restart || pageFormat != null && !"decimal".equals(pageFormat)) {
            b.append("<w:pgNumType");
            if (pageFormat != null) {
                b.append(" w:fmt=\"").append(pageFormat).append('"');
            }
            if (restart) {
                b.append(" w:start=\"").append(pageStart == null ? 1 : pageStart).append('"');
            }
            b.append("/>");
        }
        if (columns > 1) {
            boolean equal = columnWidths.size() < columns;
            b.append("<w:cols w:num=\"").append(columns).append("\" w:space=\"").append(columnSpace).append('"');
            if (lineBetween) {
                b.append(" w:sep=\"1\"");
            }
            if (!equal) {
                b.append(" w:equalWidth=\"0\">");
                for (int i = 0; i < columns; i++) {
                    int[] c = columnWidths.get(i);
                    b.append("<w:col w:w=\"").append(c[0]).append("\" w:space=\"").append(c[1]).append("\"/>");
                }
                b.append("</w:cols>");
            } else {
                b.append("/>");
            }
        }
        if (valign != null) {
            b.append("<w:vAlign w:val=\"").append(valign).append("\"/>");
        }
        if (titlePage) {
            b.append("<w:titlePg/>");
        }
        if (rtl) {
            b.append("<w:bidi/>");
        }
        return b.append("</w:sectPr>").toString();
    }

    private static void header(StringBuilder b, String tag, Map<String, String> refs) {
        for (Map.Entry<String, String> e : refs.entrySet()) {
            b.append("<w:").append(tag).append(" w:type=\"").append(e.getKey()).append("\" r:id=\"")
                    .append(e.getValue()).append("\"/>");
        }
    }
}
