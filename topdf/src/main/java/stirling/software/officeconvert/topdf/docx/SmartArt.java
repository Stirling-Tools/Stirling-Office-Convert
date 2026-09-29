package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.topdf.io.Relationship;

// A SmartArt diagram is drawn from the shapes Office cached for it, never laid out from its data model
final class SmartArt {

    private static final float EMU = 12700f;

    private static final int MAX_SHAPES = 2000;

    private final DrawingReader shapes;

    private final Theme theme;

    private int count;

    private SmartArt(DrawingReader shapes, Theme theme) {
        this.shapes = shapes;
        this.theme = theme;
    }

    static Drawing.Graphic read(DrawingReader reader, DocxPackage pkg, String part, XEl data, float w, float h) {
        XEl ids = data.child("dgm:relIds");
        if (ids == null) {
            return null;
        }
        try {
            XEl model = pkg.partXml(pkg.relationship(part, ids.attr("r:dm")));
            String drawingId = model == null ? null : drawingId(model);
            Relationship r = drawingId == null ? null : pkg.relationship(part, drawingId);
            XEl drawing = r == null ? null : pkg.partXml(r);
            XEl tree = drawing == null ? null : drawing.child("dsp:spTree");
            if (tree == null) {
                return null;
            }
            SmartArt s = new SmartArt(reader.forPart(r.part()), pkg.theme);
            List<Drawing.Child> children = new ArrayList<>();
            s.collect(tree, 0, 0, 1 / EMU, 1 / EMU, 0, 0, children, 0);
            return children.isEmpty() ? null : new Drawing.Group(children, 0, false, false, w, h);
        } catch (IOException | RuntimeException e) {
            pkg.job.warn("A SmartArt diagram could not be read: " + e.getMessage());
            return null;
        }
    }

    private static String drawingId(XEl model) {
        for (XEl e : model.descendants("dsp:dataModelExt")) {
            String id = e.attr("relId");
            if (id != null && !id.isBlank()) {
                return id;
            }
        }
        return null;
    }

    private void collect(XEl tree, float chX, float chY, float sx, float sy, float ox, float oy,
            List<Drawing.Child> out, int depth) {
        if (depth > 16) {
            return;
        }
        for (XEl k : tree.kids) {
            if (count > MAX_SHAPES) {
                return;
            }
            if (!k.is("dsp:sp") && !k.is("dsp:grpSp")) {
                continue;
            }
            XEl spPr = k.child(k.is("dsp:sp") ? "dsp:spPr" : "dsp:grpSpPr");
            XEl xfrm = spPr == null ? null : spPr.child("a:xfrm");
            if (xfrm == null) {
                continue;
            }
            float[] box = box(xfrm, chX, chY, sx, sy, ox, oy);
            if (k.is("dsp:grpSp")) {
                XEl chOff = xfrm.child("a:chOff");
                XEl chExt = xfrm.child("a:chExt");
                XEl off = xfrm.child("a:off");
                XEl ext = xfrm.child("a:ext");
                float cx = chOff != null ? Ooxml.longValue(chOff.attr("x"), 0)
                        : off == null ? 0 : Ooxml.longValue(off.attr("x"), 0);
                float cy = chOff != null ? Ooxml.longValue(chOff.attr("y"), 0)
                        : off == null ? 0 : Ooxml.longValue(off.attr("y"), 0);
                float cw = chExt != null ? Ooxml.longValue(chExt.attr("cx"), 0)
                        : ext == null ? 0 : Ooxml.longValue(ext.attr("cx"), 0);
                float ch = chExt != null ? Ooxml.longValue(chExt.attr("cy"), 0)
                        : ext == null ? 0 : Ooxml.longValue(ext.attr("cy"), 0);
                collect(k, cx, cy, cw > 0 ? box[2] / cw : sx, ch > 0 ? box[3] / ch : sy, box[0], box[1], out,
                        depth + 1);
                continue;
            }
            count++;
            shape(k, spPr, box, chX, chY, sx, sy, ox, oy, out);
        }
    }

    private static float[] box(XEl xfrm, float chX, float chY, float sx, float sy, float ox, float oy) {
        XEl off = xfrm.child("a:off");
        XEl ext = xfrm.child("a:ext");
        float x = off == null ? 0 : Ooxml.longValue(off.attr("x"), 0);
        float y = off == null ? 0 : Ooxml.longValue(off.attr("y"), 0);
        float w = ext == null ? 0 : Ooxml.longValue(ext.attr("cx"), 0);
        float h = ext == null ? 0 : Ooxml.longValue(ext.attr("cy"), 0);
        return new float[] {ox + (x - chX) * sx, oy + (y - chY) * sy, Math.max(0, w * sx), Math.max(0, h * sy)};
    }

    private void shape(XEl sp, XEl spPr, float[] box, float chX, float chY, float sx, float sy, float ox, float oy,
            List<Drawing.Child> out) {
        XEl style = sp.child("dsp:style");
        Drawing.Picture picture = shapes.filledPicture(spPr);
        XEl txBody = sp.child("dsp:txBody");
        XEl txXfrm = sp.child("dsp:txXfrm");
        Drawing.TextBox text = txBody == null ? null : text(txBody, style);
        if (text != null && text.blocks().isEmpty()) {
            text = null;
        }
        if (picture != null) {
            out.add(new Drawing.Child(picture, box[0], box[1], box[2], box[3]));
        }
        boolean separate = text != null && (txXfrm != null || picture != null);
        Drawing.Shape s = shapes.styledShape(spPr, style, separate ? null : text);
        if (picture == null && (s.fill() != null || s.line() != null || s.text() != null)) {
            out.add(new Drawing.Child(s, box[0], box[1], box[2], box[3]));
        }
        if (separate) {
            float[] tb = txXfrm == null ? box : box(txXfrm, chX, chY, sx, sy, ox, oy);
            float rot = txXfrm == null ? s.rotation() : Ooxml.integer(txXfrm.attr("rot"), 0) / 60000f;
            Drawing.Shape label = new Drawing.Shape("rect", null, null, null, rot, false, false, text);
            out.add(new Drawing.Child(label, tb[0], tb[1], tb[2], tb[3]));
        }
    }

    private Drawing.TextBox text(XEl txBody, XEl style) {
        Color color = null;
        String font = "minorHAnsi";
        if (style != null && style.child("a:fontRef") != null) {
            XEl ref = style.child("a:fontRef");
            color = Colors.drawing(ref, theme, null);
            if ("major".equals(ref.attr("idx"))) {
                font = "majorHAnsi";
            }
        }
        List<Block> blocks = ShapeParagraphs.read(txBody, theme, color == null ? Color.BLACK : color, font);
        XEl body = txBody.child("a:bodyPr");
        float l = 7.2f;
        float t = 3.6f;
        float r = 7.2f;
        float b = 3.6f;
        String anchor = "ctr";
        boolean noWrap = false;
        String vert = null;
        if (body != null) {
            l = Ooxml.emu(body.attr("lIns"), l);
            t = Ooxml.emu(body.attr("tIns"), t);
            r = Ooxml.emu(body.attr("rIns"), r);
            b = Ooxml.emu(body.attr("bIns"), b);
            anchor = body.attr("anchor", "t");
            noWrap = "none".equals(body.attr("wrap"));
            vert = body.attr("vert");
        }
        return new Drawing.TextBox(blocks, l, t, r, b, anchor, false, noWrap, vert);
    }
}
