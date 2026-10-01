package stirling.software.officeconvert.topdf.odf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

import org.w3c.dom.Element;

/** ODF master pages and page layouts written as Word section properties, with their headers and footers. */
final class WordPages {

    private final OdtWriter w;

    private final Map<String, String> refs = new HashMap<>();

    private int parts;

    private final java.util.Set<String> used = new java.util.HashSet<>();

    private final Map<String, String> empties = new HashMap<>();

    final boolean evenAndOdd;

    final boolean mirrored;

    WordPages(OdtWriter w) {
        this.w = w;
        boolean even = false;
        boolean mirror = false;
        for (Element m : w.styles.masters()) {
            even |= shown(Dom.kid(m, Ns.STYLE, "header-left")) || shown(Dom.kid(m, Ns.STYLE, "footer-left"));
            for (String kind : new String[] {"header", "footer"}) {
                if (shown(Dom.kid(m, Ns.STYLE, kind)) || shown(Dom.kid(m, Ns.STYLE, kind + "-left"))
                        || shown(Dom.kid(m, Ns.STYLE, kind + "-first"))) {
                    used.add(kind);
                }
            }
            Element layout = w.styles.pageLayout(Dom.attr(m, Ns.STYLE, "page-layout-name"));
            mirror |= "mirrored".equals(Dom.attr(layout, Ns.STYLE, "page-usage"));
        }
        evenAndOdd = even;
        mirrored = mirror;
    }

    private static boolean shown(Element hf) {
        return hf != null && !"false".equals(Dom.attr(hf, Ns.STYLE, "display"));
    }

    Props layout(String master) {
        Element m = w.styles.master(master);
        Element layout = w.styles.pageLayout(Dom.attr(m, Ns.STYLE, "page-layout-name"));
        Props p = new Props();
        p.merge(Dom.kid(layout, Ns.STYLE, "page-layout-properties"));
        return p;
    }

    Element columns(String master) {
        return layout(master).kid("columns");
    }

    static int columnCount(Element columns) {
        if (columns == null) {
            return 1;
        }
        return Math.max(1, Math.min(45, Dom.integer(columns, Ns.FO, "column-count", 1)));
    }

    String sectPr(String master, Element columns, boolean continuous, String pageStart) {
        Element m = w.styles.master(master);
        Element layout = w.styles.pageLayout(Dom.attr(m, Ns.STYLE, "page-layout-name"));
        Props p = layout(master);
        double width = p.pt("fo:page-width", 612);
        double height = p.pt("fo:page-height", 792);
        if (!(width > 36 && width < 14400)) {
            width = 612;
        }
        if (!(height > 36 && height < 14400)) {
            height = 792;
        }
        double top = p.pt("fo:margin-top", 56.7);
        double bottom = p.pt("fo:margin-bottom", 56.7);
        double left = p.pt("fo:margin-left", 56.7);
        double right = p.pt("fo:margin-right", 56.7);
        StringBuilder b = new StringBuilder("<w:sectPr>");
        double headerTop = top;
        double footerBottom = bottom;
        if (m != null) {
            b.append(references(m));
            Element header = Dom.kid(m, Ns.STYLE, "header");
            if (shown(header) || shown(Dom.kid(m, Ns.STYLE, "header-first"))) {
                top += extent(Dom.kid(Dom.kid(layout, Ns.STYLE, "header-style"), Ns.STYLE, "header-footer-properties"),
                        header == null ? Dom.kid(m, Ns.STYLE, "header-first") : header, "margin-bottom");
            }
            Element footer = Dom.kid(m, Ns.STYLE, "footer");
            if (shown(footer) || shown(Dom.kid(m, Ns.STYLE, "footer-first"))) {
                bottom += extent(Dom.kid(Dom.kid(layout, Ns.STYLE, "footer-style"), Ns.STYLE, "header-footer-properties"),
                        footer == null ? Dom.kid(m, Ns.STYLE, "footer-first") : footer, "margin-top");
            }
        }
        if (continuous) {
            b.append("<w:type w:val=\"continuous\"/>");
        }
        b.append("<w:pgSz w:w=\"").append(Length.twips(width)).append("\" w:h=\"").append(Length.twips(height))
                .append('"');
        if ("landscape".equals(p.get("style:print-orientation")) || width > height) {
            b.append(" w:orient=\"landscape\"");
        }
        b.append("/>");
        b.append("<w:pgMar w:top=\"").append(Length.twips(top)).append("\" w:right=\"").append(Length.twips(right))
                .append("\" w:bottom=\"").append(Length.twips(bottom)).append("\" w:left=\"")
                .append(Length.twips(left)).append("\" w:header=\"").append(Length.twips(headerTop))
                .append("\" w:footer=\"").append(Length.twips(footerBottom)).append("\" w:gutter=\"")
                .append(Length.twips(p.pt("loext:margin-gutter", 0))).append("\"/>");
        String fmt = switch (p.get("style:num-format", "1")) {
            case "i" -> "lowerRoman";
            case "I" -> "upperRoman";
            case "a" -> "lowerLetter";
            case "A" -> "upperLetter";
            default -> null;
        };
        int start = -1;
        if (pageStart != null) {
            try {
                start = Integer.parseInt(pageStart.trim());
            } catch (NumberFormatException e) {
                start = -1;
            }
        }
        if (fmt != null || start >= 0) {
            b.append("<w:pgNumType");
            if (fmt != null) {
                b.append(" w:fmt=\"").append(fmt).append('"');
            }
            if (start >= 0) {
                b.append(" w:start=\"").append(start).append('"');
            }
            b.append("/>");
        }
        b.append(cols(columns, width - left - right));
        if (m != null && (shown(Dom.kid(m, Ns.STYLE, "header-first")) || shown(Dom.kid(m, Ns.STYLE, "footer-first"))
                || Dom.kid(m, Ns.STYLE, "header-first") != null || Dom.kid(m, Ns.STYLE, "footer-first") != null)) {
            b.append("<w:titlePg/>");
        }
        return b.append("</w:sectPr>").toString();
    }

    private double extent(Element hfProps, Element content, String spacingSide) {
        Props p = new Props();
        p.merge(hfProps);
        double spacing = p.pt("fo:" + spacingSide, 0);
        double fixed = p.pt("svg:height", Double.NaN);
        if (!Double.isNaN(fixed)) {
            return fixed;
        }
        double min = p.pt("fo:min-height", 0);
        double text = estimate(content);
        if (!"false".equals(p.get("style:dynamic-spacing"))) {
            return Math.max(min, text);
        }
        return Math.max(min, spacing + text);
    }

    private double estimate(Element content) {
        double h = 0;
        for (Element k : Dom.kids(content)) {
            if (Dom.is(k, Ns.TEXT, "p") || Dom.is(k, Ns.TEXT, "h")) {
                String name = Dom.attr(k, Ns.TEXT, "style-name");
                Props tp = w.styles.props("paragraph", name, Styles.Scope.STYLES, "text-properties", true);
                Props pp = w.styles.props("paragraph", name, Styles.Scope.STYLES, "paragraph-properties", true);
                double size = tp.pt("fo:font-size", 12);
                double lines = 1 + k.getElementsByTagNameNS(Ns.TEXT, "line-break").getLength();
                h += lines * size * 1.17 * Length.percent(pp.get("fo:line-height"), 100) / 100
                        + pp.pt("fo:margin-top", 0) + pp.pt("fo:margin-bottom", 0);
            } else if (Dom.is(k, Ns.TABLE, "table")) {
                h += 14 * k.getElementsByTagNameNS(Ns.TABLE, "table-row").getLength();
            }
        }
        return h;
    }

    static String cols(Element columns, double textWidth) {
        int count = columnCount(columns);
        if (count <= 1) {
            return "<w:cols w:space=\"720\"/>";
        }
        StringBuilder b = new StringBuilder("<w:cols w:num=\"").append(count).append('"');
        Element sep = Dom.kid(columns, Ns.STYLE, "column-sep");
        if (sep != null && !"none".equals(Dom.attr(sep, Ns.STYLE, "style"))) {
            b.append(" w:sep=\"1\"");
        }
        java.util.List<Element> cols = Dom.kids(columns, Ns.STYLE, "column");
        String gapAttr = Dom.attr(columns, Ns.FO, "column-gap");
        if (gapAttr != null || cols.size() != count) {
            double gap = Length.pt(gapAttr, 0);
            if (gapAttr == null && cols.size() >= 2) {
                gap = Length.pt(Dom.attr(cols.get(0), Ns.FO, "end-indent"), 0)
                        + Length.pt(Dom.attr(cols.get(1), Ns.FO, "start-indent"), 0);
            }
            return b.append(" w:space=\"").append(Length.twips(gap)).append("\"/>").toString();
        }
        double total = 0;
        for (Element c : cols) {
            total += relWidth(c);
        }
        b.append(" w:equalWidth=\"0\">");
        for (int i = 0; i < cols.size(); i++) {
            Element c = cols.get(i);
            double share = total > 0 ? relWidth(c) / total * textWidth : textWidth / count;
            double start = Length.pt(Dom.attr(c, Ns.FO, "start-indent"), 0);
            double end = Length.pt(Dom.attr(c, Ns.FO, "end-indent"), 0);
            double space = i + 1 < cols.size() ? end + Length.pt(Dom.attr(cols.get(i + 1), Ns.FO, "start-indent"), 0)
                    : 0;
            b.append("<w:col w:w=\"").append(Length.twips(Math.max(18, share - start - end))).append("\" w:space=\"")
                    .append(Length.twips(space)).append("\"/>");
        }
        return b.append("</w:cols>").toString();
    }

    private static double relWidth(Element c) {
        String v = Dom.attr(c, Ns.STYLE, "rel-width", "1");
        try {
            return Math.max(0, Double.parseDouble(v.replace("*", "").trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private String references(Element m) {
        String name = Dom.attr(m, Ns.STYLE, "name", "");
        String cached = refs.get(name);
        if (cached != null) {
            return cached;
        }
        StringBuilder b = new StringBuilder();
        try {
            for (String kind : new String[] {"header", "footer"}) {
                Element main = Dom.kid(m, Ns.STYLE, kind);
                Element left = Dom.kid(m, Ns.STYLE, kind + "-left");
                Element first = Dom.kid(m, Ns.STYLE, kind + "-first");
                if (!shown(main) && !shown(left) && !shown(first)) {
                    if (used.contains(kind)) {
                        b.append(ref(kind, "default", empty(kind)));
                        if (evenAndOdd) {
                            b.append(ref(kind, "even", empty(kind)));
                        }
                        b.append(ref(kind, "first", empty(kind)));
                    }
                    continue;
                }
                String mainId = shown(main) ? part(kind, main) : part(kind, null);
                b.append(ref(kind, "default", mainId));
                if (evenAndOdd) {
                    b.append(ref(kind, "even", shown(left) ? part(kind, left) : mainId));
                }
                if (first != null || Dom.kid(m, Ns.STYLE, (kind.equals("header") ? "footer" : "header") + "-first")
                        != null) {
                    b.append(ref(kind, "first", first == null ? mainId : part(kind, shown(first) ? first : null)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        refs.put(name, b.toString());
        return b.toString();
    }

    private String empty(String kind) throws IOException {
        String id = empties.get(kind);
        if (id == null) {
            id = part(kind, null);
            empties.put(kind, id);
        }
        return id;
    }

    private static String ref(String kind, String type, String id) {
        return "<w:" + kind + "Reference w:type=\"" + type + "\" r:id=\"" + id + "\"/>";
    }

    private String part(String kind, Element content) throws IOException {
        String file = kind + (++parts) + ".xml";
        Part p = new Part("word/" + file);
        TextBody body = new TextBody(w, p, Styles.Scope.STYLES, false, 1);
        if (content != null) {
            body.blocks(content, null);
        }
        String root = kind.equals("header") ? "w:hdr" : "w:ftr";
        w.out.xml(p.name, Xml.CT + "wordprocessingml." + kind + "+xml", Xml.HEAD + "<" + root + " " + OdtWriter.NAMESPACES
                + ">" + body.cellXml() + "</" + root + ">");
        if (!p.rels.isEmpty()) {
            w.out.xml("word/_rels/" + file + ".rels", null, p.rels.xml());
        }
        return w.main.rels.add(kind, file);
    }
}
