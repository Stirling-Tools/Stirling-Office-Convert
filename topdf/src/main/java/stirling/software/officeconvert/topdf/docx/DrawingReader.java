package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.topdf.io.ActiveContent;
import stirling.software.officeconvert.topdf.io.Relationship;
import stirling.software.officeconvert.topdf.pdf.Crop;
import stirling.software.officeconvert.topdf.pdf.Gradient;
import stirling.software.officeconvert.topdf.pdf.Stroke;

final class DrawingReader {

    private static final String PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture";

    private final DocxPackage pkg;

    private final String part;

    private final ContentReader content;

    DrawingReader(DocxPackage pkg, String part, ContentReader content) {
        this.pkg = pkg;
        this.part = part;
        this.content = content;
    }

    private Fonts fonts;

    // Faces for laying out equations while the document is read
    Fonts fonts() {
        if (fonts == null && pkg.job != null) {
            fonts = new Fonts(pkg.job, pkg.theme, pkg.settings == null ? null : pkg.settings.eastAsiaLang);
        }
        return fonts;
    }

    Drawing drawingML(XEl d) {
        if (!d.is("wp:inline") && !d.is("wp:anchor")) {
            return null;
        }
        Drawing dr = new Drawing();
        dr.inline = d.is("wp:inline");
        XEl extent = d.child("wp:extent");
        if (extent != null) {
            dr.width = Math.max(0, Ooxml.emu(extent.attr("cx"), 0));
            dr.height = Math.max(0, Ooxml.emu(extent.attr("cy"), 0));
        }
        XEl eff = d.child("wp:effectExtent");
        if (eff != null) {
            dr.effL = Ooxml.emu(eff.attr("l"), 0);
            dr.effT = Ooxml.emu(eff.attr("t"), 0);
            dr.effR = Ooxml.emu(eff.attr("r"), 0);
            dr.effB = Ooxml.emu(eff.attr("b"), 0);
        }
        dr.distT = Ooxml.emu(d.attr("distT"), 0);
        dr.distB = Ooxml.emu(d.attr("distB"), 0);
        dr.distL = Ooxml.emu(d.attr("distL"), 0);
        dr.distR = Ooxml.emu(d.attr("distR"), 0);
        XEl docPr = d.child("wp:docPr");
        if (docPr != null && Ooxml.flag(docPr.attr("hidden"), false)) {
            dr.hidden = true;
        }
        if (!dr.inline) {
            anchor(d, dr);
        } else {
            dr.wrap = "inline";
        }
        XEl data = d.path("a:graphic", "a:graphicData");
        if (data == null) {
            return null;
        }
        dr.graphic = graphic(data, dr.width, dr.height);
        return dr.graphic == null ? null : dr;
    }

    private void anchor(XEl d, Drawing dr) {
        dr.behind = Ooxml.flag(d.attr("behindDoc"), false);
        dr.z = Ooxml.longValue(d.attr("relativeHeight"), 0);
        dr.layoutInCell = Ooxml.flag(d.attr("layoutInCell"), true);
        boolean simple = Ooxml.flag(d.attr("simplePos"), false);
        XEl sp = d.child("wp:simplePos");
        XEl ph = d.child("wp:positionH");
        XEl pv = d.child("wp:positionV");
        if (simple && sp != null) {
            dr.hRel = "page";
            dr.vRel = "page";
            dr.hOffset = Ooxml.emu(sp.attr("x"), 0);
            dr.vOffset = Ooxml.emu(sp.attr("y"), 0);
        } else {
            if (ph != null) {
                dr.hRel = ph.attr("relativeFrom", "column");
                XEl align = ph.child("wp:align");
                XEl off = ph.child("wp:posOffset");
                XEl pct = ph.child("wp14:pctPosHOffset");
                if (align != null) {
                    dr.hAlign = align.text().strip();
                } else if (off != null) {
                    dr.hOffset = Ooxml.emu(off.text().strip(), 0);
                } else if (pct != null) {
                    dr.hPct = Ooxml.integer(pct.text().strip(), 0) / 100000f;
                }
            }
            if (pv != null) {
                dr.vRel = pv.attr("relativeFrom", "paragraph");
                XEl align = pv.child("wp:align");
                XEl off = pv.child("wp:posOffset");
                XEl pct = pv.child("wp14:pctPosVOffset");
                if (align != null) {
                    dr.vAlign = align.text().strip();
                } else if (off != null) {
                    dr.vOffset = Ooxml.emu(off.text().strip(), 0);
                } else if (pct != null) {
                    dr.vPct = Ooxml.integer(pct.text().strip(), 0) / 100000f;
                }
            }
        }
        for (XEl k : d.kids) {
            switch (k.name) {
                case "wp14:sizeRelH" -> {
                    dr.pctWidth = percent(k.child("wp14:pctWidth"));
                    dr.pctWidthFrom = k.attr("relativeFrom", "margin");
                }
                case "wp14:sizeRelV" -> {
                    dr.pctHeight = percent(k.child("wp14:pctHeight"));
                    dr.pctHeightFrom = k.attr("relativeFrom", "margin");
                }
                case "wp:wrapNone" -> dr.wrap = "none";
                case "wp:wrapSquare" -> {
                    dr.wrap = "square";
                    dr.wrapSide = k.attr("wrapText", "bothSides");
                }
                case "wp:wrapTight" -> {
                    dr.wrap = "tight";
                    dr.wrapSide = k.attr("wrapText", "bothSides");
                    dr.wrapBounds = polygonBounds(k.child("wp:wrapPolygon"));
                    dr.wrapPoints = polygonPoints(k.child("wp:wrapPolygon"));
                }
                case "wp:wrapThrough" -> {
                    dr.wrap = "through";
                    dr.wrapSide = k.attr("wrapText", "bothSides");
                    dr.wrapBounds = polygonBounds(k.child("wp:wrapPolygon"));
                    dr.wrapPoints = polygonPoints(k.child("wp:wrapPolygon"));
                }
                case "wp:wrapTopAndBottom" -> dr.wrap = "topAndBottom";
                default -> {
                }
            }
        }
    }

    // Wrap polygon points run from 0 to 21600 across the object; only their bounding box is kept
    static float[] polygonBounds(XEl polygon) {
        if (polygon == null) {
            return null;
        }
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (XEl pt : polygon.kids) {
            if (!pt.name.equals("wp:start") && !pt.name.equals("wp:lineTo")) {
                continue;
            }
            float x = Ooxml.integer(pt.attr("x"), 0) / 21600f;
            float y = Ooxml.integer(pt.attr("y"), 0) / 21600f;
            b[0] = Math.min(b[0], x);
            b[1] = Math.min(b[1], y);
            b[2] = Math.max(b[2], x);
            b[3] = Math.max(b[3], y);
        }
        if (b[2] - b[0] <= 0.01f || b[3] - b[1] <= 0.01f) {
            return null;
        }
        return new float[] {Math.max(-1, b[0]), Math.max(-1, b[1]), Math.min(2, b[2]), Math.min(2, b[3])};
    }

    static float[] polygonPoints(XEl polygon) {
        if (polygon == null) {
            return null;
        }
        List<Float> pts = new ArrayList<>();
        for (XEl pt : polygon.kids) {
            if (pt.name.equals("wp:start") || pt.name.equals("wp:lineTo")) {
                pts.add(Math.max(-1, Math.min(2, Ooxml.integer(pt.attr("x"), 0) / 21600f)));
                pts.add(Math.max(-1, Math.min(2, Ooxml.integer(pt.attr("y"), 0) / 21600f)));
            }
        }
        if (pts.size() < 6 || pts.size() > 4000) {
            return null;
        }
        float[] out = new float[pts.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = pts.get(i);
        }
        return out;
    }

    private Drawing.Graphic graphic(XEl data, float w, float h) {
        String uri = data.attr("uri", "");
        for (XEl k : data.kids) {
            switch (k.name) {
                case "pic:pic" -> {
                    return picture(k);
                }
                case "wps:wsp" -> {
                    return shape(k, w, h);
                }
                case "wpg:wgp" -> {
                    return group(k, w, h);
                }
                case "wpc:wpc" -> {
                    return canvas(k, w, h);
                }
                default -> {
                }
            }
        }
        if (uri.endsWith("/chart")) {
            Chart chart = ChartReader.read(pkg, part, data);
            return chart == null ? new Drawing.Placeholder("chart") : new Drawing.ChartGraphic(chart);
        }
        if (uri.contains("chartex") || uri.contains("/chart")) {
            return new Drawing.Placeholder("chart");
        }
        if (uri.endsWith("/diagram")) {
            Drawing.Graphic smart = SmartArt.read(this, pkg, part, data, w, h);
            return smart != null ? smart : new Drawing.Placeholder("diagram");
        }
        if (uri.equals(PIC)) {
            return null;
        }
        return new Drawing.Placeholder("other");
    }

    private Drawing.Picture picture(XEl pic) {
        XEl blipFill = pic.child("pic:blipFill");
        XEl spPr = pic.child("pic:spPr");
        return blipPicture(blipFill, spPr);
    }

    private Drawing.Picture blipPicture(XEl blipFill, XEl spPr) {
        if (blipFill == null) {
            return null;
        }
        XEl blip = blipFill.child("a:blip");
        if (blip == null) {
            return null;
        }
        String target = imagePart(blip.attr("r:embed"));
        if (target == null) {
            return null;
        }
        Crop crop = Crop.NONE;
        XEl src = blipFill.child("a:srcRect");
        if (src != null) {
            crop = crop(Ooxml.integer(src.attr("l"), 0) / 100000f, Ooxml.integer(src.attr("t"), 0) / 100000f,
                    Ooxml.integer(src.attr("r"), 0) / 100000f, Ooxml.integer(src.attr("b"), 0) / 100000f);
        }
        float alpha = 1;
        XEl amf = blip.child("a:alphaModFix");
        if (amf != null) {
            alpha = Math.max(0, Math.min(1, Ooxml.integer(amf.attr("amt"), 100000) / 100000f));
        }
        XEl lum = blip.child("a:lum");
        if (lum != null) {
            float bright = Ooxml.integer(lum.attr("bright"), 0) / 100000f;
            if (bright > 0 && bright < 1) {
                alpha *= 1 - bright;
            }
        }
        float rot = 0;
        boolean fh = false;
        boolean fv = false;
        Stroke outline = null;
        Chart.Shadow shadow = null;
        if (spPr != null) {
            XEl xfrm = spPr.child("a:xfrm");
            if (xfrm != null) {
                rot = Ooxml.integer(xfrm.attr("rot"), 0) / 60000f;
                fh = Ooxml.flag(xfrm.attr("flipH"), false);
                fv = Ooxml.flag(xfrm.attr("flipV"), false);
            }
            outline = line(spPr.child("a:ln"), null);
            shadow = shadow(spPr.path("a:effectLst", "a:outerShdw"));
        }
        return new Drawing.Picture(target, crop, rot, fh, fv, alpha, outline, shadow);
    }

    private Chart.Shadow shadow(XEl s) {
        Color c = s == null ? null : Colors.drawing(s, pkg.theme, null);
        if (c == null || c.getAlpha() == 0) {
            return null;
        }
        float dist = Math.max(0, Math.min(200, Ooxml.emu(s.attr("dist"), 0)));
        double dir = Math.toRadians(Ooxml.integer(s.attr("dir"), 0) / 60000.0);
        float blur = Math.max(0, Math.min(50, Ooxml.emu(s.attr("blurRad"), 0)));
        return new Chart.Shadow((float) (dist * Math.cos(dir)), (float) (dist * Math.sin(dir)), blur, c);
    }

    static Crop crop(float l, float t, float r, float b) {
        l = clampCrop(l);
        t = clampCrop(t);
        r = clampCrop(r);
        b = clampCrop(b);
        if (l + r >= 0.99f) {
            l = 0;
            r = 0;
        }
        if (t + b >= 0.99f) {
            t = 0;
            b = 0;
        }
        if (l == 0 && t == 0 && r == 0 && b == 0) {
            return Crop.NONE;
        }
        return Crop.fractions(l, t, r, b);
    }

    private static float clampCrop(float v) {
        if (!Float.isFinite(v)) {
            return 0;
        }
        return Math.max(-4, Math.min(0.98f, v));
    }

    String imagePart(String id) {
        if (id == null) {
            return null;
        }
        Relationship r = pkg.relationship(part, id);
        if (r == null || !ActiveContent.mayFollow(r)) {
            return null;
        }
        return r.part();
    }

    DrawingReader forPart(String other) {
        return new DrawingReader(pkg, other, content);
    }

    private Drawing.Graphic shape(XEl wsp, float w, float h) {
        XEl spPr = wsp.child("wps:spPr");
        XEl style = wsp.child("wps:style");
        if (spPr != null && spPr.child("a:blipFill") != null) {
            Drawing.Picture p = blipPicture(spPr.child("a:blipFill"), spPr);
            if (p != null) {
                return p;
            }
        }
        XEl tc = wsp.path("wps:txbx", "w:txbxContent");
        List<Block> blocks = tc == null ? null : content.blocks(tc, null);
        Drawing.Graphic art = wordArt(wsp, spPr, blocks);
        return art != null ? art : styledShape(spPr, style, textBox(wsp, blocks));
    }

    // Plain-warped text (WordArt) is stretched like a VML text path; other warps keep their wrapped lines
    private Drawing.Graphic wordArt(XEl wsp, XEl spPr, List<Block> blocks) {
        XEl warp = wsp.path("wps:bodyPr", "a:prstTxWarp");
        XEl tc = wsp.path("wps:txbx", "w:txbxContent");
        if (warp == null || blocks == null || !warp.attr("prst", "textNoShape").equals("textPlain")) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        RunProps rp = null;
        for (Block b : blocks) {
            if (!(b instanceof Para p)) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append(' ');
            }
            for (Inline i : p.items) {
                if (i instanceof Inline.Text t) {
                    rp = rp == null && !t.text().isBlank() ? t.rp() : rp;
                    text.append(t.text());
                }
            }
        }
        String flat = text.toString().replaceAll("\\s+", " ").strip();
        Fonts f = fonts();
        if (flat.isEmpty() || rp == null || f == null) {
            return null;
        }
        Color c = rp.textColor() == null ? Color.BLACK : rp.textColor();
        List<XEl> fills = tc.descendants("w14:textFill");
        XEl rgb = fills.isEmpty() ? null : first(fills.get(0).descendants("w14:srgbClr"));
        if (rgb != null && rgb.attr("w14:val") != null) {
            Color own = Colors.hex(rgb.attr("w14:val"));
            c = own != null ? own : c;
        }
        XEl alpha = fills.isEmpty() ? null : first(fills.get(0).descendants("w14:alpha"));
        if (alpha != null) {
            float a = 1 - Ooxml.integer(alpha.attr("w14:val"), 0) / 100000f;
            c = new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(255 * Math.max(0, Math.min(1, a))));
        }
        float rot = 0;
        boolean fh = false;
        boolean fv = false;
        XEl xfrm = spPr == null ? null : spPr.child("a:xfrm");
        if (xfrm != null) {
            rot = Ooxml.integer(xfrm.attr("rot"), 0) / 60000f;
            fh = Ooxml.flag(xfrm.attr("flipH"), false);
            fv = Ooxml.flag(xfrm.attr("flipV"), false);
        }
        return new Drawing.WordArt(flat, f.family(rp, Fonts.Slot.ASCII), rp.isBold(), rp.isItalic(), c, rot, fh, fv);
    }

    private static XEl first(List<XEl> list) {
        return list.isEmpty() ? null : list.get(0);
    }

    Drawing.Picture filledPicture(XEl spPr) {
        return spPr == null || spPr.child("a:blipFill") == null ? null : blipPicture(spPr.child("a:blipFill"), spPr);
    }

    Drawing.Shape styledShape(XEl spPr, XEl style, Drawing.TextBox text) {
        String geometry = "rect";
        XEl geom = null;
        float rot = 0;
        boolean fh = false;
        boolean fv = false;
        Color fill = null;
        Stroke stroke = null;
        if (spPr != null) {
            XEl prst = spPr.child("a:prstGeom");
            if (prst != null) {
                geometry = prst.attr("prst", "rect");
                geom = prst;
            } else if (spPr.child("a:custGeom") != null) {
                geometry = "custom";
                geom = spPr.child("a:custGeom");
            }
            XEl xfrm = spPr.child("a:xfrm");
            if (xfrm != null) {
                rot = Ooxml.integer(xfrm.attr("rot"), 0) / 60000f;
                fh = Ooxml.flag(xfrm.attr("flipH"), false);
                fv = Ooxml.flag(xfrm.attr("flipV"), false);
            }
        }
        fill = fill(spPr, style);
        stroke = stroke(spPr, style);
        Drawing.LineEnds ends = stroke == null || spPr == null ? null : ends(spPr.child("a:ln"));
        Drawing.Shade shade = fill == null ? null : shade(spPr, style);
        return new Drawing.Shape(geometry, geom, fill, stroke, rot, fh, fv, text, ends, shade);
    }

    private Color fill(XEl spPr, XEl style) {
        if (spPr != null) {
            for (XEl k : spPr.kids) {
                switch (k.name) {
                    case "a:noFill" -> {
                        return null;
                    }
                    case "a:solidFill" -> {
                        return Colors.drawing(k, pkg.theme, styleColor(style, "a:fillRef"));
                    }
                    case "a:gradFill" -> {
                        return gradientAverage(k, style);
                    }
                    case "a:pattFill" -> {
                        XEl bg = k.child("a:bgClr");
                        return Colors.drawing(bg, pkg.theme, null);
                    }
                    case "a:grpFill" -> {
                        return null;
                    }
                    default -> {
                    }
                }
            }
        }
        if (style != null) {
            XEl ref = style.child("a:fillRef");
            if (ref != null && Ooxml.integer(ref.attr("idx"), 0) > 0) {
                Color placeholder = Colors.drawing(ref, pkg.theme, null);
                XEl themeFill = pkg.theme.fillStyle(Ooxml.integer(ref.attr("idx"), 0));
                if (themeFill != null && themeFill.is("a:noFill")) {
                    return null;
                }
                if (themeFill != null && themeFill.is("a:solidFill")) {
                    Color c = Colors.drawing(themeFill, pkg.theme, placeholder);
                    return c != null ? c : placeholder;
                }
                if (themeFill != null && themeFill.is("a:gradFill")) {
                    Color c = gradientAverageWith(themeFill, placeholder);
                    return c != null ? c : placeholder;
                }
                return placeholder;
            }
        }
        return null;
    }

    private Drawing.Shade shade(XEl spPr, XEl style) {
        XEl grad = null;
        Color placeholder = styleColor(style, "a:fillRef");
        if (spPr != null) {
            for (XEl k : spPr.kids) {
                if (k.is("a:gradFill")) {
                    grad = k;
                    break;
                }
                if (k.is("a:solidFill") || k.is("a:noFill") || k.is("a:pattFill") || k.is("a:blipFill")
                        || k.is("a:grpFill")) {
                    return null;
                }
            }
        }
        if (grad == null && style != null && style.child("a:fillRef") != null) {
            XEl themeFill = pkg.theme.fillStyle(Ooxml.integer(style.child("a:fillRef").attr("idx"), 0));
            if (themeFill != null && themeFill.is("a:gradFill")) {
                grad = themeFill;
            }
        }
        if (grad == null || grad.child("a:gsLst") == null) {
            return null;
        }
        List<Gradient.Stop> stops = new ArrayList<>();
        for (XEl gs : grad.child("a:gsLst").children("a:gs")) {
            Color c = Colors.drawing(gs, pkg.theme, placeholder);
            if (c != null && stops.size() < 64) {
                float pos = Math.max(0, Math.min(1, Ooxml.integer(gs.attr("pos"), 0) / 100000f));
                stops.add(new Gradient.Stop(pos, c));
            }
        }
        if (stops.size() < 2) {
            return null;
        }
        XEl lin = grad.child("a:lin");
        XEl path = grad.child("a:path");
        float angle = lin == null ? 90 : Ooxml.integer(lin.attr("ang"), 0) / 60000f;
        return new Drawing.Shade(stops, angle, path == null ? null : path.attr("path", "circle"));
    }

    private Color gradientAverage(XEl grad, XEl style) {
        return gradientAverageWith(grad, styleColor(style, "a:fillRef"));
    }

    private Color gradientAverageWith(XEl grad, Color placeholder) {
        XEl list = grad.child("a:gsLst");
        if (list == null) {
            return placeholder;
        }
        int r = 0;
        int g = 0;
        int b = 0;
        int a = 0;
        int n = 0;
        for (XEl gs : list.children("a:gs")) {
            Color c = Colors.drawing(gs, pkg.theme, placeholder);
            if (c != null) {
                r += c.getRed();
                g += c.getGreen();
                b += c.getBlue();
                a += c.getAlpha();
                n++;
            }
        }
        return n == 0 ? placeholder : new Color(r / n, g / n, b / n, a / n);
    }

    private Color styleColor(XEl style, String ref) {
        if (style == null) {
            return null;
        }
        XEl r = style.child(ref);
        return r == null ? null : Colors.drawing(r, pkg.theme, null);
    }

    private Stroke stroke(XEl spPr, XEl style) {
        XEl ln = spPr == null ? null : spPr.child("a:ln");
        Color styleLine = styleColor(style, "a:lnRef");
        int idx = 0;
        if (style != null && style.child("a:lnRef") != null) {
            idx = Ooxml.integer(style.child("a:lnRef").attr("idx"), 0);
        }
        if (ln == null) {
            if (idx > 0 && styleLine != null) {
                return Stroke.solid(pkg.theme.lineWidth(idx), styleLine);
            }
            return null;
        }
        Stroke s = line(ln, styleLine);
        if (s == null && ln.child("a:noFill") == null && idx > 0 && styleLine != null) {
            float width = ln.attr("w") != null ? Ooxml.emu(ln.attr("w"), 0.75f) : pkg.theme.lineWidth(idx);
            s = dashed(Stroke.solid(Math.max(0.25f, width), styleLine), ln);
        }
        return s;
    }

    private Stroke line(XEl ln, Color placeholder) {
        if (ln == null || ln.child("a:noFill") != null) {
            return null;
        }
        XEl solid = ln.child("a:solidFill");
        Color c = null;
        if (solid != null) {
            c = Colors.drawing(solid, pkg.theme, placeholder);
        } else if (ln.child("a:gradFill") != null) {
            c = gradientAverageWith(ln.child("a:gradFill"), placeholder);
        }
        if (c == null) {
            return null;
        }
        float w = ln.attr("w") != null ? Ooxml.emu(ln.attr("w"), 0.75f) : 0.75f;
        return dashed(Stroke.solid(Math.max(0.25f, Math.min(200, w)), c), ln);
    }

    private static Drawing.LineEnds ends(XEl ln) {
        if (ln == null) {
            return null;
        }
        XEl head = ln.child("a:headEnd");
        XEl tail = ln.child("a:tailEnd");
        String h = head == null ? "none" : head.attr("type", "none");
        String t = tail == null ? "none" : tail.attr("type", "none");
        if (h.equals("none") && t.equals("none")) {
            return null;
        }
        return new Drawing.LineEnds(h, head == null ? "med" : head.attr("w", "med"),
                head == null ? "med" : head.attr("len", "med"), t, tail == null ? "med" : tail.attr("w", "med"),
                tail == null ? "med" : tail.attr("len", "med"));
    }

    private static Stroke dashed(Stroke s, XEl ln) {
        XEl dash = ln.child("a:prstDash");
        if (dash == null || dash.val() == null) {
            return s;
        }
        float w = Math.max(0.5f, s.width());
        return switch (dash.val()) {
            case "dot", "sysDot" -> s.dash(0, w, w * 2);
            case "dash", "sysDash" -> s.dash(0, w * 4, w * 3);
            case "lgDash" -> s.dash(0, w * 8, w * 3);
            case "dashDot", "sysDashDot" -> s.dash(0, w * 4, w * 3, w, w * 3);
            case "lgDashDot", "lgDashDotDot", "sysDashDotDot" -> s.dash(0, w * 8, w * 3, w, w * 3);
            default -> s;
        };
    }

    private Drawing.TextBox textBox(XEl wsp, List<Block> blocks) {
        XEl body = wsp.child("wps:bodyPr");
        if (wsp.child("wps:txbx") == null || blocks == null) {
            return null;
        }
        float l = 7.2f;
        float t = 3.6f;
        float r = 7.2f;
        float b = 3.6f;
        String anchor = "t";
        boolean grow = false;
        boolean noWrap = false;
        String vert = null;
        if (body != null) {
            l = Ooxml.emu(body.attr("lIns"), l);
            t = Ooxml.emu(body.attr("tIns"), t);
            r = Ooxml.emu(body.attr("rIns"), r);
            b = Ooxml.emu(body.attr("bIns"), b);
            anchor = body.attr("anchor", "t");
            grow = body.child("a:spAutoFit") != null;
            noWrap = "none".equals(body.attr("wrap"));
            vert = body.attr("vert");
        }
        return new Drawing.TextBox(blocks, l, t, r, b, anchor, grow, noWrap, vert);
    }

    private Drawing.Graphic group(XEl g, float w, float h) {
        XEl grpSpPr = g.child("wpg:grpSpPr");
        List<Drawing.Child> children = new ArrayList<>();
        Frame f = frame(grpSpPr == null ? null : grpSpPr.child("a:xfrm"), w, h);
        collectChildren(g, f, children, 0);
        float rot = 0;
        boolean fh = false;
        boolean fv = false;
        if (grpSpPr != null && grpSpPr.child("a:xfrm") != null) {
            XEl x = grpSpPr.child("a:xfrm");
            rot = Ooxml.integer(x.attr("rot"), 0) / 60000f;
            fh = Ooxml.flag(x.attr("flipH"), false);
            fv = Ooxml.flag(x.attr("flipV"), false);
        }
        return new Drawing.Group(children, rot, fh, fv, w, h);
    }

    private Drawing.Graphic canvas(XEl c, float w, float h) {
        List<Drawing.Child> children = new ArrayList<>();
        Frame f = new Frame(0, 0, 1f / 12700f, 1f / 12700f, 0, 0);
        collectChildren(c, f, children, 0);
        return new Drawing.Group(children, 0, false, false, w, h);
    }

    private record Frame(float chX, float chY, float sx, float sy, float ox, float oy) {
        float x(float emu) {
            return ox + (emu - chX) * sx;
        }

        float y(float emu) {
            return oy + (emu - chY) * sy;
        }
    }

    private static Frame frame(XEl xfrm, float w, float h) {
        if (xfrm == null) {
            return new Frame(0, 0, 1f / 12700f, 1f / 12700f, 0, 0);
        }
        XEl chOff = xfrm.child("a:chOff");
        XEl chExt = xfrm.child("a:chExt");
        XEl ext = xfrm.child("a:ext");
        float cx = chOff == null ? 0 : Ooxml.longValue(chOff.attr("x"), 0);
        float cy = chOff == null ? 0 : Ooxml.longValue(chOff.attr("y"), 0);
        float cw = chExt == null ? 0 : Ooxml.longValue(chExt.attr("cx"), 0);
        float chh = chExt == null ? 0 : Ooxml.longValue(chExt.attr("cy"), 0);
        float ew = ext == null ? w * 12700 : Ooxml.longValue(ext.attr("cx"), 0);
        float eh = ext == null ? h * 12700 : Ooxml.longValue(ext.attr("cy"), 0);
        float sx = cw > 0 ? w / cw : (ew > 0 ? w / ew : 1f / 12700f);
        float sy = chh > 0 ? h / chh : (eh > 0 ? h / eh : 1f / 12700f);
        return new Frame(cx, cy, sx, sy, 0, 0);
    }

    private void collectChildren(XEl g, Frame f, List<Drawing.Child> out, int depth) {
        if (depth > 16) {
            return;
        }
        for (XEl k : g.kids) {
            switch (k.name) {
                case "wps:wsp", "pic:pic", "wpg:grpSp" -> {
                    XEl spPr = k.is("wps:wsp") ? k.child("wps:spPr") : k.is("pic:pic") ? k.child("pic:spPr")
                            : k.child("wpg:grpSpPr");
                    XEl xfrm = spPr == null ? null : spPr.child("a:xfrm");
                    if (xfrm == null) {
                        continue;
                    }
                    XEl off = xfrm.child("a:off");
                    XEl ext = xfrm.child("a:ext");
                    float ox = off == null ? 0 : Ooxml.longValue(off.attr("x"), 0);
                    float oy = off == null ? 0 : Ooxml.longValue(off.attr("y"), 0);
                    float ew = ext == null ? 0 : Ooxml.longValue(ext.attr("cx"), 0);
                    float eh = ext == null ? 0 : Ooxml.longValue(ext.attr("cy"), 0);
                    float x = f.x(ox);
                    float y = f.y(oy);
                    float w = ew * f.sx();
                    float h = eh * f.sy();
                    float turn = ((Ooxml.integer(xfrm.attr("rot"), 0) / 60000f) % 360 + 360) % 360;
                    if (turn >= 45 && turn < 135 || turn >= 225 && turn < 315) {
                        // a quarter-turned child takes the group's scale along its turned axes
                        float midX = f.x(ox + ew / 2);
                        float midY = f.y(oy + eh / 2);
                        w = ew * f.sy();
                        h = eh * f.sx();
                        x = midX - w / 2;
                        y = midY - h / 2;
                    }
                    if (k.is("wpg:grpSp")) {
                        XEl chOff = xfrm.child("a:chOff");
                        XEl chExt = xfrm.child("a:chExt");
                        float cx = chOff == null ? ox : Ooxml.longValue(chOff.attr("x"), 0);
                        float cy = chOff == null ? oy : Ooxml.longValue(chOff.attr("y"), 0);
                        float cw = chExt == null ? ew : Ooxml.longValue(chExt.attr("cx"), 0);
                        float ch = chExt == null ? eh : Ooxml.longValue(chExt.attr("cy"), 0);
                        Frame inner = new Frame(cx, cy, cw > 0 ? w / cw : f.sx(), ch > 0 ? h / ch : f.sy(), 0, 0);
                        List<Drawing.Child> kids = new ArrayList<>();
                        collectChildren(k, inner, kids, depth + 1);
                        if (!kids.isEmpty()) {
                            boolean fh = Ooxml.flag(xfrm.attr("flipH"), false);
                            boolean fv = Ooxml.flag(xfrm.attr("flipV"), false);
                            out.add(new Drawing.Child(new Drawing.Group(kids, turn, fh, fv, w, h), x, y, w, h));
                        }
                    } else {
                        Drawing.Graphic gr = k.is("wps:wsp") ? shape(k, w, h) : picture(k);
                        if (gr != null) {
                            out.add(new Drawing.Child(gr, x, y, w, h));
                        }
                    }
                }
                case "wpc:bg", "wpc:whole" -> {
                }
                default -> {
                }
            }
        }
    }

    private static float percent(XEl pct) {
        if (pct == null) {
            return 0;
        }
        float v = Ooxml.integer(pct.text().strip(), 0) / 100000f;
        return Float.isFinite(v) && v > 0 && v <= 100 ? v : 0;
    }

    Drawing vml(XEl holder) {
        return new VmlReader(this, pkg, content).read(holder);
    }
}
