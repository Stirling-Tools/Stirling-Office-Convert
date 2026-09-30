package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import stirling.software.officeconvert.topdf.pdf.Crop;
import stirling.software.officeconvert.topdf.pdf.Stroke;

final class VmlReader {

    // Word keeps text 9 pt from the sides of a square-wrapped VML shape unless its style says otherwise
    static final float WRAP_SIDE = 9;

    private final DrawingReader drawings;

    private final DocxPackage pkg;

    private final ContentReader content;

    VmlReader(DrawingReader drawings, DocxPackage pkg, ContentReader content) {
        this.drawings = drawings;
        this.pkg = pkg;
        this.content = content;
    }

    static Map<String, String> style(String css) {
        Map<String, String> out = new HashMap<>();
        if (css == null) {
            return out;
        }
        for (String decl : css.split(";")) {
            int colon = decl.indexOf(':');
            if (colon > 0) {
                out.put(decl.substring(0, colon).trim().toLowerCase(Locale.ROOT), decl.substring(colon + 1).trim());
            }
        }
        return out;
    }

    static Float length(String v) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        String s = v.trim().toLowerCase(Locale.ROOT);
        Float u = Ooxml.universal(s);
        if (u != null) {
            return u;
        }
        try {
            float f = Float.parseFloat(s);
            return Float.isFinite(f) ? f * 0.75f : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Float number(String v) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        try {
            float f = Float.parseFloat(v.trim());
            return Float.isFinite(f) ? f : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    Drawing read(XEl holder) {
        for (XEl k : holder.kids) {
            if (isShape(k)) {
                Drawing d = shapeDrawing(k);
                if (d != null) {
                    return d;
                }
            }
        }
        return null;
    }

    private static boolean isShape(XEl k) {
        return switch (k.name) {
            case "v:shape", "v:rect", "v:roundrect", "v:oval", "v:line", "v:group", "v:image", "v:polyline" -> true;
            default -> false;
        };
    }

    private Drawing shapeDrawing(XEl s) {
        Map<String, String> st = style(s.attr("style"));
        if ("hidden".equals(st.get("visibility"))) {
            return null;
        }
        Drawing d = new Drawing();
        Float w = length(st.get("width"));
        Float h = length(st.get("height"));
        if (s.is("v:line")) {
            float[] from = pair(s.attr("from"));
            float[] to = pair(s.attr("to"));
            if (w == null) {
                w = Math.abs(to[0] - from[0]);
            }
            if (h == null) {
                h = Math.abs(to[1] - from[1]);
            }
        }
        d.width = w == null ? 0 : Math.max(0, w);
        d.height = h == null ? 0 : Math.max(0, h);
        String position = st.getOrDefault("position", "static");
        d.inline = !position.equals("absolute");
        if (d.inline) {
            d.wrap = "inline";
        } else {
            Float ml = length(st.get("margin-left"));
            Float mt = length(st.get("margin-top"));
            Float left = length(st.get("left"));
            Float top = length(st.get("top"));
            d.hOffset = ml != null ? ml : left != null ? left : 0;
            d.vOffset = mt != null ? mt : top != null ? top : 0;
            if (s.is("v:line")) {
                // a line sits where its end points say, on top of any offset in its style
                float[] from = pair(s.attr("from"));
                float[] to = pair(s.attr("to"));
                d.hOffset += Math.min(from[0], to[0]);
                d.vOffset += Math.min(from[1], to[1]);
            }
            XEl anchorWrap = s.child("w10:wrap");
            String ax = anchorWrap == null ? null : anchorWrap.attr("anchorx");
            String ay = anchorWrap == null ? null : anchorWrap.attr("anchory");
            d.hRel = hRel(st.getOrDefault("mso-position-horizontal-relative", ax == null ? "text" : ax));
            d.vRel = vRel(st.getOrDefault("mso-position-vertical-relative", ay == null ? "text" : ay));
            String ha = st.get("mso-position-horizontal");
            if (ha != null && !ha.equals("absolute")) {
                d.hAlign = ha;
            }
            String va = st.get("mso-position-vertical");
            if (va != null && !va.equals("absolute")) {
                d.vAlign = va;
            }
            Integer z = Ooxml.integer(st.get("z-index"));
            d.behind = z != null && z < 0;
            d.z = z == null ? 0 : z;
            d.wrap = "square";
            XEl wrap = s.child("w10:wrap");
            if (wrap != null) {
                String type = wrap.attr("type", "none");
                d.wrap = switch (type) {
                    case "none" -> "none";
                    case "topAndBottom" -> "topAndBottom";
                    case "tight" -> "tight";
                    case "through" -> "through";
                    default -> "square";
                };
            } else if (st.containsKey("mso-wrap-style") || d.behind || st.containsKey("z-index")) {
                d.wrap = "none";
            }
            d.wrapBounds = wrapCoords(s.attr("wrapcoords"));
            d.wrapPoints = d.wrapBounds == null ? null : wrapPoints(s.attr("wrapcoords"));
            float side = d.wrap.equals("square") ? WRAP_SIDE : 0;
            d.distL = orDefault(length(st.get("mso-wrap-distance-left")), side);
            d.distR = orDefault(length(st.get("mso-wrap-distance-right")), side);
            d.distT = orZero(length(st.get("mso-wrap-distance-top")));
            d.distB = orZero(length(st.get("mso-wrap-distance-bottom")));
        }
        if (Ooxml.flag(s.attr("o:hr"), false)) {
            Float pct = number(s.attr("o:hrpct"));
            if (pct != null && pct > 0) {
                d.rulePct = Math.min(1, pct / 1000f);
            } else if (d.width <= 0) {
                d.rulePct = 1;
            }
        }
        d.graphic = graphic(s, st, d.width, d.height, 0);
        return d.graphic == null ? null : d;
    }

    private static float orZero(Float f) {
        return f == null ? 0 : f;
    }

    private static float orDefault(Float f, float fallback) {
        return f == null ? fallback : f;
    }


    private static String hRel(String v) {
        return switch (v) {
            case "page" -> "page";
            case "margin" -> "margin";
            case "left-margin-area" -> "leftMargin";
            case "right-margin-area" -> "rightMargin";
            case "char" -> "character";
            default -> "column";
        };
    }

    private static String vRel(String v) {
        return switch (v) {
            case "page" -> "page";
            case "margin" -> "margin";
            case "top-margin-area" -> "topMargin";
            case "bottom-margin-area" -> "bottomMargin";
            case "line" -> "line";
            default -> "paragraph";
        };
    }

    private static Drawing.LineEnds ends(XEl stroke) {
        String h = arrow(stroke.attr("startarrow"));
        String t = arrow(stroke.attr("endarrow"));
        if (h.equals("none") && t.equals("none")) {
            return null;
        }
        return new Drawing.LineEnds(h, size(stroke.attr("startarrowwidth")), size(stroke.attr("startarrowlength")), t,
                size(stroke.attr("endarrowwidth")), size(stroke.attr("endarrowlength")));
    }

    private static String arrow(String v) {
        return switch (v == null ? "none" : v) {
            case "block" -> "triangle";
            case "classic" -> "stealth";
            case "open" -> "arrow";
            case "diamond" -> "diamond";
            case "oval" -> "oval";
            default -> "none";
        };
    }

    private static String size(String v) {
        return switch (v == null ? "medium" : v) {
            case "narrow", "short" -> "sm";
            case "wide", "long" -> "lg";
            default -> "med";
        };
    }

    private static float[] wrapCoords(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String[] parts = v.trim().split("[\\s,]+");
        if (parts.length < 6 || parts.length > 20_000) {
            return null;
        }
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int i = 0; i + 1 < parts.length; i += 2) {
            Float x = number(parts[i]);
            Float y = number(parts[i + 1]);
            if (x == null || y == null) {
                return null;
            }
            minX = Math.min(minX, x / 21600f);
            minY = Math.min(minY, y / 21600f);
            maxX = Math.max(maxX, x / 21600f);
            maxY = Math.max(maxY, y / 21600f);
        }
        if (maxX - minX <= 0.01f || maxY - minY <= 0.01f) {
            return null;
        }
        return new float[] {Math.max(-1, minX), Math.max(-1, minY), Math.min(2, maxX), Math.min(2, maxY)};
    }

    private static float[] wrapPoints(String v) {
        String[] parts = v.trim().split("[\\s,]+");
        float[] pts = new float[Math.min(parts.length / 2, 4096) * 2];
        for (int i = 0; i < pts.length; i++) {
            Float f = number(parts[i]);
            pts[i] = f == null ? 0 : Math.max(-1, Math.min(2, f / 21600f));
        }
        return pts.length < 6 ? null : pts;
    }

    private static float[] pair(String v) {
        float[] out = new float[2];
        if (v == null) {
            return out;
        }
        String[] parts = v.split(",");
        for (int i = 0; i < Math.min(2, parts.length); i++) {
            Float f = length(parts[i]);
            out[i] = f == null ? 0 : f;
        }
        return out;
    }

    private Drawing.Graphic graphic(XEl s, Map<String, String> st, float w, float h, int depth) {
        float rot = 0;
        Float r = number(st.get("rotation"));
        if (r != null) {
            rot = r;
        }
        String flip = st.getOrDefault("flip", "");
        boolean fh = flip.contains("x");
        boolean fv = flip.contains("y");
        if (s.is("v:line")) {
            float[] from = pair(s.attr("from"));
            float[] to = pair(s.attr("to"));
            fh ^= to[0] < from[0];
            fv ^= to[1] < from[1];
        }
        if (s.is("v:group")) {
            if (depth > 16) {
                pkg.leftOut("Shapes grouped more than 16 levels deep were left out");
                return null;
            }
            return group(s, w, h, depth);
        }
        XEl image = s.child("v:imagedata");
        if (image != null || s.is("v:image")) {
            XEl src = image != null ? image : s;
            String part = drawings.imagePart(src.attr("r:id") != null ? src.attr("r:id") : src.attr("r:pict"));
            if (part != null) {
                Crop crop = DrawingReader.crop(fraction(src.attr("cropleft")), fraction(src.attr("croptop")),
                        fraction(src.attr("cropright")), fraction(src.attr("cropbottom")));
                return new Drawing.Picture(part, crop, rot, fh, fv, washout(src), null);
            }
        }
        Color fill = null;
        boolean filled = Ooxml.flag(s.attr("filled"), true);
        XEl fillEl = s.child("v:fill");
        if (fillEl != null && !Ooxml.flag(fillEl.attr("on"), true)) {
            filled = false;
        }
        if (filled) {
            fill = Colors.vml(s.attr("fillcolor"));
            if (fill == null && fillEl != null) {
                fill = Colors.vml(fillEl.attr("color"));
            }
            if (fill == null && s.attr("fillcolor") == null && !s.is("v:line") && s.attr("type") == null) {
                fill = Color.WHITE;
            }
            if (fill != null && fillEl != null && fillEl.attr("opacity") != null) {
                float o = fraction(fillEl.attr("opacity"));
                fill = new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), Math.round(Math.max(0, Math.min(1, o)) * 255));
            }
        }
        XEl textPath = s.child("v:textpath");
        if (textPath != null) {
            return wordArt(textPath, fill, rot, fh, fv);
        }
        Stroke stroke = null;
        boolean stroked = Ooxml.flag(s.attr("stroked"), true);
        XEl strokeEl = s.child("v:stroke");
        if (strokeEl != null && !Ooxml.flag(strokeEl.attr("on"), true)) {
            stroked = false;
        }
        if (stroked) {
            Color c = Colors.vml(s.attr("strokecolor"));
            if (c == null && strokeEl != null) {
                c = Colors.vml(strokeEl.attr("color"));
            }
            if (c == null) {
                c = Color.BLACK;
            }
            Float weight = length(s.attr("strokeweight"));
            stroke = Stroke.solid(weight == null ? 0.75f : Math.max(0.25f, weight), c);
            if (strokeEl != null && strokeEl.attr("dashstyle") != null && !"solid".equals(strokeEl.attr("dashstyle"))) {
                float sw = Math.max(0.5f, stroke.width());
                stroke = stroke.dash(0, sw * 3, sw * 2);
            }
        }
        String geometry = switch (s.name) {
            case "v:oval" -> "ellipse";
            case "v:roundrect" -> "roundRect";
            case "v:line" -> "line";
            default -> "rect";
        };
        XEl outline = null;
        if (s.is("v:shape") && s.attr("type") != null && s.attr("type").contains("_x0000_t32")) {
            geometry = "line";
        } else if (s.is("v:shape")) {
            String preset = ShapePath.preset(s.attr("type"));
            if (preset != null) {
                geometry = preset;
            } else if (s.attr("path") != null) {
                geometry = "vmlpath";
                outline = s;
            }
        }
        Drawing.TextBox text = null;
        XEl tb = s.child("v:textbox");
        if (tb != null) {
            XEl tc = tb.child("w:txbxContent");
            float[] ins = inset(tb.attr("inset"));
            Map<String, String> tbStyle = style(tb.attr("style"));
            boolean grow = "t".equals(tbStyle.get("mso-fit-shape-to-text")) || "true".equals(tbStyle.get("mso-fit-shape-to-text"));
            String vert = null;
            if ("vertical".equals(tbStyle.get("layout-flow"))) {
                vert = "bottom-to-top".equals(tbStyle.get("mso-layout-flow-alt")) ? "vert270" : "vert";
            }
            text = new Drawing.TextBox(content.blocks(tc, null), ins[0], ins[1], ins[2], ins[3], "t", grow, false,
                    vert, !grow);
        }
        return new Drawing.Shape(geometry, outline, fill, stroke, rot, fh, fv, text,
                stroke == null || strokeEl == null ? null : ends(strokeEl), null);
    }

    private static Drawing.Graphic wordArt(XEl textPath, Color fill, float rot, boolean fh, boolean fv) {
        String text = textPath.attr("string");
        if (text == null || text.isBlank() || !Ooxml.flag(textPath.attr("on"), true) || fill == null) {
            return null;
        }
        Map<String, String> ts = style(textPath.attr("style"));
        String family = ts.getOrDefault("font-family", "Arial").replace("\"", "").replace("'", "").trim();
        boolean bold = ts.getOrDefault("font-weight", "").startsWith("bold");
        boolean italic = "italic".equals(ts.get("font-style"));
        String flat = text.replace('\r', ' ').replace('\n', ' ').strip();
        return new Drawing.WordArt(flat, family.isEmpty() ? "Arial" : family, bold, italic, fill, rot, fh, fv);
    }

    private static float[] inset(String v) {
        float[] out = {7.2f, 3.6f, 7.2f, 3.6f};
        if (v == null) {
            return out;
        }
        String[] parts = v.split(",");
        for (int i = 0; i < Math.min(4, parts.length); i++) {
            Float f = length(parts[i]);
            if (f != null) {
                out[i] = f;
            }
        }
        return out;
    }

    // A washed out picture (gain below one with a raised black level) is drawn faded against the page
    private static float washout(XEl image) {
        float gain = image.attr("gain") == null ? 1 : fraction(image.attr("gain"));
        float black = image.attr("blacklevel") == null ? 0 : fraction(image.attr("blacklevel"));
        if (gain > 0 && gain < 1 && black > 0) {
            return Math.max(0.05f, gain);
        }
        return 1;
    }

    private static float fraction(String v) {
        if (v == null || v.isEmpty()) {
            return 0;
        }
        String s = v.trim();
        try {
            if (s.endsWith("f")) {
                return Float.parseFloat(s.substring(0, s.length() - 1)) / 65536f;
            }
            if (s.endsWith("%")) {
                return Float.parseFloat(s.substring(0, s.length() - 1)) / 100f;
            }
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Drawing.Graphic group(XEl g, float w, float h, int depth) {
        float[] size = numbers(g.attr("coordsize"), 1000, 1000);
        float[] origin = numbers(g.attr("coordorigin"), 0, 0);
        float sx = size[0] == 0 ? 1 : w / size[0];
        float sy = size[1] == 0 ? 1 : h / size[1];
        List<Drawing.Child> children = new ArrayList<>();
        for (XEl k : g.kids) {
            if (!isShape(k)) {
                continue;
            }
            Map<String, String> st = style(k.attr("style"));
            float cl = orZero(number(st.get("left")));
            float ct = orZero(number(st.get("top")));
            float cw = orZero(number(st.get("width")));
            float ch = orZero(number(st.get("height")));
            if (k.is("v:line")) {
                float[] from = numbers(k.attr("from"), 0, 0);
                float[] to = numbers(k.attr("to"), 0, 0);
                cl = Math.min(from[0], to[0]);
                ct = Math.min(from[1], to[1]);
                cw = Math.abs(to[0] - from[0]);
                ch = Math.abs(to[1] - from[1]);
            }
            float x = (cl - origin[0]) * sx;
            float y = (ct - origin[1]) * sy;
            Drawing.Graphic gr = graphic(k, st, cw * sx, ch * sy, depth + 1);
            if (gr != null) {
                children.add(new Drawing.Child(gr, x, y, cw * sx, ch * sy));
            }
        }
        return new Drawing.Group(children, 0, false, false, w, h);
    }

    private static float[] numbers(String v, float a, float b) {
        float[] out = {a, b};
        if (v == null) {
            return out;
        }
        String[] parts = v.split(",");
        for (int i = 0; i < Math.min(2, parts.length); i++) {
            Float f = number(parts[i]);
            if (f != null) {
                out[i] = f;
            }
        }
        return out;
    }
}
