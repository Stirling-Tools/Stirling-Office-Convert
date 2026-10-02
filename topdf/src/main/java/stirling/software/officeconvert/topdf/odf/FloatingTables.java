package stirling.software.officeconvert.topdf.odf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.w3c.dom.Element;

final class FloatingTables {

    private final OdtWriter w;

    private final Set<Element> hoisted = Collections.newSetFromMap(new IdentityHashMap<>());

    FloatingTables(OdtWriter w) {
        this.w = w;
    }

    boolean hoisted(Element frame) {
        return hoisted.contains(frame);
    }

    List<String> hoist(Element paragraph, TextBody body) throws IOException {
        List<String> out = new ArrayList<>();
        collect(paragraph, body, out, 0);
        return out;
    }

    private void collect(Element parent, TextBody body, List<String> out, int depth) throws IOException {
        for (Element k : Dom.kids(parent)) {
            if (Dom.is(k, Ns.DRAW, "frame")) {
                String xml = floating(k, body);
                if (xml != null) {
                    hoisted.add(k);
                    out.add(xml);
                }
            } else if (depth < 8 && (Dom.is(k, Ns.TEXT, "span") || Dom.is(k, Ns.TEXT, "a"))) {
                collect(k, body, out, depth + 1);
            }
        }
    }

    private String floating(Element frame, TextBody body) throws IOException {
        String anchor = Dom.attr(frame, Ns.TEXT, "anchor-type", "paragraph");
        if (!anchor.equals("paragraph") && !anchor.equals("char")) {
            return null;
        }
        Element table = onlyTable(Dom.kid(frame, Ns.DRAW, "text-box"));
        if (table == null) {
            return null;
        }
        Props g = w.styles.props("graphic", Dom.attr(frame, Ns.DRAW, "style-name"), body.scope, "graphic-properties",
                true);
        if (visible(g)) {
            return null;
        }
        String xml = w.tables.table(table, body);
        String open = "<w:tbl><w:tblPr>";
        if (xml == null || !xml.startsWith(open)) {
            return null;
        }
        return open + tblpPr(frame, g) + xml.substring(open.length());
    }

    private static Element onlyTable(Element box) {
        if (box == null) {
            return null;
        }
        Element table = null;
        for (Element k : Dom.kids(box)) {
            if (Dom.is(k, Ns.TABLE, "table") && table == null) {
                table = k;
            } else if (!Dom.is(k, Ns.TEXT, "p") || !k.getTextContent().isBlank() || !Dom.kids(k).isEmpty()) {
                return null;
            }
        }
        return table;
    }

    private static boolean visible(Props g) {
        for (String side : new String[] {"fo:border", "fo:border-top", "fo:border-bottom", "fo:border-left",
            "fo:border-right"}) {
            String b = g.get(side);
            if (b != null && !b.equals("none") && !b.startsWith("0") && !b.contains("hidden")) {
                return true;
            }
        }
        String fill = g.get("draw:fill");
        if (fill != null && !fill.equals("none")) {
            return true;
        }
        String bg = g.get("fo:background-color");
        return fill == null && bg != null && !bg.equals("transparent");
    }

    static String tblpPr(Element frame, Props g) {
        StringBuilder b = new StringBuilder("<w:tblpPr w:leftFromText=\"")
                .append(Length.twips(g.pt("fo:margin-left", 0))).append("\" w:rightFromText=\"")
                .append(Length.twips(g.pt("fo:margin-right", 0))).append("\" w:topFromText=\"")
                .append(Length.twips(g.pt("fo:margin-top", 0))).append("\" w:bottomFromText=\"")
                .append(Length.twips(g.pt("fo:margin-bottom", 0))).append("\" w:vertAnchor=\"")
                .append(anchor(g.get("style:vertical-rel", "paragraph"))).append("\" w:horzAnchor=\"")
                .append(anchor(g.get("style:horizontal-rel", "paragraph"))).append('"');
        String hpos = g.get("style:horizontal-pos", "from-left");
        switch (hpos) {
            case "left", "center", "right", "inside", "outside" -> b.append(" w:tblpXSpec=\"").append(hpos).append('"');
            default -> b.append(" w:tblpX=\"").append(Length.twips(Length.pt(Dom.attr(frame, Ns.SVG, "x"), 0)))
                    .append('"');
        }
        String vpos = g.get("style:vertical-pos", "from-top");
        switch (vpos) {
            case "top" -> b.append(" w:tblpYSpec=\"top\"");
            case "middle" -> b.append(" w:tblpYSpec=\"center\"");
            case "bottom" -> b.append(" w:tblpYSpec=\"bottom\"");
            default -> b.append(" w:tblpY=\"").append(Length.twips(Length.pt(Dom.attr(frame, Ns.SVG, "y"), 0)))
                    .append('"');
        }
        return b.append("/>").toString();
    }

    private static String anchor(String rel) {
        return switch (rel) {
            case "page", "page-start-margin", "page-end-margin" -> "page";
            case "page-content" -> "margin";
            default -> "text";
        };
    }
}
