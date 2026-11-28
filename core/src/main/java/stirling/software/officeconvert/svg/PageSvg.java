package stirling.software.officeconvert.svg;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.common.function.PDFunction;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDAbstractPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDShadingPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.PDSoftMask;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import stirling.software.officeconvert.extract.HiddenContent;
import stirling.software.officeconvert.extract.ImageBudget;
import stirling.software.officeconvert.extract.OperatorBudget;
import stirling.software.officeconvert.extract.PdfColours;
import stirling.software.officeconvert.sink.Links;

final class PageSvg extends PDFGraphicsStreamEngine {

    static final int MAX_DEPTH = 10;

    static final long MAX_RASTER_PIXELS = 4_000_000L;

    static final long MAX_PAGE_IMAGE_PIXELS = 64_000_000L;

    static final int MAX_ELEMENTS = 400_000;

    static final int MAX_PATH_SEGMENTS = 2_000_000;

    private static final Set<String> GENERATED = Set.of("Circle", "Highlight", "Ink", "Popup", "Square",
            "Squiggly", "StrikeOut", "Text", "Underline");
    private static final COSName MATTE = COSName.getPDFName("Matte");
    private static final AffineTransform FLIP = new AffineTransform(1, 0, 0, -1, 0, 0);

    record Stats(int textGlyphs, int pathGlyphs, int invisibleGlyphs, int images, int rasterShadings) {}

    private static final class Level {
        final int base;
        final List<Path2D> open = new ArrayList<>();
        final List<Boolean> emitted = new ArrayList<>();

        Level(int base) {
            this.base = base;
        }
    }

    private static final class Out {
        final StringBuilder sb;
        final AffineTransform toSvg;
        final Rectangle2D area;
        final int decimals;
        final Deque<Level> levels = new ArrayDeque<>();

        Out(StringBuilder sb, AffineTransform toSvg, Rectangle2D area, int base, int decimals) {
            this.sb = sb;
            this.toSvg = toSvg;
            this.area = area;
            this.decimals = decimals;
            levels.push(new Level(base));
        }
    }

    private record Paint(String value, double opacity, PDShading raster, AffineTransform rasterToSvg) {
        static final Paint NONE = new Paint("none", 1, null, null);
    }

    private final SvgFonts fonts;
    private final float width;
    private final float height;
    private final StringBuilder defs = new StringBuilder();
    private final Map<String, String> defIds = new HashMap<>();
    private final Map<Object, String> imageIds = new HashMap<>();
    private final Map<Object, String> maskIds = new HashMap<>();
    private final Map<Object, String> tileIds = new HashMap<>();
    private final Set<SvgFonts.Face> facesUsed = new LinkedHashSet<>();
    private final StringBuilder links = new StringBuilder();
    private Out out;
    private final GeneralPath path = new GeneralPath();
    private int clipRule = -1;
    private final OperatorBudget budget = new OperatorBudget("drawing an SVG page");
    private final HiddenContent hidden;
    private int depth;
    private int ids;
    private int elements;
    private long imagePixels;
    private Run run;
    private GeneralPath textClip;
    private int textGlyphs;
    private int pathGlyphs;
    private int invisibleGlyphs;
    private int images;
    private int rasterShadings;

    private final org.apache.pdfbox.pdmodel.PDDocument document;

    PageSvg(org.apache.pdfbox.pdmodel.PDDocument document, PDPage page, SvgFonts fonts, HiddenContent hidden,
            float width, float height, AffineTransform toSvg) {
        super(page);
        this.document = document;
        this.fonts = fonts;
        this.hidden = hidden;
        this.width = width;
        this.height = height;
        this.out = new Out(new StringBuilder(1 << 14), toSvg, new Rectangle2D.Double(0, 0, width, height), 1, 2);
    }

    static AffineTransform toSvg(PDRectangle box, int rotation) {
        AffineTransform t = new AffineTransform();
        float w = box.getWidth();
        float h = box.getHeight();
        switch (rotation) {
            case 90 -> t.concatenate(new AffineTransform(0, 1, -1, 0, h, 0));
            case 180 -> t.concatenate(new AffineTransform(-1, 0, 0, -1, w, h));
            case 270 -> t.concatenate(new AffineTransform(0, -1, 1, 0, 0, w));
            default -> { }
        }
        t.concatenate(new AffineTransform(1, 0, 0, -1, 0, h));
        t.translate(-box.getLowerLeftX(), -box.getLowerLeftY());
        return t;
    }

    Stats stats() {
        return new Stats(textGlyphs, pathGlyphs, invisibleGlyphs, images, rasterShadings);
    }

    void write(PDPage page, Writer w) throws IOException {
        processPage(page);
        annotations(page);
        flushText();
        closeAll(out);
        StringBuilder head = new StringBuilder(1024);
        head.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" version=\"1.1\"")
                .append(" width=\"").append(Num.of(width)).append("pt\" height=\"").append(Num.of(height))
                .append("pt\" viewBox=\"0 0 ").append(Num.of(width)).append(' ').append(Num.of(height))
                .append("\" xml:space=\"preserve\">\n");
        String css = SvgFonts.css(new ArrayList<>(facesUsed));
        if (!css.isEmpty() || !defs.isEmpty()) {
            head.append("<defs>\n");
            if (!css.isEmpty()) {
                head.append("<style type=\"text/css\">\n");
                chunked(w, head);
                head.setLength(0);
                w.write(css);
                head.append("</style>\n");
            }
            chunked(w, head);
            head.setLength(0);
            chunked(w, defs);
            head.append("</defs>\n");
        }
        head.append("<rect width=\"").append(Num.of(width)).append("\" height=\"").append(Num.of(height))
                .append("\" fill=\"#fff\"/>\n");
        chunked(w, head);
        chunked(w, out.sb);
        chunked(w, links);
        w.write("</svg>\n");
    }

    private static void chunked(Writer w, CharSequence sb) throws IOException {
        char[] buf = new char[8192];
        StringBuilder b = sb instanceof StringBuilder x ? x : new StringBuilder(sb);
        for (int i = 0; i < b.length(); i += buf.length) {
            int n = Math.min(buf.length, b.length() - i);
            b.getChars(i, i + n, buf, 0);
            w.write(buf, 0, n);
        }
    }

    private String id(String prefix) {
        return prefix + (++ids);
    }

    private String define(String prefix, String body) {
        String known = defIds.get(body);
        if (known != null) {
            return known;
        }
        String id = id(prefix);
        defIds.put(body, id);
        defs.append(body.replaceFirst("\u0001", id)).append('\n');
        return id;
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
        if (!budget.run(operator)) {
            return;
        }
        Operator painted = hidden.painted(operator, operands, getResources());
        if (painted == null) {
            return;
        }
        try {
            super.processOperator(painted, operands);
        } catch (RuntimeException e) {
            path.reset();
            clipRule = -1;
        }
    }

    @Override
    public void showForm(PDFormXObject form) throws IOException {
        if (budget.form() && !hidden.hidden(form) && depth < 64) {
            depth++;
            try {
                super.showForm(form);
            } finally {
                depth--;
            }
        }
    }

    @Override
    public void showTransparencyGroup(PDTransparencyGroup group) throws IOException {
        if (!budget.form() || hidden.hidden(group) || depth >= 64) {
            return;
        }
        flushText();
        PDGraphicsState gs = getGraphicsState();
        double alpha = gs.getNonStrokeAlphaConstant();
        String mask = gs.getSoftMask() == null ? null : softMask(gs.getSoftMask());
        String blend = blend(gs.getBlendMode());
        boolean isolated = group.getGroup() != null && group.getGroup().isIsolated();
        if (alpha >= 1 && mask == null && blend == null && !isolated) {
            depth++;
            try {
                super.showTransparencyGroup(group);
            } finally {
                depth--;
            }
            return;
        }
        if (alpha <= 0) {
            return;
        }
        String outer = "";
        if (blend != null) {
            outer = openBlend(blend, gs.getCurrentClippingPaths(), out);
        } else {
            sync();
        }
        StringBuilder sb = out.sb;
        sb.append("<g");
        if (alpha < 1) {
            sb.append(" opacity=\"");
            Num.append(sb, alpha, 3);
            sb.append('"');
        }
        if (mask != null) {
            sb.append(" mask=\"url(#").append(mask).append(")\"");
        }
        if (isolated) {
            sb.append(" style=\"isolation:isolate\"");
        }
        sb.append(">\n");
        out.levels.push(new Level(gs.getCurrentClippingPaths().size()));
        depth++;
        try {
            super.showTransparencyGroup(group);
        } finally {
            depth--;
            flushText();
            closeLevel(out);
            out.sb.append("</g>").append(outer).append('\n');
        }
    }

    private void closeLevel(Out o) {
        Level level = o.levels.pop();
        for (int i = level.open.size() - 1; i >= 0; i--) {
            if (level.emitted.get(i)) {
                o.sb.append("</g>\n");
            }
        }
    }

    private void closeAll(Out o) {
        while (!o.levels.isEmpty()) {
            closeLevel(o);
        }
    }

    private void sync() {
        List<Path2D> clips = getGraphicsState().getCurrentClippingPaths();
        Level level = out.levels.peek();
        int n = Math.max(0, clips.size() - level.base);
        int common = 0;
        while (common < level.open.size() && common < n && level.open.get(common) == clips.get(level.base + common)) {
            common++;
        }
        while (level.open.size() > common) {
            int last = level.open.size() - 1;
            if (level.emitted.get(last)) {
                out.sb.append("</g>\n");
            }
            level.open.remove(last);
            level.emitted.remove(last);
        }
        for (int i = common; i < n; i++) {
            Path2D clip = clips.get(level.base + i);
            String id = clip(clip);
            if (id != null) {
                out.sb.append("<g clip-path=\"url(#").append(id).append(")\">\n");
            }
            level.open.add(clip);
            level.emitted.add(id != null);
        }
    }

    private String clip(Path2D clip) {
        Shape shape = out.toSvg.createTransformedShape(clip);
        Rectangle2D box = rectangle(shape);
        if (box != null && out.area != null && box.contains(out.area)) {
            return null;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("<clipPath id=\"\u0001\" clipPathUnits=\"userSpaceOnUse\">");
        if (box != null) {
            sb.append("<rect x=\"");
            Num.append(sb, box.getX(), out.decimals);
            sb.append("\" y=\"");
            Num.append(sb, box.getY(), out.decimals);
            sb.append("\" width=\"");
            Num.append(sb, box.getWidth(), out.decimals);
            sb.append("\" height=\"");
            Num.append(sb, box.getHeight(), out.decimals);
            sb.append("\"/>");
        } else {
            sb.append("<path d=\"");
            if (Num.path(sb, shape, null, out.decimals) == 0) {
                sb.append("M0 0");
            }
            sb.append('"');
            if (Num.evenOdd(clip)) {
                sb.append(" clip-rule=\"evenodd\"");
            }
            sb.append("/>");
        }
        sb.append("</clipPath>");
        return define("c", sb.toString());
    }

    static Rectangle2D rectangle(Shape shape) {
        PathIterator it = shape.getPathIterator(null);
        double[] c = new double[6];
        double[] xs = new double[5];
        double[] ys = new double[5];
        int n = 0;
        while (!it.isDone()) {
            int type = it.currentSegment(c);
            if (type == PathIterator.SEG_CLOSE) {
                it.next();
                if (!it.isDone()) {
                    return null;
                }
                break;
            }
            if (type == PathIterator.SEG_QUADTO || type == PathIterator.SEG_CUBICTO || n >= 5
                    || type == PathIterator.SEG_MOVETO && n > 0) {
                return null;
            }
            xs[n] = c[0];
            ys[n] = c[1];
            n++;
            it.next();
        }
        if (n == 5 && Math.abs(xs[4] - xs[0]) < 1e-6 && Math.abs(ys[4] - ys[0]) < 1e-6) {
            n = 4;
        }
        if (n != 4) {
            return null;
        }
        boolean first = false;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            boolean horizontal = Math.abs(ys[i] - ys[j]) < 1e-6;
            boolean vertical = Math.abs(xs[i] - xs[j]) < 1e-6;
            if (horizontal == vertical) {
                return null;
            }
            if (i == 0) {
                first = horizontal;
            } else if (horizontal != (first == (i % 2 == 0))) {
                return null;
            }
        }
        double x0 = Math.min(Math.min(xs[0], xs[1]), Math.min(xs[2], xs[3]));
        double x1 = Math.max(Math.max(xs[0], xs[1]), Math.max(xs[2], xs[3]));
        double y0 = Math.min(Math.min(ys[0], ys[1]), Math.min(ys[2], ys[3]));
        double y1 = Math.max(Math.max(ys[0], ys[1]), Math.max(ys[2], ys[3]));
        return new Rectangle2D.Double(x0, y0, x1 - x0, y1 - y0);
    }

    private static String blend(BlendMode mode) {
        if (mode == null || mode == BlendMode.NORMAL) {
            return null;
        }
        String name = mode.getCOSName().getName();
        return switch (name) {
            case "Multiply" -> "multiply";
            case "Screen" -> "screen";
            case "Overlay" -> "overlay";
            case "Darken" -> "darken";
            case "Lighten" -> "lighten";
            case "ColorDodge" -> "color-dodge";
            case "ColorBurn" -> "color-burn";
            case "HardLight" -> "hard-light";
            case "SoftLight" -> "soft-light";
            case "Difference" -> "difference";
            case "Exclusion" -> "exclusion";
            case "Hue" -> "hue";
            case "Saturation" -> "saturation";
            case "Color" -> "color";
            case "Luminosity" -> "luminosity";
            default -> null;
        };
    }

    private String softMask(PDSoftMask mask) {
        Matrix initial = mask.getInitialTransformationMatrix();
        Object key = java.util.Arrays.asList(mask.getCOSObject(), initial == null ? "" : initial.toString(), out);
        if (maskIds.containsKey(key)) {
            return maskIds.get(key);
        }
        String id = null;
        try {
            id = buildMask(mask);
        } catch (IOException | RuntimeException e) {
            id = null;
        }
        maskIds.put(key, id);
        return id;
    }

    private String buildMask(PDSoftMask mask) throws IOException {
        PDTransparencyGroup group = mask.getGroup();
        if (group == null || depth >= MAX_DEPTH) {
            return null;
        }
        boolean luminosity = COSName.LUMINOSITY.equals(mask.getSubType());
        int backdrop = 0;
        if (luminosity) {
            COSArray bc = mask.getBackdropColor();
            PDColorSpace cs = group.getGroup() == null ? null : group.getGroup().getColorSpace(group.getResources());
            if (bc != null && cs != null && bc.size() == cs.getNumberOfComponents()) {
                float[] v = new float[bc.size()];
                for (int i = 0; i < v.length; i++) {
                    v[i] = bc.get(i) instanceof COSNumber n ? n.floatValue() : 0;
                }
                backdrop = PdfColours.toRgb(new PDColor(v, cs), 0);
            }
        }
        flushText();
        Out saved = out;
        Rectangle2D area = saved.area != null ? saved.area : new Rectangle2D.Double(-1e4, -1e4, 2e4, 2e4);
        out = new Out(new StringBuilder(), saved.toSvg, saved.area,
                getGraphicsState().getCurrentClippingPaths().size(), saved.decimals);
        depth++;
        boolean done = false;
        try {
            nested(() -> processSoftMask(group));
            flushText();
            closeAll(out);
            done = true;
        } finally {
            depth--;
            if (!done) {
                run = null;
                out = saved;
            }
        }
        StringBuilder content = out.sb;
        out = saved;
        String transfer = transferTable(mask);
        String id = id("m");
        StringBuilder sb = new StringBuilder(content.length() + 256);
        sb.append("<mask id=\"").append(id).append("\" maskUnits=\"userSpaceOnUse\" x=\"");
        Num.append(sb, area.getX() - 1, 2);
        sb.append("\" y=\"");
        Num.append(sb, area.getY() - 1, 2);
        sb.append("\" width=\"");
        Num.append(sb, area.getWidth() + 2, 2);
        sb.append("\" height=\"");
        Num.append(sb, area.getHeight() + 2, 2);
        sb.append('"');
        if (!luminosity || transfer != null) {
            sb.append(" mask-type=\"alpha\" style=\"mask-type:alpha\"");
        }
        sb.append(">\n");
        if (transfer != null) {
            String fid = id("t");
            defs.append("<filter id=\"").append(fid).append("\" filterUnits=\"userSpaceOnUse\" x=\"");
            Num.append(defs, area.getX() - 1, 2);
            defs.append("\" y=\"");
            Num.append(defs, area.getY() - 1, 2);
            defs.append("\" width=\"");
            Num.append(defs, area.getWidth() + 2, 2);
            defs.append("\" height=\"");
            Num.append(defs, area.getHeight() + 2, 2);
            defs.append("\" color-interpolation-filters=\"sRGB\">");
            if (luminosity) {
                defs.append("<feColorMatrix type=\"luminanceToAlpha\"/>");
            }
            defs.append("<feComponentTransfer><feFuncA type=\"table\" tableValues=\"").append(transfer)
                    .append("\"/></feComponentTransfer></filter>\n");
            sb.append("<g filter=\"url(#").append(fid).append(")\">\n");
        }
        if (luminosity && backdrop != 0) {
            sb.append("<rect x=\"");
            Num.append(sb, area.getX() - 1, 2);
            sb.append("\" y=\"");
            Num.append(sb, area.getY() - 1, 2);
            sb.append("\" width=\"");
            Num.append(sb, area.getWidth() + 2, 2);
            sb.append("\" height=\"");
            Num.append(sb, area.getHeight() + 2, 2);
            sb.append("\" fill=\"");
            Num.hex(sb, backdrop);
            sb.append("\"/>\n");
        }
        sb.append(content);
        if (transfer != null) {
            sb.append("</g>\n");
        }
        sb.append("</mask>");
        defs.append(sb).append('\n');
        return id;
    }

    private static String transferTable(PDSoftMask mask) {
        COSBase tr = mask.getCOSObject().getDictionaryObject(COSName.TR);
        if (tr == null || COSName.IDENTITY.equals(tr)) {
            return null;
        }
        PDFunction f;
        try {
            f = PDFunction.create(tr);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean identity = true;
        int n = 33;
        for (int i = 0; i < n; i++) {
            float x = i / (float) (n - 1);
            float y;
            try {
                float[] r = f.eval(new float[] {x});
                y = r.length == 0 || !Float.isFinite(r[0]) ? x : Math.max(0, Math.min(1, r[0]));
            } catch (IOException | RuntimeException e) {
                return null;
            }
            identity &= Math.abs(y - x) < 1e-4;
            if (i > 0) {
                sb.append(' ');
            }
            Num.append(sb, y, 4);
        }
        return identity ? null : sb.toString();
    }

    private String openBlend(String blend, List<Path2D> clips, Out o) {
        syncFor(List.of(), o);
        o.sb.append("<g style=\"mix-blend-mode:").append(blend).append("\">");
        StringBuilder close = new StringBuilder("</g>");
        Level level = o.levels.peek();
        for (int i = level.base; i < clips.size(); i++) {
            String id = clip(clips.get(i));
            if (id != null) {
                o.sb.append("<g clip-path=\"url(#").append(id).append(")\">");
                close.append("</g>");
            }
        }
        return close.toString();
    }

    private boolean emit(CharSequence element) {
        if (++elements > MAX_ELEMENTS) {
            return false;
        }
        PDGraphicsState gs = getGraphicsState();
        String mask = gs.getSoftMask() == null ? null : softMask(gs.getSoftMask());
        String blend = blend(gs.getBlendMode());
        StringBuilder sb = out.sb;
        if (blend != null) {
            String close = openBlend(blend, gs.getCurrentClippingPaths(), out);
            if (mask != null) {
                sb.append("<g mask=\"url(#").append(mask).append(")\">").append(element).append("</g>");
            } else {
                sb.append(element);
            }
            sb.append(close).append('\n');
            return true;
        }
        sync();
        if (mask != null || blend != null) {
            sb.append("<g");
            if (mask != null) {
                sb.append(" mask=\"url(#").append(mask).append(")\"");
            }
            if (blend != null) {
                sb.append(" style=\"mix-blend-mode:").append(blend).append('"');
            }
            sb.append('>').append(element).append("</g>\n");
        } else {
            sb.append(element).append('\n');
        }
        return true;
    }

    private Paint paint(boolean stroke, AffineTransform element, Rectangle2D area) {
        PDGraphicsState gs = getGraphicsState();
        PDColor color = stroke ? gs.getStrokingColor() : gs.getNonStrokingColor();
        double alpha = stroke ? gs.getAlphaConstant() : gs.getNonStrokeAlphaConstant();
        if (color != null && color.getColorSpace() instanceof PDPattern pattern) {
            try {
                PDAbstractPattern p = pattern.getPattern(color);
                if (p == null) {
                    return Paint.NONE;
                }
                AffineTransform t = new AffineTransform(out.toSvg);
                t.concatenate(Matrix.concatenate(getInitialMatrix(), p.getMatrix()).createAffineTransform());
                AffineTransform inverse = element == null ? new AffineTransform() : element.createInverse();
                AffineTransform local = new AffineTransform(inverse);
                local.concatenate(t);
                if (p instanceof PDShadingPattern sp && sp.getShading() != null) {
                    Rectangle2D localArea = area == null ? null : inverse.createTransformedShape(area).getBounds2D();
                    String body = localArea == null ? null
                            : Shades.gradient(sp.getShading(), local, localArea, "\u0001");
                    if (body != null) {
                        return new Paint("url(#" + define("g", body) + ")", alpha, null, null);
                    }
                    return new Paint(null, alpha, sp.getShading(), t);
                }
                if (p instanceof PDTilingPattern tp) {
                    String id = tile(tp, color, pattern.getUnderlyingColorSpace(), local);
                    if (id != null) {
                        return new Paint("url(#" + id + ")", alpha, null, null);
                    }
                }
                return new Paint("#808080", alpha, null, null);
            } catch (IOException | RuntimeException | NoninvertibleTransformException e) {
                return new Paint("#808080", alpha, null, null);
            }
        }
        return new Paint(Num.hex(PdfColours.toRgb(color, 0)), alpha, null, null);
    }

    private String tile(PDTilingPattern tp, PDColor color, PDColorSpace underlying, AffineTransform t)
            throws IOException {
        PDRectangle bbox = tp.getBBox();
        float xs = Math.abs(tp.getXStep());
        float ys = Math.abs(tp.getYStep());
        if (bbox == null || depth >= MAX_DEPTH || !(xs > 0) || !(ys > 0) || !Float.isFinite(xs) || !Float.isFinite(ys)) {
            return null;
        }
        boolean uncoloured = tp.getPaintType() == PDTilingPattern.PAINT_UNCOLORED;
        Object key = java.util.Arrays.asList(tp.getCOSObject(), Num.matrix(t),
                uncoloured ? java.util.Arrays.toString(color.getComponents()) : "");
        String known = tileIds.get(key);
        if (known != null) {
            return known;
        }
        flushText();
        Out saved = out;
        Rectangle2D area = new Rectangle2D.Double(bbox.getLowerLeftX(), bbox.getLowerLeftY(), bbox.getWidth(),
                bbox.getHeight());
        out = new Out(new StringBuilder(), new AffineTransform(), area, 1, 4);
        depth++;
        boolean done = false;
        try {
            nested(() -> processTilingPattern(tp, uncoloured ? color : null, uncoloured ? underlying : null,
                    new Matrix()));
            flushText();
            closeAll(out);
            done = true;
        } finally {
            depth--;
            if (!done) {
                run = null;
                out = saved;
            }
        }
        StringBuilder content = out.sb;
        out = saved;
        String id = id("p");
        StringBuilder sb = new StringBuilder(content.length() + 200);
        sb.append("<pattern id=\"").append(id).append("\" patternUnits=\"userSpaceOnUse\" x=\"");
        Num.append(sb, area.getX(), 4);
        sb.append("\" y=\"");
        Num.append(sb, area.getY(), 4);
        sb.append("\" width=\"");
        Num.append(sb, xs, 4);
        sb.append("\" height=\"");
        Num.append(sb, ys, 4);
        sb.append("\" patternTransform=\"");
        Num.matrix(sb, t);
        sb.append("\"><g transform=\"translate(");
        Num.append(sb, -area.getX(), 4);
        sb.append(' ');
        Num.append(sb, -area.getY(), 4);
        sb.append(")\">\n").append(content).append("</g></pattern>");
        defs.append(sb).append('\n');
        tileIds.put(key, id);
        return id;
    }

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        path.moveTo(p0.getX(), p0.getY());
        path.lineTo(p1.getX(), p1.getY());
        path.lineTo(p2.getX(), p2.getY());
        path.lineTo(p3.getX(), p3.getY());
        path.closePath();
    }

    @Override
    public void moveTo(float x, float y) {
        path.moveTo(x, y);
    }

    @Override
    public void lineTo(float x, float y) {
        if (path.getCurrentPoint() == null) {
            path.moveTo(x, y);
        } else {
            path.lineTo(x, y);
        }
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        if (path.getCurrentPoint() == null) {
            path.moveTo(x3, y3);
        } else {
            path.curveTo(x1, y1, x2, y2, x3, y3);
        }
    }

    @Override
    public Point2D getCurrentPoint() {
        return path.getCurrentPoint();
    }

    @Override
    public void closePath() {
        if (path.getCurrentPoint() != null) {
            path.closePath();
        }
    }

    @Override
    public void clip(int windingRule) {
        clipRule = windingRule;
    }

    @Override
    public void endPath() {
        finishPath();
    }

    private interface Nested {
        void run() throws IOException;
    }

    private void nested(Nested content) throws IOException {
        GeneralPath saved = new GeneralPath(path);
        int savedRule = clipRule;
        path.reset();
        clipRule = -1;
        try {
            content.run();
        } finally {
            path.reset();
            path.append(saved, false);
            path.setWindingRule(saved.getWindingRule());
            clipRule = savedRule;
        }
    }

    private void finishPath() {
        if (clipRule >= 0) {
            path.setWindingRule(clipRule);
            getGraphicsState().intersectClippingPath(path);
            clipRule = -1;
        }
        path.reset();
    }

    @Override
    public void strokePath() throws IOException {
        draw(false, true, Path2D.WIND_NON_ZERO);
        finishPath();
    }

    @Override
    public void fillPath(int windingRule) throws IOException {
        draw(true, false, windingRule);
        finishPath();
    }

    @Override
    public void fillAndStrokePath(int windingRule) throws IOException {
        draw(true, true, windingRule);
        finishPath();
    }

    private static boolean similar(AffineTransform t) {
        double a = t.getScaleX();
        double b = t.getShearY();
        double c = t.getShearX();
        double d = t.getScaleY();
        double l1 = Math.hypot(a, b);
        double l2 = Math.hypot(c, d);
        return l1 > 0 && Math.abs(l1 - l2) <= 1e-3 * Math.max(l1, l2) && Math.abs(a * c + b * d) <= 1e-3 * l1 * l2;
    }

    private void draw(boolean fill, boolean stroke, int rule) throws IOException {
        if (path.getCurrentPoint() == null) {
            return;
        }
        flushText();
        PDGraphicsState gs = getGraphicsState();
        AffineTransform ctm = new AffineTransform(out.toSvg);
        ctm.concatenate(gs.getCurrentTransformationMatrix().createAffineTransform());
        boolean local = stroke && !similar(ctm);
        AffineTransform element = null;
        Shape shape;
        if (local) {
            try {
                AffineTransform user = gs.getCurrentTransformationMatrix().createAffineTransform().createInverse();
                shape = user.createTransformedShape(path);
                element = ctm;
            } catch (NoninvertibleTransformException e) {
                return;
            }
        } else {
            shape = out.toSvg.createTransformedShape(path);
        }
        Rectangle2D area = (element == null ? shape : element.createTransformedShape(shape)).getBounds2D();
        Paint fp = fill ? paint(false, element, area) : Paint.NONE;
        Paint sp = stroke ? paint(true, element, area) : Paint.NONE;
        if (fill && fp.raster() != null) {
            rasterFill(fp, shape, element, rule);
            fill = false;
            fp = Paint.NONE;
            if (!stroke) {
                return;
            }
        }
        if (stroke && sp.raster() != null) {
            sp = new Paint("#808080", sp.opacity(), null, null);
        }
        if ((!fill || fp.opacity() <= 0) && (!stroke || sp.opacity() <= 0)) {
            return;
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append("<path d=\"");
        int segments = Num.path(sb, shape, null, out.decimals);
        if (segments == 0 || segments > MAX_PATH_SEGMENTS) {
            return;
        }
        sb.append('"');
        if (element != null) {
            sb.append(" transform=\"");
            Num.matrix(sb, element);
            sb.append('"');
        }
        sb.append(" fill=\"").append(fill ? fp.value() : "none").append('"');
        if (fill) {
            if (fp.opacity() < 1) {
                sb.append(" fill-opacity=\"");
                Num.append(sb, fp.opacity(), 3);
                sb.append('"');
            }
            if (rule == Path2D.WIND_EVEN_ODD) {
                sb.append(" fill-rule=\"evenodd\"");
            }
        }
        if (stroke) {
            double device = Math.sqrt(Math.abs(ctm.getDeterminant()));
            strokeAttributes(sb, sp, gs, element == null ? device : 1, device);
        }
        sb.append("/>");
        emit(sb);
    }

    private void strokeAttributes(StringBuilder sb, Paint sp, PDGraphicsState gs, double scale, double device) {
        sb.append(" stroke=\"").append(sp.value()).append('"');
        if (sp.opacity() < 1) {
            sb.append(" stroke-opacity=\"");
            Num.append(sb, sp.opacity(), 3);
            sb.append('"');
        }
        double w = gs.getLineWidth() * scale;
        if (!(gs.getLineWidth() > 0)) {
            w = 0.35 * scale / Math.max(1e-9, device);
        }
        if (Math.abs(w - 1) > 1e-4) {
            sb.append(" stroke-width=\"");
            Num.append(sb, w, 3);
            sb.append('"');
        }
        switch (gs.getLineCap()) {
            case 1 -> sb.append(" stroke-linecap=\"round\"");
            case 2 -> sb.append(" stroke-linecap=\"square\"");
            default -> { }
        }
        switch (gs.getLineJoin()) {
            case 1 -> sb.append(" stroke-linejoin=\"round\"");
            case 2 -> sb.append(" stroke-linejoin=\"bevel\"");
            default -> {
                float limit = Math.max(1, gs.getMiterLimit());
                if (Math.abs(limit - 4) > 1e-3) {
                    sb.append(" stroke-miterlimit=\"");
                    Num.append(sb, limit, 3);
                    sb.append('"');
                }
            }
        }
        var dash = gs.getLineDashPattern();
        if (dash != null && dash.getDashArray() != null && dash.getDashArray().length > 0) {
            float[] d = dash.getDashArray();
            double total = 0;
            for (float v : d) {
                total += Math.max(0, v);
            }
            if (total > 0 && d.length <= 64) {
                sb.append(" stroke-dasharray=\"");
                for (int i = 0; i < d.length; i++) {
                    if (i > 0) {
                        sb.append(' ');
                    }
                    Num.append(sb, Math.max(0, d[i]) * scale, 3);
                }
                sb.append('"');
                if (dash.getPhase() != 0) {
                    sb.append(" stroke-dashoffset=\"");
                    Num.append(sb, dash.getPhase() * scale, 3);
                    sb.append('"');
                }
            }
        }
    }

    private void rasterFill(Paint fp, Shape shape, AffineTransform element, int rule) {
        Shape inSvg = element == null ? shape : element.createTransformedShape(shape);
        Rectangle2D area = inSvg.getBounds2D().createIntersection(clipBounds());
        if (area.isEmpty()) {
            return;
        }
        StringBuilder clip = new StringBuilder(256);
        clip.append("<clipPath id=\"\u0001\" clipPathUnits=\"userSpaceOnUse\"><path d=\"");
        Num.path(clip, inSvg, null, out.decimals);
        clip.append('"');
        if (rule == Path2D.WIND_EVEN_ODD) {
            clip.append(" clip-rule=\"evenodd\"");
        }
        clip.append("/></clipPath>");
        String id = define("c", clip.toString());
        String image = raster(fp.raster(), fp.rasterToSvg(), area, fp.opacity());
        if (image != null) {
            emit("<g clip-path=\"url(#" + id + ")\">" + image + "</g>");
        }
    }

    private Rectangle2D clipBounds() {
        List<Path2D> clips = getGraphicsState().getCurrentClippingPaths();
        Rectangle2D box = null;
        for (Path2D p : clips) {
            Rectangle2D b = out.toSvg.createTransformedShape(p.getBounds2D()).getBounds2D();
            box = box == null ? b : box.createIntersection(b);
        }
        if (box == null) {
            box = out.area != null ? out.area : new Rectangle2D.Double(-1e4, -1e4, 2e4, 2e4);
        }
        if (out.area != null) {
            box = box.createIntersection(out.area);
        }
        return box;
    }

    private String raster(PDShading shading, AffineTransform toSvg, Rectangle2D area, double opacity) {
        if (area.getWidth() <= 0 || area.getHeight() <= 0) {
            return null;
        }
        AffineTransform device = out.toSvg;
        double scale = out.area == null ? 2 * Math.sqrt(Math.abs(device.getDeterminant())) : 2;
        double px = area.getWidth() * scale * area.getHeight() * scale;
        if (px > MAX_RASTER_PIXELS) {
            scale *= Math.sqrt(MAX_RASTER_PIXELS / px);
        }
        int w = Math.max(1, (int) Math.ceil(area.getWidth() * scale));
        int h = Math.max(1, (int) Math.ceil(area.getHeight() * scale));
        if (imagePixels + (long) w * h > MAX_PAGE_IMAGE_PIXELS) {
            return null;
        }
        imagePixels += (long) w * h;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.scale(w / area.getWidth(), h / area.getHeight());
            g.translate(-area.getX(), -area.getY());
            g.setPaint(shading.toPaint(new Matrix(toSvg)));
            g.fill(area);
        } catch (RuntimeException e) {
            return null;
        } finally {
            g.dispose();
        }
        rasterShadings++;
        try {
            Pictures.Encoded enc = Pictures.encode(img);
            StringBuilder sb = new StringBuilder(enc.bytes().length * 4 / 3 + 200);
            sb.append("<image x=\"");
            Num.append(sb, area.getX(), out.decimals);
            sb.append("\" y=\"");
            Num.append(sb, area.getY(), out.decimals);
            sb.append("\" width=\"");
            Num.append(sb, area.getWidth(), out.decimals);
            sb.append("\" height=\"");
            Num.append(sb, area.getHeight(), out.decimals);
            sb.append("\" preserveAspectRatio=\"none\"");
            if (opacity < 1) {
                sb.append(" opacity=\"");
                Num.append(sb, opacity, 3);
                sb.append('"');
            }
            sb.append(" xlink:href=\"");
            enc.appendUri(sb);
            sb.append("\"/>");
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void shadingFill(COSName name) throws IOException {
        PDShading shading = getResources().getShading(name);
        if (shading == null) {
            return;
        }
        flushText();
        PDGraphicsState gs = getGraphicsState();
        AffineTransform t = new AffineTransform(out.toSvg);
        t.concatenate(gs.getCurrentTransformationMatrix().createAffineTransform());
        Rectangle2D area = clipBounds();
        PDRectangle bbox = shading.getBBox();
        if (bbox != null) {
            area = area.createIntersection(t.createTransformedShape(bbox.toGeneralPath()).getBounds2D());
        }
        if (area.isEmpty()) {
            return;
        }
        double alpha = gs.getNonStrokeAlphaConstant();
        String body = Shades.gradient(shading, t, area, "\u0001");
        if (body != null) {
            StringBuilder sb = new StringBuilder(160);
            sb.append("<rect x=\"");
            Num.append(sb, area.getX(), out.decimals);
            sb.append("\" y=\"");
            Num.append(sb, area.getY(), out.decimals);
            sb.append("\" width=\"");
            Num.append(sb, area.getWidth(), out.decimals);
            sb.append("\" height=\"");
            Num.append(sb, area.getHeight(), out.decimals);
            sb.append("\" fill=\"url(#").append(define("g", body)).append(")\"");
            if (alpha < 1) {
                sb.append(" fill-opacity=\"");
                Num.append(sb, alpha, 3);
                sb.append('"');
            }
            sb.append("/>");
            emit(sb);
            return;
        }
        String image = raster(shading, t, area, alpha);
        if (image != null) {
            emit(image);
        }
    }

    @Override
    public void drawImage(PDImage image) throws IOException {
        if (image instanceof PDXObject x && hidden.hidden(x)) {
            return;
        }
        flushText();
        PDGraphicsState gs = getGraphicsState();
        double alpha = gs.getNonStrokeAlphaConstant();
        if (alpha <= 0) {
            return;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        AffineTransform t = new AffineTransform(out.toSvg);
        t.concatenate(gs.getCurrentTransformationMatrix().createAffineTransform());
        t.translate(0, 1);
        t.scale(1.0 / w, -1.0 / h);
        String id;
        try {
            id = imageDef(image, w, h);
        } catch (IOException | RuntimeException e) {
            id = null;
        }
        if (id == null) {
            return;
        }
        images++;
        StringBuilder sb = new StringBuilder(160);
        sb.append("<use xlink:href=\"#").append(id).append("\" transform=\"");
        Num.matrix(sb, t);
        sb.append('"');
        if (alpha < 1) {
            sb.append(" opacity=\"");
            Num.append(sb, alpha, 3);
            sb.append('"');
        }
        sb.append("/>");
        emit(sb);
    }

    private String imageDef(PDImage image, int w, int h) throws IOException {
        boolean stencil = image.isStencil();
        int fill = stencil ? PdfColours.toRgb(getGraphicsState().getNonStrokingColor(),
                0) : 0;
        Object base = image instanceof PDImageXObject x ? x.getCOSObject() : image;
        Object key = stencil ? List.of(base, fill) : base;
        String known = imageIds.get(key);
        if (known != null) {
            return known;
        }
        PDImage fitted = ImageBudget.fitted(image);
        if (fitted == null) {
            return null;
        }
        long px = (long) w * h;
        if (imagePixels + px > MAX_PAGE_IMAGE_PIXELS) {
            return null;
        }
        imagePixels += px;
        String id = id("i");
        StringBuilder sb = defs;
        String smaskId = null;
        Pictures.Encoded enc = null;
        if (!stencil && fitted == image && image instanceof PDImageXObject x && x.getMask() == null) {
            enc = Pictures.jpeg(x);
            PDImageXObject smask = enc == null ? null : x.getSoftMask();
            if (smask != null) {
                long mpx = (long) smask.getWidth() * smask.getHeight();
                if (ImageBudget.fitted(smask) != smask || smask.getCOSObject().containsKey(MATTE)
                        || imagePixels + mpx > MAX_PAGE_IMAGE_PIXELS) {
                    enc = null;
                    smask = null;
                } else {
                    imagePixels += mpx;
                }
            }
            if (smask != null) {
                BufferedImage m = smask.getOpaqueImage();
                Pictures.Encoded menc = Pictures.encode(Pictures.gray(m));
                smaskId = id("m");
                sb.append("<mask id=\"").append(smaskId).append("\" maskUnits=\"userSpaceOnUse\" x=\"0\" y=\"0\" width=\"")
                        .append(w).append("\" height=\"").append(h).append("\"><image width=\"").append(w)
                        .append("\" height=\"").append(h).append("\" preserveAspectRatio=\"none\" xlink:href=\"");
                menc.appendUri(sb);
                sb.append("\"/></mask>\n");
            }
        }
        if (enc == null) {
            BufferedImage img;
            if (stencil) {
                img = fitted.getStencilImage(new Color(fill));
            } else {
                img = fitted.getImage();
            }
            if (img == null) {
                return null;
            }
            enc = Pictures.encode(img);
        }
        sb.append("<image id=\"").append(id).append("\" width=\"").append(w).append("\" height=\"").append(h)
                .append("\" preserveAspectRatio=\"none\"");
        if (!image.getInterpolate() && Math.max(w, h) <= 64) {
            sb.append(" style=\"image-rendering:optimizeSpeed;image-rendering:pixelated\"");
        }
        if (smaskId != null) {
            sb.append(" mask=\"url(#").append(smaskId).append(")\"");
        }
        sb.append(" xlink:href=\"");
        enc.appendUri(sb);
        sb.append("\"/>\n");
        imageIds.put(key, id);
        return id;
    }

    private final class Run {
        final SvgFonts.Face face;
        final AffineTransform linear;
        final AffineTransform inverse;
        final double ox;
        final double oy;
        final double size;
        final String fill;
        final double fillOpacity;
        final String stroke;
        final List<Path2D> clips;
        final PDSoftMask mask;
        final String maskId;
        final BlendMode blend;
        final RenderingMode mode;
        final double lineWidth;
        final StringBuilder text = new StringBuilder();
        final StringBuilder xs = new StringBuilder();
        final StringBuilder ys = new StringBuilder();
        final StringBuilder paths = new StringBuilder();
        boolean anyY;
        boolean visible;
        boolean inHidden;
        int chars;

        Run(SvgFonts.Face face, AffineTransform linear, AffineTransform inverse, double ox, double oy, double size,
                String fill, double fillOpacity, String stroke, double lineWidth, PDGraphicsState gs) {
            this.face = face;
            this.linear = linear;
            this.inverse = inverse;
            this.ox = ox;
            this.oy = oy;
            this.size = size;
            this.fill = fill;
            this.fillOpacity = fillOpacity;
            this.stroke = stroke;
            this.lineWidth = lineWidth;
            this.clips = List.copyOf(gs.getCurrentClippingPaths());
            this.mask = gs.getSoftMask();
            this.maskId = mask == null ? null : softMask(mask);
            this.blend = gs.getBlendMode();
            this.mode = gs.getTextState().getRenderingMode();
        }

        boolean fits(SvgFonts.Face f, AffineTransform l, double s, String fill, double fo, String stroke, double lw,
                PDGraphicsState gs) {
            return f == face && Math.abs(s - size) <= 1e-3 * size && close(l, linear) && fill.equals(this.fill)
                    && fo == fillOpacity && stroke.equals(this.stroke) && lw == lineWidth
                    && sameClips(gs.getCurrentClippingPaths()) && gs.getSoftMask() == mask && gs.getBlendMode() == blend
                    && gs.getTextState().getRenderingMode() == mode && chars < 4000;
        }

        private boolean sameClips(List<Path2D> now) {
            if (now.size() != clips.size()) {
                return false;
            }
            for (int i = 0; i < now.size(); i++) {
                if (now.get(i) != clips.get(i)) {
                    return false;
                }
            }
            return true;
        }

        void add(String chars, double x, double y, boolean show, double advance) {
            int n = chars.codePointCount(0, chars.length());
            int k = 0;
            for (int i = 0; i < chars.length(); ) {
                int cp = chars.codePointAt(i);
                i += Character.charCount(cp);
                if (!xmlChar(cp)) {
                    k++;
                    continue;
                }
                if (show == inHidden) {
                    if (inHidden) {
                        text.append("</tspan>");
                    } else {
                        text.append("<tspan fill-opacity=\"0\" stroke=\"none\">");
                    }
                    inHidden = !inHidden;
                }
                double cx = x + advance * k / n;
                if (!xs.isEmpty()) {
                    xs.append(' ');
                    ys.append(' ');
                }
                Num.append(xs, cx, 2);
                Num.append(ys, y, 2);
                if (Math.abs(y) > 0.005) {
                    anyY = true;
                }
                escape(text, cp);
                this.chars++;
                k++;
                if (show) {
                    visible = true;
                }
            }
        }
    }

    private static boolean close(AffineTransform a, AffineTransform b) {
        return Math.abs(a.getScaleX() - b.getScaleX()) < 1e-4 && Math.abs(a.getScaleY() - b.getScaleY()) < 1e-4
                && Math.abs(a.getShearX() - b.getShearX()) < 1e-4 && Math.abs(a.getShearY() - b.getShearY()) < 1e-4;
    }

    static boolean xmlChar(int cp) {
        return cp == 0x9 || cp == 0xA || cp == 0xD || cp >= 0x20 && cp <= 0xD7FF || cp >= 0xE000 && cp <= 0xFFFD
                || cp >= 0x10000 && cp <= 0x10FFFF;
    }

    static void escape(StringBuilder sb, int cp) {
        switch (cp) {
            case '&' -> sb.append("&amp;");
            case '<' -> sb.append("&lt;");
            case '>' -> sb.append("&gt;");
            case '"' -> sb.append("&quot;");
            case 0x9, 0xA, 0xD -> sb.append(' ');
            default -> sb.appendCodePoint(cp);
        }
    }

    static void escape(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (xmlChar(cp)) {
                escape(sb, cp);
            }
        }
    }

    private void flushText() {
        Run r = run;
        run = null;
        if (r == null || r.chars == 0 && r.paths.isEmpty()) {
            return;
        }
        if (r.inHidden) {
            r.text.append("</tspan>");
        }
        StringBuilder sb = new StringBuilder(r.text.length() + r.xs.length() * 2 + r.paths.length() + 256);
        sb.append(r.paths);
        if (r.chars > 0) {
            sb.append("<text xml:space=\"preserve\" transform=\"");
            AffineTransform t = new AffineTransform(r.linear);
            t.preConcatenate(AffineTransform.getTranslateInstance(r.ox, r.oy));
            Num.matrix(sb, t);
            sb.append("\" font-size=\"");
            Num.append(sb, r.size, 3);
            sb.append("\" x=\"").append(r.xs).append('"');
            if (r.anyY) {
                sb.append(" y=\"").append(r.ys).append('"');
            }
            sb.append(" font-family=\"");
            if (r.face.web()) {
                sb.append('\'').append(r.face.family).append("',");
            }
            sb.append(r.face.fallback.replace('"', '\'')).append('"');
            if (!r.face.web()) {
                if (r.face.bold) {
                    sb.append(" font-weight=\"bold\"");
                }
                if (r.face.italic) {
                    sb.append(" font-style=\"italic\"");
                }
            }
            boolean filled = r.mode.isFill();
            boolean stroked = r.mode.isStroke();
            if (!r.visible || !filled && !stroked) {
                sb.append(" fill=\"none\"");
            } else {
                sb.append(" fill=\"").append(filled ? r.fill : "none").append('"');
                if (filled && r.fillOpacity < 1) {
                    sb.append(" fill-opacity=\"");
                    Num.append(sb, r.fillOpacity, 3);
                    sb.append('"');
                }
                if (stroked) {
                    sb.append(r.stroke);
                }
            }
            sb.append('>').append(r.text).append("</text>");
        }
        Out o = out;
        if (++elements <= MAX_ELEMENTS) {
            String mask = r.maskId;
            String blend = blend(r.blend);
            if (blend != null) {
                String close = openBlend(blend, r.clips, o);
                if (mask != null) {
                    o.sb.append("<g mask=\"url(#").append(mask).append(")\">").append(sb).append("</g>");
                } else {
                    o.sb.append(sb);
                }
                o.sb.append(close).append('\n');
                return;
            }
            syncFor(r.clips, o);
            if (mask != null || blend != null) {
                o.sb.append("<g");
                if (mask != null) {
                    o.sb.append(" mask=\"url(#").append(mask).append(")\"");
                }
                if (blend != null) {
                    o.sb.append(" style=\"mix-blend-mode:").append(blend).append('"');
                }
                o.sb.append('>').append(sb).append("</g>\n");
            } else {
                o.sb.append(sb).append('\n');
            }
        }
    }

    private void syncFor(List<Path2D> clips, Out o) {
        Level level = o.levels.peek();
        int n = Math.max(0, clips.size() - level.base);
        int common = 0;
        while (common < level.open.size() && common < n && level.open.get(common) == clips.get(level.base + common)) {
            common++;
        }
        while (level.open.size() > common) {
            int last = level.open.size() - 1;
            if (level.emitted.get(last)) {
                o.sb.append("</g>\n");
            }
            level.open.remove(last);
            level.emitted.remove(last);
        }
        for (int i = common; i < n; i++) {
            Path2D clip = clips.get(level.base + i);
            String id = clip(clip);
            if (id != null) {
                o.sb.append("<g clip-path=\"url(#").append(id).append(")\">\n");
            }
            level.open.add(clip);
            level.emitted.add(id != null);
        }
    }

    @Override
    public void beginText() throws IOException {
        super.beginText();
        textClip = null;
    }

    @Override
    public void endText() throws IOException {
        super.endText();
        if (textClip != null) {
            flushText();
            getGraphicsState().intersectClippingPath(textClip);
            textClip = null;
        }
    }

    @Override
    protected void showFontGlyph(Matrix trm, PDFont font, int code, Vector displacement) throws IOException {
        PDGraphicsState gs = getGraphicsState();
        RenderingMode mode = gs.getTextState().getRenderingMode();
        if (hidden.active() && !mode.isClip()) {
            return;
        }
        AffineTransform glyphToSvg = new AffineTransform(out.toSvg);
        glyphToSvg.concatenate(trm.createAffineTransform());
        if (mode.isClip() && font instanceof PDVectorFont vector) {
            GeneralPath outline = outline(vector, font, code, trm);
            if (outline != null) {
                if (textClip == null) {
                    textClip = new GeneralPath();
                }
                textClip.append(outline, false);
            }
        }
        String unicode = font.toUnicode(code);
        SvgFonts.Face face = fonts.face(font);
        boolean paints = mode.isFill() || mode.isStroke();
        int cp = unicode == null || unicode.isEmpty() ? -1 : unicode.codePointAt(0);
        boolean single = unicode != null && unicode.codePointCount(0, unicode.length()) == 1 && xmlChar(cp)
                && !Character.isISOControl(cp);
        boolean asText = paints && single && face.text(code, cp);
        AffineTransform m = new AffineTransform(glyphToSvg);
        m.concatenate(FLIP);
        double det = m.getDeterminant();
        if (!(Math.abs(det) > 1e-12) || !Double.isFinite(det)) {
            return;
        }
        double size = Math.sqrt(Math.abs(m.getShearX() * m.getShearX() + m.getScaleY() * m.getScaleY()));
        if (!(size > 1e-6)) {
            size = Math.sqrt(Math.abs(det));
        }
        AffineTransform linear = new AffineTransform(m.getScaleX() / size, m.getShearY() / size, m.getShearX() / size,
                m.getScaleY() / size, 0, 0);
        String fill = "#000";
        double fillOpacity = 1;
        String strokeAttrs = "";
        double lineWidth = 0;
        if (paints) {
            AffineTransform element = new AffineTransform(linear);
            element.preConcatenate(AffineTransform.getTranslateInstance(m.getTranslateX(), m.getTranslateY()));
            if (mode.isFill()) {
                Paint p = paint(false, run != null && run.face == face ? textElement(run) : element, null);
                fill = p.value() == null ? "#808080" : p.value();
                fillOpacity = p.opacity();
            }
            if (mode.isStroke()) {
                Paint p = paint(true, element, null);
                StringBuilder sa = new StringBuilder();
                AffineTransform ctm = new AffineTransform(out.toSvg);
                ctm.concatenate(gs.getCurrentTransformationMatrix().createAffineTransform());
                double device = Math.sqrt(Math.abs(ctm.getDeterminant()));
                double textScale = device / Math.sqrt(Math.abs(linear.getDeterminant()));
                strokeAttributes(sa, p.value() == null ? new Paint("#808080", p.opacity(), null, null) : p, gs,
                        textScale, textScale);
                strokeAttrs = sa.toString();
                lineWidth = gs.getLineWidth();
            }
        }
        if (run == null || !run.fits(face, linear, size, fill, fillOpacity, strokeAttrs, lineWidth, gs)) {
            flushText();
            AffineTransform inverse;
            try {
                inverse = linear.createInverse();
            } catch (NoninvertibleTransformException e) {
                return;
            }
            run = new Run(face, linear, inverse, m.getTranslateX(), m.getTranslateY(), size, fill, fillOpacity,
                    strokeAttrs, lineWidth, gs);
        }
        Point2D local = run.inverse.transform(new Point2D.Double(m.getTranslateX() - run.ox,
                m.getTranslateY() - run.oy), null);
        double advance = displacement.getX() * size;
        if (asText) {
            run.add(unicode, local.getX(), local.getY(), true, advance);
            face.used.add(cp);
            facesUsed.add(face);
            textGlyphs++;
            return;
        }
        if (paints && font instanceof PDVectorFont vector) {
            GeneralPath outline = outline(vector, font, code, trm);
            if (outline != null && outline.getCurrentPoint() != null) {
                glyphPath(run.paths, out.toSvg.createTransformedShape(outline), mode);
                pathGlyphs++;
            }
        } else if (!paints) {
            invisibleGlyphs++;
        }
        if (unicode != null && !unicode.isEmpty()) {
            run.add(unicode, local.getX(), local.getY(), false, advance);
            if (run.face.web()) {
                facesUsed.add(face);
            }
        }
    }

    private AffineTransform textElement(Run r) {
        AffineTransform t = new AffineTransform(r.linear);
        t.preConcatenate(AffineTransform.getTranslateInstance(r.ox, r.oy));
        return t;
    }

    private void glyphPath(StringBuilder sb, Shape shape, RenderingMode mode) {
        PDGraphicsState gs = getGraphicsState();
        Rectangle2D area = shape.getBounds2D();
        Paint fp = mode.isFill() ? paint(false, null, area) : Paint.NONE;
        Paint sp = mode.isStroke() ? paint(true, null, area) : Paint.NONE;
        sb.append("<path d=\"");
        Num.path(sb, shape, null, out.decimals);
        sb.append("\" fill=\"").append(mode.isFill() ? (fp.value() == null ? "#808080" : fp.value()) : "none").append('"');
        if (mode.isFill() && fp.opacity() < 1) {
            sb.append(" fill-opacity=\"");
            Num.append(sb, fp.opacity(), 3);
            sb.append('"');
        }
        if (mode.isStroke()) {
            AffineTransform ctm = new AffineTransform(out.toSvg);
            ctm.concatenate(gs.getCurrentTransformationMatrix().createAffineTransform());
            double device = Math.sqrt(Math.abs(ctm.getDeterminant()));
            strokeAttributes(sb, sp.value() == null ? new Paint("#808080", sp.opacity(), null, null) : sp, gs,
                    device, device);
        }
        sb.append("/>\n");
    }

    private static GeneralPath outline(PDVectorFont vector, PDFont font, int code, Matrix trm) {
        try {
            GeneralPath glyph = vector.getNormalizedPath(code);
            if (glyph == null) {
                return null;
            }
            AffineTransform at = trm.createAffineTransform();
            at.concatenate(font.getFontMatrix().createAffineTransform());
            GeneralPath p = (GeneralPath) glyph.clone();
            p.transform(at);
            return p;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Override
    protected void showType3Glyph(Matrix trm, PDType3Font font, int code, Vector displacement) throws IOException {
        PDGraphicsState gs = getGraphicsState();
        RenderingMode mode = gs.getTextState().getRenderingMode();
        if (mode.isFill() || mode.isStroke()) {
            flushText();
            if (depth < MAX_DEPTH) {
                depth++;
                try {
                    super.showType3Glyph(trm, font, code, displacement);
                } finally {
                    depth--;
                }
            }
            flushText();
            pathGlyphs++;
        } else {
            invisibleGlyphs++;
        }
        String unicode = font.toUnicode(code);
        if (unicode == null || unicode.isEmpty()) {
            return;
        }
        AffineTransform m = new AffineTransform(out.toSvg);
        m.concatenate(trm.createAffineTransform());
        m.concatenate(font.getFontMatrix().createAffineTransform());
        m.scale(1000, -1000);
        double det = m.getDeterminant();
        if (!(Math.abs(det) > 1e-12) || !Double.isFinite(det)) {
            return;
        }
        double size = Math.sqrt(Math.abs(det));
        AffineTransform linear = new AffineTransform(m.getScaleX() / size, m.getShearY() / size, m.getShearX() / size,
                m.getScaleY() / size, 0, 0);
        SvgFonts.Face face = fonts.face(font);
        if (run == null || !run.fits(face, linear, size, "none", 1, "", 0, gs)) {
            flushText();
            AffineTransform inverse;
            try {
                inverse = linear.createInverse();
            } catch (NoninvertibleTransformException e) {
                return;
            }
            run = new Run(face, linear, inverse, m.getTranslateX(), m.getTranslateY(), size, "none", 1, "", 0, gs);
        }
        Point2D local = run.inverse.transform(new Point2D.Double(m.getTranslateX() - run.ox,
                m.getTranslateY() - run.oy), null);
        run.add(unicode, local.getX(), local.getY(), false, displacement.getX() * size);
    }

    private void annotations(PDPage page) throws IOException {
        List<PDAnnotation> list;
        try {
            list = page.getAnnotations();
        } catch (IOException | RuntimeException e) {
            return;
        }
        int n = 0;
        for (PDAnnotation a : list) {
            if (++n > 10_000) {
                break;
            }
            if (a instanceof PDAnnotationPopup || a.isHidden() || a.isNoView() || a.isInvisible()) {
                continue;
            }
            try {
                var appearance = a.getAppearance();
                if (appearance == null || appearance.getNormalAppearance() == null) {
                    if (!GENERATED.contains(a.getSubtype())) {
                        if (a instanceof PDAnnotationLink link) {
                            link(link);
                        }
                        continue;
                    }
                    a.constructAppearances(document);
                }
                showAnnotation(a);
            } catch (IOException | RuntimeException e) {
                continue;
            }
            flushText();
            if (a instanceof PDAnnotationLink link) {
                link(link);
            }
        }
    }

    private void link(PDAnnotationLink link) {
        PDAction action;
        try {
            action = link.getAction();
        } catch (RuntimeException e) {
            return;
        }
        String url = action instanceof PDActionURI uri && uri.getURI() != null ? Links.safeUrl(uri.getURI()) : null;
        if (url != null && !url.regionMatches(true, 0, "http://", 0, 7) && !url.regionMatches(true, 0, "https://", 0, 8)
                && !url.regionMatches(true, 0, "mailto:", 0, 7)) {
            url = null;
        }
        PDRectangle r = link.getRectangle();
        if (url == null || r == null) {
            return;
        }
        Rectangle2D box = out.toSvg.createTransformedShape(r.toGeneralPath()).getBounds2D();
        if (box.isEmpty()) {
            return;
        }
        links.append("<a xlink:href=\"");
        escape(links, url);
        links.append("\"><rect x=\"");
        Num.append(links, box.getX(), 2);
        links.append("\" y=\"");
        Num.append(links, box.getY(), 2);
        links.append("\" width=\"");
        Num.append(links, box.getWidth(), 2);
        links.append("\" height=\"");
        Num.append(links, box.getHeight(), 2);
        links.append("\" fill=\"#fff\" fill-opacity=\"0\"/></a>\n");
    }
}
