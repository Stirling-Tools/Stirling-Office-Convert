package stirling.software.officeconvert.topdf.html;

import java.awt.Color;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType2;
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType3;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDShadingPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType3;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;
import org.jsoup.nodes.Element;

import stirling.software.officeconvert.extract.PdfFiles;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.io.DecodedPicture;
import stirling.software.officeconvert.topdf.io.PictureDecoder;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.PdfOutput;

final class SvgDraw {

    static final int MAX_ELEMENTS = 200_000;
    static final int MAX_DEPTH = 64;
    static final int MAX_USE_DEPTH = 24;
    static final int MAX_GROUP_DEPTH = 24;
    static final long MAX_WORK = 400_000_000L;
    static final int MAX_PATH_CHARS = 8_000_000;
    static final float MAX_PAGE_PT = 14_400f;

    final SvgDoc doc;
    final PdfOutput output;
    final FontLibrary fonts;
    final List<String> warnings;
    PdfCanvas canvas;
    private final Deque<float[]> viewports = new ArrayDeque<>();
    private final Set<Element> active = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Element, SvgStyle> domStyles = new IdentityHashMap<>();
    static final class Budget {
        long elements;
        long work;
    }

    private Budget budget = new Budget();
    private int groups;
    private boolean externalSkipped;
    final SvgText text;
    Paint contextFill;
    Paint contextStroke;
    private AffineTransform rootView;
    int feImages;

    final java.util.Set<Element> activeFilters = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    List<float[]> recolour;
    private int markerLoops;

    record Paint(int rgb, float alpha, Element server, boolean none, String fallback, AffineTransform space,
            Rectangle2D box) {
        static final Paint NONE = new Paint(0, 0, null, true, null);

        Paint(int rgb, float alpha, Element server, boolean none) {
            this(rgb, alpha, server, none, null);
        }

        Paint(int rgb, float alpha, Element server, boolean none, String fallback) {
            this(rgb, alpha, server, none, fallback, null, null);
        }

        Paint inContext(AffineTransform at, Rectangle2D bounds) {
            if (server == null || space != null) {
                return this;
            }
            return new Paint(rgb, alpha, server, none, fallback, at, bounds);
        }
    }

    private AffineTransform rawOverride;

    private SvgDraw(SvgDoc doc, PdfOutput output, FontLibrary fonts, List<String> warnings) {
        this.doc = doc;
        this.output = output;
        this.fonts = fonts;
        this.warnings = warnings;
        this.text = new SvgText(this);
    }

    static float[] pageSize(Element root) {
        float[] vb = SvgDraw.numbers(SvgDoc.attr(root, "viewBox"));
        float w = length(SvgDoc.attr(root, "width"), 16, Float.NaN, Float.NaN);
        float h = length(SvgDoc.attr(root, "height"), 16, Float.NaN, Float.NaN);
        boolean box = vb.length == 4 && vb[2] > 0 && vb[3] > 0;
        if (!(w > 0) && !(h > 0)) {
            w = box ? vb[2] : 300;
            h = box ? vb[3] : 150;
        } else if (!(w > 0)) {
            w = box ? h * vb[2] / vb[3] : 300;
        } else if (!(h > 0)) {
            h = box ? w * vb[3] / vb[2] : 150;
        }
        return new float[] {w, h};
    }

    static void render(SvgDoc doc, PdfOutput output, FontLibrary fonts, List<String> warnings) throws IOException {
        float[] px = pageSize(doc.root);
        float scale = 0.75f;
        float w = px[0] * scale;
        float h = px[1] * scale;
        float big = Math.max(w, h);
        if (big > MAX_PAGE_PT) {
            scale *= MAX_PAGE_PT / big;
            w = px[0] * scale;
            h = px[1] * scale;
        }
        w = Math.max(1, w);
        h = Math.max(1, h);
        SvgDraw d = new SvgDraw(doc, output, withWebFonts(fonts, doc, warnings), warnings);
        try (PdfCanvas page = output.newPage(w, h)) {
            d.canvas = page;
            page.scale(scale, scale);
            d.root(doc.root, px[0], px[1]);
        }
        if (d.externalSkipped) {
            warnings.add("Pictures and other files the SVG drawing links to were left out: only data: URIs and the"
                    + " drawing itself are read");
        }
    }

    private static FontLibrary withWebFonts(FontLibrary fonts, SvgDoc doc, List<String> warnings) {
        if (doc.css.fontFaces.isEmpty()) {
            return fonts;
        }
        List<WebFonts.Face> faces = new ArrayList<>();
        for (Map<String, String> f : doc.css.fontFaces) {
            String src = f.get("src");
            if (src == null) {
                continue;
            }
            List<String> kept = new ArrayList<>();
            for (String part : Css.splitTopLevel(src, ',')) {
                String url = Css.firstUrl(part);
                if (url != null && url.regionMatches(true, 0, "data:", 0, 5)) {
                    kept.add(part);
                }
            }
            if (kept.isEmpty()) {
                continue;
            }
            Map<String, String> only = new java.util.HashMap<>(f);
            only.put("src", String.join(",", kept));
            faces.add(new WebFonts.Face(only, ""));
        }
        try {
            WebFonts.Loaded loaded = WebFonts.load(faces, null, warnings);
            return loaded.fonts().isEmpty() ? fonts : fonts.withFonts(loaded.fonts());
        } catch (IOException | RuntimeException e) {
            return fonts;
        }
    }

    private void root(Element svg, float width, float height) throws IOException {
        SvgStyle style = SvgStyle.root().child(doc.specified(svg));
        if (style.own("display", "inline").equalsIgnoreCase("none") || !conditionsPass(svg)) {
            return;
        }
        String background = style.own("background-color", "");
        Integer bg = background.isEmpty() ? null : Css.color(background);
        if (bg != null && bg >= 0) {
            Path2D.Float all = new Path2D.Float();
            all.moveTo(0, 0);
            all.lineTo(width, 0);
            all.lineTo(width, height);
            all.lineTo(0, height);
            all.closePath();
            fill(all, new Paint(bg, Css.alpha(background), null, false), 1, null, style);
        }
        float[] vb = numbers(SvgDoc.attr(svg, "viewBox"));
        AffineTransform t = viewBox(vb, SvgDoc.attr(svg, "preserveAspectRatio"), 0, 0, width, height);
        viewports.push(vb.length == 4 && vb[2] > 0 && vb[3] > 0 ? new float[] {vb[2], vb[3]}
                : new float[] {width, height});
        canvas.save();
        try {
            rootView = t;
            rootElement = svg;
            rootSize = new float[] {width, height};
            render(svg, style, "svg", 0, 0);
        } finally {
            canvas.restore();
            viewports.pop();
        }
    }


    static float number(String v, float fallback) {
        if (v == null) {
            return fallback;
        }
        String s = v.strip();
        if (s.isEmpty()) {
            return fallback;
        }
        try {
            float f = Float.parseFloat(s);
            return Float.isFinite(f) ? f : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static float[] numbers(String v) {
        return Svg.numbers(v == null ? "" : v.replace("-", " -").replace("e -", "e-").replace("E -", "E-"));
    }

    static float length(String v, float fontSize, float percent, float fallback) {
        if (v == null) {
            return fallback;
        }
        String s = v.strip().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("auto")) {
            return fallback;
        }
        int unit = s.length();
        while (unit > 0 && Character.isLetter(s.charAt(unit - 1)) || unit > 0 && s.charAt(unit - 1) == '%') {
            unit--;
            if (s.charAt(unit) == '%') {
                break;
            }
        }
        float n;
        try {
            n = Float.parseFloat(s.substring(0, unit).strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
        if (!Float.isFinite(n)) {
            return fallback;
        }
        float r = switch (s.substring(unit)) {
            case "", "px" -> n;
            case "pt" -> n * 4f / 3f;
            case "pc" -> n * 16f;
            case "mm" -> n * 96f / 25.4f;
            case "cm" -> n * 96f / 2.54f;
            case "in" -> n * 96f;
            case "q" -> n * 96f / 101.6f;
            case "em" -> n * fontSize;
            case "rem" -> n * 16f;
            case "ex" -> n * fontSize / 2;
            case "ch" -> n * fontSize / 2;
            case "%" -> n * percent / 100f;
            default -> Float.NaN;
        };
        return Float.isFinite(r) ? r : fallback;
    }

    float vw() {
        return viewports.isEmpty() ? 100 : viewports.peek()[0];
    }

    float vh() {
        return viewports.isEmpty() ? 100 : viewports.peek()[1];
    }

    float diagonal() {
        float w = vw();
        float h = vh();
        return (float) Math.sqrt((w * w + h * h) / 2);
    }

    float x(Element e, String name, SvgStyle s, float fallback) {
        return length(SvgDoc.attr(e, name), s.fontSize, vw(), fallback);
    }

    float y(Element e, String name, SvgStyle s, float fallback) {
        return length(SvgDoc.attr(e, name), s.fontSize, vh(), fallback);
    }

    float d(Element e, String name, SvgStyle s, float fallback) {
        return length(SvgDoc.attr(e, name), s.fontSize, diagonal(), fallback);
    }


    AffineTransform transform(SvgStyle s, Element e) {
        String v = s.own.get("transform");
        if (v == null || v.isBlank() || v.equalsIgnoreCase("none")) {
            return null;
        }
        AffineTransform t = parseTransform(v);
        if (t == null) {
            return null;
        }
        if (e == rootElement && rootSize != null) {
            String origin = s.own.get("transform-origin");
            viewports.push(rootSize);
            try {
                return withOrigin(t, origin == null || origin.isBlank() ? "center" : origin, s.fontSize);
            } finally {
                viewports.pop();
            }
        }
        return withOrigin(t, s.own.get("transform-origin"), s.fontSize);
    }

    private Element rootElement;
    private float[] rootSize;

    AffineTransform withOrigin(AffineTransform t, String origin, float fontSize) {
        if (origin == null || origin.isBlank()) {
            return t;
        }
        float[] o = originOf(origin, fontSize);
        if (o[0] == 0 && o[1] == 0) {
            return t;
        }
        AffineTransform out = AffineTransform.getTranslateInstance(o[0], o[1]);
        out.concatenate(t);
        out.translate(-o[0], -o[1]);
        return out;
    }

    private float[] originOf(String v, float fontSize) {
        String[] parts = v.strip().toLowerCase(Locale.ROOT).split("[\\s,]+");
        String x = parts[0];
        String y = parts.length > 1 ? parts[1] : null;
        boolean swap = y == null
                ? x.equals("top") || x.equals("bottom")
                : x.equals("top") || x.equals("bottom") || y.equals("left") || y.equals("right");
        if (swap) {
            String t = x;
            x = y;
            y = t;
        }
        return new float[] {originPart(x, fontSize, vw()), originPart(y, fontSize, vh())};
    }

    private static float originPart(String p, float fontSize, float size) {
        if (p == null) {
            return size / 2;
        }
        return switch (p) {
            case "left", "top" -> 0;
            case "center" -> size / 2;
            case "right", "bottom" -> size;
            default -> length(p, fontSize, size, 0);
        };
    }

    static AffineTransform parseTransform(String v) {
        AffineTransform t = new AffineTransform();
        String s = v.toLowerCase(Locale.ROOT);
        int i = 0;
        int ops = 0;
        while (i < s.length() && ops++ < 256) {
            int open = s.indexOf('(', i);
            int close = s.indexOf(')', open + 1);
            if (open < 0 || close < 0) {
                break;
            }
            String name = s.substring(i, open).replace(",", " ").strip();
            float[] a = numbers(s.substring(open + 1, close).replace("deg", "").replace("px", ""));
            switch (name) {
                case "translate" -> {
                    if (a.length < 1 || a.length > 2) {
                        return null;
                    }
                    t.translate(a[0], a.length > 1 ? a[1] : 0);
                }
                case "translatex" -> t.translate(a.length > 0 ? a[0] : 0, 0);
                case "translatey" -> t.translate(0, a.length > 0 ? a[0] : 0);
                case "scale" -> {
                    if (a.length < 1 || a.length > 2) {
                        return null;
                    }
                    t.scale(a[0], a.length > 1 ? a[1] : a[0]);
                }
                case "rotate" -> {
                    if (a.length == 3) {
                        t.rotate(Math.toRadians(a[0]), a[1], a[2]);
                    } else if (a.length == 1) {
                        t.rotate(Math.toRadians(a[0]));
                    } else {
                        return null;
                    }
                }
                case "matrix" -> {
                    if (a.length != 6) {
                        return null;
                    }
                    t.concatenate(new AffineTransform(a[0], a[1], a[2], a[3], a[4], a[5]));
                }
                case "skewx" -> t.shear(a.length > 0 ? Math.tan(Math.toRadians(a[0])) : 0, 0);
                case "skewy" -> t.shear(0, a.length > 0 ? Math.tan(Math.toRadians(a[0])) : 0);
                default -> {
                    return null;
                }
            }
            i = close + 1;
            while (i < s.length() && (s.charAt(i) == ',' || Character.isWhitespace(s.charAt(i)))) {
                i++;
            }
        }
        if (i < s.length() && !s.substring(i).isBlank()) {
            return null;
        }
        return t;
    }

    static AffineTransform viewBox(float[] vb, String aspect, float x, float y, float w, float h) {
        if (vb.length != 4 || !(vb[2] > 0) || !(vb[3] > 0)) {
            return null;
        }
        AffineTransform t = new AffineTransform();
        String a = aspect == null ? "" : aspect.strip().toLowerCase(Locale.ROOT);
        if (a.startsWith("defer")) {
            a = a.substring(5).strip();
        }
        float sx = w / vb[2];
        float sy = h / vb[3];
        if (a.startsWith("none")) {
            t.translate(x, y);
            t.scale(sx, sy);
            t.translate(-vb[0], -vb[1]);
            return t;
        }
        boolean slice = a.endsWith("slice");
        float scale = slice ? Math.max(sx, sy) : Math.min(sx, sy);
        String align = a.isEmpty() ? "xmidymid" : a.split("\\s+")[0];
        float fx = align.contains("xmin") ? 0 : align.contains("xmax") ? 1 : 0.5f;
        float fy = align.contains("ymin") ? 0 : align.contains("ymax") ? 1 : 0.5f;
        t.translate(x + (w - vb[2] * scale) * fx, y + (h - vb[3] * scale) * fy);
        t.scale(scale, scale);
        t.translate(-vb[0], -vb[1]);
        return t;
    }


    SvgStyle domStyle(Element e) {
        SvgStyle known = domStyles.get(e);
        if (known != null) {
            return known;
        }
        Element p = e.parent();
        SvgStyle parent = p == null || p.parent() == null ? SvgStyle.root() : domStyle(p);
        SvgStyle s = parent.child(doc.specified(e));
        domStyles.put(e, s);
        return s;
    }

    void charge(long units) {
        budget.work += Math.max(0, units);
    }

    boolean spent() {
        return budget.work > MAX_WORK || budget.elements > MAX_ELEMENTS;
    }

    void group(Element parent, SvgStyle style, int depth, int useDepth, Element skip) throws IOException {
        for (Element c : parent.children()) {
            if (c != skip) {
                element(c, style, depth + 1, useDepth);
            }
        }
    }

    static boolean conditionsPass(Element e) {
        if (SvgDoc.has(e, "requiredExtensions")) {
            return false;
        }
        if (SvgDoc.has(e, "systemLanguage")) {
            String langs = SvgDoc.attr(e, "systemLanguage").toLowerCase(Locale.ROOT);
            boolean ok = false;
            for (String l : langs.split(",")) {
                String t = l.strip();
                if (t.equals("en") || t.startsWith("en-")) {
                    ok = true;
                }
            }
            return ok;
        }
        return true;
    }

    void element(Element e, SvgStyle parent, int depth, int useDepth) throws IOException {
        if (++budget.elements > MAX_ELEMENTS || depth > MAX_DEPTH || spent()) {
            return;
        }
        if ((budget.elements & 255) == 0) {
            PdfFiles.stopIfInterrupted();
        }
        String tag = SvgDoc.local(e);
        switch (tag) {
            case "g", "a", "svg", "switch", "use", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon",
                    "text", "image" -> { }
            default -> {
                return;
            }
        }
        if (!conditionsPass(e)) {
            return;
        }
        SvgStyle s = parent.child(doc.specified(e));
        if (s.own("display", "inline").equalsIgnoreCase("none")) {
            return;
        }
        render(e, s, tag, depth, useDepth);
    }

    private void render(Element e, SvgStyle s, String tag, int depth, int useDepth) throws IOException {
        AffineTransform own = transform(s, e);
        if (own != null && Math.abs(own.getDeterminant()) < 1e-12) {
            return;
        }
        float opacity = Css.opacity(s.own("opacity", "1"));
        String maskRef = s.own("mask", "none");
        String filterRef = s.own("filter", "none");
        String clipRef = s.own("clip-path", "none");
        String blend = s.own("mix-blend-mode", "normal").toLowerCase(Locale.ROOT);
        boolean isolate = s.own("isolation", "auto").equalsIgnoreCase("isolate");
        if (opacity <= 0) {
            return;
        }
        Element mask = maskRef.equalsIgnoreCase("none") ? null : doc.byUrl(maskRef);
        if (mask != null && !SvgDoc.local(mask).equals("mask")) {
            mask = null;
            return;
        }
        if (mask != null && active.contains(mask)) {
            mask = null;
        }
        int filterState = filterRef.equalsIgnoreCase("none") || filterRef.isBlank() ? 0
                : SvgFilter.state(this, filterRef);
        if (filterState < 0) {
            return;
        }
        boolean filtered = filterState > 0 && feImages < SvgFilter.MAX_IMAGE_DEPTH;
        Element clip = clipRef.equalsIgnoreCase("none") ? null : doc.byUrl(clipRef);
        if (clip != null && !SvgDoc.local(clip).equals("clippath")) {
            return;
        }
        canvas.save();
        try {
            if (own != null) {
                canvas.transform(own);
            }
            Rectangle2D bbox = null;
            Shape clipShape = null;
            boolean clipAsMask = false;
            if (clip != null) {
                bbox = bbox(e, s, tag, 0);
                clipShape = clipShape(clip, bbox, 0);
                if (clipShape == null) {
                    clipAsMask = true;
                } else if (clipShape.getBounds2D().isEmpty()) {
                    return;
                }
            } else if (!clipRef.equalsIgnoreCase("none") && clipRef.toLowerCase(Locale.ROOT).startsWith("inset(")
                    || clipRef.toLowerCase(Locale.ROOT).startsWith("circle(")) {
                clipShape = basicShape(clipRef, e, s, tag);
            }
            boolean shape = isShape(tag);
            boolean groupOpacity = opacity < 1 && !(shape && simpleOpacity(e, s, tag));
            boolean composite = groupOpacity || mask != null || filtered || !blend.equals("normal") || isolate
                    || clipAsMask;
            if (!composite) {
                if (clipShape != null) {
                    canvas.clip(clipShape);
                }
                content(e, s, tag, depth, useDepth, opacity < 1 ? opacity : 1);
                return;
            }
            if (groups >= MAX_GROUP_DEPTH) {
                content(e, s, tag, depth, useDepth, 1);
                return;
            }
            if (bbox == null && (mask != null || filtered || clipAsMask)) {
                bbox = bbox(e, s, tag, 0);
            }
            Rectangle2D box = bbox;
            if (clipShape != null) {
                canvas.clip(clipShape);
            }
            Element maskEl = mask;
            Element clipEl = clipAsMask ? clip : null;
            composite(() -> {
                if (filtered) {
                    SvgFilter.apply(this, filterRef, e, s, box, () -> content(e, s, tag, depth, useDepth, 1));
                } else {
                    content(e, s, tag, depth, useDepth, 1);
                }
            }, opacity, blend, maskEl, clipEl, e, s, box);
        } finally {
            canvas.restore();
        }
    }

    private Shape basicShape(String v, Element e, SvgStyle s, String tag) {
        Rectangle2D b = bbox(e, s, tag, 0);
        if (b == null) {
            return null;
        }
        String l = v.strip().toLowerCase(Locale.ROOT);
        int open = l.indexOf('(');
        int close = l.lastIndexOf(')');
        if (open < 0 || close < open) {
            return null;
        }
        String[] p = l.substring(open + 1, close).strip().split("\\s+");
        if (l.startsWith("inset(")) {
            String pt = p[0];
            String pr = p.length > 1 ? p[1] : pt;
            String pb = p.length > 2 ? p[2] : pt;
            String pl = p.length > 3 ? p[3] : pr;
            float t = length(pt, s.fontSize, (float) b.getHeight(), 0);
            float r = length(pr, s.fontSize, (float) b.getWidth(), 0);
            float bo = length(pb, s.fontSize, (float) b.getHeight(), 0);
            float le = length(pl, s.fontSize, (float) b.getWidth(), 0);
            return new Rectangle2D.Double(b.getX() + le, b.getY() + t, b.getWidth() - le - r, b.getHeight() - t - bo);
        }
        if (l.startsWith("circle(")) {
            float r = length(p[0], s.fontSize, (float) Math.hypot(b.getWidth(), b.getHeight()) / (float) Math.sqrt(2),
                    (float) Math.min(b.getWidth(), b.getHeight()) / 2);
            double cx = b.getCenterX();
            double cy = b.getCenterY();
            return new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r);
        }
        return null;
    }

    private static boolean isShape(String tag) {
        return switch (tag) {
            case "path", "rect", "circle", "ellipse", "line", "polyline", "polygon", "image" -> true;
            default -> false;
        };
    }

    private boolean simpleOpacity(Element e, SvgStyle s, String tag) {
        if (tag.equals("image")) {
            return true;
        }
        boolean fill = !paint(s.get("fill"), s).none();
        boolean stroke = !paint(s.get("stroke"), s).none() && strokeWidth(s) > 0;
        boolean markers = isShape(tag)
                && (!s.get("marker-start").equalsIgnoreCase("none") || !s.get("marker-mid").equalsIgnoreCase("none")
                        || !s.get("marker-end").equalsIgnoreCase("none"));
        return !(fill && stroke) && !markers;
    }

    @FunctionalInterface
    interface Content {
        void draw() throws IOException;
    }

    private static final Set<String> SWITCHABLE = Set.of("path", "rect", "circle", "ellipse", "line", "polyline",
            "polygon", "text", "g", "svg", "use", "image", "switch", "a", "foreignobject");

    private void content(Element e, SvgStyle s, String tag, int depth, int useDepth, float alpha) throws IOException {
        switch (tag) {
            case "g", "a" -> group(e, s, depth, useDepth, null);
            case "switch" -> {
                for (Element c : e.children()) {
                    String t = SvgDoc.local(c);
                    if (conditionsPass(c) && SWITCHABLE.contains(t)) {
                        element(c, s, depth + 1, useDepth);
                        break;
                    }
                }
            }
            case "svg" -> {
                if (e == doc.root) {
                    AffineTransform view = rootView;
                    rootView = null;
                    if (view != null) {
                        canvas.transform(view);
                    }
                    group(e, s, depth, useDepth, null);
                } else {
                    nested(e, s, depth, useDepth, Float.NaN, Float.NaN);
                }
            }
            case "use" -> use(e, s, depth, useDepth);
            case "text" -> text.draw(e, s, alpha);
            case "image" -> image(e, s, alpha);
            default -> shape(e, s, tag, alpha);
        }
    }

    void composite(Content content, float opacity, String blend, Element mask, Element clipMask, Element e,
            SvgStyle s, Rectangle2D bbox) throws IOException {
        AffineTransform ctm = canvas.transform();
        PDFormXObject form = capture(content, ctm);
        if (form == null) {
            return;
        }
        PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
        boolean any = false;
        if (opacity < 1) {
            gs.setNonStrokingAlphaConstant(opacity);
            gs.setStrokingAlphaConstant(opacity);
            any = true;
        }
        String bm = blendName(blend);
        if (bm != null) {
            gs.getCOSObject().setName(COSName.BM, bm);
            any = true;
        }
        PDFormXObject maskForm = null;
        boolean alphaMask = false;
        if (mask != null) {
            int loops = markerLoops;
            maskForm = maskForm(mask, e, s, bbox, ctm);
            alphaMask = SvgDoc.lower(domStyle(mask).own("mask-type", SvgDoc.attr(mask, "mask-type"))).equals("alpha");
            if (markerLoops > loops) {
                maskForm = null;
            } else if (maskForm == null) {
                return;
            }
        }
        if (clipMask != null) {
            PDFormXObject clipForm = clipMaskForm(clipMask, bbox, ctm);
            if (clipForm == null) {
                return;
            }
            if (maskForm != null) {
                PDFormXObject inner = form;
                PDExtendedGraphicsState innerGs = smaskState(clipForm, true);
                form = capture(() -> {
                    PDPageContentStream cs = canvas.stream();
                    cs.saveGraphicsState();
                    cs.transform(new Matrix(rawInverse()));
                    cs.setGraphicsStateParameters(innerGs);
                    cs.drawForm(inner);
                    cs.restoreGraphicsState();
                }, ctm);
                if (form == null) {
                    return;
                }
            } else {
                maskForm = clipForm;
                alphaMask = true;
            }
        }
        if (maskForm != null) {
            COSDictionary smask = smaskDict(maskForm, alphaMask);
            gs.getCOSObject().setItem(COSName.SMASK, smask);
            any = true;
        }
        PDPageContentStream cs = canvas.stream();
        cs.saveGraphicsState();
        try {
            cs.transform(new Matrix(rawInverse()));
            if (any) {
                cs.setGraphicsStateParameters(gs);
            }
            cs.drawForm(form);
        } finally {
            cs.restoreGraphicsState();
        }
    }

    private PDExtendedGraphicsState smaskState(PDFormXObject maskForm, boolean alpha) {
        PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
        gs.getCOSObject().setItem(COSName.SMASK, smaskDict(maskForm, alpha));
        return gs;
    }

    private static COSDictionary smaskDict(PDFormXObject maskForm, boolean alpha) {
        COSDictionary smask = new COSDictionary();
        smask.setItem(COSName.TYPE, COSName.MASK);
        smask.setItem(COSName.S, alpha ? COSName.ALPHA : COSName.LUMINOSITY);
        smask.setItem(COSName.G, maskForm);
        return smask;
    }

    AffineTransform rawInverse() {
        AffineTransform raw = raw();
        try {
            return raw.createInverse();
        } catch (NoninvertibleTransformException e) {
            return new AffineTransform();
        }
    }

    AffineTransform raw() {
        if (rawOverride != null) {
            return new AffineTransform(rawOverride);
        }
        AffineTransform raw = new AffineTransform(1, 0, 0, -1, 0, canvas.height());
        raw.concatenate(canvas.transform());
        return raw;
    }

    PDFormXObject capture(Content content, AffineTransform ctm) throws IOException {
        PdfCanvas saved = canvas;
        PdfCanvas form = output.newForm(saved.width(), saved.height(), Math.max(saved.width(), saved.height()));
        canvas = form;
        groups++;
        try {
            form.transform(ctm);
            content.draw();
        } finally {
            groups--;
            canvas = saved;
            form.close();
        }
        PDFormXObject x = form.form();
        COSDictionary group = new COSDictionary();
        group.setItem(COSName.TYPE, COSName.GROUP);
        group.setItem(COSName.S, COSName.TRANSPARENCY);
        group.setItem(COSName.CS, COSName.DEVICERGB);
        group.setBoolean(COSName.I, true);
        x.getCOSObject().setItem(COSName.GROUP, group);
        return x;
    }

    private static String blendName(String blend) {
        return switch (blend) {
            case "multiply" -> "Multiply";
            case "screen" -> "Screen";
            case "overlay" -> "Overlay";
            case "darken" -> "Darken";
            case "lighten" -> "Lighten";
            case "color-dodge" -> "ColorDodge";
            case "color-burn" -> "ColorBurn";
            case "hard-light" -> "HardLight";
            case "soft-light" -> "SoftLight";
            case "difference" -> "Difference";
            case "exclusion" -> "Exclusion";
            case "hue" -> "Hue";
            case "saturation" -> "Saturation";
            case "color" -> "Color";
            case "luminosity" -> "Luminosity";
            default -> null;
        };
    }


    private PDFormXObject maskForm(Element mask, Element target, SvgStyle targetStyle, Rectangle2D bbox,
            AffineTransform ctm) throws IOException {
        if (groups >= MAX_GROUP_DEPTH || !active.add(mask)) {
            return null;
        }
        try {
            boolean userUnits = SvgDoc.lower(SvgDoc.attr(mask, "maskUnits")).equals("userspaceonuse");
            boolean contentBox = SvgDoc.lower(SvgDoc.attr(mask, "maskContentUnits")).equals("objectboundingbox");
            SvgStyle ms = domStyle(mask);
            Rectangle2D region;
            if (userUnits) {
                region = new Rectangle2D.Double(x(mask, "x", ms, -0.1f * vw()), y(mask, "y", ms, -0.1f * vh()),
                        x(mask, "width", ms, 1.2f * vw()), y(mask, "height", ms, 1.2f * vh()));
            } else {
                if (bbox == null || bbox.getWidth() <= 0 || bbox.getHeight() <= 0) {
                    return null;
                }
                region = new Rectangle2D.Double(bbox.getX() + fraction(mask, "x", -0.1f) * bbox.getWidth(),
                        bbox.getY() + fraction(mask, "y", -0.1f) * bbox.getHeight(),
                        fraction(mask, "width", 1.2f) * bbox.getWidth(),
                        fraction(mask, "height", 1.2f) * bbox.getHeight());
            }
            if (region.getWidth() <= 0 || region.getHeight() <= 0) {
                return null;
            }
            if (contentBox && (bbox == null || bbox.getWidth() <= 0 || bbox.getHeight() <= 0)) {
                return null;
            }
            String nestedRef = ms.own("mask", "none");
            Element nested = nestedRef.equalsIgnoreCase("none") ? null : doc.byUrl(nestedRef);
            PDFormXObject form = capture(() -> {
                canvas.clip(region);
                if (contentBox) {
                    canvas.translate((float) bbox.getX(), (float) bbox.getY());
                    canvas.scale((float) bbox.getWidth(), (float) bbox.getHeight());
                }
                if (nested != null && SvgDoc.local(nested).equals("mask")) {
                    composite(() -> group(mask, ms, 0, 0, null), 1, "normal", nested, null, target, targetStyle,
                            bbox);
                } else {
                    group(mask, ms, 0, 0, null);
                }
            }, ctm);
            return form;
        } finally {
            active.remove(mask);
        }
    }

    private static float fraction(Element e, String name, float fallback) {
        String v = SvgDoc.attr(e, name).strip();
        if (v.isEmpty()) {
            return fallback;
        }
        if (v.endsWith("%")) {
            return number(v.substring(0, v.length() - 1), fallback * 100) / 100f;
        }
        return number(v, fallback);
    }

    private PDFormXObject clipMaskForm(Element clip, Rectangle2D bbox, AffineTransform ctm) throws IOException {
        if (!active.add(clip)) {
            return null;
        }
        try {
            boolean box = SvgDoc.lower(SvgDoc.attr(clip, "clipPathUnits")).equals("objectboundingbox");
            if (box && (bbox == null || bbox.getWidth() <= 0 || bbox.getHeight() <= 0)) {
                return null;
            }
            SvgStyle cs = domStyle(clip);
            return capture(() -> {
                AffineTransform own = transform(cs, clip);
                if (own != null) {
                    canvas.transform(own);
                }
                if (box) {
                    canvas.translate((float) bbox.getX(), (float) bbox.getY());
                    canvas.scale((float) bbox.getWidth(), (float) bbox.getHeight());
                }
                Paint saveFill = contextFill;
                for (Element c : clip.children()) {
                    clipChild(c, cs);
                }
                contextFill = saveFill;
            }, ctm);
        } finally {
            active.remove(clip);
        }
    }

    private void clipChild(Element c, SvgStyle parent) throws IOException {
        String tag = SvgDoc.local(c);
        if (!conditionsPass(c)) {
            return;
        }
        SvgStyle s = parent.child(doc.specified(c));
        if (s.own("display", "inline").equalsIgnoreCase("none") || !s.visible()) {
            return;
        }
        Element target = c;
        AffineTransform own = transform(s, c);
        canvas.save();
        try {
            if (own != null) {
                canvas.transform(own);
            }
            if (tag.equals("use")) {
                target = doc.target(c);
                if (target == null) {
                    return;
                }
                canvas.translate(x(c, "x", s, 0), y(c, "y", s, 0));
                tag = SvgDoc.local(target);
                s = s.child(doc.specified(target));
                AffineTransform t2 = transform(s, target);
                if (t2 != null) {
                    canvas.transform(t2);
                }
            }
            Shape g = tag.equals("text") ? text.outline(target, s) : geometry(target, s, tag);
            if (g == null) {
                return;
            }
            String clipRef = s.own("clip-path", "none");
            Element inner = clipRef.equalsIgnoreCase("none") ? null : doc.byUrl(clipRef);
            if (inner != null && SvgDoc.local(inner).equals("clippath")) {
                Shape cs = clipShape(inner, g.getBounds2D(), 1);
                if (cs != null) {
                    canvas.clip(cs);
                }
            }
            Path2D p = new Path2D.Double(g);
            p.setWindingRule(SvgDoc.lower(s.get("clip-rule")).equals("evenodd") ? Path2D.WIND_EVEN_ODD
                    : Path2D.WIND_NON_ZERO);
            PDPageContentStream cs = canvas.stream();
            cs.saveGraphicsState();
            cs.setNonStrokingColor(1f);
            boolean eo = append(cs, p);
            if (eo) {
                cs.fillEvenOdd();
            } else {
                cs.fill();
            }
            cs.restoreGraphicsState();
        } finally {
            canvas.restore();
        }
    }

    Shape clipShape(Element clip, Rectangle2D bbox, int depth) {
        if (depth > 8 || !active.add(clip)) {
            return null;
        }
        try {
            boolean box = SvgDoc.lower(SvgDoc.attr(clip, "clipPathUnits")).equals("objectboundingbox");
            if (box && (bbox == null || bbox.getWidth() <= 0 || bbox.getHeight() <= 0)) {
                return new Rectangle2D.Double();
            }
            SvgStyle cs = domStyle(clip);
            AffineTransform base = new AffineTransform();
            AffineTransform own = transform(cs, clip);
            if (own != null) {
                base.concatenate(own);
            }
            if (box) {
                base.translate(bbox.getX(), bbox.getY());
                base.scale(bbox.getWidth(), bbox.getHeight());
            }
            Area union = null;
            Shape single = null;
            int count = 0;
            for (Element c : clip.children()) {
                String tag = SvgDoc.local(c);
                if (!conditionsPass(c)) {
                    continue;
                }
                SvgStyle s = cs.child(doc.specified(c));
                if (s.own("display", "inline").equalsIgnoreCase("none") || !s.visible()) {
                    continue;
                }
                if (!isShape(tag) && !tag.equals("text") && !tag.equals("use") || tag.equals("image")) {
                    continue;
                }
                AffineTransform t = new AffineTransform(base);
                AffineTransform ct = transform(s, c);
                if (ct != null) {
                    t.concatenate(ct);
                }
                Element target = c;
                if (tag.equals("use")) {
                    target = doc.target(c);
                    if (target == null) {
                        continue;
                    }
                    t.translate(x(c, "x", s, 0), y(c, "y", s, 0));
                    tag = SvgDoc.local(target);
                    if (!isShape(tag) && !tag.equals("text") || tag.equals("image")) {
                        continue;
                    }
                    s = s.child(doc.specified(target));
                    AffineTransform tt = transform(s, target);
                    if (tt != null) {
                        t.concatenate(tt);
                    }
                }
                String childRef = s.own("clip-path", "none");
                Element childClip = childRef.equalsIgnoreCase("none") ? null : doc.byUrl(childRef);
                if (!childRef.equalsIgnoreCase("none")
                        && (childClip == null || !SvgDoc.local(childClip).equals("clippath"))) {
                    return null;
                }
                Shape g = tag.equals("text") ? text.outline(target, s) : geometry(target, s, tag);
                if (g == null) {
                    continue;
                }
                Shape shape = g;
                if (childClip != null && !active.contains(childClip)) {
                    Shape cut = clipShape(childClip, g.getBounds2D(), depth + 1);
                    if (cut == null) {
                        return null;
                    }
                    Path2D.Double own2 = new Path2D.Double(g);
                    own2.setWindingRule(SvgDoc.lower(s.get("clip-rule")).equals("evenodd") ? Path2D.WIND_EVEN_ODD
                            : Path2D.WIND_NON_ZERO);
                    Area a = new Area(own2);
                    a.intersect(new Area(cut));
                    shape = a;
                }
                Path2D p = new Path2D.Double(shape, t);
                if (shape == g) {
                    p.setWindingRule(SvgDoc.lower(s.get("clip-rule")).equals("evenodd") ? Path2D.WIND_EVEN_ODD
                            : Path2D.WIND_NON_ZERO);
                }
                count++;
                if (count == 1) {
                    single = p;
                } else {
                    if (union == null) {
                        union = new Area(single);
                    }
                    union.add(new Area(p));
                }
            }
            Shape result = count == 0 ? new Rectangle2D.Double() : union != null ? union : single;
            String outer = cs.own("clip-path", "none");
            Element o = outer.equalsIgnoreCase("none") ? null : doc.byUrl(outer);
            if (o != null && SvgDoc.local(o).equals("clippath") && !active.contains(o)) {
                Shape os = clipShape(o, bbox, depth + 1);
                if (os == null) {
                    return null;
                }
                Area a = new Area(result);
                a.intersect(new Area(os));
                result = a;
            } else if (o == null && !outer.equalsIgnoreCase("none")) {
                return null;
            }
            return result;
        } finally {
            active.remove(clip);
        }
    }


    Rectangle2D bbox(Element e, SvgStyle s, String tag, int depth) {
        charge(1);
        if (depth > 16 || spent()) {
            return null;
        }
        switch (tag) {
            case "text":
                return text.bbox(e, s);
            case "image": {
                float w = x(e, "width", s, 0);
                float h = y(e, "height", s, 0);
                return new Rectangle2D.Double(x(e, "x", s, 0), y(e, "y", s, 0), w, h);
            }
            case "g", "a", "svg", "switch", "use", "symbol": {
                Rectangle2D out = null;
                List<Element> kids = new ArrayList<>(e.children());
                AffineTransform base = new AffineTransform();
                if (tag.equals("use")) {
                    Element t = doc.target(e);
                    if (t == null || !active.add(t)) {
                        return null;
                    }
                    try {
                        base.translate(x(e, "x", s, 0), y(e, "y", s, 0));
                        String tt = SvgDoc.local(t);
                        SvgStyle ts = s.child(doc.specified(t));
                        AffineTransform own = transform(ts, t);
                        if (own != null && !tt.equals("symbol")) {
                            base.concatenate(own);
                        }
                        Rectangle2D b = bbox(t, ts, tt, depth + 1);
                        return b == null ? null : base.createTransformedShape(b).getBounds2D();
                    } finally {
                        active.remove(t);
                    }
                }
                for (Element c : kids) {
                    String ct = SvgDoc.local(c);
                    if (!isShape(ct) && !ct.equals("g") && !ct.equals("text") && !ct.equals("use") && !ct.equals("a")
                            && !ct.equals("svg") && !ct.equals("switch")) {
                        continue;
                    }
                    SvgStyle cs = s.child(doc.specified(c));
                    if (cs.own("display", "inline").equalsIgnoreCase("none")) {
                        continue;
                    }
                    Rectangle2D b = bbox(c, cs, ct, depth + 1);
                    if (b == null) {
                        continue;
                    }
                    AffineTransform t = transform(cs, c);
                    if (t != null) {
                        b = t.createTransformedShape(b).getBounds2D();
                    }
                    out = out == null ? b : out.createUnion(b);
                }
                return out;
            }
            default: {
                Shape g = geometry(e, s, tag);
                return g == null ? null : g.getBounds2D();
            }
        }
    }


    Shape geometry(Element e, SvgStyle s, String tag) {
        switch (tag) {
            case "path": {
                String d = SvgDoc.attr(e, "d");
                if (d.length() > MAX_PATH_CHARS) {
                    return null;
                }
                charge(d.length());
                return SvgPath.parse(d);
            }
            case "rect": {
                float w = x(e, "width", s, 0);
                float h = y(e, "height", s, 0);
                if (!(w > 0) || !(h > 0)) {
                    return null;
                }
                float rx = x(e, "rx", s, Float.NaN);
                float ry = y(e, "ry", s, Float.NaN);
                if (!(rx >= 0)) {
                    rx = Float.NaN;
                }
                if (!(ry >= 0)) {
                    ry = Float.NaN;
                }
                if (Float.isNaN(rx)) {
                    rx = Float.isNaN(ry) ? 0 : ry;
                }
                if (Float.isNaN(ry)) {
                    ry = rx;
                }
                rx = Math.min(rx, w / 2);
                ry = Math.min(ry, h / 2);
                float x = x(e, "x", s, 0);
                float y = y(e, "y", s, 0);
                if (rx > 0 && ry > 0) {
                    return roundRect(x, y, w, h, rx, ry);
                }
                Path2D.Float p = new Path2D.Float();
                p.moveTo(x, y);
                p.lineTo(x + w, y);
                p.lineTo(x + w, y + h);
                p.lineTo(x, y + h);
                p.closePath();
                return p;
            }
            case "circle": {
                float r = d(e, "r", s, 0);
                if (!(r > 0)) {
                    return null;
                }
                return ellipse(x(e, "cx", s, 0), y(e, "cy", s, 0), r, r);
            }
            case "ellipse": {
                float rx = x(e, "rx", s, Float.NaN);
                float ry = y(e, "ry", s, Float.NaN);
                if (rx < 0) {
                    rx = Float.NaN;
                }
                if (ry < 0) {
                    ry = Float.NaN;
                }
                if (Float.isNaN(rx)) {
                    rx = ry;
                }
                if (Float.isNaN(ry)) {
                    ry = rx;
                }
                if (!(rx > 0) || !(ry > 0)) {
                    return null;
                }
                return ellipse(x(e, "cx", s, 0), y(e, "cy", s, 0), rx, ry);
            }
            case "line": {
                Path2D.Float p = new Path2D.Float();
                p.moveTo(x(e, "x1", s, 0), y(e, "y1", s, 0));
                p.lineTo(x(e, "x2", s, 0), y(e, "y2", s, 0));
                return p;
            }
            case "polyline", "polygon": {
                String pts = SvgDoc.attr(e, "points");
                charge(pts.length());
                float[] p = numbers(pts);
                if (p.length < 4) {
                    return null;
                }
                Path2D.Float path = new Path2D.Float();
                path.moveTo(p[0], p[1]);
                for (int i = 2; i + 1 < p.length; i += 2) {
                    path.lineTo(p[i], p[i + 1]);
                }
                if (tag.equals("polygon")) {
                    path.closePath();
                }
                return path;
            }
            default:
                return null;
        }
    }

    private static Shape ellipse(float cx, float cy, float rx, float ry) {
        double k = 0.5522847498;
        Path2D.Double p = new Path2D.Double();
        p.moveTo(cx + rx, cy);
        p.curveTo(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry);
        p.curveTo(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy);
        p.curveTo(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry);
        p.curveTo(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy);
        p.closePath();
        return p;
    }

    private static Shape roundRect(float x, float y, float w, float h, float rx, float ry) {
        double k = 0.5522847498;
        Path2D.Double p = new Path2D.Double();
        p.moveTo(x + rx, y);
        p.lineTo(x + w - rx, y);
        p.curveTo(x + w - rx + rx * k, y, x + w, y + ry - ry * k, x + w, y + ry);
        p.lineTo(x + w, y + h - ry);
        p.curveTo(x + w, y + h - ry + ry * k, x + w - rx + rx * k, y + h, x + w - rx, y + h);
        p.lineTo(x + rx, y + h);
        p.curveTo(x + rx - rx * k, y + h, x, y + h - ry + ry * k, x, y + h - ry);
        p.lineTo(x, y + ry);
        p.curveTo(x, y + ry - ry * k, x + rx - rx * k, y, x + rx, y);
        p.closePath();
        return p;
    }

    private void shape(Element e, SvgStyle s, String tag, float alpha) throws IOException {
        if (tag.equals("rect") && !(x(e, "width", s, 0) > 0 && y(e, "height", s, 0) > 0)) {
            return;
        }
        Shape g = geometry(e, s, tag);
        if (g == null) {
            return;
        }
        Path2D p = new Path2D.Double(g);
        p.setWindingRule(SvgDoc.lower(s.get("fill-rule")).equals("evenodd") ? Path2D.WIND_EVEN_ODD
                : Path2D.WIND_NON_ZERO);
        boolean markable = isShape(tag) && !tag.equals("image");
        paintShape(p, e, s, alpha, markable);
    }

    void paintShape(Path2D p, Element e, SvgStyle s, float alpha, boolean markable) throws IOException {
        boolean visible = s.visible();
        Rectangle2D bbox = p.getBounds2D();
        List<String> order = paintOrder(s.get("paint-order"));
        for (String step : order) {
            switch (step) {
                case "fill" -> {
                    if (visible) {
                        Paint fill = paint(s.get("fill"), s);
                        if (!fill.none()) {
                            fill(p, fill, alpha * Css.opacity(s.get("fill-opacity")), bbox, s);
                        }
                    }
                }
                case "stroke" -> {
                    if (visible) {
                        Paint stroke = paint(s.get("stroke"), s);
                        float w = strokeWidth(s);
                        if (!stroke.none() && w > 0) {
                            float savedScale = dashScale;
                            double savedLength = dashedLength;
                            dashedLength = dashes(s) == null ? 0 : pathLength(p);
                            dashScale = dashScale(e, s, dashedLength);
                            try {
                                stroke(SvgDoc.lower(s.get("stroke-linecap")).equals("butt") ? p : dots(p), stroke,
                                        alpha * Css.opacity(s.get("stroke-opacity")), bbox, s, w);
                            } finally {
                                dashScale = savedScale;
                                dashedLength = savedLength;
                            }
                        }
                    }
                }
                default -> {
                    if (markable && visible) {
                        markers(p, e, s);
                    }
                }
            }
        }
    }

    static Path2D dots(Path2D p) {
        if (!hasDot(p)) {
            return p;
        }
        Path2D.Double out = new Path2D.Double(p.getWindingRule());
        List<double[]> sub = new ArrayList<>();
        List<Integer> kinds = new ArrayList<>();
        double[] c = new double[6];
        int n = 0;
        for (PathIterator it = p.getPathIterator(null); !it.isDone() && n++ < 2_000_000; it.next()) {
            int k = it.currentSegment(c);
            if (k == PathIterator.SEG_MOVETO && !sub.isEmpty()) {
                flushDot(out, sub, kinds);
            }
            sub.add(c.clone());
            kinds.add(k);
        }
        flushDot(out, sub, kinds);
        return out;
    }

    private static int points(int kind) {
        return switch (kind) {
            case PathIterator.SEG_CUBICTO -> 3;
            case PathIterator.SEG_QUADTO -> 2;
            case PathIterator.SEG_CLOSE -> 0;
            default -> 1;
        };
    }

    private static boolean hasDot(Path2D p) {
        double[] c = new double[6];
        double sx = 0;
        double sy = 0;
        int segments = 0;
        boolean same = false;
        int n = 0;
        for (PathIterator it = p.getPathIterator(null); !it.isDone() && n++ < 2_000_000; it.next()) {
            int k = it.currentSegment(c);
            if (k == PathIterator.SEG_MOVETO) {
                if (same && segments > 1) {
                    return true;
                }
                sx = c[0];
                sy = c[1];
                segments = 0;
                same = true;
                continue;
            }
            segments++;
            int pts = points(k);
            for (int j = 0; j < pts; j++) {
                if (c[2 * j] != sx || c[2 * j + 1] != sy) {
                    same = false;
                }
            }
        }
        return same && segments > 1;
    }

    private static void flushDot(Path2D.Double out, List<double[]> sub, List<Integer> kinds) {
        if (sub.isEmpty()) {
            return;
        }
        double[] first = sub.get(0);
        boolean degenerate = sub.size() > 2 && kinds.get(0) == PathIterator.SEG_MOVETO;
        for (int i = 1; i < sub.size() && degenerate; i++) {
            double[] q = sub.get(i);
            int pts = points(kinds.get(i));
            for (int j = 0; j < pts; j++) {
                if (q[2 * j] != first[0] || q[2 * j + 1] != first[1]) {
                    degenerate = false;
                }
            }
        }
        if (degenerate) {
            out.moveTo(first[0], first[1]);
            out.lineTo(first[0], first[1]);
        } else {
            for (int i = 0; i < sub.size(); i++) {
                double[] q = sub.get(i);
                switch (kinds.get(i)) {
                    case PathIterator.SEG_MOVETO -> out.moveTo(q[0], q[1]);
                    case PathIterator.SEG_LINETO -> out.lineTo(q[0], q[1]);
                    case PathIterator.SEG_QUADTO -> out.quadTo(q[0], q[1], q[2], q[3]);
                    case PathIterator.SEG_CUBICTO -> out.curveTo(q[0], q[1], q[2], q[3], q[4], q[5]);
                    default -> out.closePath();
                }
            }
        }
        sub.clear();
        kinds.clear();
    }

    static List<String> paintOrder(String v) {
        String l = v == null ? "" : v.strip().toLowerCase(Locale.ROOT);
        if (l.isEmpty() || l.equals("normal")) {
            return List.of("fill", "stroke", "markers");
        }
        List<String> out = new ArrayList<>();
        for (String t : l.split("\\s+")) {
            if (!t.equals("fill") && !t.equals("stroke") && !t.equals("markers") || out.contains(t)) {
                return List.of("fill", "stroke", "markers");
            }
            out.add(t);
        }
        for (String t : List.of("fill", "stroke", "markers")) {
            if (!out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    float strokeWidth(SvgStyle s) {
        return length(s.get("stroke-width"), s.fontSize, diagonal(), 1);
    }

    Paint paint(String v, SvgStyle s) {
        String l = v == null ? "" : v.strip();
        String lower = l.toLowerCase(Locale.ROOT);
        if (lower.isEmpty() || lower.equals("none")) {
            return Paint.NONE;
        }
        if (lower.equals("context-fill")) {
            return contextFill == null ? Paint.NONE : contextFill;
        }
        if (lower.equals("context-stroke")) {
            return contextStroke == null ? Paint.NONE : contextStroke;
        }
        if (lower.equals("currentcolor")) {
            return new Paint(s.color, 1, null, false);
        }
        if (lower.startsWith("url(")) {
            int close = l.indexOf(')');
            Element server = doc.byUrl(l.substring(0, close < 0 ? l.length() : close + 1));
            String fallback = close < 0 ? "" : l.substring(close + 1).strip();
            if (server != null) {
                String t = SvgDoc.local(server);
                if (t.equals("lineargradient") || t.equals("radialgradient") || t.equals("pattern")) {
                    return new Paint(0, 1, server, false, fallback.isEmpty() ? null : fallback);
                }
            }
            if (fallback.isEmpty() || fallback.toLowerCase(Locale.ROOT).startsWith("url(")) {
                return Paint.NONE;
            }
            return paint(fallback, s);
        }
        String colour = percentAlpha(l);
        Integer c = Css.color(colour);
        if (c == null) {
            return Paint.NONE;
        }
        if (c < 0) {
            return Paint.NONE;
        }
        return new Paint(c, Css.alpha(colour), null, false);
    }

    private static final Map<String, String> SYSTEM_COLOURS = Map.ofEntries(Map.entry("activeborder", "#c0c0c0"),
            Map.entry("activecaption", "#000080"), Map.entry("appworkspace", "#808080"),
            Map.entry("background", "#008080"), Map.entry("buttonface", "#c0c0c0"),
            Map.entry("buttonhighlight", "#ffffff"), Map.entry("buttonshadow", "#808080"),
            Map.entry("buttontext", "#000000"), Map.entry("captiontext", "#ffffff"),
            Map.entry("graytext", "#808080"), Map.entry("highlight", "#000080"),
            Map.entry("highlighttext", "#ffffff"), Map.entry("inactiveborder", "#c0c0c0"),
            Map.entry("inactivecaption", "#808080"), Map.entry("inactivecaptiontext", "#c0c0c0"),
            Map.entry("infobackground", "#ffffe1"), Map.entry("infotext", "#000000"),
            Map.entry("menu", "#c0c0c0"), Map.entry("menutext", "#000000"), Map.entry("scrollbar", "#c0c0c0"),
            Map.entry("threeddarkshadow", "#000000"), Map.entry("threedface", "#c0c0c0"),
            Map.entry("threedhighlight", "#ffffff"), Map.entry("threedlightshadow", "#dfdfdf"),
            Map.entry("threedshadow", "#808080"), Map.entry("window", "#ffffff"),
            Map.entry("windowframe", "#000000"), Map.entry("windowtext", "#000000"));

    static String percentAlpha(String v) {
        if (v.length() <= 20 && v.indexOf('(') < 0) {
            String system = SYSTEM_COLOURS.get(v.strip().toLowerCase(Locale.ROOT));
            if (system != null) {
                return system;
            }
        }
        int open = v.indexOf('(');
        int close = v.lastIndexOf(')');
        if (open < 0 || close < open || v.length() > 200) {
            return v;
        }
        String fn = v.substring(0, open).strip().toLowerCase(Locale.ROOT);
        if (!fn.equals("rgb") && !fn.equals("rgba") && !fn.equals("hsl") && !fn.equals("hsla")) {
            return v;
        }
        String inner = v.substring(open + 1, close).strip();
        int cut = Math.max(Math.max(inner.lastIndexOf(','), inner.lastIndexOf('/')), inner.lastIndexOf(' '));
        if (cut < 0 || !inner.endsWith("%")) {
            return v;
        }
        String last = inner.substring(cut + 1).strip();
        long commas = inner.chars().filter(ch -> ch == ',').count();
        boolean alphaArg = inner.charAt(cut) == '/' || commas == 3;
        if (!alphaArg) {
            return v;
        }
        float a = number(last.substring(0, last.length() - 1), Float.NaN);
        if (!Float.isFinite(a)) {
            return v;
        }
        return v.substring(0, open + 1) + inner.substring(0, cut + 1) + (inner.charAt(cut) == ' ' ? "" : " ")
                + Math.max(0, Math.min(1, a / 100)) + ")";
    }

    void fill(Path2D p, Paint paint, float alpha, Rectangle2D bbox, SvgStyle s) throws IOException {
        paintWith(p, paint, alpha, bbox, s, false, 0);
    }

    void stroke(Path2D p, Paint paint, float alpha, Rectangle2D bbox, SvgStyle s, float width) throws IOException {
        if (dashedLength > 0 || dashes(s) == null) {
            paintWith(p, paint, alpha, bbox, s, true, width);
            return;
        }
        double saved = dashedLength;
        dashedLength = pathLength(p);
        try {
            paintWith(p, paint, alpha, bbox, s, true, width);
        } finally {
            dashedLength = saved;
        }
    }

    private void paintWith(Path2D p, Paint paint, float alpha, Rectangle2D bbox, SvgStyle s, boolean stroke,
            float width) throws IOException {
        float a = alpha * paint.alpha();
        if (a <= 0) {
            return;
        }
        PDPageContentStream cs = canvas.stream();
        Server server = null;
        if (paint.server() != null) {
            AffineTransform savedRaw = rawOverride;
            if (paint.space() != null) {
                rawOverride = paint.space();
                bbox = paint.box();
            }
            try {
                server = server(paint.server(), bbox, s);
            } finally {
                rawOverride = savedRaw;
            }
        }
        if (paint.server() != null && server == null) {
            boolean degenerate = bbox == null || !(bbox.getWidth() > 0) || !(bbox.getHeight() > 0);
            if (paint.fallback() == null || stops(paint.server()).size() > 0 && !degenerate
                    && !SvgDoc.local(paint.server()).equals("pattern")) {
                return;
            }
            Paint fb = paint(paint.fallback(), s);
            if (fb.none() || fb.server() != null) {
                return;
            }
            paint = fb;
            a = alpha * fb.alpha();
        }
        if (server != null && server.solid() != null) {
            a *= server.solid().getAlpha() / 255f;
            paint = new Paint(server.solid().getRGB() & 0xFFFFFF, 1, null, false);
            server = null;
            if (a <= 0) {
                return;
            }
        }
        if (server == null && recolour != null && !recolour.isEmpty()) {
            float[] c = {((paint.rgb() >> 16) & 0xFF) / 255f, ((paint.rgb() >> 8) & 0xFF) / 255f,
                (paint.rgb() & 0xFF) / 255f, a};
            for (float[] m : recolour) {
                float r = c[0];
                float g = c[1];
                float b = c[2];
                float al = c[3];
                c[0] = Math.clamp(m[0] * r + m[1] * g + m[2] * b + m[3] * al + m[4], 0f, 1f);
                c[1] = Math.clamp(m[5] * r + m[6] * g + m[7] * b + m[8] * al + m[9], 0f, 1f);
                c[2] = Math.clamp(m[10] * r + m[11] * g + m[12] * b + m[13] * al + m[14], 0f, 1f);
                c[3] = Math.clamp(m[15] * r + m[16] * g + m[17] * b + m[18] * al + m[19], 0f, 1f);
            }
            a = c[3];
            if (a <= 0) {
                return;
            }
            paint = new Paint(Math.round(c[0] * 255) << 16 | Math.round(c[1] * 255) << 8 | Math.round(c[2] * 255), 1,
                    null, false);
        }
        if (stroke && server != null && a < 1 && width > 0 && dashes(s) == null) {
            String cap = SvgDoc.lower(s.get("stroke-linecap"));
            String join = SvgDoc.lower(s.get("stroke-linejoin"));
            float limit = number(s.get("stroke-miterlimit"), 4);
            java.awt.BasicStroke outline = new java.awt.BasicStroke(width,
                    cap.equals("round") ? java.awt.BasicStroke.CAP_ROUND
                            : cap.equals("square") ? java.awt.BasicStroke.CAP_SQUARE : java.awt.BasicStroke.CAP_BUTT,
                    join.equals("round") ? java.awt.BasicStroke.JOIN_ROUND
                            : join.equals("bevel") ? java.awt.BasicStroke.JOIN_BEVEL : java.awt.BasicStroke.JOIN_MITER,
                    limit >= 1 ? Math.min(limit, 1000) : 4);
            p = new Path2D.Double(outline.createStrokedShape(p), null);
            stroke = false;
        }
        cs.saveGraphicsState();
        try {
            PDExtendedGraphicsState gs = null;
            if (a < 1 || server != null && server.alphaMask() != null) {
                gs = new PDExtendedGraphicsState();
                if (a < 1) {
                    if (stroke) {
                        gs.setStrokingAlphaConstant(a);
                    } else {
                        gs.setNonStrokingAlphaConstant(a);
                    }
                }
                if (server != null && server.alphaMask() != null) {
                    gs.getCOSObject().setItem(COSName.SMASK, smaskDict(server.alphaMask(), false));
                    cs.transform(new Matrix(rawInverse()));
                    cs.setGraphicsStateParameters(gs);
                    cs.transform(new Matrix(raw()));
                } else {
                    cs.setGraphicsStateParameters(gs);
                }
            }
            if (server != null) {
                PDColor colour = new PDColor(server.name(), new PDPattern(null));
                if (stroke) {
                    cs.setStrokingColor(colour);
                } else {
                    cs.setNonStrokingColor(colour);
                }
            } else {
                int rgb = paint.rgb();
                float r = ((rgb >> 16) & 0xFF) / 255f;
                float g = ((rgb >> 8) & 0xFF) / 255f;
                float b = (rgb & 0xFF) / 255f;
                if (stroke) {
                    cs.setStrokingColor(r, g, b);
                } else {
                    cs.setNonStrokingColor(r, g, b);
                }
            }
            if (stroke) {
                strokeStyle(cs, s, width);
            }
            boolean eo = append(cs, p);
            if (stroke) {
                cs.stroke();
            } else if (eo) {
                cs.fillEvenOdd();
            } else {
                cs.fill();
            }
        } finally {
            cs.restoreGraphicsState();
        }
    }

    private float dashScale = 1;

    private double dashedLength;

    private static final double MAX_DASHES = 100_000;

    private float dashScale(Element e, SvgStyle s, double total) {
        if (e == null || !(total > 0) || !SvgDoc.has(e, "pathLength") || !isShape(SvgDoc.local(e))) {
            return 1;
        }
        float want = number(SvgDoc.attr(e, "pathLength"), Float.NaN);
        if (want == 0) {
            return Float.POSITIVE_INFINITY;
        }
        if (!(want > 0)) {
            return 1;
        }
        float k = (float) (total / want);
        return Float.isFinite(k) && k > 0 ? k : 1;
    }

    private double pathLength(Path2D p) {
        double total = 0;
        double[] c = new double[6];
        double x = 0;
        double y = 0;
        double sx = 0;
        double sy = 0;
        int steps = 0;
        for (PathIterator it = p.getPathIterator(null, 0.01); !it.isDone() && steps++ < 1_000_000; it.next()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> {
                    x = sx = c[0];
                    y = sy = c[1];
                }
                case PathIterator.SEG_LINETO -> {
                    total += Math.hypot(c[0] - x, c[1] - y);
                    x = c[0];
                    y = c[1];
                }
                case PathIterator.SEG_CLOSE -> {
                    total += Math.hypot(sx - x, sy - y);
                    x = sx;
                    y = sy;
                }
                default -> {
                }
            }
        }
        charge(steps);
        return total;
    }

    void strokeStyle(PDPageContentStream cs, SvgStyle s, float width) throws IOException {
        cs.setLineWidth(width);
        cs.setLineCapStyle(switch (SvgDoc.lower(s.get("stroke-linecap"))) {
            case "round" -> 1;
            case "square" -> 2;
            default -> 0;
        });
        String join = SvgDoc.lower(s.get("stroke-linejoin"));
        cs.setLineJoinStyle(join.equals("round") ? 1 : join.equals("bevel") ? 2 : 0);
        float limit = number(s.get("stroke-miterlimit"), 4);
        cs.setMiterLimit(limit >= 1 ? Math.min(limit, 1000) : 4);
        float[] dash = dashes(s);
        if (dash != null && !Float.isFinite(dashScale) && dash[0] == 0 && dash.length > 1 && dash[1] > 0) {
            cs.setLineDashPattern(new float[] {0, (float) Math.max(1, dashedLength * 2 + 1)}, 0);
        } else if (dash != null) {
            float offset = length(s.get("stroke-dashoffset"), s.fontSize, diagonal(), 0);
            double period = 0;
            for (int i = 0; i < dash.length; i++) {
                dash[i] *= dashScale;
                period += dash[i];
            }
            offset *= dashScale;
            if (Float.isFinite(dashScale) && (!(dashedLength > 0) || dashedLength / period <= MAX_DASHES)) {
                cs.setLineDashPattern(dash, offset);
            }
        }
    }

    float[] dashes(SvgStyle s) {
        String v = s.get("stroke-dasharray").strip();
        if (v.isEmpty() || v.equalsIgnoreCase("none")) {
            return null;
        }
        String[] parts = v.replace(',', ' ').trim().split("\\s+");
        if (parts.length > 1000) {
            return null;
        }
        float[] d = new float[parts.length];
        float total = 0;
        for (int i = 0; i < parts.length; i++) {
            d[i] = length(parts[i], s.fontSize, diagonal(), Float.NaN);
            if (!(d[i] >= 0)) {
                return null;
            }
            total += d[i];
        }
        if (!(total > 0)) {
            return null;
        }
        if (d.length % 2 == 1) {
            float[] twice = new float[d.length * 2];
            System.arraycopy(d, 0, twice, 0, d.length);
            System.arraycopy(d, 0, twice, d.length, d.length);
            d = twice;
        }
        return d;
    }

    static boolean append(PDPageContentStream cs, Shape shape) throws IOException {
        PathIterator it = shape.getPathIterator(null);
        double[] c = new double[6];
        double lastX = 0;
        double lastY = 0;
        while (!it.isDone()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> {
                    cs.moveTo((float) c[0], (float) c[1]);
                    lastX = c[0];
                    lastY = c[1];
                }
                case PathIterator.SEG_LINETO -> {
                    cs.lineTo((float) c[0], (float) c[1]);
                    lastX = c[0];
                    lastY = c[1];
                }
                case PathIterator.SEG_QUADTO -> {
                    cs.curveTo((float) (lastX + 2.0 / 3 * (c[0] - lastX)), (float) (lastY + 2.0 / 3 * (c[1] - lastY)),
                            (float) (c[2] + 2.0 / 3 * (c[0] - c[2])), (float) (c[3] + 2.0 / 3 * (c[1] - c[3])),
                            (float) c[2], (float) c[3]);
                    lastX = c[2];
                    lastY = c[3];
                }
                case PathIterator.SEG_CUBICTO -> {
                    cs.curveTo((float) c[0], (float) c[1], (float) c[2], (float) c[3], (float) c[4], (float) c[5]);
                    lastX = c[4];
                    lastY = c[5];
                }
                case PathIterator.SEG_CLOSE -> cs.closePath();
                default -> { }
            }
            it.next();
        }
        return it.getWindingRule() == PathIterator.WIND_EVEN_ODD;
    }

    PDResources resources() {
        if (canvas.form() != null) {
            PDResources r = canvas.form().getResources();
            if (r == null) {
                r = new PDResources();
                canvas.form().setResources(r);
            }
            return r;
        }
        PDResources r = canvas.page().getResources();
        if (r == null) {
            r = new PDResources();
            canvas.page().setResources(r);
        }
        return r;
    }


    record Server(COSName name, PDFormXObject alphaMask, Color solid) {}

    record Stop(float offset, int rgb, float alpha) {}

    private AffineTransform validTransform(Element e, String attr) {
        Element cur = e;
        for (int i = 0; i < 16 && cur != null; i++) {
            if (SvgDoc.has(cur, attr)) {
                AffineTransform t = parseTransform(SvgDoc.attr(cur, attr));
                if (t != null) {
                    SvgStyle cs = domStyle(cur);
                    t = withOrigin(t, cs.own("transform-origin", SvgDoc.attr(cur, "transform-origin")), cs.fontSize);
                    return Math.abs(t.getDeterminant()) < 1e-12 ? null : t;
                }
            }
            Element next = doc.target(cur);
            if (next == null || next == cur) {
                break;
            }
            String tag = SvgDoc.local(next);
            if (!tag.equals("lineargradient") && !tag.equals("radialgradient") && !tag.equals("pattern")) {
                break;
            }
            cur = next;
        }
        return null;
    }

    private static final java.util.Set<String> GEOMETRY = java.util.Set.of("x1", "y1", "x2", "y2", "cx", "cy", "r",
            "fx", "fy", "fr");

    private Element chained(Element e, String attr) {
        Element cur = e;
        String own = SvgDoc.local(e);
        for (int i = 0; i < 16 && cur != null; i++) {
            if (SvgDoc.has(cur, attr) && (!GEOMETRY.contains(attr) || SvgDoc.local(cur).equals(own)
                    && validCoordinate(SvgDoc.attr(cur, attr)))) {
                return cur;
            }
            Element next = doc.target(cur);
            if (next == null || next == cur) {
                break;
            }
            String t = SvgDoc.local(next);
            if (!t.equals("lineargradient") && !t.equals("radialgradient") && !t.equals("pattern")) {
                break;
            }
            cur = next;
        }
        return null;
    }

    private static boolean validCoordinate(String v) {
        String t = v.strip();
        if (t.endsWith("%")) {
            return Float.isFinite(number(t.substring(0, t.length() - 1), Float.NaN));
        }
        return Float.isFinite(length(t, 16, 1, Float.NaN));
    }

    private String chainedAttr(Element e, String attr, String fallback) {
        Element owner = chained(e, attr);
        return owner == null ? fallback : SvgDoc.attr(owner, attr);
    }

    private List<Stop> stops(Element g) {
        Element cur = g;
        for (int i = 0; i < 16 && cur != null; i++) {
            List<Stop> stops = new ArrayList<>();
            float last = 0;
            for (Element c : cur.children()) {
                if (!SvgDoc.local(c).equals("stop")) {
                    continue;
                }
                SvgStyle st = domStyle(c);
                String o = SvgDoc.attr(c, "offset").strip();
                float off = o.endsWith("%") ? number(o.substring(0, o.length() - 1), 0) / 100 : number(o, 0);
                off = Math.max(last, Math.max(0, Math.min(1, off)));
                last = off;
                String colour = percentAlpha(st.own("stop-color", "black"));
                int rgb;
                float alpha;
                if (colour.equalsIgnoreCase("currentcolor")) {
                    rgb = st.color;
                    alpha = 1;
                } else {
                    Integer parsed = Css.color(colour);
                    rgb = parsed == null || parsed < 0 ? 0 : parsed;
                    alpha = parsed != null && parsed < 0 ? 0 : Css.alpha(colour);
                }
                alpha *= Css.opacity(st.own("stop-opacity", "1"));
                stops.add(new Stop(off, rgb, alpha));
                if (stops.size() > 1024) {
                    break;
                }
            }
            if (!stops.isEmpty()) {
                return stops;
            }
            Element next = doc.target(cur);
            if (next == null || next == cur || !SvgDoc.local(next).endsWith("gradient")) {
                break;
            }
            cur = next;
        }
        return List.of();
    }

    private Server server(Element e, Rectangle2D bbox, SvgStyle s) throws IOException {
        String tag = SvgDoc.local(e);
        if (tag.equals("pattern")) {
            return pattern(e, bbox, s);
        }
        List<Stop> stops = stops(e);
        if (stops.isEmpty()) {
            return null;
        }
        if (stops.size() == 1) {
            Stop st = stops.get(0);
            return new Server(null, null, new Color((st.rgb() >> 16) & 0xFF, (st.rgb() >> 8) & 0xFF, st.rgb() & 0xFF,
                    Math.round(st.alpha() * 255)));
        }
        if (stops.get(0).offset() <= 0 && stops.get(1).offset() <= 0) {
            List<Stop> kept = new ArrayList<>(stops);
            int last = 1;
            while (last + 1 < kept.size() && kept.get(last + 1).offset() <= 0) {
                last++;
            }
            Stop edge = kept.get(last);
            float next = last + 1 < kept.size() ? kept.get(last + 1).offset() : 1;
            kept.subList(1, last + 1).clear();
            kept.add(1, new Stop(Math.min(1e-4f, next / 2), edge.rgb(), edge.alpha()));
            stops = kept;
        }
        boolean box = !SvgDoc.lower(chainedAttr(e, "gradientUnits", "objectBoundingBox")).equals("userspaceonuse");
        if (box && (bbox == null || bbox.getWidth() <= 0 || bbox.getHeight() <= 0)) {
            return null;
        }
        AffineTransform t = new AffineTransform();
        if (box) {
            t.translate(bbox.getX(), bbox.getY());
            t.scale(bbox.getWidth(), bbox.getHeight());
        }
        AffineTransform gt = validTransform(e, "gradientTransform");
        if (gt != null) {
            t.concatenate(gt);
        }
        if (Math.abs(t.getDeterminant()) < 1e-12) {
            return null;
        }
        String spread = SvgDoc.lower(chainedAttr(e, "spreadMethod", "pad"));
        float[] geo;
        boolean radial = tag.equals("radialgradient");
        SvgStyle gs = domStyle(e);
        if (radial) {
            float cx = coord(e, "cx", box, gs, true, 0.5f);
            float cy = coord(e, "cy", box, gs, false, 0.5f);
            float r = coordD(e, "r", box, gs, 0.5f);
            Element fxOwner = chained(e, "fx");
            Element fyOwner = chained(e, "fy");
            float fx = fxOwner == null ? cx : coord(e, "fx", box, gs, true, cx);
            float fy = fyOwner == null ? cy : coord(e, "fy", box, gs, false, cy);
            float fr = coordD(e, "fr", box, gs, 0);
            if (!(r > 0)) {
                Stop last = stops.get(stops.size() - 1);
                return new Server(null, null, new Color((last.rgb() >> 16) & 0xFF, (last.rgb() >> 8) & 0xFF,
                        last.rgb() & 0xFF, Math.round(last.alpha() * 255)));
            }
            double d = Math.hypot(fx - cx, fy - cy);
            if (d >= r) {
                double k = r * 0.99 / d;
                fx = (float) (cx + (fx - cx) * k);
                fy = (float) (cy + (fy - cy) * k);
            }
            fr = Math.max(0, Math.min(fr, r));
            geo = new float[] {fx, fy, fr, cx, cy, r};
        } else {
            geo = new float[] {coord(e, "x1", box, gs, true, 0), coord(e, "y1", box, gs, false, 0),
                coord(e, "x2", box, gs, true, 1), coord(e, "y2", box, gs, false, 0)};
            if (geo[0] == geo[2] && geo[1] == geo[3]) {
                Stop last = stops.get(stops.size() - 1);
                return new Server(null, null, new Color((last.rgb() >> 16) & 0xFF, (last.rgb() >> 8) & 0xFF,
                        last.rgb() & 0xFF, Math.round(last.alpha() * 255)));
            }
        }
        if (spread.equals("reflect") || spread.equals("repeat")) {
            Repeated rep = repeat(stops, geo, radial, t, bbox, spread.equals("reflect"));
            if (rep != null) {
                stops = rep.stops();
                geo = rep.geo();
            }
        }
        if (SvgDoc.lower(gs.get("color-interpolation")).equals("linearrgb")) {
            stops = linearStops(stops);
        }
        boolean alpha = false;
        for (Stop st : stops) {
            if (st.alpha() < 0.999f) {
                alpha = true;
                break;
            }
        }
        PDShading colour = shading(geo, radial, stops, false);
        AffineTransform matrix = raw();
        matrix.concatenate(t);
        PDShadingPattern sp = new PDShadingPattern();
        sp.setShading(colour);
        sp.setMatrix(matrix);
        COSName name = resources().add(sp);
        PDFormXObject mask = null;
        if (alpha) {
            PDShading grey = shading(geo, radial, stops, true);
            mask = alphaForm(grey, t);
        }
        return new Server(name, mask, null);
    }

    private static List<Stop> linearStops(List<Stop> stops) {
        List<Stop> out = new ArrayList<>();
        int steps = stops.size() > 64 ? 2 : 12;
        for (int i = 0; i < stops.size(); i++) {
            Stop a = stops.get(i);
            out.add(a);
            if (i + 1 >= stops.size()) {
                break;
            }
            Stop b = stops.get(i + 1);
            if (b.offset() <= a.offset() || a.rgb() == b.rgb()) {
                continue;
            }
            for (int k = 1; k < steps; k++) {
                float t = (float) k / steps;
                int rgb = 0;
                for (int shift = 16; shift >= 0; shift -= 8) {
                    float ca = SvgFilter.linear(((a.rgb() >> shift) & 0xFF) / 255f);
                    float cb = SvgFilter.linear(((b.rgb() >> shift) & 0xFF) / 255f);
                    int v = Math.round(SvgFilter.srgb(ca + (cb - ca) * t) * 255);
                    rgb |= Math.max(0, Math.min(255, v)) << shift;
                }
                out.add(new Stop(a.offset() + (b.offset() - a.offset()) * t, rgb, a.alpha() + (b.alpha() - a.alpha()) * t));
            }
        }
        return out;
    }

    private PDFormXObject alphaForm(PDShading grey, AffineTransform t) throws IOException {
        AffineTransform ctm = canvas.transform();
        return capture(() -> {
            PDPageContentStream cs = canvas.stream();
            AffineTransform m = raw();
            m.concatenate(t);
            PDShadingPattern sp = new PDShadingPattern();
            sp.setShading(grey);
            sp.setMatrix(m);
            COSName name = resources().add(sp);
            cs.saveGraphicsState();
            cs.setNonStrokingColor(new PDColor(name, new PDPattern(null)));
            AffineTransform inv = rawInverse();
            double big = 1e5;
            Shape all = inv.createTransformedShape(new Rectangle2D.Double(-big, -big, 2 * big, 2 * big));
            append(cs, all);
            cs.fill();
            cs.restoreGraphicsState();
        }, ctm);
    }

    record Repeated(List<Stop> stops, float[] geo) {}

    private static double focalReach(float[] geo, Point2D p) {
        double dx = geo[3] - geo[0];
        double dy = geo[4] - geo[1];
        double dr = geo[5] - geo[2];
        double qx = p.getX() - geo[0];
        double qy = p.getY() - geo[1];
        double a = dx * dx + dy * dy - dr * dr;
        double b = qx * dx + qy * dy + geo[2] * dr;
        double c = qx * qx + qy * qy - geo[2] * geo[2];
        if (Math.abs(a) < 1e-12) {
            return Math.abs(b) < 1e-12 ? 1 : c / (2 * b);
        }
        double disc = b * b - a * c;
        if (disc < 0) {
            return 1;
        }
        double t = a < 0 ? (b - Math.sqrt(disc)) / a : (b + Math.sqrt(disc)) / a;
        return Double.isFinite(t) ? t : 1;
    }

    private Repeated repeat(List<Stop> stops, float[] geo, boolean radial, AffineTransform t, Rectangle2D bbox,
            boolean reflect) {
        if (bbox == null) {
            return null;
        }
        AffineTransform inv;
        try {
            inv = t.createInverse();
        } catch (NoninvertibleTransformException e) {
            return null;
        }
        Rectangle2D b = bbox;
        Point2D[] corners = {new Point2D.Double(b.getMinX(), b.getMinY()), new Point2D.Double(b.getMaxX(), b.getMinY()),
            new Point2D.Double(b.getMinX(), b.getMaxY()), new Point2D.Double(b.getMaxX(), b.getMaxY())};
        double lo = 0;
        double hi = 1;
        for (Point2D c : corners) {
            Point2D p = inv.transform(c, null);
            double u;
            if (radial) {
                u = focalReach(geo, p);
            } else {
                double dx = geo[2] - geo[0];
                double dy = geo[3] - geo[1];
                u = ((p.getX() - geo[0]) * dx + (p.getY() - geo[1]) * dy) / (dx * dx + dy * dy);
            }
            lo = Math.min(lo, u);
            hi = Math.max(hi, u);
        }
        int from = radial ? 0 : (int) Math.floor(lo);
        int to = (int) Math.ceil(hi);
        if (to - from > 64 || to - from <= 1 && from == 0) {
            if (to - from > 64) {
                to = from + 64;
            } else {
                return null;
            }
        }
        int periods = to - from;
        List<Stop> out = new ArrayList<>();
        for (int k = from; k < to; k++) {
            boolean flip = reflect && Math.floorMod(k, 2) == 1;
            for (int i = 0; i < stops.size(); i++) {
                Stop st = stops.get(flip ? stops.size() - 1 - i : i);
                float o = flip ? 1 - st.offset() : st.offset();
                out.add(new Stop((k - from + o) / periods, st.rgb(), st.alpha()));
            }
        }
        float[] g = geo.clone();
        if (radial) {
            g[3] = geo[0] + (geo[3] - geo[0]) * to;
            g[4] = geo[1] + (geo[4] - geo[1]) * to;
            g[5] = geo[2] + (geo[5] - geo[2]) * to;
        } else {
            double dx = geo[2] - geo[0];
            double dy = geo[3] - geo[1];
            g[0] = (float) (geo[0] + dx * from);
            g[1] = (float) (geo[1] + dy * from);
            g[2] = (float) (geo[0] + dx * to);
            g[3] = (float) (geo[1] + dy * to);
        }
        return new Repeated(out, g);
    }

    private float coord(Element g, String attr, boolean box, SvgStyle s, boolean horizontal, float fallback) {
        String v = chainedAttr(g, attr, "");
        if (v.isEmpty()) {
            return box ? fallback : fallback * (horizontal ? vw() : vh());
        }
        if (box) {
            String t = v.strip();
            if (t.endsWith("%")) {
                return number(t.substring(0, t.length() - 1), fallback * 100) / 100f;
            }
            return length(t, s.fontSize, 1, fallback);
        }
        return length(v, s.fontSize, horizontal ? vw() : vh(), fallback);
    }

    private float coordD(Element g, String attr, boolean box, SvgStyle s, float fallback) {
        String v = chainedAttr(g, attr, "");
        if (v.isEmpty()) {
            return box ? fallback : fallback * diagonal();
        }
        if (box) {
            String t = v.strip();
            if (t.endsWith("%")) {
                return number(t.substring(0, t.length() - 1), fallback * 100) / 100f;
            }
            return length(t, s.fontSize, 1, fallback);
        }
        return length(v, s.fontSize, diagonal(), fallback);
    }

    private static PDShading shading(float[] geo, boolean radial, List<Stop> stops, boolean alpha) throws IOException {
        PDShading sh = radial ? new PDShadingType3(new COSDictionary()) : new PDShadingType2(new COSDictionary());
        sh.setShadingType(radial ? PDShading.SHADING_TYPE3 : PDShading.SHADING_TYPE2);
        sh.setColorSpace(alpha ? PDDeviceGray.INSTANCE : PDDeviceRGB.INSTANCE);
        COSArray coords = new COSArray();
        for (float f : geo) {
            coords.add(new COSFloat(f));
        }
        sh.getCOSObject().setItem(COSName.COORDS, coords);
        COSArray extend = new COSArray();
        extend.add(COSBoolean.TRUE);
        extend.add(COSBoolean.TRUE);
        sh.getCOSObject().setItem(COSName.EXTEND, extend);
        sh.getCOSObject().setItem(COSName.FUNCTION, function(stops, alpha));
        return sh;
    }

    private static COSDictionary function(List<Stop> stops, boolean alpha) {
        List<Stop> list = new ArrayList<>(stops);
        if (list.get(0).offset() > 0) {
            list.add(0, new Stop(0, list.get(0).rgb(), list.get(0).alpha()));
        }
        Stop lastStop = list.get(list.size() - 1);
        if (lastStop.offset() < 1) {
            list.add(new Stop(1, lastStop.rgb(), lastStop.alpha()));
        }
        COSArray functions = new COSArray();
        COSArray bounds = new COSArray();
        COSArray encode = new COSArray();
        for (int i = 0; i + 1 < list.size(); i++) {
            Stop a = list.get(i);
            Stop b = list.get(i + 1);
            functions.add(pair(a, b, alpha));
            if (i + 2 < list.size()) {
                bounds.add(new COSFloat(b.offset()));
            }
            encode.add(COSInteger.ZERO);
            encode.add(COSInteger.ONE);
        }
        if (functions.size() == 1) {
            return (COSDictionary) functions.getObject(0);
        }
        COSDictionary f = new COSDictionary();
        f.setInt(COSName.FUNCTION_TYPE, 3);
        COSArray domain = new COSArray();
        domain.add(COSInteger.ZERO);
        domain.add(COSInteger.ONE);
        f.setItem(COSName.DOMAIN, domain);
        f.setItem(COSName.FUNCTIONS, functions);
        f.setItem(COSName.BOUNDS, bounds);
        f.setItem(COSName.ENCODE, encode);
        return f;
    }

    private static COSDictionary pair(Stop a, Stop b, boolean alpha) {
        COSDictionary f = new COSDictionary();
        f.setInt(COSName.FUNCTION_TYPE, 2);
        COSArray domain = new COSArray();
        domain.add(COSInteger.ZERO);
        domain.add(COSInteger.ONE);
        f.setItem(COSName.DOMAIN, domain);
        f.setItem(COSName.C0, components(a, alpha));
        f.setItem(COSName.C1, components(b, alpha));
        f.setInt(COSName.N, 1);
        return f;
    }

    private static COSArray components(Stop s, boolean alpha) {
        COSArray c = new COSArray();
        if (alpha) {
            c.add(new COSFloat(s.alpha()));
        } else {
            c.add(new COSFloat(((s.rgb() >> 16) & 0xFF) / 255f));
            c.add(new COSFloat(((s.rgb() >> 8) & 0xFF) / 255f));
            c.add(new COSFloat((s.rgb() & 0xFF) / 255f));
        }
        return c;
    }

    private Server pattern(Element e, Rectangle2D bbox, SvgStyle s) throws IOException {
        if (groups >= MAX_GROUP_DEPTH || spent() || !active.add(e)) {
            return null;
        }
        try {
            boolean box = !SvgDoc.lower(chainedAttr(e, "patternUnits", "objectBoundingBox")).equals("userspaceonuse");
            boolean contentBox = SvgDoc.lower(chainedAttr(e, "patternContentUnits", "userSpaceOnUse"))
                    .equals("objectboundingbox");
            if ((box || contentBox) && (bbox == null || bbox.getWidth() <= 0 || bbox.getHeight() <= 0)) {
                return null;
            }
            SvgStyle ps = domStyle(e);
            float px;
            float py;
            float pw;
            float ph;
            if (box) {
                px = (float) (bbox.getX() + frac(chainedAttr(e, "x", "0")) * bbox.getWidth());
                py = (float) (bbox.getY() + frac(chainedAttr(e, "y", "0")) * bbox.getHeight());
                pw = (float) (frac(chainedAttr(e, "width", "0")) * bbox.getWidth());
                ph = (float) (frac(chainedAttr(e, "height", "0")) * bbox.getHeight());
            } else {
                px = length(chainedAttr(e, "x", "0"), ps.fontSize, vw(), 0);
                py = length(chainedAttr(e, "y", "0"), ps.fontSize, vh(), 0);
                pw = length(chainedAttr(e, "width", "0"), ps.fontSize, vw(), 0);
                ph = length(chainedAttr(e, "height", "0"), ps.fontSize, vh(), 0);
            }
            if (!(pw > 0) || !(ph > 0)) {
                return null;
            }
            Element owner = e;
            for (int i = 0; i < 16; i++) {
                boolean kids = false;
                for (Element c : owner.children()) {
                    String t = SvgDoc.local(c);
                    if (!t.equals("title") && !t.equals("desc")) {
                        kids = true;
                        break;
                    }
                }
                if (kids) {
                    break;
                }
                Element next = doc.target(owner);
                if (next == null || !SvgDoc.local(next).equals("pattern")) {
                    break;
                }
                owner = next;
            }
            Element contentOwner = owner;
            AffineTransform pt = validTransform(e, "patternTransform");
            float[] vb = numbers(chainedAttr(e, "viewBox", ""));
            String aspect = chainedAttr(e, "preserveAspectRatio", "");
            AffineTransform content = new AffineTransform();
            AffineTransform vbt = viewBox(vb, aspect, 0, 0, pw, ph);
            if (vbt != null) {
                content = vbt;
            } else if (contentBox) {
                content.scale(bbox.getWidth(), bbox.getHeight());
            }
            AffineTransform matrix = raw();
            if (pt != null) {
                matrix.concatenate(pt);
            }
            matrix.translate(px, py);
            double scale = Math.sqrt(Math.abs(matrix.getDeterminant()));
            if (!(scale > 1e-9) || pw * scale > 1e6 || ph * scale > 1e6) {
                return null;
            }
            float cw = Math.max(pw, 1e-3f);
            float ch = Math.max(ph, 1e-3f);
            PdfCanvas saved = canvas;
            PdfCanvas cell = output.newForm(cw, ch, 0);
            canvas = cell;
            Deque<float[]> savedViewports = new ArrayDeque<>(viewports);
            groups++;
            float outerScale = dashScale;
            double outerLength = dashedLength;
            dashScale = 1;
            dashedLength = 0;
            try {
                cell.transform(content);
                if (vbt != null) {
                    viewports.push(new float[] {vb[2], vb[3]});
                }
                group(contentOwner, ps, 0, 0, null);
            } finally {
                dashScale = outerScale;
                dashedLength = outerLength;
                groups--;
                canvas = saved;
                cell.close();
                viewports.clear();
                viewports.addAll(savedViewports);
            }
            PDFormXObject form = cell.form();
            PDTilingPattern tp = new PDTilingPattern();
            tp.setBBox(new PDRectangle(0, 0, cw, ch));
            tp.setXStep(cw);
            tp.setYStep(ch);
            tp.setPaintType(PDTilingPattern.PAINT_COLORED);
            tp.setTilingType(PDTilingPattern.TILING_CONSTANT_SPACING);
            PDResources res = new PDResources();
            COSName fx = res.add(form);
            tp.setResources(res);
            try (var os = tp.getContentStream().createOutputStream()) {
                os.write(("/" + fx.getName() + " Do\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            matrix.concatenate(new AffineTransform(1, 0, 0, -1, 0, ch));
            tp.setMatrix(matrix);
            COSName name = resources().add(tp);
            return new Server(name, null, null);
        } finally {
            active.remove(e);
        }
    }

    private static float frac(String v) {
        String t = v.strip();
        if (t.endsWith("%")) {
            return number(t.substring(0, t.length() - 1), 0) / 100f;
        }
        return number(t, 0);
    }


    private void use(Element e, SvgStyle s, int depth, int useDepth) throws IOException {
        Element target = doc.target(e);
        if (target == null || useDepth >= MAX_USE_DEPTH || !active.add(target)) {
            if (target == null && !SvgDoc.href(e).isEmpty() && !SvgDoc.href(e).startsWith("#")) {
                externalSkipped = true;
            }
            return;
        }
        Paint savedFill = contextFill;
        Paint savedStroke = contextStroke;
        try {
            for (Element p = e; p != null; p = p.parent()) {
                if (p == target) {
                    return;
                }
            }
            Paint fill = paint(s.get("fill"), s);
            Paint stroke = paint(s.get("stroke"), s);
            if (fill.server() != null || stroke.server() != null) {
                AffineTransform here = raw();
                active.remove(target);
                Rectangle2D box = bbox(e, s, "use", 0);
                active.add(target);
                contextFill = fill.inContext(here, box);
                contextStroke = stroke.inContext(here, box);
            } else {
                contextFill = fill;
                contextStroke = stroke;
            }
            canvas.translate(x(e, "x", s, 0), y(e, "y", s, 0));
            String tag = SvgDoc.local(target);
            if (tag.equals("symbol")) {
                SvgStyle ts = s.child(doc.specified(target));
                if (ts.own("display", "inline").equalsIgnoreCase("none")) {
                    return;
                }
                float w = x(e, "width", s, Float.NaN);
                float h = y(e, "height", s, Float.NaN);
                if (Float.isNaN(w)) {
                    w = x(target, "width", ts, vw());
                }
                if (Float.isNaN(h)) {
                    h = y(target, "height", ts, vh());
                }
                if (!(w > 0) || !(h > 0)) {
                    return;
                }
                AffineTransform st = transform(ts, target);
                if (st != null) {
                    canvas.transform(st);
                }
                float sx = x(target, "x", ts, 0);
                float sy = y(target, "y", ts, 0);
                float[] vb = numbers(SvgDoc.attr(target, "viewBox"));
                AffineTransform vbt = viewBox(vb, SvgDoc.attr(target, "preserveAspectRatio"), sx, sy, w, h);
                String overflow = SvgDoc.lower(ts.own("overflow", "hidden"));
                if (!overflow.equals("visible") && !overflow.equals("auto")) {
                    canvas.clip(new Rectangle2D.Float(sx, sy, w, h));
                }
                if (vbt != null) {
                    canvas.transform(vbt);
                    viewports.push(new float[] {vb[2], vb[3]});
                } else {
                    canvas.translate(sx, sy);
                    viewports.push(new float[] {w, h});
                }
                try {
                    float opacity = Css.opacity(ts.own("opacity", "1"));
                    if (opacity <= 0) {
                        return;
                    }
                    if (opacity < 1 && groups < MAX_GROUP_DEPTH) {
                        composite(() -> group(target, ts, depth, useDepth + 1, null), opacity, "normal", null, null,
                                target, ts, null);
                    } else {
                        group(target, ts, depth, useDepth + 1, null);
                    }
                } finally {
                    viewports.pop();
                }
                return;
            }
            if (tag.equals("svg")) {
                SvgStyle ts = s.child(doc.specified(target));
                if (ts.own("display", "inline").equalsIgnoreCase("none")) {
                    return;
                }
                nested(target, ts, depth, useDepth + 1, x(e, "width", s, Float.NaN), y(e, "height", s, Float.NaN));
                return;
            }
            element(target, s, depth + 1, useDepth + 1);
        } finally {
            contextFill = savedFill;
            contextStroke = savedStroke;
            active.remove(target);
        }
    }

    private void nested(Element e, SvgStyle s, int depth, int useDepth, float useW, float useH) throws IOException {
        float x = x(e, "x", s, 0);
        float y = y(e, "y", s, 0);
        float w = Float.isNaN(useW) ? x(e, "width", s, vw()) : useW;
        float h = Float.isNaN(useH) ? y(e, "height", s, vh()) : useH;
        if (!(w > 0) || !(h > 0)) {
            return;
        }
        String overflow = SvgDoc.lower(s.own("overflow", "hidden"));
        if (!overflow.equals("visible") && !overflow.equals("auto")) {
            canvas.clip(new Rectangle2D.Float(x, y, w, h));
        }
        float[] vb = numbers(SvgDoc.attr(e, "viewBox"));
        AffineTransform t = viewBox(vb, SvgDoc.attr(e, "preserveAspectRatio"), x, y, w, h);
        if (t != null) {
            canvas.transform(t);
            viewports.push(new float[] {vb[2], vb[3]});
        } else {
            canvas.translate(x, y);
            viewports.push(new float[] {w, h});
        }
        try {
            group(e, s, depth, useDepth, null);
        } finally {
            viewports.pop();
        }
    }

    private void image(Element e, SvgStyle s, float alpha) throws IOException {
        if (!s.visible()) {
            return;
        }
        String href = SvgDoc.href(e);
        byte[] data = dataUri(href);
        if (data == null) {
            if (!href.isEmpty()) {
                externalSkipped = true;
            }
            return;
        }
        float x = x(e, "x", s, 0);
        float y = y(e, "y", s, 0);
        String mime = href.substring(5, Math.max(5, href.indexOf(','))).toLowerCase(Locale.ROOT);
        boolean svg = mime.contains("svg") || looksLikeSvg(data);
        if (svg) {
            nestedSvgImage(data, e, s, x, y, alpha);
            return;
        }
        charge(data.length);
        if (spent()) {
            return;
        }
        if (WebP.is(data)) {
            var redrawn = WebP.redraw(data);
            if (redrawn == null) {
                return;
            }
            data = redrawn.bytes();
        }
        DecodedPicture pic;
        try {
            pic = PictureDecoder.decode(output.document(), data);
        } catch (IOException | RuntimeException ex) {
            return;
        }
        if (pic == null) {
            return;
        }
        charge((long) Math.max(0, pic.pixelWidth()) * Math.max(0, pic.pixelHeight()));
        if (spent()) {
            return;
        }
        String rendering = SvgDoc.lower(s.get("image-rendering"));
        if (!pic.vector() && pic.image() != null) {
            pic.image().setInterpolate(!rendering.equals("optimizespeed") && !rendering.equals("pixelated")
                    && !rendering.equals("crisp-edges"));
        }
        float nw = pic.pixelWidth() > 0 ? pic.pixelWidth() : pic.naturalWidth();
        float nh = pic.pixelHeight() > 0 ? pic.pixelHeight() : pic.naturalHeight();
        float w = x(e, "width", s, Float.NaN);
        float h = y(e, "height", s, Float.NaN);
        if (Float.isNaN(w) && Float.isNaN(h)) {
            w = nw;
            h = nh;
        } else if (Float.isNaN(w)) {
            w = nh > 0 ? h * nw / nh : 0;
        } else if (Float.isNaN(h)) {
            h = nw > 0 ? w * nh / nw : 0;
        }
        if (!(w > 0) || !(h > 0) || !(nw > 0) || !(nh > 0)) {
            return;
        }
        placeImage(pic, nw, nh, x, y, w, h, SvgDoc.attr(e, "preserveAspectRatio"), alpha);
    }

    void picture(byte[] bytes, float x, float y, float w, float h, String aspect, String rendering) throws IOException {
        byte[] data = bytes;
        if (WebP.is(data)) {
            var redrawn = WebP.redraw(data);
            if (redrawn == null) {
                return;
            }
            data = redrawn.bytes();
        }
        DecodedPicture pic;
        try {
            pic = PictureDecoder.decode(output.document(), data);
        } catch (IOException | RuntimeException ex) {
            return;
        }
        if (pic == null || !(w > 0) || !(h > 0)) {
            return;
        }
        charge((long) Math.max(0, pic.pixelWidth()) * Math.max(0, pic.pixelHeight()));
        if (spent()) {
            return;
        }
        float nw = pic.pixelWidth() > 0 ? pic.pixelWidth() : pic.naturalWidth();
        float nh = pic.pixelHeight() > 0 ? pic.pixelHeight() : pic.naturalHeight();
        if (!(nw > 0) || !(nh > 0)) {
            return;
        }
        if (!pic.vector() && pic.image() != null) {
            pic.image().setInterpolate(!rendering.equals("optimizespeed") && !rendering.equals("pixelated")
                    && !rendering.equals("crisp-edges"));
        }
        placeImage(pic, nw, nh, x, y, w, h, aspect, 1);
    }

    private void placeImage(DecodedPicture pic, float nw, float nh, float x, float y, float w, float h, String aspect,
            float alpha) throws IOException {
        AffineTransform t = viewBox(new float[] {0, 0, nw, nh}, aspect, x, y, w, h);
        canvas.save();
        try {
            canvas.clip(new Rectangle2D.Float(x, y, w, h));
            canvas.transform(t);
            canvas.image(pic, 0, 0, nw, nh, stirling.software.officeconvert.topdf.pdf.Crop.NONE, 0, false, false,
                    alpha);
        } finally {
            canvas.restore();
        }
    }

    private static boolean looksLikeSvg(byte[] data) {
        int n = Math.min(data.length, 512);
        String head = new String(data, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        return head.contains("<svg");
    }

    private void nestedSvgImage(byte[] data, Element e, SvgStyle s, float x, float y, float alpha) throws IOException {
        charge(data.length);
        if (groups > 4 || spent()) {
            return;
        }
        SvgDoc inner;
        try {
            inner = SvgDoc.parse(data);
        } catch (IOException | RuntimeException ex) {
            return;
        }
        float[] size = pageSize(inner.root);
        float w = x(e, "width", s, Float.NaN);
        float h = y(e, "height", s, Float.NaN);
        if (Float.isNaN(w) && Float.isNaN(h)) {
            w = size[0];
            h = size[1];
        } else if (Float.isNaN(w)) {
            w = h * size[0] / size[1];
        } else if (Float.isNaN(h)) {
            h = w * size[1] / size[0];
        }
        if (!(w > 0) || !(h > 0)) {
            return;
        }
        float[] vb = numbers(SvgDoc.attr(inner.root, "viewBox"));
        if (vb.length != 4) {
            vb = new float[] {0, 0, size[0], size[1]};
        }
        AffineTransform t = viewBox(vb, SvgDoc.attr(e, "preserveAspectRatio"), x, y, w, h);
        if (t == null) {
            return;
        }
        SvgDraw d = new SvgDraw(inner, output, fonts, warnings);
        d.canvas = canvas;
        d.groups = groups + 1;
        d.budget = budget;
        d.viewports.push(new float[] {vb[2], vb[3]});
        float[] vbf = vb;
        float wf = w;
        float hf = h;
        canvas.save();
        try {
            canvas.clip(new Rectangle2D.Float(x, y, wf, hf));
            if (alpha < 1) {
                composite(() -> {
                    d.canvas = canvas;
                    canvas.transform(t);
                    d.group(inner.root, SvgStyle.root().child(inner.specified(inner.root)), 0, 0, null);
                }, alpha, "normal", null, null, e, s, null);
            } else {
                canvas.transform(t);
                d.group(inner.root, SvgStyle.root().child(inner.specified(inner.root)), 0, 0, null);
            }
        } finally {
            canvas.restore();
        }
    }

    static byte[] dataUri(String href) {
        if (href == null || !href.regionMatches(true, 0, "data:", 0, 5)) {
            return null;
        }
        int comma = href.indexOf(',');
        if (comma < 0) {
            return null;
        }
        String meta = href.substring(5, comma).toLowerCase(Locale.ROOT);
        String body = href.substring(comma + 1);
        if (body.length() > 128 << 20) {
            return null;
        }
        try {
            if (meta.endsWith(";base64")) {
                return Base64.getMimeDecoder().decode(body.replaceAll("\\s", ""));
            }
            return percentDecoded(body);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static byte[] percentDecoded(String body) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(body.length());
        byte[] utf8 = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i < utf8.length; i++) {
            int b = utf8[i];
            if (b == '%' && i + 2 < utf8.length) {
                int hi = Character.digit(utf8[i + 1], 16);
                int lo = Character.digit(utf8[i + 2], 16);
                if (hi >= 0 && lo >= 0) {
                    out.write(hi << 4 | lo);
                    i += 2;
                    continue;
                }
            }
            out.write(b);
        }
        return out.toByteArray();
    }


    private record Vertex(double x, double y, double in, double out, boolean hasIn, boolean hasOut) {}

    private void markers(Path2D p, Element e, SvgStyle s) throws IOException {
        Element start = marker(s.get("marker-start"));
        Element mid = marker(s.get("marker-mid"));
        Element end = marker(s.get("marker-end"));
        if (start == null && mid == null && end == null) {
            return;
        }
        List<Vertex> v = vertices(p);
        if (v.isEmpty()) {
            return;
        }
        float sw = strokeWidth(s);
        Paint fill = paint(s.get("fill"), s);
        Paint stroke = paint(s.get("stroke"), s);
        Paint savedFill = contextFill;
        Paint savedStroke = contextStroke;
        AffineTransform here = raw();
        Rectangle2D box = p.getBounds2D();
        contextFill = fill.inContext(here, box);
        contextStroke = stroke.inContext(here, box);
        try {
            for (int i = 0; i < v.size(); i++) {
                Element m = i == 0 ? start : i == v.size() - 1 ? end : mid;
                if (m != null) {
                    drawMarker(m, v.get(i), i == 0, sw);
                }
            }
        } finally {
            contextFill = savedFill;
            contextStroke = savedStroke;
        }
    }

    private Element marker(String ref) {
        if (ref == null || ref.isBlank() || ref.equalsIgnoreCase("none")) {
            return null;
        }
        Element m = doc.byUrl(ref);
        return m != null && SvgDoc.local(m).equals("marker") ? m : null;
    }

    private final Map<Element, List<Element>> markerPatterns = new IdentityHashMap<>();

    private boolean refersToActive(Element root) {
        for (Element t : markerPatterns.computeIfAbsent(root, this::patternsUnder)) {
            if (active.contains(t)) {
                return true;
            }
        }
        return false;
    }

    private List<Element> patternsUnder(Element root) {
        List<Element> found = new ArrayList<>();
        Deque<Element> todo = new ArrayDeque<>();
        todo.push(root);
        int n = 0;
        while (!todo.isEmpty()) {
            if (++n > 2000) {
                return List.of();
            }
            Element e = todo.pop();
            charge(e.attributesSize() + 1L);
            for (org.jsoup.nodes.Attribute a : e.attributes()) {
                String v = a.getValue();
                int at = v.indexOf("url(");
                while (at >= 0) {
                    int close = v.indexOf(')', at);
                    Element t = doc.byUrl(v.substring(at, close < 0 ? v.length() : close + 1));
                    if (t != null && SvgDoc.local(t).equals("pattern") && !found.contains(t)) {
                        found.add(t);
                    }
                    at = close < 0 ? -1 : v.indexOf("url(", close);
                }
            }
            for (Element c : e.children()) {
                todo.push(c);
            }
        }
        return found;
    }

    private void drawMarker(Element m, Vertex v, boolean first, float strokeWidth) throws IOException {
        if (groups >= MAX_GROUP_DEPTH || spent()) {
            return;
        }
        if (refersToActive(m) || !active.add(m)) {
            markerLoops++;
            return;
        }
        try {
            SvgStyle ms = domStyle(m);
            if (ms.own("display", "inline").equalsIgnoreCase("none")) {
                return;
            }
            float mw = x(m, "markerWidth", ms, 3);
            float mh = y(m, "markerHeight", ms, 3);
            if (!(mw > 0) || !(mh > 0)) {
                return;
            }
            String orient = SvgDoc.attr(m, "orient").strip().toLowerCase(Locale.ROOT);
            double angle;
            if (orient.equals("auto") || orient.equals("auto-start-reverse")) {
                angle = bisector(v);
                if (first && orient.equals("auto-start-reverse")) {
                    angle += Math.PI;
                }
            } else {
                angle = Math.toRadians(angleOf(orient));
            }
            boolean user = SvgDoc.lower(SvgDoc.attr(m, "markerUnits")).equals("userspaceonuse");
            float[] vb = numbers(SvgDoc.attr(m, "viewBox"));
            AffineTransform vbt = viewBox(vb, SvgDoc.attr(m, "preserveAspectRatio"), 0, 0, mw, mh);
            float refX = x(m, "refX", ms, 0);
            float refY = y(m, "refY", ms, 0);
            Point2D ref = vbt == null ? new Point2D.Float(refX, refY) : vbt.transform(new Point2D.Float(refX, refY), null);
            canvas.save();
            try {
                canvas.translate((float) v.x(), (float) v.y());
                canvas.transform(AffineTransform.getRotateInstance(angle));
                if (!user) {
                    canvas.scale(strokeWidth, strokeWidth);
                }
                canvas.translate((float) -ref.getX(), (float) -ref.getY());
                String raw = ms.own.get("overflow");
                String overflow = raw != null && raw.strip().equalsIgnoreCase("inherit") && ms.parent != null
                        ? SvgDoc.lower(ms.parent.own("overflow", "visible"))
                        : SvgDoc.lower(ms.own("overflow", "hidden"));
                if (!overflow.equals("visible") && !overflow.equals("auto")) {
                    canvas.clip(new Rectangle2D.Float(0, 0, mw, mh));
                }
                if (vbt != null) {
                    canvas.transform(vbt);
                    viewports.push(new float[] {vb[2], vb[3]});
                } else {
                    viewports.push(new float[] {mw, mh});
                }
                groups++;
                try {
                    group(m, ms, 0, 0, null);
                } finally {
                    groups--;
                    viewports.pop();
                }
            } finally {
                canvas.restore();
            }
        } finally {
            active.remove(m);
        }
    }

    private static float angleOf(String v) {
        String t = v.strip();
        float k = 1;
        if (t.endsWith("deg")) {
            t = t.substring(0, t.length() - 3);
        } else if (t.endsWith("grad")) {
            t = t.substring(0, t.length() - 4);
            k = 0.9f;
        } else if (t.endsWith("rad")) {
            t = t.substring(0, t.length() - 3);
            k = (float) (180 / Math.PI);
        } else if (t.endsWith("turn")) {
            t = t.substring(0, t.length() - 4);
            k = 360;
        }
        return number(t, 0) * k;
    }

    private static double bisector(Vertex v) {
        if (v.hasIn() && v.hasOut()) {
            double a = v.in();
            double b = v.out();
            double d = b - a;
            while (d > Math.PI) {
                d -= 2 * Math.PI;
            }
            while (d < -Math.PI) {
                d += 2 * Math.PI;
            }
            return a + d / 2;
        }
        return v.hasIn() ? v.in() : v.hasOut() ? v.out() : 0;
    }

    private static List<Vertex> vertices(Path2D p) {
        List<double[]> segs = new ArrayList<>();
        PathIterator it = p.getPathIterator(null);
        double[] c = new double[6];
        double sx = 0;
        double sy = 0;
        double lx = 0;
        double ly = 0;
        while (!it.isDone()) {
            int type = it.currentSegment(c);
            switch (type) {
                case PathIterator.SEG_MOVETO -> {
                    segs.add(new double[] {0, c[0], c[1]});
                    sx = c[0];
                    sy = c[1];
                    lx = c[0];
                    ly = c[1];
                }
                case PathIterator.SEG_LINETO -> {
                    segs.add(new double[] {1, lx, ly, c[0], c[1], lx, ly, c[0], c[1]});
                    lx = c[0];
                    ly = c[1];
                }
                case PathIterator.SEG_QUADTO -> {
                    segs.add(new double[] {2, lx, ly, c[2], c[3], c[0], c[1], c[0], c[1]});
                    lx = c[2];
                    ly = c[3];
                }
                case PathIterator.SEG_CUBICTO -> {
                    segs.add(new double[] {3, lx, ly, c[4], c[5], c[0], c[1], c[2], c[3]});
                    lx = c[4];
                    ly = c[5];
                }
                case PathIterator.SEG_CLOSE -> {
                    segs.add(new double[] {4, lx, ly, sx, sy, lx, ly, sx, sy});
                    lx = sx;
                    ly = sy;
                }
                default -> { }
            }
            it.next();
            if (segs.size() > 200_000) {
                break;
            }
        }
        List<Vertex> out = new ArrayList<>();
        double prevAngle = 0;
        boolean prev = false;
        int subStart = -1;
        double startOut = 0;
        for (int i = 0; i < segs.size(); i++) {
            double[] s = segs.get(i);
            if (s[0] == 0) {
                double outA = 0;
                boolean hasOut = false;
                if (i + 1 < segs.size() && segs.get(i + 1)[0] != 0) {
                    outA = startAngle(segs.get(i + 1));
                    hasOut = true;
                }
                out.add(new Vertex(s[1], s[2], prevAngle, outA, false, hasOut));
                subStart = out.size() - 1;
                startOut = outA;
                prev = false;
                continue;
            }
            double inA = endAngle(s);
            double outA = 0;
            boolean hasOut = false;
            if (i + 1 < segs.size() && segs.get(i + 1)[0] != 0) {
                outA = startAngle(segs.get(i + 1));
                hasOut = true;
            }
            if (s[0] == 4) {
                if (subStart >= 0) {
                    Vertex first = out.get(subStart);
                    out.set(subStart, new Vertex(first.x(), first.y(), inA, first.out(), true, first.hasOut()));
                }
                out.add(new Vertex(s[3], s[4], inA, startOut, true, true));
            } else {
                out.add(new Vertex(s[3], s[4], inA, outA, true, hasOut));
            }
            prevAngle = inA;
            prev = true;
        }
        if (!prev && out.size() == 1) {
            return out;
        }
        return out;
    }

    private static double startAngle(double[] s) {
        double x0 = s[1];
        double y0 = s[2];
        double[][] pts = {{s[5], s[6]}, {s[7], s[8]}, {s[3], s[4]}};
        for (double[] q : pts) {
            if (Math.abs(q[0] - x0) > 1e-9 || Math.abs(q[1] - y0) > 1e-9) {
                return Math.atan2(q[1] - y0, q[0] - x0);
            }
        }
        return 0;
    }

    private static double endAngle(double[] s) {
        double x1 = s[3];
        double y1 = s[4];
        double[][] pts = {{s[7], s[8]}, {s[5], s[6]}, {s[1], s[2]}};
        for (double[] q : pts) {
            if (Math.abs(q[0] - x1) > 1e-9 || Math.abs(q[1] - y1) > 1e-9) {
                return Math.atan2(y1 - q[1], x1 - q[0]);
            }
        }
        return 0;
    }
}
