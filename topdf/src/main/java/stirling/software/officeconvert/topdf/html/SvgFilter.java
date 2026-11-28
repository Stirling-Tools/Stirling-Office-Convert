package stirling.software.officeconvert.topdf.html;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.SinglePixelPackedSampleModel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.util.Matrix;
import org.jsoup.nodes.Element;

final class SvgFilter {

    static final long MAX_PIXELS = 2_000_000L;
    static final int MAX_STEPS = 16;
    static final int MAX_RESULTS = 8;
    static final float PIXELS_PER_POINT = 2.5f;
    static final int MAX_PRIMITIVES = 256;

    private SvgFilter() {}

    static final class Img {
        final int w;
        final int h;
        final float[] p;
        boolean linear;
        java.awt.Shape area;
        Rectangle2D box;

        Img(int w, int h) {
            this(w, h, new float[w * h * 4]);
        }

        private Img(int w, int h, float[] p) {
            this.w = w;
            this.h = h;
            this.p = p;
        }

        Img view() {
            Img c = new Img(w, h, p);
            c.linear = linear;
            c.area = area;
            c.box = box;
            return c;
        }

        Img copy() {
            Img c = new Img(w, h);
            System.arraycopy(p, 0, c.p, 0, p.length);
            c.linear = linear;
            c.area = area;
            c.box = box;
            return c;
        }
    }

    private record Step(String kind, List<String> args, Element element) {}

    record Frame(Rectangle2D device, AffineTransform grid, Rectangle2D exact) {}

    record Ctx(SvgDraw d, AffineTransform toPixel, Frame device, int w, int h, Rectangle2D bbox, boolean boxUnits,
            Rectangle2D region) {}


    static int state(SvgDraw d, String value) {
        List<Step> steps = parse(d, value);
        if (steps == null) {
            return -1;
        }
        return steps.isEmpty() ? 0 : 1;
    }

    private static boolean angleWithUnit(String a) {
        for (String unit : new String[] {"deg", "grad", "rad", "turn"}) {
            if (a.endsWith(unit)) {
                return Float.isFinite(SvgDraw.number(a.substring(0, a.length() - unit.length()), Float.NaN));
            }
        }
        return SvgDraw.number(a, Float.NaN) == 0;
    }

    private static boolean validArgs(String name, List<String> args, String raw) {
        if (Css.splitTopLevel(raw, ',').size() > 1) {
            return false;
        }
        switch (name) {
            case "blur": {
                if (args.size() > 1) {
                    return false;
                }
                if (args.isEmpty()) {
                    return true;
                }
                String a = args.get(0);
                float v = SvgDraw.length(a, 16, Float.NaN, Float.NaN);
                return !a.endsWith("%") && Float.isFinite(v) && v >= 0;
            }
            case "drop-shadow": {
                int lengths = 0;
                for (String a : args) {
                    float v = SvgDraw.length(a, 16, Float.NaN, Float.NaN);
                    if (Float.isFinite(v)) {
                        lengths++;
                    }
                }
                return lengths >= 2 && lengths <= 3;
            }
            case "hue-rotate": {
                if (args.isEmpty()) {
                    return true;
                }
                return args.size() == 1 && angleWithUnit(args.get(0).strip().toLowerCase(Locale.ROOT));
            }
            default: {
                if (args.size() > 1) {
                    return false;
                }
                if (args.isEmpty()) {
                    return true;
                }
                String a = args.get(0).strip();
                float v = a.endsWith("%") ? SvgDraw.number(a.substring(0, a.length() - 1), Float.NaN)
                        : SvgDraw.number(a, Float.NaN);
                return Float.isFinite(v) && v >= 0;
            }
        }
    }

    private static int closing(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static List<Step> parse(SvgDraw d, String value) {
        List<Step> out = new ArrayList<>();
        boolean missing = false;
        String s = value.strip();
        int i = 0;
        while (i < s.length()) {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            if (i >= s.length()) {
                break;
            }
            int open = s.indexOf('(', i);
            int close = open < 0 ? -1 : closing(s, open);
            if (open < 0 || close < 0) {
                return List.of();
            }
            String name = s.substring(i, open).strip().toLowerCase(Locale.ROOT);
            String arg = s.substring(open + 1, close).strip();
            if (name.equals("url")) {
                Element f = d.doc.byUrl("url(" + arg + ")");
                if (f == null || !SvgDoc.local(f).equals("filter")) {
                    missing = true;
                } else {
                    out.add(new Step("url", List.of(), f));
                }
            } else {
                List<String> parts = new ArrayList<>();
                for (String p : Css.splitTopLevel(arg, ' ')) {
                    if (!p.isBlank()) {
                        parts.add(p.strip());
                    }
                }
                switch (name) {
                    case "blur", "drop-shadow", "grayscale", "sepia", "saturate", "hue-rotate", "invert", "opacity",
                            "brightness", "contrast" -> {
                        if (!validArgs(name, parts, arg)) {
                            return List.of();
                        }
                        out.add(new Step(name, parts, null));
                    }
                    default -> {
                        return List.of();
                    }
                }
            }
            i = close + 1;
        }
        return missing && out.isEmpty() ? null : out;
    }

    static void apply(SvgDraw d, String value, Element e, SvgStyle s, Rectangle2D bbox, SvgDraw.Content content)
            throws IOException {
        List<Step> steps = parse(d, value);
        if (steps == null || steps.isEmpty() || steps.size() > MAX_STEPS) {
            content.draw();
            return;
        }
        Rectangle2D through = passThrough(d, steps, bbox);
        if (through != null) {
            d.canvas.save();
            try {
                d.canvas.clip(through);
                content.draw();
            } finally {
                d.canvas.restore();
            }
            return;
        }
        List<float[]> vector = vectorColours(d, steps, e, s);
        if (vector != null) {
            List<float[]> saved = d.recolour;
            List<float[]> now = new ArrayList<>(vector);
            if (saved != null) {
                now.addAll(saved);
            }
            d.recolour = now;
            try {
                content.draw();
            } finally {
                d.recolour = saved;
            }
            return;
        }
        if (d.feImages > 0) {
            for (Step st : steps) {
                if (st.element() != null && d.activeFilters.contains(st.element())) {
                    content.draw();
                    return;
                }
            }
        }
        List<Element> mine = new ArrayList<>();
        for (Step st : steps) {
            if (st.element() != null && d.activeFilters.add(st.element())) {
                mine.add(st.element());
            }
        }
        try {
            applySteps(d, steps, e, s, bbox, content);
        } finally {
            mine.forEach(d.activeFilters::remove);
        }
    }

    private static final java.util.Set<String> SHAPES = java.util.Set.of("rect", "circle", "ellipse", "line",
            "polyline", "polygon", "path");

    private static Rectangle2D passThrough(SvgDraw d, List<Step> steps, Rectangle2D bbox) {
        Rectangle2D region = null;
        for (Step st : steps) {
            Element owner = st.element();
            if (owner == null) {
                return null;
            }
            for (int i = 0; owner != null && i < 8 && owner.children().isEmpty(); i++) {
                Element next = d.doc.target(owner);
                owner = next != null && SvgDoc.local(next).equals("filter") ? next : null;
            }
            if (owner == null) {
                return null;
            }
            int n = 0;
            for (Element p : owner.children()) {
                String tag = SvgDoc.local(p).toLowerCase(Locale.ROOT);
                if (!tag.startsWith("fe")) {
                    continue;
                }
                n++;
                String in = SvgDoc.attr(p, "in").strip();
                if (!in.isEmpty() && !in.equals("SourceGraphic") || SvgDoc.has(p, "x") || SvgDoc.has(p, "y")
                        || SvgDoc.has(p, "width") || SvgDoc.has(p, "height")) {
                    return null;
                }
                boolean same = switch (tag) {
                    case "feconvolvematrix" -> SvgLighting.passesThrough(p);
                    case "femorphology" -> {
                        float[] r = SvgDraw.numbers(SvgDoc.attr(p, "radius"));
                        float rx = r.length > 0 ? r[0] : 0;
                        float ry = r.length > 1 ? r[1] : rx;
                        yield rx < 0 || ry < 0 || rx == 0 && ry == 0;
                    }
                    default -> false;
                };
                if (!same) {
                    return null;
                }
            }
            Rectangle2D r = n == 0 ? null : region(d, st.element(), bbox);
            if (r == null) {
                return null;
            }
            region = region == null ? r : region.createIntersection(r);
        }
        return region;
    }

    private static List<float[]> vectorColours(SvgDraw d, List<Step> steps, Element e, SvgStyle s) {
        List<float[]> out = new ArrayList<>();
        boolean fades = false;
        for (Step st : steps) {
            if (st.element() != null) {
                return null;
            }
            if (st.kind().equals("blur")) {
                if (!st.args().isEmpty() && SvgDraw.length(st.args().get(0), s.fontSize, 0, 0) != 0) {
                    return null;
                }
                continue;
            }
            String[] tv = st.kind().equals("drop-shadow") ? null : cssColour(st);
            float[] m = tv == null ? null : matrixOf(tv[0], tv[1]);
            if (m == null) {
                return null;
            }
            fades |= m[18] != 1 || m[15] != 0 || m[16] != 0 || m[17] != 0 || m[19] != 0;
            out.add(m);
        }
        if (out.isEmpty()) {
            return out;
        }
        if (!SHAPES.contains(SvgDoc.local(e)) || d.recolour == null && d.feImages > 0) {
            return null;
        }
        String fill = SvgDoc.lower(s.get("fill"));
        String stroke = SvgDoc.lower(s.get("stroke"));
        if (fill.contains("url(") || stroke.contains("url(") || fill.startsWith("context-")
                || stroke.startsWith("context-")) {
            return null;
        }
        for (String m : List.of("marker-start", "marker-mid", "marker-end")) {
            String v = SvgDoc.lower(s.get(m));
            if (!v.isEmpty() && !v.equals("none")) {
                return null;
            }
        }
        if (fades && !fill.equals("none") && !stroke.equals("none") && !stroke.isEmpty()) {
            return null;
        }
        return out;
    }

    private static void applySteps(SvgDraw d, List<Step> steps, Element e, SvgStyle s, Rectangle2D bbox,
            SvgDraw.Content content) throws IOException {
        Rectangle2D region = null;
        for (Step st : steps) {
            Rectangle2D r = st.element() != null ? region(d, st.element(), bbox) : functionRegion(bbox, st, s);
            if (r == null) {
                return;
            }
            region = region == null ? r : region.createUnion(r);
        }
        if (region == null || region.isEmpty()) {
            return;
        }
        AffineTransform raw = d.raw();
        if ((Math.abs(raw.getShearX()) > 1e-9 || Math.abs(raw.getShearY()) > 1e-9) && directional(d, steps)) {
            applyLocal(d, steps, region, raw, bbox, s, content);
            return;
        }
        Rectangle2D device = raw.createTransformedShape(region).getBounds2D();
        Rectangle2D page = new Rectangle2D.Double(-2 * d.canvas.width(), -2 * d.canvas.height(), 5 * d.canvas.width(),
                5 * d.canvas.height());
        device = device.createIntersection(page);
        if (device.isEmpty()) {
            return;
        }
        double scale = PIXELS_PER_POINT;
        double px = device.getWidth() * scale * device.getHeight() * scale;
        if (px > MAX_PIXELS) {
            scale *= Math.sqrt(MAX_PIXELS / px);
        }
        int w = Math.max(1, (int) Math.ceil(device.getWidth() * scale));
        int h = Math.max(1, (int) Math.ceil(device.getHeight() * scale));
        device = new Rectangle2D.Double(device.getX(), device.getY() + device.getHeight() - h / scale, w / scale,
                h / scale);
        d.charge((long) w * h * (steps.size() + 1));
        if (d.spent()) {
            return;
        }
        AffineTransform toPixel = new AffineTransform(w / device.getWidth(), 0, 0, -h / device.getHeight(),
                -device.getX() * w / device.getWidth(), (device.getY() + device.getHeight()) * h / device.getHeight());
        toPixel.concatenate(raw);
        PDFormXObject form = d.capture(content, d.canvas.transform());
        if (form == null) {
            return;
        }
        long bytes = (long) w * h * 16L * (6 + MAX_RESULTS);
        stirling.software.officeconvert.memory.Admission.Ticket ticket =
                stirling.software.officeconvert.memory.Admission.jvm().enter(bytes);
        try {
            filtered(d, steps, form, new Frame(device, null, null), w, h, toPixel, bbox, s,
                    shadowLast(steps) ? content : null, region);
        } finally {
            ticket.close();
        }
    }

    private static boolean directional(SvgDraw d, List<Step> steps) {
        for (Step st : steps) {
            Element owner = st.element();
            for (int i = 0; owner != null && i < 8 && owner.children().isEmpty(); i++) {
                Element next = d.doc.target(owner);
                owner = next != null && SvgDoc.local(next).equals("filter") ? next : null;
            }
            if (owner == null) {
                continue;
            }
            for (Element p : owner.children()) {
                String tag = SvgDoc.local(p).toLowerCase(Locale.ROOT);
                String pair = tag.equals("fegaussianblur") ? "stdDeviation" : tag.equals("femorphology") ? "radius" : null;
                if (tag.equals("feconvolvematrix")) {
                    return true;
                }
                if (pair != null) {
                    float[] v = SvgDraw.numbers(SvgDoc.attr(p, pair));
                    if (v.length > 1 && v[0] != v[1]) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void applyLocal(SvgDraw d, List<Step> steps, Rectangle2D region, AffineTransform raw,
            Rectangle2D bbox, SvgStyle s, SvgDraw.Content content) throws IOException {
        double kx = PIXELS_PER_POINT * Math.hypot(raw.getScaleX(), raw.getShearY());
        double ky = PIXELS_PER_POINT * Math.hypot(raw.getShearX(), raw.getScaleY());
        double px = region.getWidth() * kx * region.getHeight() * ky;
        if (!(px > 0) || !Double.isFinite(px)) {
            return;
        }
        if (px > MAX_PIXELS) {
            double f = Math.sqrt(MAX_PIXELS / px);
            kx *= f;
            ky *= f;
        }
        int w = Math.max(1, (int) Math.ceil(region.getWidth() * kx));
        int h = Math.max(1, (int) Math.ceil(region.getHeight() * ky));
        d.charge((long) w * h * (steps.size() + 1));
        if (d.spent()) {
            return;
        }
        AffineTransform toPixel = new AffineTransform(kx, 0, 0, ky, -region.getX() * kx, -region.getY() * ky);
        AffineTransform grid;
        try {
            grid = new AffineTransform(toPixel);
            grid.concatenate(raw.createInverse());
        } catch (java.awt.geom.NoninvertibleTransformException e) {
            return;
        }
        PDFormXObject form = d.capture(content, d.canvas.transform());
        if (form == null) {
            return;
        }
        long bytes = (long) w * h * 16L * (6 + MAX_RESULTS);
        stirling.software.officeconvert.memory.Admission.Ticket ticket =
                stirling.software.officeconvert.memory.Admission.jvm().enter(bytes);
        try {
            filtered(d, steps, form, new Frame(null, grid,
                    new Rectangle2D.Double(0, 0, region.getWidth() * kx, region.getHeight() * ky)), w, h, toPixel, bbox, s,
                    shadowLast(steps) ? content : null, region);
        } finally {
            ticket.close();
        }
    }

    private static boolean shadowLast(List<Step> steps) {
        Step last = steps.get(steps.size() - 1);
        return last.element() == null && last.kind().equals("drop-shadow");
    }

    private static void filtered(SvgDraw d, List<Step> steps, PDFormXObject form, Frame device, int w, int h,
            AffineTransform toPixel, Rectangle2D bbox, SvgStyle s, SvgDraw.Content over, Rectangle2D region)
            throws IOException {
        Img src = rasterize(form, device, w, h);
        Img result = src;
        for (int i = 0; i < steps.size(); i++) {
            Step st = steps.get(i);
            stirling.software.officeconvert.extract.PdfFiles.stopIfInterrupted();
            result = st.element() != null ? run(d, st.element(), bbox, toPixel, result, device)
                    : function(st, result, toPixel, s, over != null && i == steps.size() - 1);
            if (result == null) {
                return;
            }
        }
        BufferedImage out = toImage(result);
        PDImageXObject image = encode(d.output.document(), out);
        PDPageContentStream cs = d.canvas.stream();
        cs.saveGraphicsState();
        try {
            cs.transform(new Matrix(d.rawInverse()));
            if (device.grid() == null) {
                Rectangle2D box = device.device();
                cs.drawImage(image, (float) box.getX(), (float) box.getY(), (float) box.getWidth(),
                        (float) box.getHeight());
            } else {
                AffineTransform place = device.grid().createInverse();
                place.concatenate(new AffineTransform(w, 0, 0, -h, 0, h));
                cs.transform(new Matrix(place));
                Rectangle2D exact = device.exact();
                cs.addRect(0, (float) (1 - exact.getHeight() / h), (float) (exact.getWidth() / w),
                        (float) (exact.getHeight() / h));
                cs.clip();
                cs.drawImage(image, 0, 0, 1, 1);
            }
        } catch (java.awt.geom.NoninvertibleTransformException e) {
            return;
        } finally {
            cs.restoreGraphicsState();
        }
        if (over != null) {
            d.canvas.save();
            try {
                d.canvas.clip(region);
                over.draw();
            } finally {
                d.canvas.restore();
            }
        }
    }

    private static Img rasterize(PDFormXObject form, Frame frame, int w, int h) throws IOException {
        if (frame.grid() != null) {
            try (PDDocument tmp = new PDDocument()) {
                PDPage page = new PDPage(new PDRectangle(w + 2, h + 2));
                tmp.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(tmp, page)) {
                    AffineTransform t = new AffineTransform(1, 0, 0, -1, 0, h + 2);
                    t.concatenate(frame.grid());
                    cs.transform(new Matrix(t));
                    cs.drawForm(form);
                }
                BufferedImage img = new PDFRenderer(tmp).renderImage(0, 1, ImageType.ARGB);
                return fromImage(img, w, h);
            }
        }
        Rectangle2D device = frame.device();
        double scale = w / device.getWidth();
        try (PDDocument tmp = new PDDocument()) {
            float pw = (float) ((w + 2) / scale);
            float ph = (float) ((h + 2) / scale);
            PDPage page = new PDPage(new PDRectangle(pw, ph));
            tmp.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(tmp, page)) {
                cs.transform(Matrix.getTranslateInstance((float) -device.getX(),
                        (float) (ph - device.getHeight() - device.getY())));
                cs.drawForm(form);
            }
            PDFRenderer r = new PDFRenderer(tmp);
            BufferedImage img = r.renderImage(0, (float) scale, ImageType.ARGB);
            return fromImage(img, w, h);
        }
    }

    private static Rectangle2D region(SvgDraw d, Element f, Rectangle2D bbox) {
        Element owner = f;
        SvgStyle fs = d.domStyle(f);
        boolean user = SvgDoc.lower(attr(d, f, "filterUnits")).equals("userspaceonuse");
        if (user) {
            return new Rectangle2D.Double(len(d, f, "x", fs, d.vw(), -0.1f * d.vw()),
                    len(d, f, "y", fs, d.vh(), -0.1f * d.vh()), len(d, f, "width", fs, d.vw(), 1.2f * d.vw()),
                    len(d, f, "height", fs, d.vh(), 1.2f * d.vh()));
        }
        if (bbox == null || bbox.getWidth() <= 0 || bbox.getHeight() <= 0) {
            return null;
        }
        return new Rectangle2D.Double(bbox.getX() + frac(attr(d, owner, "x"), -0.1f) * bbox.getWidth(),
                bbox.getY() + frac(attr(d, owner, "y"), -0.1f) * bbox.getHeight(),
                frac(attr(d, owner, "width"), 1.2f) * bbox.getWidth(),
                frac(attr(d, owner, "height"), 1.2f) * bbox.getHeight());
    }

    private static String attr(SvgDraw d, Element f, String name) {
        Element cur = f;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (SvgDoc.has(cur, name)) {
                return SvgDoc.attr(cur, name);
            }
            Element next = d.doc.target(cur);
            if (next == null || !SvgDoc.local(next).equals("filter")) {
                break;
            }
            cur = next;
        }
        return "";
    }

    private static float len(SvgDraw d, Element f, String name, SvgStyle s, float percent, float fallback) {
        String v = attr(d, f, name);
        return v.isEmpty() ? fallback : SvgDraw.length(v, s.fontSize, percent, fallback);
    }

    private static float frac(String v, float fallback) {
        String t = v.strip();
        if (t.isEmpty()) {
            return fallback;
        }
        if (t.endsWith("%")) {
            return SvgDraw.number(t.substring(0, t.length() - 1), fallback * 100) / 100f;
        }
        return SvgDraw.number(t, fallback);
    }

    private static Rectangle2D functionRegion(Rectangle2D bbox, Step st, SvgStyle s) {
        if (bbox == null) {
            return null;
        }
        double grow = 0;
        if (st.kind().equals("blur") && !st.args().isEmpty()) {
            grow = 3 * SvgDraw.length(st.args().get(0), s.fontSize, 0, 0);
        } else if (st.kind().equals("drop-shadow")) {
            grow = 0;
            for (String a : st.args()) {
                float v = SvgDraw.length(a, s.fontSize, 0, Float.NaN);
                if (Float.isFinite(v)) {
                    grow += Math.abs(v) * 3;
                }
            }
        }
        return new Rectangle2D.Double(bbox.getX() - grow - 0.1 * bbox.getWidth(),
                bbox.getY() - grow - 0.1 * bbox.getHeight(), bbox.getWidth() * 1.2 + 2 * grow,
                bbox.getHeight() * 1.2 + 2 * grow);
    }


    private static Img run(SvgDraw d, Element f, Rectangle2D bbox, AffineTransform toPixel, Img sourceGraphic,
            Frame device) throws IOException {
        SvgStyle fs = d.domStyle(f);
        Element owner = f;
        for (int i = 0; i < 8; i++) {
            if (!owner.children().isEmpty()) {
                break;
            }
            Element next = d.doc.target(owner);
            if (next == null || !SvgDoc.local(next).equals("filter")) {
                break;
            }
            owner = next;
        }
        boolean boxUnits = SvgDoc.lower(attr(d, f, "primitiveUnits")).equals("objectboundingbox");
        Rectangle2D region = region(d, f, bbox);
        if (region == null) {
            return null;
        }
        Ctx ctx = new Ctx(d, toPixel, device, sourceGraphic.w, sourceGraphic.h, bbox, boxUnits, region);
        java.awt.Shape regionShape = toPixel.createTransformedShape(region);
        Map<String, Img> results = new HashMap<>();
        Img source = sourceGraphic.view();
        source.linear = false;
        source.area = regionShape;
        source.box = region;
        Img sourceLinear = null;
        Img last = null;
        int n = 0;
        for (Element p : owner.children()) {
            if (++n > MAX_PRIMITIVES || d.spent() || Thread.currentThread().isInterrupted()) {
                break;
            }
            d.charge((long) sourceGraphic.w * sourceGraphic.h);
            String tag = SvgDoc.local(p).toLowerCase(Locale.ROOT);
            if (!tag.startsWith("fe")) {
                continue;
            }
            SvgStyle ps = fs.child(d.doc.specified(p));
            boolean lin = !SvgDoc.lower(ps.get("color-interpolation-filters")).equals("srgb");
            if (lin && sourceLinear == null) {
                sourceLinear = toLinear(source);
            }
            Img src = lin ? sourceLinear : source;
            Rectangle2D def = defaultBox(tag, p, results, last, region);
            Rectangle2D box = userBox(d, p, ps, ctx, def);
            java.awt.Shape sub = box == null ? new Rectangle2D.Double() : clipped(ctx, box, regionShape);
            Img in1 = space(input(SvgDoc.attr(p, "in"), results, last, src), lin);
            Img out;
            try {
                out = primitive(tag, p, ps, in1, results, last, src, lin, ctx, sub, box);
            } catch (RuntimeException ex) {
                out = null;
            }
            if (out == null) {
                out = in1.copy();
            } else if (out == in1 || out == src || out == last || out.p == source.p || results.containsValue(out)) {
                out = out.copy();
            }
            out.linear = lin;
            out.area = sub;
            out.box = box;
            clipTo(out, sub);
            String name = SvgDoc.attr(p, "result").strip();
            last = out;
            if (!name.isEmpty() && (results.size() < MAX_RESULTS || results.containsKey(name))) {
                results.put(name, out);
            }
        }
        if (last == null) {
            return new Img(sourceGraphic.w, sourceGraphic.h);
        }
        Img fin = last.linear ? srgbInPlace(last) : last;
        clipTo(fin, regionShape);
        return fin;
    }

    private static Img space(Img img, boolean linear) {
        if (img.linear == linear) {
            return img;
        }
        return linear ? toLinear(img) : toSrgb(img);
    }

    private static Img input(String name, Map<String, Img> results, Img last, Img src) {
        String n = name.strip();
        if (n.isEmpty()) {
            if (last == null) {
                return src;
            }
            return last;
        }
        switch (n) {
            case "SourceGraphic":
                return src;
            case "SourceAlpha": {
                Img a = new Img(src.w, src.h);
                a.linear = src.linear;
                a.area = src.area;
                for (int i = 3; i < src.p.length; i += 4) {
                    a.p[i] = src.p[i];
                }
                return a;
            }
            case "BackgroundImage", "BackgroundAlpha", "FillPaint", "StrokePaint": {
                Img empty = new Img(src.w, src.h);
                empty.linear = src.linear;
                empty.area = src.area;
                return empty;
            }
            default: {
                Img r = results.get(n);
                if (r != null) {
                    return r;
                }
                return last == null ? src : last;
            }
        }
    }

    private static Rectangle2D defaultBox(String tag, Element p, Map<String, Img> results, Img last,
            Rectangle2D region) {
        if (tag.equals("feflood") || tag.equals("feimage") || tag.equals("feturbulence") || tag.equals("fetile")) {
            return region;
        }
        List<String> names = new ArrayList<>();
        names.add(SvgDoc.attr(p, "in").strip());
        if (tag.equals("fecomposite") || tag.equals("feblend") || tag.equals("fedisplacementmap")) {
            names.add(SvgDoc.attr(p, "in2").strip());
        }
        if (tag.equals("femerge")) {
            names.clear();
            for (Element node : p.children()) {
                if (SvgDoc.local(node).equalsIgnoreCase("femergenode")) {
                    names.add(SvgDoc.attr(node, "in").strip());
                }
            }
            if (names.isEmpty()) {
                return region;
            }
        }
        Rectangle2D union = null;
        for (String n : names) {
            Img r = n.isEmpty() ? last : results.get(n);
            if (r == null || r.box == null) {
                return region;
            }
            union = union == null ? r.box : union.createUnion(r.box);
        }
        return union == null ? region : union;
    }

    private static Rectangle2D userBox(SvgDraw d, Element p, SvgStyle ps, Ctx c, Rectangle2D def) {
        Rectangle2D bbox = c.bbox();
        double x;
        double y;
        double ww;
        double hh;
        if (c.boxUnits() && bbox != null) {
            x = SvgDoc.has(p, "x") ? bbox.getX() + frac(SvgDoc.attr(p, "x"), 0) * bbox.getWidth() : def.getX();
            y = SvgDoc.has(p, "y") ? bbox.getY() + frac(SvgDoc.attr(p, "y"), 0) * bbox.getHeight() : def.getY();
            ww = SvgDoc.has(p, "width") ? frac(SvgDoc.attr(p, "width"), 1) * bbox.getWidth() : def.getWidth();
            hh = SvgDoc.has(p, "height") ? frac(SvgDoc.attr(p, "height"), 1) * bbox.getHeight() : def.getHeight();
        } else {
            x = SvgDoc.has(p, "x") ? d.x(p, "x", ps, 0) : def.getX();
            y = SvgDoc.has(p, "y") ? d.y(p, "y", ps, 0) : def.getY();
            ww = SvgDoc.has(p, "width") ? d.x(p, "width", ps, 0) : def.getWidth();
            hh = SvgDoc.has(p, "height") ? d.y(p, "height", ps, 0) : def.getHeight();
        }
        if (!(ww > 0) || !(hh > 0)) {
            return null;
        }
        return new Rectangle2D.Double(x, y, ww, hh);
    }

    private static java.awt.Shape clipped(Ctx c, Rectangle2D box, java.awt.Shape region) {
        java.awt.geom.Area a = new java.awt.geom.Area(c.toPixel().createTransformedShape(box));
        a.intersect(new java.awt.geom.Area(region));
        return a;
    }

    static int[] bounds(java.awt.Shape s, int w, int h) {
        Rectangle2D b = s.getBounds2D();
        int x0 = (int) Math.max(0, Math.floor(b.getMinX() + 1e-6));
        int y0 = (int) Math.max(0, Math.floor(b.getMinY() + 1e-6));
        int x1 = (int) Math.min(w, Math.ceil(b.getMaxX() - 1e-6));
        int y1 = (int) Math.min(h, Math.ceil(b.getMaxY() - 1e-6));
        return new int[] {x0, y0, Math.max(x0, x1), Math.max(y0, y1)};
    }

    private static void clipTo(Img img, java.awt.Shape area) {
        if (area == null) {
            return;
        }
        BufferedImage mask = new BufferedImage(img.w, img.h, BufferedImage.TYPE_BYTE_GRAY);
        var g = mask.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(java.awt.Color.WHITE);
            g.fill(area);
        } finally {
            g.dispose();
        }
        byte[] m = ((java.awt.image.DataBufferByte) mask.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < m.length; i++) {
            int v = m[i] & 0xFF;
            if (v == 255) {
                continue;
            }
            float k = v / 255f;
            int j = i * 4;
            img.p[j] *= k;
            img.p[j + 1] *= k;
            img.p[j + 2] *= k;
            img.p[j + 3] *= k;
        }
    }

    private static double[] scales(AffineTransform toPixel) {
        double sx = Math.hypot(toPixel.getScaleX(), toPixel.getShearY());
        double sy = Math.hypot(toPixel.getShearX(), toPixel.getScaleY());
        return new double[] {sx, sy};
    }

    private static Img primitive(String tag, Element p, SvgStyle ps, Img in1, Map<String, Img> results, Img last,
            Img src, boolean lin, Ctx c, java.awt.Shape sub, Rectangle2D box) throws IOException {
        SvgDraw d = c.d();
        AffineTransform toPixel = c.toPixel();
        Rectangle2D bbox = c.bbox();
        double bw = c.boxUnits() && bbox != null ? bbox.getWidth() : 1;
        double bh = c.boxUnits() && bbox != null ? bbox.getHeight() : 1;
        switch (tag) {
            case "fegaussianblur": {
                float[] sd = SvgDraw.numbers(SvgDoc.attr(p, "stdDeviation"));
                float sx = sd.length > 0 ? sd[0] : 0;
                float sy = sd.length > 1 ? sd[1] : sx;
                if (sx < 0 || sy < 0 || sd.length > 2) {
                    return in1;
                }
                double[] k = scales(toPixel);
                return blur(in1, sx * bw * k[0], sy * bh * k[1]);
            }
            case "feoffset": {
                float dx = SvgDraw.number(SvgDoc.attr(p, "dx"), 0);
                float dy = SvgDraw.number(SvgDoc.attr(p, "dy"), 0);
                Point2D v = new Point2D.Double(dx * bw, dy * bh);
                Point2D o = toPixel.deltaTransform(v, null);
                return offset(in1, o.getX(), o.getY());
            }
            case "feflood": {
                return flood(in1.w, in1.h, ps.own("flood-color", "black"), Css.opacity(ps.own("flood-opacity", "1")),
                        lin, ps.color);
            }
            case "fecomposite": {
                Img in2 = space(input(SvgDoc.attr(p, "in2"), results, last, src), lin);
                String op = SvgDoc.lower(SvgDoc.attr(p, "operator"));
                return composite(in1, in2, op.isEmpty() ? "over" : op, SvgDraw.number(SvgDoc.attr(p, "k1"), 0),
                        SvgDraw.number(SvgDoc.attr(p, "k2"), 0), SvgDraw.number(SvgDoc.attr(p, "k3"), 0),
                        SvgDraw.number(SvgDoc.attr(p, "k4"), 0));
            }
            case "femerge": {
                Img out = new Img(in1.w, in1.h);
                for (Element node : p.children()) {
                    if (!SvgDoc.local(node).equalsIgnoreCase("femergenode")) {
                        continue;
                    }
                    d.charge((long) in1.w * in1.h);
                    if (d.spent() || Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    Img in = space(input(SvgDoc.attr(node, "in"), results, last, src), lin);
                    out = composite(in, out, "over", 0, 0, 0, 0);
                }
                return out;
            }
            case "feblend": {
                Img in2 = space(input(SvgDoc.attr(p, "in2"), results, last, src), lin);
                return blend(in1, in2, SvgDoc.lower(SvgDoc.attr(p, "mode")));
            }
            case "fecolormatrix":
                return colorMatrix(in1, SvgDoc.lower(SvgDoc.attr(p, "type")), SvgDoc.attr(p, "values"));
            case "fecomponenttransfer":
                return transfer(in1, p);
            case "fedropshadow": {
                float[] sd = SvgDraw.numbers(SvgDoc.attr(p, "stdDeviation"));
                float sx = sd.length > 0 ? sd[0] : 2;
                float sy = sd.length > 1 ? sd[1] : sx;
                double[] k = scales(toPixel);
                Img alpha = new Img(in1.w, in1.h);
                Img flood = flood(in1.w, in1.h, ps.own("flood-color", "black"),
                        Css.opacity(ps.own("flood-opacity", "1")), lin, ps.color);
                for (int i = 0; i < alpha.p.length; i += 4) {
                    float a = in1.p[i + 3];
                    alpha.p[i] = flood.p[i] * a;
                    alpha.p[i + 1] = flood.p[i + 1] * a;
                    alpha.p[i + 2] = flood.p[i + 2] * a;
                    alpha.p[i + 3] = flood.p[i + 3] * a;
                }
                Img blurred = blur(alpha, sx * bw * k[0], sy * bh * k[1]);
                Point2D o = toPixel.deltaTransform(new Point2D.Double(SvgDraw.number(SvgDoc.attr(p, "dx"), 2) * bw,
                        SvgDraw.number(SvgDoc.attr(p, "dy"), 2) * bh), null);
                Img shadow = offset(blurred, o.getX(), o.getY());
                return composite(in1, shadow, "over", 0, 0, 0, 0);
            }
            case "femorphology": {
                float[] r = SvgDraw.numbers(SvgDoc.attr(p, "radius"));
                float rx = r.length > 0 ? r[0] : 0;
                float ry = r.length > 1 ? r[1] : rx;
                if (rx < 0 || ry < 0 || rx == 0 && ry == 0) {
                    return in1;
                }
                double[] k = scales(toPixel);
                boolean dilate = SvgDoc.lower(SvgDoc.attr(p, "operator")).equals("dilate");
                return morphology(in1, pixels(rx * bw * k[0], in1.w), pixels(ry * bh * k[1], in1.h), dilate);
            }
            case "fetile":
                return tile(in1, in1.area == null ? null : in1.area.getBounds2D());
            case "feconvolvematrix":
                return SvgLighting.convolve(p, in1, d);
            case "fedisplacementmap": {
                Img map = space(input(SvgDoc.attr(p, "in2"), results, last, src), lin);
                double[] k = scales(toPixel);
                float scale = SvgDraw.number(SvgDoc.attr(p, "scale"), 0);
                return SvgLighting.displace(in1, map, scale * bw * k[0], scale * bh * k[1],
                        SvgDoc.attr(p, "xChannelSelector"), SvgDoc.attr(p, "yChannelSelector"));
            }
            case "fediffuselighting", "fespecularlighting":
                return SvgLighting.light(p, ps, in1, lin, tag.equals("fespecularlighting"), c);
            case "feimage":
                return image(p, ps, c, sub, box, lin);
            case "feturbulence":
                d.charge((long) in1.w * in1.h * 4 * Math.max(1, Math.min(10,
                        (long) SvgDraw.number(SvgDoc.attr(p, "numOctaves"), 1))));
                if (d.spent()) {
                    return new Img(in1.w, in1.h);
                }
                int[] tb = bounds(sub, in1.w, in1.h);
                return SvgTurbulence.render(p, in1.w, in1.h, toPixel, lin,
                        new Rectangle2D.Double(tb[0], tb[1], tb[2] - tb[0], tb[3] - tb[1]));
            default:
                return null;
        }
    }


    static final int MAX_IMAGE_DEPTH = 2;

    private static Img image(Element p, SvgStyle ps, Ctx c, java.awt.Shape sub, Rectangle2D box, boolean lin)
            throws IOException {
        SvgDraw d = c.d();
        Img empty = new Img(c.w(), c.h());
        if (d.feImages >= MAX_IMAGE_DEPTH || d.spent()) {
            return empty;
        }
        String href = SvgDoc.href(p);
        Rectangle2D area;
        try {
            area = c.toPixel().createInverse().createTransformedShape(sub).getBounds2D();
        } catch (java.awt.geom.NoninvertibleTransformException e) {
            return empty;
        }
        PDFormXObject form;
        d.feImages++;
        try {
            if (href.startsWith("#")) {
                Element target = d.doc.byUrl(href);
                if (target == null) {
                    return empty;
                }
                Element parent = target.parent();
                SvgStyle base = parent == null || parent.parent() == null ? SvgStyle.root() : d.domStyle(parent);
                Rectangle2D origin = box != null ? box : area;
                form = d.capture(() -> {
                    d.canvas.translate((float) origin.getX(), (float) origin.getY());
                    d.element(target, base, 0, 0);
                }, d.canvas.transform());
            } else {
                byte[] data = SvgDraw.dataUri(href);
                if (data == null) {
                    return empty;
                }
                d.charge(data.length);
                form = d.capture(() -> d.picture(data, (float) area.getX(), (float) area.getY(),
                        (float) area.getWidth(), (float) area.getHeight(), SvgDoc.attr(p, "preserveAspectRatio"),
                        SvgDoc.lower(ps.get("image-rendering"))), d.canvas.transform());
            }
        } finally {
            d.feImages--;
        }
        if (form == null) {
            return empty;
        }
        Img picture = rasterize(form, c.device(), c.w(), c.h());
        return lin ? toLinear(picture) : picture;
    }

    private static Img function(Step st, Img in, AffineTransform toPixel, SvgStyle s, boolean shadowOnly) {
        List<String> a = st.args();
        double[] k = scales(toPixel);
        switch (st.kind()) {
            case "blur": {
                float r = a.isEmpty() ? 0 : SvgDraw.length(a.get(0), s.fontSize, 0, 0);
                return blur(in, r * k[0], r * k[1]);
            }
            case "drop-shadow": {
                List<Float> nums = new ArrayList<>();
                String colour = null;
                for (String x : a) {
                    float v = SvgDraw.length(x, s.fontSize, 0, Float.NaN);
                    if (Float.isFinite(v) && nums.size() < 3) {
                        nums.add(v);
                    } else {
                        colour = colour == null ? x : colour + " " + x;
                    }
                }
                float dx = nums.size() > 0 ? nums.get(0) : 0;
                float dy = nums.size() > 1 ? nums.get(1) : 0;
                float sd = nums.size() > 2 ? nums.get(2) : 0;
                Img lin = in;
                Img flood = flood(in.w, in.h, colour == null ? "currentcolor" : colour, 1, false, s.color);
                Img alpha = new Img(in.w, in.h);
                for (int i = 0; i < alpha.p.length; i += 4) {
                    float al = lin.p[i + 3];
                    for (int c = 0; c < 4; c++) {
                        alpha.p[i + c] = flood.p[i + c] * al;
                    }
                }
                Point2D o = toPixel.deltaTransform(new Point2D.Double(dx, dy), null);
                Img shadow = offset(blur(alpha, sd * k[0], sd * k[1]), o.getX(), o.getY());
                return shadowOnly ? shadow : composite(lin, shadow, "over", 0, 0, 0, 0);
            }
            default: {
                String[] tv = cssColour(st);
                return tv == null ? in : colorMatrix(in, tv[0], tv[1]);
            }
        }
    }

    private static String[] cssColour(Step st) {
        List<String> a = st.args();
        float amount = a.isEmpty() ? (st.kind().equals("hue-rotate") ? 0 : 1) : amountOf(a.get(0), st.kind());
        String values;
        String type = "matrix";
        switch (st.kind()) {
            case "grayscale" -> {
                float t = 1 - Math.min(1, amount);
                values = (0.2126 + 0.7874 * t) + " " + (0.7152 - 0.7152 * t) + " " + (0.0722 - 0.0722 * t)
                        + " 0 0 " + (0.2126 - 0.2126 * t) + " " + (0.7152 + 0.2848 * t) + " "
                        + (0.0722 - 0.0722 * t) + " 0 0 " + (0.2126 - 0.2126 * t) + " " + (0.7152 - 0.7152 * t)
                        + " " + (0.0722 + 0.9278 * t) + " 0 0 0 0 0 1 0";
            }
            case "sepia" -> {
                float t = 1 - Math.min(1, amount);
                values = (0.393 + 0.607 * t) + " " + (0.769 - 0.769 * t) + " " + (0.189 - 0.189 * t)
                        + " 0 0 " + (0.349 - 0.349 * t) + " " + (0.686 + 0.314 * t) + " " + (0.168 - 0.168 * t)
                        + " 0 0 " + (0.272 - 0.272 * t) + " " + (0.534 - 0.534 * t) + " " + (0.131 + 0.869 * t)
                        + " 0 0 0 0 0 1 0";
            }
            case "saturate" -> {
                type = "saturate";
                values = String.valueOf(amount);
            }
            case "hue-rotate" -> {
                type = "huerotate";
                values = String.valueOf(a.isEmpty() ? 0 : angle(a.get(0)));
            }
            case "invert" -> {
                float t = Math.min(1, amount);
                float m = 1 - 2 * t;
                values = m + " 0 0 0 " + t + " 0 " + m + " 0 0 " + t + " 0 0 " + m + " 0 " + t + " 0 0 0 1 0";
            }
            case "opacity" -> values = "1 0 0 0 0 0 1 0 0 0 0 0 1 0 0 0 0 0 " + Math.min(1, amount) + " 0";
            case "brightness" -> values = amount + " 0 0 0 0 0 " + amount + " 0 0 0 0 0 " + amount
                    + " 0 0 0 0 0 1 0";
            case "contrast" -> {
                float off = (1 - amount) / 2;
                values = amount + " 0 0 0 " + off + " 0 " + amount + " 0 0 " + off + " 0 0 " + amount + " 0 "
                        + off + " 0 0 0 1 0";
            }
            default -> {
                return null;
            }
        }
        return new String[] {type, values};
    }

    private static float amountOf(String v, String kind) {
        String t = v.strip();
        if (t.endsWith("%")) {
            return Math.max(0, SvgDraw.number(t.substring(0, t.length() - 1), 100) / 100f);
        }
        return Math.max(0, SvgDraw.number(t, 1));
    }

    private static int pixels(double v, int size) {
        if (v != v) {
            return 0;
        }
        long bound = (long) size + 1;
        return (int) Math.max(-bound, Math.min(bound, Math.round(v)));
    }

    private static float angle(String v) {
        String t = v.strip().toLowerCase(Locale.ROOT);
        if (t.endsWith("deg")) {
            return SvgDraw.number(t.substring(0, t.length() - 3), 0);
        }
        if (t.endsWith("grad")) {
            return SvgDraw.number(t.substring(0, t.length() - 4), 0) * 0.9f;
        }
        if (t.endsWith("rad")) {
            return (float) Math.toDegrees(SvgDraw.number(t.substring(0, t.length() - 3), 0));
        }
        if (t.endsWith("turn")) {
            return SvgDraw.number(t.substring(0, t.length() - 4), 0) * 360;
        }
        return SvgDraw.number(t, 0);
    }


    static Img fromImage(BufferedImage img) {
        return fromImage(img, img.getWidth(), img.getHeight());
    }

    static Img fromImage(BufferedImage img, int w, int h) {
        Img out = new Img(w, h);
        int cw = Math.min(w, img.getWidth());
        int ch = Math.min(h, img.getHeight());
        int[] data = null;
        int stride = 0;
        if (img.getType() == BufferedImage.TYPE_INT_ARGB
                && img.getRaster().getDataBuffer() instanceof DataBufferInt db
                && img.getRaster().getSampleModel() instanceof SinglePixelPackedSampleModel sm
                && img.getRaster().getSampleModelTranslateX() == 0 && img.getRaster().getSampleModelTranslateY() == 0) {
            data = db.getData();
            stride = sm.getScanlineStride();
        }
        int[] row = data == null ? new int[cw] : null;
        float[] p = out.p;
        for (int y = 0; y < ch; y++) {
            int base = y * stride;
            if (data == null) {
                img.getRGB(0, y, cw, 1, row, 0, cw);
            }
            int i = y * w * 4;
            for (int x = 0; x < cw; x++, i += 4) {
                int c = data == null ? row[x] : data[base + x];
                int ai = c >>> 24;
                if (ai == 0) {
                    continue;
                }
                float a = ai / 255f;
                p[i] = ((c >> 16) & 0xFF) / 255f * a;
                p[i + 1] = ((c >> 8) & 0xFF) / 255f * a;
                p[i + 2] = (c & 0xFF) / 255f * a;
                p[i + 3] = a;
            }
        }
        return out;
    }

    static PDImageXObject encode(org.apache.pdfbox.pdmodel.PDDocument doc, BufferedImage img) throws IOException {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        boolean opaque = true;
        for (int v : px) {
            if (v >>> 24 != 255) {
                opaque = false;
                break;
            }
        }
        PDImageXObject image = flate(doc, px, w, h, 3);
        if (!opaque) {
            image.getCOSObject().setItem(COSName.SMASK, flate(doc, px, w, h, 1));
        }
        return image;
    }

    private static PDImageXObject flate(org.apache.pdfbox.pdmodel.PDDocument doc, int[] px, int w, int h, int colours)
            throws IOException {
        int row = w * colours;
        byte[] prev = new byte[row];
        byte[] cur = new byte[row];
        byte[] line = new byte[row + 1];
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(Math.max(64, w * h * colours / 4));
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION);
        try (java.util.zip.DeflaterOutputStream z = new java.util.zip.DeflaterOutputStream(bytes, deflater, 1 << 16)) {
            for (int y = 0; y < h; y++) {
                int o = y * w;
                if (colours == 3) {
                    for (int x = 0; x < w; x++) {
                        int v = px[o + x];
                        cur[x * 3] = (byte) (v >> 16);
                        cur[x * 3 + 1] = (byte) (v >> 8);
                        cur[x * 3 + 2] = (byte) v;
                    }
                } else {
                    for (int x = 0; x < w; x++) {
                        cur[x] = (byte) (px[o + x] >>> 24);
                    }
                }
                line[0] = 4;
                for (int i = 0; i < row; i++) {
                    int a = i >= colours ? cur[i - colours] & 0xFF : 0;
                    int b = prev[i] & 0xFF;
                    int c = i >= colours ? prev[i - colours] & 0xFF : 0;
                    int pa = Math.abs(b - c);
                    int pb = Math.abs(a - c);
                    int pc = Math.abs(a + b - 2 * c);
                    int pred = pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
                    line[i + 1] = (byte) (cur[i] - pred);
                }
                z.write(line);
                byte[] t = prev;
                prev = cur;
                cur = t;
            }
        } finally {
            deflater.end();
        }
        PDImageXObject image = new PDImageXObject(doc, new java.io.ByteArrayInputStream(bytes.toByteArray()),
                COSName.FLATE_DECODE, w, h, 8,
                colours == 3 ? org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB.INSTANCE
                        : org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray.INSTANCE);
        org.apache.pdfbox.cos.COSDictionary parms = new org.apache.pdfbox.cos.COSDictionary();
        parms.setInt(COSName.PREDICTOR, 15);
        parms.setInt(COSName.COLORS, colours);
        parms.setInt(COSName.BITS_PER_COMPONENT, 8);
        parms.setInt(COSName.COLUMNS, w);
        image.getCOSObject().setItem(COSName.DECODE_PARMS, parms);
        return image;
    }

    static BufferedImage toImage(Img img) {
        BufferedImage out = new BufferedImage(img.w, img.h, BufferedImage.TYPE_INT_ARGB);
        int[] data = ((DataBufferInt) out.getRaster().getDataBuffer()).getData();
        float[] p = img.p;
        int n = img.w * img.h;
        for (int k = 0, i = 0; k < n; k++, i += 4) {
            float a = clamp(p[i + 3]);
            if (a == 0) {
                continue;
            }
            int r = Math.round(clamp(p[i] / a) * 255);
            int g = Math.round(clamp(p[i + 1] / a) * 255);
            int b = Math.round(clamp(p[i + 2] / a) * 255);
            int ai = Math.round(a * 255);
            data[k] = ai << 24 | r << 16 | g << 8 | b;
        }
        return out;
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v != v ? 0 : v;
    }

    private static final float[] TO_LINEAR = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            TO_LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
    }

    static float linear(float c) {
        return TO_LINEAR[Math.round(clamp(c) * 255)];
    }

    private static final int SRGB_STEPS = 4096;

    private static final float[] TO_SRGB = new float[SRGB_STEPS + 2];

    static {
        for (int i = 0; i <= SRGB_STEPS; i++) {
            double v = i / (double) SRGB_STEPS;
            TO_SRGB[i] = (float) (v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055);
        }
        TO_SRGB[SRGB_STEPS + 1] = 1;
    }

    static float srgb(float c) {
        float v = clamp(c) * SRGB_STEPS;
        int i = (int) v;
        float f = v - i;
        return TO_SRGB[i] + (TO_SRGB[i + 1] - TO_SRGB[i]) * f;
    }

    static Img toLinear(Img in) {
        Img out = new Img(in.w, in.h);
        out.linear = true;
        out.area = in.area;
        out.box = in.box;
        for (int i = 0; i < in.p.length; i += 4) {
            float a = in.p[i + 3];
            out.p[i + 3] = a;
            if (a > 0) {
                for (int c = 0; c < 3; c++) {
                    out.p[i + c] = linear(in.p[i + c] / a) * a;
                }
            }
        }
        return out;
    }

    static Img toSrgb(Img in) {
        Img out = new Img(in.w, in.h);
        out.area = in.area;
        out.box = in.box;
        srgbInto(in.p, out.p);
        return out;
    }

    private static Img srgbInPlace(Img in) {
        srgbInto(in.p, in.p);
        in.linear = false;
        return in;
    }

    private static void srgbInto(float[] from, float[] to) {
        for (int i = 0; i < from.length; i += 4) {
            float a = clamp(from[i + 3]);
            to[i + 3] = a;
            if (a > 0) {
                for (int c = 0; c < 3; c++) {
                    to[i + c] = srgb(from[i + c] / a) * a;
                }
            } else {
                to[i] = 0;
                to[i + 1] = 0;
                to[i + 2] = 0;
            }
        }
    }

    static Img flood(int w, int h, String colour, float opacity, boolean lin, int current) {
        Img out = new Img(w, h);
        int rgb;
        float a;
        if (colour.equalsIgnoreCase("currentcolor")) {
            rgb = current;
            a = 1;
        } else {
            Integer c = Css.color(colour);
            rgb = c == null || c < 0 ? 0 : c;
            a = c != null && c < 0 ? 0 : Css.alpha(colour);
        }
        a *= opacity;
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;
        if (lin) {
            r = linear(r);
            g = linear(g);
            b = linear(b);
        }
        for (int i = 0; i < out.p.length; i += 4) {
            out.p[i] = r * a;
            out.p[i + 1] = g * a;
            out.p[i + 2] = b * a;
            out.p[i + 3] = a;
        }
        return out;
    }

    static Img offset(Img in, double dx, double dy) {
        Img out = new Img(in.w, in.h);
        int ox = pixels(dx, in.w);
        int oy = pixels(dy, in.h);
        for (int y = 0; y < in.h; y++) {
            int sy = y - oy;
            if (sy < 0 || sy >= in.h) {
                continue;
            }
            int x0 = Math.max(0, ox);
            int x1 = (int) Math.min(in.w, (long) in.w + ox);
            if (x1 > x0) {
                System.arraycopy(in.p, (sy * in.w + x0 - ox) * 4, out.p, (y * in.w + x0) * 4, (x1 - x0) * 4);
            }
        }
        return out;
    }

    static Img blur(Img in, double sx, double sy) {
        Img out = in.copy();
        if (sx > 0) {
            blurAxis(out, sx, true);
        }
        if (sy > 0) {
            blurAxis(out, sy, false);
        }
        return out;
    }

    private static void blurAxis(Img img, double sigma, boolean horizontal) {
        if (sigma < 2.0) {
            int radius = (int) Math.ceil(sigma * 3);
            float[] k = new float[2 * radius + 1];
            float sum = 0;
            for (int i = -radius; i <= radius; i++) {
                k[i + radius] = (float) Math.exp(-(i * i) / (2 * sigma * sigma));
                sum += k[i + radius];
            }
            for (int i = 0; i < k.length; i++) {
                k[i] /= sum;
            }
            convolve(img, k, radius, horizontal);
            return;
        }
        int d = (int) Math.floor(sigma * 3 * Math.sqrt(2 * Math.PI) / 4 + 0.5);
        if (d % 2 == 1) {
            box(img, d / 2, d / 2, horizontal);
            box(img, d / 2, d / 2, horizontal);
            box(img, d / 2, d / 2, horizontal);
        } else {
            box(img, d / 2, d / 2 - 1, horizontal);
            box(img, d / 2 - 1, d / 2, horizontal);
            box(img, d / 2, d / 2, horizontal);
        }
    }

    private static Img transpose(Img img) {
        Img t = new Img(img.h, img.w);
        transposeInto(img, t);
        return t;
    }

    private static void transposeInto(Img from, Img to) {
        for (int y = 0; y < from.h; y++) {
            for (int x = 0; x < from.w; x++) {
                System.arraycopy(from.p, (y * from.w + x) * 4, to.p, (x * to.w + y) * 4, 4);
            }
        }
    }

    private static void convolve(Img img, float[] k, int radius, boolean horizontal) {
        if (!horizontal) {
            Img t = transpose(img);
            convolve(t, k, radius, true);
            transposeInto(t, img);
            return;
        }
        int len = horizontal ? img.w : img.h;
        int lines = horizontal ? img.h : img.w;
        float[] line = new float[len * 4];
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) {
                int idx = horizontal ? (l * img.w + i) * 4 : (i * img.w + l) * 4;
                System.arraycopy(img.p, idx, line, i * 4, 4);
            }
            for (int i = 0; i < len; i++) {
                float r = 0;
                float g = 0;
                float b = 0;
                float a = 0;
                for (int j = -radius; j <= radius; j++) {
                    int s = i + j;
                    if (s < 0 || s >= len) {
                        continue;
                    }
                    float w = k[j + radius];
                    r += line[s * 4] * w;
                    g += line[s * 4 + 1] * w;
                    b += line[s * 4 + 2] * w;
                    a += line[s * 4 + 3] * w;
                }
                int idx = horizontal ? (l * img.w + i) * 4 : (i * img.w + l) * 4;
                img.p[idx] = r;
                img.p[idx + 1] = g;
                img.p[idx + 2] = b;
                img.p[idx + 3] = a;
            }
        }
    }

    private static void box(Img img, int left, int right, boolean horizontal) {
        if (!horizontal) {
            boxColumns(img, left, right);
            return;
        }
        int len = horizontal ? img.w : img.h;
        int lines = horizontal ? img.h : img.w;
        float[] line = new float[len * 4];
        float[] acc = new float[(len + 1) * 4];
        float n = left + right + 1;
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) {
                int idx = horizontal ? (l * img.w + i) * 4 : (i * img.w + l) * 4;
                System.arraycopy(img.p, idx, line, i * 4, 4);
            }
            for (int i = 0; i < len; i++) {
                for (int c = 0; c < 4; c++) {
                    acc[(i + 1) * 4 + c] = acc[i * 4 + c] + line[i * 4 + c];
                }
            }
            for (int i = 0; i < len; i++) {
                int lo = Math.max(0, i - left);
                int hi = Math.min(len - 1, i + right);
                int idx = horizontal ? (l * img.w + i) * 4 : (i * img.w + l) * 4;
                for (int c = 0; c < 4; c++) {
                    img.p[idx + c] = (acc[(hi + 1) * 4 + c] - acc[lo * 4 + c]) / n;
                }
            }
        }
    }

    private static void boxColumns(Img img, int left, int right) {
        int len = img.h;
        int stride = img.w * 4;
        int block = Math.min(stride, 64);
        float[] acc = new float[(len + 1) * block];
        float n = left + right + 1;
        for (int x0 = 0; x0 < stride; x0 += block) {
            int bw = Math.min(block, stride - x0);
            for (int i = 0; i < len; i++) {
                int row = i * stride + x0;
                int from = i * bw;
                int to = from + bw;
                for (int c = 0; c < bw; c++) {
                    acc[to + c] = acc[from + c] + img.p[row + c];
                }
            }
            for (int i = 0; i < len; i++) {
                int lo = Math.max(0, i - left) * bw;
                int hi = (Math.min(len - 1, i + right) + 1) * bw;
                int row = i * stride + x0;
                for (int c = 0; c < bw; c++) {
                    img.p[row + c] = (acc[hi + c] - acc[lo + c]) / n;
                }
            }
        }
    }

    static Img composite(Img a, Img b, String op, float k1, float k2, float k3, float k4) {
        Img out = new Img(a.w, a.h);
        int mode = switch (op) {
            case "in" -> 1;
            case "out" -> 2;
            case "atop" -> 3;
            case "xor" -> 4;
            case "arithmetic" -> 5;
            case "lighter" -> 6;
            default -> 0;
        };
        for (int i = 0; i < out.p.length; i += 4) {
            float aa = a.p[i + 3];
            float ba = b.p[i + 3];
            for (int c = 0; c < 4; c++) {
                float x = a.p[i + c];
                float y = b.p[i + c];
                float v = switch (mode) {
                    case 1 -> x * ba;
                    case 2 -> x * (1 - ba);
                    case 3 -> x * ba + y * (1 - aa);
                    case 4 -> x * (1 - ba) + y * (1 - aa);
                    case 5 -> k1 * x * y + k2 * x + k3 * y + k4;
                    case 6 -> x + y;
                    default -> x + y * (1 - aa);
                };
                out.p[i + c] = v;
            }
            if (op.equals("arithmetic") || op.equals("lighter")) {
                float al = clamp(out.p[i + 3]);
                out.p[i + 3] = al;
                for (int c = 0; c < 3; c++) {
                    out.p[i + c] = Math.min(al, Math.max(0, out.p[i + c]));
                }
            }
        }
        return out;
    }

    static Img blend(Img a, Img b, String mode) {
        Img out = new Img(a.w, a.h);
        boolean whole = mode.equals("hue") || mode.equals("saturation") || mode.equals("color")
                || mode.equals("luminosity");
        for (int i = 0; i < out.p.length; i += 4) {
            float qa = a.p[i + 3];
            float qb = b.p[i + 3];
            if (whole) {
                float[] cs = {qa > 0 ? a.p[i] / qa : 0, qa > 0 ? a.p[i + 1] / qa : 0, qa > 0 ? a.p[i + 2] / qa : 0};
                float[] cb = {qb > 0 ? b.p[i] / qb : 0, qb > 0 ? b.p[i + 1] / qb : 0, qb > 0 ? b.p[i + 2] / qb : 0};
                float[] f = switch (mode) {
                    case "hue" -> setLum(setSat(cs, sat(cb)), lum(cb));
                    case "saturation" -> setLum(setSat(cb, sat(cs)), lum(cb));
                    case "color" -> setLum(cs, lum(cb));
                    default -> setLum(cb, lum(cs));
                };
                for (int c = 0; c < 3; c++) {
                    out.p[i + c] = (1 - qb) * a.p[i + c] + (1 - qa) * b.p[i + c] + qa * qb * f[c];
                }
                out.p[i + 3] = 1 - (1 - qa) * (1 - qb);
                continue;
            }
            for (int c = 0; c < 3; c++) {
                float ca = a.p[i + c];
                float cb = b.p[i + c];
                float v = switch (mode) {
                    case "multiply" -> (1 - qa) * cb + (1 - qb) * ca + ca * cb;
                    case "screen" -> cb + ca - ca * cb;
                    case "darken" -> Math.min((1 - qa) * cb + ca, (1 - qb) * ca + cb);
                    case "lighten" -> Math.max((1 - qa) * cb + ca, (1 - qb) * ca + cb);
                    default -> separable(mode, ca, qa, cb, qb);
                };
                out.p[i + c] = v;
            }
            out.p[i + 3] = 1 - (1 - qa) * (1 - qb);
        }
        return out;
    }

    private static float lum(float[] c) {
        return 0.3f * c[0] + 0.59f * c[1] + 0.11f * c[2];
    }

    private static float sat(float[] c) {
        return Math.max(c[0], Math.max(c[1], c[2])) - Math.min(c[0], Math.min(c[1], c[2]));
    }

    private static float[] setLum(float[] c, float l) {
        float d = l - lum(c);
        float[] o = {c[0] + d, c[1] + d, c[2] + d};
        float lo = lum(o);
        float n = Math.min(o[0], Math.min(o[1], o[2]));
        float x = Math.max(o[0], Math.max(o[1], o[2]));
        for (int k = 0; k < 3; k++) {
            if (n < 0 && lo - n != 0) {
                o[k] = lo + (o[k] - lo) * lo / (lo - n);
            }
            if (x > 1 && x - lo != 0) {
                o[k] = lo + (o[k] - lo) * (1 - lo) / (x - lo);
            }
        }
        return o;
    }

    private static float[] setSat(float[] c, float s) {
        int max = c[0] >= c[1] ? (c[0] >= c[2] ? 0 : 2) : (c[1] >= c[2] ? 1 : 2);
        int min = c[0] <= c[1] ? (c[0] <= c[2] ? 0 : 2) : (c[1] <= c[2] ? 1 : 2);
        float[] o = new float[3];
        if (max == min) {
            return o;
        }
        int mid = 3 - max - min;
        float range = c[max] - c[min];
        o[mid] = range > 0 ? (c[mid] - c[min]) * s / range : 0;
        o[max] = range > 0 ? s : 0;
        o[min] = 0;
        return o;
    }

    private static float separable(String mode, float ca, float qa, float cb, float qb) {
        if (mode.isEmpty() || mode.equals("normal")) {
            return ca + cb * (1 - qa);
        }
        float s = qa > 0 ? ca / qa : 0;
        float d = qb > 0 ? cb / qb : 0;
        float f = switch (mode) {
            case "overlay" -> d <= 0.5f ? 2 * s * d : 1 - 2 * (1 - s) * (1 - d);
            case "hard-light" -> s <= 0.5f ? 2 * s * d : 1 - 2 * (1 - s) * (1 - d);
            case "color-dodge" -> d == 0 ? 0 : s >= 1 ? 1 : Math.min(1, d / (1 - s));
            case "color-burn" -> d >= 1 ? 1 : s <= 0 ? 0 : 1 - Math.min(1, (1 - d) / s);
            case "difference" -> Math.abs(s - d);
            case "exclusion" -> s + d - 2 * s * d;
            case "soft-light" -> {
                if (s <= 0.5f) {
                    yield d - (1 - 2 * s) * d * (1 - d);
                }
                float dd = d <= 0.25f ? ((16 * d - 12) * d + 4) * d : (float) Math.sqrt(d);
                yield d + (2 * s - 1) * (dd - d);
            }
            default -> s;
        };
        return (1 - qb) * ca + (1 - qa) * cb + qa * qb * f;
    }

    static Img colorMatrix(Img in, String type, String values) {
        float[] m = matrixOf(type, values);
        if (m == null) {
            return in;
        }
        Img out = new Img(in.w, in.h);
        for (int i = 0; i < in.p.length; i += 4) {
            float a = in.p[i + 3];
            float r = a > 0 ? in.p[i] / a : 0;
            float g = a > 0 ? in.p[i + 1] / a : 0;
            float b = a > 0 ? in.p[i + 2] / a : 0;
            float nr = clamp(m[0] * r + m[1] * g + m[2] * b + m[3] * a + m[4]);
            float ng = clamp(m[5] * r + m[6] * g + m[7] * b + m[8] * a + m[9]);
            float nb = clamp(m[10] * r + m[11] * g + m[12] * b + m[13] * a + m[14]);
            float na = clamp(m[15] * r + m[16] * g + m[17] * b + m[18] * a + m[19]);
            out.p[i] = nr * na;
            out.p[i + 1] = ng * na;
            out.p[i + 2] = nb * na;
            out.p[i + 3] = na;
        }
        return out;
    }

    private static float[] matrixOf(String type, String values) {
        float[] m;
        float[] v = SvgDraw.numbers(values);
        switch (type) {
            case "saturate" -> {
                float s = v.length > 0 ? v[0] : 1;
                m = new float[] {0.213f + 0.787f * s, 0.715f - 0.715f * s, 0.072f - 0.072f * s, 0, 0,
                    0.213f - 0.213f * s, 0.715f + 0.285f * s, 0.072f - 0.072f * s, 0, 0, 0.213f - 0.213f * s,
                    0.715f - 0.715f * s, 0.072f + 0.928f * s, 0, 0, 0, 0, 0, 1, 0};
            }
            case "huerotate" -> {
                double a = Math.toRadians(v.length > 0 ? v[0] : 0);
                float c = (float) Math.cos(a);
                float s = (float) Math.sin(a);
                m = new float[] {0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f,
                    0.072f - c * 0.072f + s * 0.928f, 0, 0, 0.213f - c * 0.213f + s * 0.143f,
                    0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f, 0, 0,
                    0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f,
                    0, 0, 0, 0, 0, 1, 0};
            }
            case "luminancetoalpha" -> m = new float[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.2125f, 0.7154f,
                0.0721f, 0, 0};
            default -> {
                if (v.length != 20) {
                    return null;
                }
                m = v;
            }
        }
        return m;
    }

    private static Img transfer(Img in, Element p) {
        Func[] funcs = new Func[4];
        for (Element c : p.children()) {
            String t = SvgDoc.local(c).toLowerCase(Locale.ROOT);
            switch (t) {
                case "fefuncr" -> funcs[0] = Func.of(c);
                case "fefuncg" -> funcs[1] = Func.of(c);
                case "fefuncb" -> funcs[2] = Func.of(c);
                case "fefunca" -> funcs[3] = Func.of(c);
                default -> { }
            }
        }
        Img out = new Img(in.w, in.h);
        float[] c = new float[4];
        for (int i = 0; i < in.p.length; i += 4) {
            float a = in.p[i + 3];
            c[0] = a > 0 ? in.p[i] / a : 0;
            c[1] = a > 0 ? in.p[i + 1] / a : 0;
            c[2] = a > 0 ? in.p[i + 2] / a : 0;
            c[3] = a;
            for (int k = 0; k < 4; k++) {
                if (funcs[k] != null) {
                    c[k] = clamp(funcs[k].apply(c[k]));
                }
            }
            out.p[i] = c[0] * c[3];
            out.p[i + 1] = c[1] * c[3];
            out.p[i + 2] = c[2] * c[3];
            out.p[i + 3] = c[3];
        }
        return out;
    }

    private record Func(String type, float[] t, float slope, float intercept, float amplitude, float exponent,
            float offset) {
        static Func of(Element f) {
            return new Func(SvgDoc.lower(SvgDoc.attr(f, "type")), SvgDraw.numbers(SvgDoc.attr(f, "tableValues")),
                    SvgDraw.number(SvgDoc.attr(f, "slope"), 1), SvgDraw.number(SvgDoc.attr(f, "intercept"), 0),
                    SvgDraw.number(SvgDoc.attr(f, "amplitude"), 1), SvgDraw.number(SvgDoc.attr(f, "exponent"), 1),
                    SvgDraw.number(SvgDoc.attr(f, "offset"), 0));
        }

        float apply(float c) {
            switch (type) {
                case "table": {
                    int n = t.length - 1;
                    if (n < 1) {
                        return n == 0 ? t[0] : c;
                    }
                    int k = (int) Math.min(n - 1, Math.floor(c * n));
                    return t[k] + (c - (float) k / n) * n * (t[k + 1] - t[k]);
                }
                case "discrete": {
                    int n = t.length;
                    if (n == 0) {
                        return c;
                    }
                    int k = (int) Math.min(n - 1, Math.floor(c * n));
                    return t[k];
                }
                case "linear":
                    return slope * c + intercept;
                case "gamma":
                    return amplitude * (float) Math.pow(c, exponent) + offset;
                default:
                    return c;
            }
        }
    }

    private static Img morphology(Img in, int rx, int ry, boolean dilate) {
        Img out = in.copy();
        morph(out, Math.min(rx, out.w), dilate, true);
        morph(out, Math.min(ry, out.h), dilate, false);
        return out;
    }

    private static void morph(Img img, int r, boolean dilate, boolean horizontal) {
        if (r <= 0) {
            return;
        }
        if (!horizontal) {
            Img t = transpose(img);
            morph(t, r, dilate, true);
            transposeInto(t, img);
            return;
        }
        int len = horizontal ? img.w : img.h;
        int lines = horizontal ? img.h : img.w;
        float[] line = new float[len];
        float[] result = new float[len];
        int[] queue = new int[len + 2 * r + 2];
        for (int l = 0; l < lines; l++) {
            for (int c = 0; c < 4; c++) {
                for (int i = 0; i < len; i++) {
                    line[i] = img.p[(horizontal ? l * img.w + i : i * img.w + l) * 4 + c];
                }
                int head = 0;
                int tail = 0;
                for (int i = -r; i < len + r; i++) {
                    int enter = i + r;
                    if (enter < len) {
                        float v = line[enter];
                        while (tail > head && (dilate ? line[queue[tail - 1]] <= v : line[queue[tail - 1]] >= v)) {
                            tail--;
                        }
                        queue[tail++] = enter;
                    }
                    if (i >= 0 && i < len) {
                        while (head < tail && queue[head] < i - r) {
                            head++;
                        }
                        float best = head < tail ? line[queue[head]] : 0;
                        if (!dilate && (i - r < 0 || i + r >= len)) {
                            best = 0;
                        }
                        result[i] = best;
                    }
                }
                for (int i = 0; i < len; i++) {
                    img.p[(horizontal ? l * img.w + i : i * img.w + l) * 4 + c] = result[i];
                }
            }
        }
    }

    private static Img tile(Img in, Rectangle2D area) {
        double x0;
        double y0;
        double tw;
        double th;
        if (area != null && area.getWidth() >= 1 && area.getHeight() >= 1) {
            x0 = area.getX();
            y0 = area.getY();
            tw = area.getWidth();
            th = area.getHeight();
        } else {
            int[] r = bounds(in);
            if (r == null) {
                return new Img(in.w, in.h);
            }
            x0 = r[0];
            y0 = r[1];
            tw = r[2] - r[0];
            th = r[3] - r[1];
        }
        Img out = new Img(in.w, in.h);
        for (int y = 0; y < in.h; y++) {
            int sy = (int) Math.floor(y0 + Math.floorMod((long) Math.floor((y + 0.5 - y0) * 1024), (long) (th * 1024))
                    / 1024.0);
            if (sy < 0 || sy >= in.h) {
                continue;
            }
            for (int x = 0; x < in.w; x++) {
                int sx = (int) Math.floor(x0 + Math.floorMod((long) Math.floor((x + 0.5 - x0) * 1024),
                        (long) (tw * 1024)) / 1024.0);
                if (sx >= 0 && sx < in.w) {
                    System.arraycopy(in.p, (sy * in.w + sx) * 4, out.p, (y * in.w + x) * 4, 4);
                }
            }
        }
        return out;
    }

    private static int[] bounds(Img in) {
        int x0 = in.w;
        int y0 = in.h;
        int x1 = -1;
        int y1 = -1;
        for (int y = 0; y < in.h; y++) {
            for (int x = 0; x < in.w; x++) {
                if (in.p[(y * in.w + x) * 4 + 3] > 0) {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        return x1 < 0 ? null : new int[] {x0, y0, x1 + 1, y1 + 1};
    }
}
