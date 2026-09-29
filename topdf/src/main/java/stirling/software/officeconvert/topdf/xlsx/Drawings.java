package stirling.software.officeconvert.topdf.xlsx;

import java.awt.Color;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.io.ActiveContent;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.Relationship;
import stirling.software.officeconvert.topdf.io.Relationships;

final class Drawings {

    static final double EMU = 12700.0;

    record Anchor(int col, double colOff, int row, double rowOff) {}

    record Rect(double x, double y, double w, double h) {}

    record Para(List<TextRun> runs, String align) {}

    record Item(Anchor from, Anchor to, double absX, double absY, double absW, double absH, Rect child,
            String picture, Element crop, String geometry, Color fill, Color line, double lineWidth,
            List<Para> text, double[] insets, String textAnchor, double rotation, boolean flipH, boolean flipV,
            boolean connector, Element custom, Map<String, Long> adjust, String head, String tail, String chart) {}

    static final Drawings NONE = new Drawings(List.of());

    final List<Item> items;

    private Drawings(List<Item> items) {
        this.items = items;
    }

    boolean isEmpty() {
        return items.isEmpty();
    }

    static Drawings read(Book book, String part, String sheetName, Grid grid) throws InterruptedIOException {
        RenderJob job = book.job();
        List<Item> items = new ArrayList<>();
        try {
            OfficeZip zip = job.zip();
            Relationships rels = zip.relationships(part);
            for (Relationship r : rels.ofType("drawing")) {
                if (!ActiveContent.mayFollow(r)) {
                    continue;
                }
                Document doc = zip.xml(r);
                Relationships drels = zip.relationships(r.part());
                new Reader(book, drels, items).root(doc.getDocumentElement());
            }
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            job.warn("A drawing on sheet " + sheetName + " could not be read");
        }
        for (Item i : items) {
            if (i.to != null) {
                grid.extend(i.to.row, i.to.col);
            } else if (i.from != null) {
                grid.extend(i.from.row, i.from.col);
            }
        }
        return items.isEmpty() ? NONE : new Drawings(items);
    }

    private static final class Reader {

        private final Book book;

        private final Relationships rels;

        private final List<Item> out;

        Reader(Book book, Relationships rels, List<Item> out) {
            this.book = book;
            this.rels = rels;
            this.out = out;
        }

        void root(Element wsDr) {
            for (Element anchor : Dml.children(wsDr)) {
                if (out.size() >= 2000) {
                    return;
                }
                String kind = anchor.getLocalName();
                Anchor from = null;
                Anchor to = null;
                double ax = 0;
                double ay = 0;
                double aw = 0;
                double ah = 0;
                if (kind.equals("twoCellAnchor")) {
                    from = anchor(Dml.child(anchor, "from"));
                    to = anchor(Dml.child(anchor, "to"));
                } else if (kind.equals("oneCellAnchor")) {
                    from = anchor(Dml.child(anchor, "from"));
                    Element ext = Dml.child(anchor, "ext");
                    aw = Dml.number(ext, "cx", 0) / EMU;
                    ah = Dml.number(ext, "cy", 0) / EMU;
                } else if (kind.equals("absoluteAnchor")) {
                    Element pos = Dml.child(anchor, "pos");
                    Element ext = Dml.child(anchor, "ext");
                    ax = Dml.number(pos, "x", 0) / EMU;
                    ay = Dml.number(pos, "y", 0) / EMU;
                    aw = Dml.number(ext, "cx", 0) / EMU;
                    ah = Dml.number(ext, "cy", 0) / EMU;
                } else {
                    continue;
                }
                if (from == null && !kind.equals("absoluteAnchor")) {
                    continue;
                }
                Element client = Dml.child(anchor, "clientData");
                if (client != null && "0".equals(client.getAttribute("fPrintsWithSheet"))
                        || client != null && "false".equals(client.getAttribute("fPrintsWithSheet"))) {
                    continue;
                }
                for (Element obj : Dml.children(anchor)) {
                    object(obj, from, to, ax, ay, aw, ah, null);
                }
            }
        }

        private Anchor anchor(Element e) {
            if (e == null) {
                return null;
            }
            int col = (int) Math.max(0, Math.min(Columns.MAX - 1, Dml.text(Dml.child(e, "col"), 0)));
            int row = (int) Math.max(0, Math.min(Grid.MAX_ROWS - 1, Dml.text(Dml.child(e, "row"), 0)));
            double co = Dml.text(Dml.child(e, "colOff"), 0) / EMU;
            double ro = Dml.text(Dml.child(e, "rowOff"), 0) / EMU;
            return new Anchor(col, co, row, ro);
        }

        private void object(Element obj, Anchor from, Anchor to, double ax, double ay, double aw, double ah,
                Rect child) {
            if (hidden(obj)) {
                return;
            }
            switch (obj.getLocalName()) {
                case "graphicFrame" -> chart(obj, from, to, ax, ay, aw, ah, child);
                case "pic" -> picture(obj, from, to, ax, ay, aw, ah, child);
                case "sp" -> shape(obj, from, to, ax, ay, aw, ah, child, false);
                case "cxnSp" -> shape(obj, from, to, ax, ay, aw, ah, child, true);
                case "grpSp" -> group(obj, from, to, ax, ay, aw, ah, child);
                case "AlternateContent" -> {
                    Element fallback = Dml.child(obj, "Fallback");
                    if (fallback != null) {
                        for (Element c : Dml.children(fallback)) {
                            object(c, from, to, ax, ay, aw, ah, child);
                        }
                    }
                }
                default -> {
                }
            }
        }

        // Shapes hidden in the selection pane never print
        private static boolean hidden(Element obj) {
            for (Element nv : Dml.children(obj)) {
                if (nv.getLocalName().startsWith("nv")) {
                    Element pr = Dml.child(nv, "cNvPr");
                    String h = pr == null ? null : pr.getAttribute("hidden");
                    return "1".equals(h) || "true".equals(h);
                }
            }
            return false;
        }

        // Charts are drawn from their cached values; the embedded workbook is never opened
        private void chart(Element frame, Anchor from, Anchor to, double ax, double ay, double aw, double ah,
                Rect child) {
            Element chart = Dml.path(frame, "graphic", "graphicData", "chart");
            String id = chart == null ? null : Dml.attrNs(chart, "id");
            Relationship r = id == null ? null : rels.get(id);
            if (r == null || !ActiveContent.mayFollow(r)) {
                return;
            }
            out.add(new Item(from, to, ax, ay, aw, ah, child, null, null, null, null, null, 0, null, null, null, 0,
                    false, false, false, null, null, null, null, r.part()));
        }

        private void group(Element grp, Anchor from, Anchor to, double ax, double ay, double aw, double ah,
                Rect parent) {
            Element xfrm = Dml.path(grp, "grpSpPr", "xfrm");
            Element off = Dml.child(xfrm, "off");
            Element ext = Dml.child(xfrm, "ext");
            Element chOff = Dml.child(xfrm, "chOff");
            Element chExt = Dml.child(xfrm, "chExt");
            double gx = Dml.number(off, "x", 0);
            double gy = Dml.number(off, "y", 0);
            double gw = Math.max(1, Dml.number(ext, "cx", 1));
            double gh = Math.max(1, Dml.number(ext, "cy", 1));
            double cx = Dml.number(chOff, "x", (long) gx);
            double cy = Dml.number(chOff, "y", (long) gy);
            double cw = Math.max(1, Dml.number(chExt, "cx", (long) gw));
            double ch = Math.max(1, Dml.number(chExt, "cy", (long) gh));
            for (Element c : Dml.children(grp)) {
                String n = c.getLocalName();
                if (!(n.equals("pic") || n.equals("sp") || n.equals("cxnSp") || n.equals("grpSp")
                        || n.equals("AlternateContent") || n.equals("graphicFrame"))) {
                    continue;
                }
                Element cx2 = childXfrm(c);
                Rect inGroup;
                if (cx2 == null) {
                    inGroup = new Rect(0, 0, 1, 1);
                } else {
                    double x = (Dml.number(Dml.child(cx2, "off"), "x", (long) cx) - cx) / cw;
                    double y = (Dml.number(Dml.child(cx2, "off"), "y", (long) cy) - cy) / ch;
                    double w = Dml.number(Dml.child(cx2, "ext"), "cx", 0) / cw;
                    double h = Dml.number(Dml.child(cx2, "ext"), "cy", 0) / ch;
                    inGroup = new Rect(x, y, w, h);
                }
                Rect r = parent == null ? inGroup : new Rect(parent.x + inGroup.x * parent.w,
                        parent.y + inGroup.y * parent.h, inGroup.w * parent.w, inGroup.h * parent.h);
                if (n.equals("grpSp")) {
                    group(c, from, to, ax, ay, aw, ah, r);
                } else {
                    object(c, from, to, ax, ay, aw, ah, r);
                }
            }
        }

        private static Element childXfrm(Element c) {
            String n = c.getLocalName();
            if (n.equals("grpSp")) {
                return Dml.path(c, "grpSpPr", "xfrm");
            }
            if (n.equals("graphicFrame")) {
                return Dml.child(c, "xfrm");
            }
            return Dml.path(c, "spPr", "xfrm");
        }

        private void picture(Element pic, Anchor from, Anchor to, double ax, double ay, double aw, double ah,
                Rect child) {
            Element blip = Dml.path(pic, "blipFill", "blip");
            String id = blip == null ? null : Dml.attrNs(blip, "embed");
            if (id == null) {
                return;
            }
            Relationship r = rels.get(id);
            if (r == null || !ActiveContent.mayFollow(r)) {
                return;
            }
            Element xfrm = Dml.path(pic, "spPr", "xfrm");
            double rot = Dml.number(xfrm, "rot", 0) / 60000.0;
            out.add(new Item(from, to, ax, ay, aw, ah, child, r.part(), Dml.path(pic, "blipFill", "srcRect"), null,
                    null, null, 0, null, null, null, rot, Dml.flag(xfrm, "flipH"), Dml.flag(xfrm, "flipV"), false, null,
                    null, null, null, null));
        }

        private void shape(Element sp, Anchor from, Anchor to, double ax, double ay, double aw, double ah,
                Rect child, boolean connector) {
            Element spPr = Dml.child(sp, "spPr");
            Element style = Dml.child(sp, "style");
            ExcelColors colors = book.colors();
            Element geom = Dml.child(spPr, "prstGeom");
            Element custom = Dml.child(spPr, "custGeom");
            String prst = geom == null ? "rect" : Dml.attr(geom, "prst");
            Map<String, Long> adjust = new HashMap<>();
            for (Element gd : Dml.children(Dml.child(geom, "avLst"), "gd")) {
                String f = gd.getAttribute("fmla");
                if (f != null && f.startsWith("val ")) {
                    try {
                        adjust.put(gd.getAttribute("name"), Long.parseLong(f.substring(4).trim()));
                    } catch (NumberFormatException ignored) {
                        continue;
                    }
                }
            }
            Color fill = null;
            if (Dml.child(spPr, "noFill") == null) {
                Element solid = Dml.child(spPr, "solidFill");
                if (solid != null) {
                    fill = Dml.color(solid, colors, null);
                } else if (Dml.child(spPr, "gradFill") != null) {
                    Element gs = Dml.path(spPr, "gradFill", "gsLst", "gs");
                    fill = gs == null ? null : Dml.color(gs, colors, null);
                } else if (style != null && !connector) {
                    Element ref = Dml.child(style, "fillRef");
                    if (ref != null && Dml.number(ref, "idx", 0) > 0) {
                        fill = Dml.color(ref, colors, null);
                    }
                }
            }
            Color line = null;
            double width = 0.75;
            Element ln = Dml.child(spPr, "ln");
            if (ln != null && Dml.attr(ln, "w") != null) {
                width = Dml.number(ln, "w", 9525) / EMU;
            }
            if (ln == null || Dml.child(ln, "noFill") == null) {
                Element solid = ln == null ? null : Dml.child(ln, "solidFill");
                if (solid != null) {
                    line = Dml.color(solid, colors, null);
                } else if (style != null) {
                    Element ref = Dml.child(style, "lnRef");
                    if (ref != null && Dml.number(ref, "idx", 0) > 0) {
                        line = Dml.color(ref, colors, null);
                        if (ln == null || Dml.attr(ln, "w") == null) {
                            width = Dml.number(ref, "idx", 1) >= 2 ? 1.0 : 0.75;
                        }
                    }
                }
            }
            Color textColor = Color.BLACK;
            if (style != null) {
                Element fontRef = Dml.child(style, "fontRef");
                Color c = Dml.color(fontRef, colors, null);
                if (c != null) {
                    textColor = c;
                }
            }
            List<Para> paras = new ArrayList<>();
            double[] insets = {7.2, 3.6, 7.2, 3.6};
            String anchor = "t";
            Element tx = Dml.child(sp, "txBody");
            if (tx != null) {
                Element body = Dml.child(tx, "bodyPr");
                if (body != null) {
                    insets[0] = Dml.number(body, "lIns", 91440) / EMU;
                    insets[1] = Dml.number(body, "tIns", 45720) / EMU;
                    insets[2] = Dml.number(body, "rIns", 91440) / EMU;
                    insets[3] = Dml.number(body, "bIns", 45720) / EMU;
                    String a = Dml.attr(body, "anchor");
                    anchor = a == null ? "t" : a;
                }
                FontSpec base = book.styles().defaultFont().color(textColor);
                for (Element p : Dml.children(tx, "p")) {
                    paras.add(paragraph(p, base));
                }
            }
            Element xfrm = Dml.child(spPr, "xfrm");
            double rot = Dml.number(xfrm, "rot", 0) / 60000.0;
            out.add(new Item(from, to, ax, ay, aw, ah, child, null, null, prst, fill, line, width, paras, insets,
                    anchor, rot, Dml.flag(xfrm, "flipH"), Dml.flag(xfrm, "flipV"), connector, custom, adjust,
                    end(ln, "headEnd"), end(ln, "tailEnd"), null));
        }

        private static String end(Element ln, String name) {
            Element e = ln == null ? null : Dml.child(ln, name);
            String t = e == null ? null : e.getAttribute("type");
            return t == null || t.isEmpty() || t.equals("none") ? null : t;
        }

        private Para paragraph(Element p, FontSpec base) {
            Element pPr = Dml.child(p, "pPr");
            String align = pPr == null ? null : Dml.attr(pPr, "algn");
            List<TextRun> runs = new ArrayList<>();
            for (Element r : Dml.children(p)) {
                String n = r.getLocalName();
                if (n.equals("r") || n.equals("fld")) {
                    Element t = Dml.child(r, "t");
                    String s = t == null ? "" : t.getTextContent();
                    runs.add(new TextRun(s, font(Dml.child(r, "rPr"), base)));
                } else if (n.equals("br")) {
                    runs.add(new TextRun("\n", font(Dml.child(r, "rPr"), base)));
                }
            }
            if (runs.isEmpty()) {
                runs.add(new TextRun("", font(Dml.child(p, "endParaRPr"), base)));
            }
            return new Para(runs, align);
        }

        private FontSpec font(Element rPr, FontSpec base) {
            if (rPr == null) {
                return base;
            }
            double size = Dml.attr(rPr, "sz") == null ? base.size() : Dml.number(rPr, "sz", 1100) / 100.0;
            boolean bold = Dml.attr(rPr, "b") == null ? base.bold() : Dml.flag(rPr, "b");
            boolean italic = Dml.attr(rPr, "i") == null ? base.italic() : Dml.flag(rPr, "i");
            String u = Dml.attr(rPr, "u");
            FontSpec.Underline under = u == null || u.equals("none") ? FontSpec.Underline.NONE
                    : u.startsWith("dbl") ? FontSpec.Underline.DOUBLE : FontSpec.Underline.SINGLE;
            boolean strike = Dml.attr(rPr, "strike") != null && !"noStrike".equals(Dml.attr(rPr, "strike"));
            Color color = base.color();
            Element solid = Dml.child(rPr, "solidFill");
            if (solid != null) {
                Color c = Dml.color(solid, book.colors(), base.color());
                if (c != null) {
                    color = c;
                }
            }
            String family = base.family();
            Element latin = Dml.child(rPr, "latin");
            String face = latin == null ? null : Dml.attr(latin, "typeface");
            if (face != null && !face.startsWith("+")) {
                family = face;
            }
            return new FontSpec(family, size, bold, italic, under, strike, color, base.offset());
        }
    }
}
