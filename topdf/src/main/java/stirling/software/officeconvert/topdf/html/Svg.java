package stirling.software.officeconvert.topdf.html;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

import org.jsoup.nodes.Element;

/** A small SVG painter for the icons, logos and simple charts web pages draw inline: shapes, paths, groups,
 * transforms and same-document {@code <use>}. Nothing outside the document is ever read: external references,
 * images, scripts, foreign objects and filters are left out. Work and size are bounded. */
final class Svg {

    static final int MAX_ELEMENTS = 20_000;
    static final int MAX_PATH_CHARS = 400_000;
    static final int MAX_DEPTH = 48;
    static final int MAX_USE_DEPTH = 8;
    static final long MAX_PIXELS = 4_000_000L;

    // Work is path characters read plus pixels covered, so neither reused paths nor huge fills run away
    static final long MAX_WORK = 60_000_000L;

    /** A painted picture and the size it is shown at, in points. */
    record Painted(byte[] png, int pixelWidth, int pixelHeight, float width, float height, List<Label> labels) {}

    record Label(String text, float x, float baseline, float size, int rgb, boolean bold, String family, String anchor,
            float width) {}

    static final int MAX_LABELS = 400;

    private final Map<String, Element> ids;
    private int elements;
    private int drawn;
    private long[] budget;
    private long work;
    private double canvas;
    private float viewportW;
    private float viewportH;
    private List<Label> labels;
    private float pointsPerPixel;

    private Svg(Map<String, Element> ids) {
        this.ids = ids;
    }

    /** Paints {@code svg} at {@code width} x {@code height} points; {@code ids} resolves same-document references. */
    static Painted paint(Element svg, Map<String, Element> ids, float width, float height, int textColor) {
        return paint(svg, ids, width, height, textColor, new long[] {MAX_WORK});
    }

    /** Paints with {@code budget[0]} work left for the whole document, and takes what it uses from it. */
    static Painted paint(Element svg, Map<String, Element> ids, float width, float height, int textColor, long[] budget) {
        return paint(svg, ids, width, height, textColor, budget, false);
    }

    static Painted paint(Element svg, Map<String, Element> ids, float width, float height, int textColor, long[] budget,
            boolean labelled) {
        if (!(width > 0) || !(height > 0)) {
            return null;
        }
        float scale = 2f / 0.75f;
        long px = (long) Math.ceil(width * scale) * (long) Math.ceil(height * scale);
        if (px > MAX_PIXELS) {
            scale *= (float) Math.sqrt((double) MAX_PIXELS / px);
        }
        int w = Math.max(1, Math.round(width * scale));
        int h = Math.max(1, Math.round(height * scale));
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        List<Label> labels = labelled ? new ArrayList<>() : null;
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setTransform(viewBox(svg, w, h));
            Svg painter = new Svg(ids);
            painter.budget = budget;
            painter.labels = labels;
            painter.pointsPerPixel = width / w;
            painter.canvas = (double) w * h;
            float[] vb = numbers(svg.attr("viewbox"));
            float[] size = size(svg);
            painter.viewportW = vb.length == 4 ? vb[2] : size[0];
            painter.viewportH = vb.length == 4 ? vb[3] : size[1];
            painter.children(g, svg, Style.initial(textColor), 0, 0);
            if (painter.drawn == 0) {
                return null;
            }
        } catch (RuntimeException e) {
            return null;
        } finally {
            g.dispose();
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return new Painted(out.toByteArray(), w, h, width, height, labels == null ? List.of() : labels);
        } catch (IOException e) {
            return null;
        }
    }

    /** The size an svg element asks for, in CSS pixels: its width and height, else its viewBox. */
    static float[] size(Element svg) {
        float[] vb = numbers(svg.attr("viewbox"));
        float w = px(svg.attr("width"));
        float h = px(svg.attr("height"));
        if (vb.length == 4 && vb[2] > 0 && vb[3] > 0) {
            if (w <= 0 && h <= 0) {
                return new float[] {vb[2], vb[3]};
            }
            if (w <= 0) {
                w = h * vb[2] / vb[3];
            } else if (h <= 0) {
                h = w * vb[3] / vb[2];
            }
        }
        return new float[] {w > 0 ? w : 300, h > 0 ? h : 150};
    }

    private static float px(String v) {
        String s = v.strip().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.endsWith("%")) {
            return -1;
        }
        Float pt = Css.length(s, 12, 0);
        return pt == null ? -1 : pt / 0.75f;
    }

    private static AffineTransform viewBox(Element svg, int w, int h) {
        float[] vb = numbers(svg.attr("viewbox"));
        float[] size = size(svg);
        AffineTransform t = new AffineTransform();
        if (vb.length == 4 && vb[2] > 0 && vb[3] > 0) {
            float sx = w / vb[2];
            float sy = h / vb[3];
            String aspect = svg.attr("preserveaspectratio").strip().toLowerCase(Locale.ROOT);
            if (!aspect.startsWith("none")) {
                float s = Math.min(sx, sy);
                t.translate((w - vb[2] * s) / 2, (h - vb[3] * s) / 2);
                sx = s;
                sy = s;
            }
            t.scale(sx, sy);
            t.translate(-vb[0], -vb[1]);
        } else {
            t.scale(w / size[0], h / size[1]);
        }
        return t;
    }

    private record Style(Color fill, Color stroke, float strokeWidth, float opacity, boolean evenOdd, int color,
            float fontSize, String fontFamily, boolean bold, String anchor) {

        static final String INHERITED_FAMILY = "SansSerif";

        static Style initial(int color) {
            return new Style(Color.BLACK, null, 1, 1, false, color, 16, INHERITED_FAMILY, false, "start");
        }
    }

    private void children(Graphics2D g, Element parent, Style style, int depth, int useDepth) {
        for (Element c : parent.children()) {
            element(g, c, style, depth + 1, useDepth);
        }
    }

    private void element(Graphics2D g, Element e, Style parent, int depth, int useDepth) {
        if (++elements > MAX_ELEMENTS || depth > MAX_DEPTH || spent()
                || Thread.currentThread().isInterrupted()) {
            return;
        }
        String tag = e.normalName();
        int colon = tag.indexOf(':');
        if (colon >= 0) {
            tag = tag.substring(colon + 1);
        }
        if (tag.equals("defs") || tag.equals("symbol") && useDepth == 0 || tag.equals("clippath") || tag.equals("mask")
                || tag.equals("lineargradient") || tag.equals("radialgradient") || tag.equals("pattern")
                || tag.equals("title") || tag.equals("desc") || tag.equals("metadata") || tag.equals("style")
                || tag.equals("script") || tag.equals("foreignobject") || tag.equals("image") || tag.equals("filter")
                || tag.equals("marker")) {
            return;
        }
        Map<String, String> props = props(e);
        if ("none".equals(props.get("display")) || "hidden".equals(props.get("visibility"))) {
            return;
        }
        Style style = style(parent, props);
        AffineTransform saved = g.getTransform();
        java.awt.Composite composite = g.getComposite();
        try {
            AffineTransform own = transform(props.getOrDefault("transform", ""));
            if (own != null) {
                g.transform(own);
            }
            float groupOpacity = number(props.get("opacity"), 1f);
            if (groupOpacity < 1f) {
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, groupOpacity)));
            }
            switch (tag) {
                case "g", "a", "svg", "switch", "symbol" -> children(g, e, style, depth, useDepth);
                case "use" -> use(g, e, style, depth, useDepth);
                case "text" -> text(g, e, style);
                default -> {
                    if (tag.equals("path")) {
                        charge(e.attr("d").length());
                    }
                    draw(g, shape(tag, e), style);
                }
            }
        } finally {
            g.setTransform(saved);
            g.setComposite(composite);
        }
    }

    private void use(Graphics2D g, Element e, Style style, int depth, int useDepth) {
        String href = e.hasAttr("href") ? e.attr("href") : e.attr("xlink:href");
        if (!href.startsWith("#") || useDepth >= MAX_USE_DEPTH) {
            return;
        }
        Element target = ids.get(href.substring(1));
        if (target == null) {
            return;
        }
        g.translate(number(e.attr("x"), 0), number(e.attr("y"), 0));
        if (target.normalName().endsWith("symbol") && !target.attr("viewbox").isBlank()) {
            float[] vb = numbers(target.attr("viewbox"));
            // without a size of its own, a symbol fills the drawing it is used in
            float w = number(e.attr("width"), viewportW);
            float h = number(e.attr("height"), viewportH);
            if (vb.length == 4 && vb[2] > 0 && vb[3] > 0 && w > 0 && h > 0) {
                float s = Math.min(w / vb[2], h / vb[3]);
                g.scale(s, s);
                g.translate(-vb[0], -vb[1]);
            }
            children(g, target, style, depth, useDepth + 1);
        } else {
            element(g, target, style, depth, useDepth + 1);
        }
    }

    private void text(Graphics2D g, Element e, Style style) {
        String text = e.text().strip();
        if (text.isEmpty() || style.fill() == null) {
            return;
        }
        Font font = new Font(Look.family(style.fontFamily()) == null ? "SansSerif" : Look.family(style.fontFamily()),
                style.bold() ? Font.BOLD : Font.PLAIN, 1).deriveFont(Math.max(1f, style.fontSize()));
        g.setFont(font);
        float x = first(e.attr("x"));
        float y = first(e.attr("y"));
        float width = g.getFontMetrics().stringWidth(text);
        if (style.anchor().equals("middle")) {
            x -= width / 2;
        } else if (style.anchor().equals("end")) {
            x -= width;
        }
        y += shift(e.attr("dy"), style.fontSize());
        drawn++;
        if (label(g, text, x + width * anchorShare(style.anchor()), y, style, width)) {
            return;
        }
        g.setColor(style.fill());
        g.drawString(text, x, y);
    }

    private static float anchorShare(String anchor) {
        return switch (anchor) {
            case "middle" -> 0.5f;
            case "end" -> 1f;
            default -> 0f;
        };
    }

    private static float shift(String dy, float fontSize) {
        String v = dy.strip().toLowerCase(Locale.ROOT);
        if (v.endsWith("em")) {
            return number(v.substring(0, v.length() - 2), 0) * fontSize;
        }
        return v.isEmpty() || v.indexOf(' ') >= 0 || v.indexOf(',') >= 0 ? 0 : number(v, 0);
    }

    private boolean label(Graphics2D g, String text, float x, float y, Style style, float width) {
        AffineTransform t = g.getTransform();
        if (labels == null || labels.size() >= MAX_LABELS || Math.abs(t.getShearX()) > 1e-6
                || Math.abs(t.getShearY()) > 1e-6 || t.getScaleX() <= 0 || t.getScaleY() <= 0
                || Math.abs(t.getScaleX() / t.getScaleY() - 1) > 0.02 || style.fill().getAlpha() < 128
                || g.getComposite() instanceof AlphaComposite a && a.getAlpha() < 0.5f) {
            return false;
        }
        Point2D at = t.transform(new Point2D.Float(x, y), null);
        float scale = (float) t.getScaleY() * pointsPerPixel;
        String family = style.fontFamily().equals(Style.INHERITED_FAMILY) ? null : style.fontFamily();
        labels.add(new Label(text, (float) at.getX() * pointsPerPixel, (float) at.getY() * pointsPerPixel,
                style.fontSize() * scale, style.fill().getRGB() & 0xFFFFFF, style.bold(), family, style.anchor(),
                width * (float) t.getScaleX() * pointsPerPixel));
        return true;
    }

    private static float first(String v) {
        float[] n = numbers(v);
        return n.length == 0 ? 0 : n[0];
    }

    private boolean spent() {
        return work > MAX_WORK || budget[0] <= 0;
    }

    private void charge(long units) {
        work += units;
        budget[0] -= units;
    }

    private void draw(Graphics2D g, Shape shape, Style style) {
        if (shape == null || spent()) {
            return;
        }
        Rectangle2D box = g.getTransform().createTransformedShape(shape.getBounds2D()).getBounds2D();
        charge((long) Math.min(canvas, Math.max(1, box.getWidth() * box.getHeight())) / 16 + 1);
        drawn++;
        if (style.fill() != null) {
            if (shape instanceof Path2D p) {
                p.setWindingRule(style.evenOdd() ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
            }
            g.setColor(style.fill());
            g.fill(shape);
        }
        if (style.stroke() != null && style.strokeWidth() > 0) {
            g.setColor(style.stroke());
            g.setStroke(new BasicStroke(Math.min(style.strokeWidth(), 1000), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            g.draw(shape);
        }
    }

    private static Shape shape(String tag, Element e) {
        return switch (tag) {
            case "path" -> e.attr("d").length() > MAX_PATH_CHARS ? null : SvgPath.parse(e.attr("d"));
            case "rect" -> {
                float w = number(e.attr("width"), 0);
                float h = number(e.attr("height"), 0);
                float rx = number(e.attr("rx"), number(e.attr("ry"), 0));
                float ry = number(e.attr("ry"), rx);
                float x = number(e.attr("x"), 0);
                float y = number(e.attr("y"), 0);
                yield rx > 0 || ry > 0 ? new RoundRectangle2D.Float(x, y, w, h, 2 * rx, 2 * ry)
                        : new Rectangle2D.Float(x, y, w, h);
            }
            case "circle" -> {
                float r = number(e.attr("r"), 0);
                yield new Ellipse2D.Float(number(e.attr("cx"), 0) - r, number(e.attr("cy"), 0) - r, 2 * r, 2 * r);
            }
            case "ellipse" -> {
                float rx = number(e.attr("rx"), 0);
                float ry = number(e.attr("ry"), 0);
                yield new Ellipse2D.Float(number(e.attr("cx"), 0) - rx, number(e.attr("cy"), 0) - ry, 2 * rx, 2 * ry);
            }
            case "line" -> new Line2D.Float(number(e.attr("x1"), 0), number(e.attr("y1"), 0), number(e.attr("x2"), 0),
                    number(e.attr("y2"), 0));
            case "polyline", "polygon" -> poly(numbers(e.attr("points")), tag.equals("polygon"));
            default -> null;
        };
    }

    private static Shape poly(float[] p, boolean close) {
        if (p.length < 4) {
            return null;
        }
        Path2D.Float path = new Path2D.Float();
        path.moveTo(p[0], p[1]);
        for (int i = 2; i + 1 < p.length; i += 2) {
            path.lineTo(p[i], p[i + 1]);
        }
        if (close) {
            path.closePath();
        }
        return path;
    }

    // Presentation attributes, then the style attribute over them
    private static Map<String, String> props(Element e) {
        Map<String, String> out = new HashMap<>();
        for (String name : new String[] {"fill", "stroke", "stroke-width", "opacity", "fill-opacity", "stroke-opacity",
                "fill-rule", "display", "visibility", "transform", "color", "font-size", "font-family", "font-weight",
                "text-anchor"}) {
            if (e.hasAttr(name)) {
                out.put(name, e.attr(name).strip());
            }
        }
        if (e.hasAttr("style")) {
            out.putAll(Css.declarations(e.attr("style")));
            out.replaceAll((k, v) -> v.strip());
        }
        return out;
    }

    private Style style(Style p, Map<String, String> props) {
        int color = p.color();
        Integer c = Css.color(props.get("color"));
        if (c != null && c >= 0) {
            color = c;
        }
        Color fill = paint(props.get("fill"), p.fill(), color, number(props.get("fill-opacity"), 1f));
        Color stroke = paint(props.get("stroke"), p.stroke(), color, number(props.get("stroke-opacity"), 1f));
        float width = props.containsKey("stroke-width") ? number(props.get("stroke-width").replace("px", ""), 1)
                : p.strokeWidth();
        boolean evenOdd = props.containsKey("fill-rule") ? "evenodd".equals(props.get("fill-rule")) : p.evenOdd();
        Float size = props.containsKey("font-size") ? Css.length(props.get("font-size"), p.fontSize(), p.fontSize())
                : null;
        String weight = props.getOrDefault("font-weight", p.bold() ? "bold" : "normal");
        return new Style(fill, stroke, width, p.opacity(), evenOdd, color, size == null ? p.fontSize() : size / 0.75f,
                props.getOrDefault("font-family", p.fontFamily()),
                weight.equals("bold") || weight.matches("[6-9]00"), props.getOrDefault("text-anchor", p.anchor()));
    }

    private static Color paint(String v, Color inherited, int currentColor, float opacity) {
        if (v == null || v.equals("inherit")) {
            return withOpacity(inherited, opacity);
        }
        String s = v.toLowerCase(Locale.ROOT);
        if (s.equals("none") || s.startsWith("url(")) {
            // gradients and patterns are referenced by url(#id); a flat middle grey stands in better than nothing
            return s.equals("none") ? null : withOpacity(new Color(0x808080), opacity);
        }
        if (s.equals("currentcolor")) {
            return withOpacity(new Color(currentColor), opacity);
        }
        Integer c = Css.color(s);
        if (c == null) {
            return withOpacity(inherited, opacity);
        }
        return c < 0 ? null : withOpacity(new Color(c), opacity);
    }

    private static Color withOpacity(Color c, float opacity) {
        if (c == null || opacity >= 1f) {
            return c;
        }
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(255 * Math.max(0, Math.min(1, opacity))));
    }

    static AffineTransform transform(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        AffineTransform t = new AffineTransform();
        int i = 0;
        String s = v.toLowerCase(Locale.ROOT);
        int ops = 0;
        while (i < s.length() && ops++ < 64) {
            int open = s.indexOf('(', i);
            int close = s.indexOf(')', open + 1);
            if (open < 0 || close < 0) {
                break;
            }
            String name = s.substring(i, open).replace(",", " ").strip();
            float[] a = numbers(s.substring(open + 1, close));
            switch (name) {
                case "translate" -> t.translate(a.length > 0 ? a[0] : 0, a.length > 1 ? a[1] : 0);
                case "scale" -> {
                    float sx = a.length > 0 ? a[0] : 1;
                    t.scale(sx, a.length > 1 ? a[1] : sx);
                }
                case "rotate" -> {
                    if (a.length >= 3) {
                        t.rotate(Math.toRadians(a[0]), a[1], a[2]);
                    } else if (a.length == 1) {
                        t.rotate(Math.toRadians(a[0]));
                    }
                }
                case "matrix" -> {
                    if (a.length == 6) {
                        t.concatenate(new AffineTransform(a[0], a[1], a[2], a[3], a[4], a[5]));
                    }
                }
                case "skewx" -> t.shear(a.length > 0 ? Math.tan(Math.toRadians(a[0])) : 0, 0);
                case "skewy" -> t.shear(0, a.length > 0 ? Math.tan(Math.toRadians(a[0])) : 0);
                default -> { }
            }
            i = close + 1;
        }
        return t;
    }

    static float number(String v, float fallback) {
        if (v == null || v.isBlank()) {
            return fallback;
        }
        String s = v.strip().replace("px", "");
        try {
            float f = Float.parseFloat(s.endsWith("%") ? s.substring(0, s.length() - 1) : s);
            if (s.endsWith("%")) {
                f /= 100;
            }
            return Float.isFinite(f) ? f : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // At most 100,000 numbers, read without splitting the whole value first
    static float[] numbers(String v) {
        if (v == null || v.isBlank()) {
            return new float[0];
        }
        List<Float> out = new ArrayList<>();
        int i = 0;
        int n = v.length();
        while (i < n && out.size() < 100_000) {
            while (i < n && (Character.isWhitespace(v.charAt(i)) || v.charAt(i) == ',')) {
                i++;
            }
            int start = i;
            while (i < n && !Character.isWhitespace(v.charAt(i)) && v.charAt(i) != ',' && i - start < 64) {
                i++;
            }
            if (i > start) {
                try {
                    out.add(Float.parseFloat(v.substring(start, i)));
                } catch (NumberFormatException e) {
                    // skipped
                }
            }
        }
        float[] a = new float[out.size()];
        for (int k = 0; k < a.length; k++) {
            a[k] = out.get(k);
        }
        return a;
    }

}
