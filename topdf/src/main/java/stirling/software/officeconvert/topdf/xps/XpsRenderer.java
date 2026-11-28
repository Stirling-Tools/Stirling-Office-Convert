package stirling.software.officeconvert.topdf.xps;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.PdfOutput;

final class XpsRenderer {

    static final double POINTS = 0.75;

    static final int MAX_VISUAL_DEPTH = 8;

    static final long MAX_PAGE_SEGMENTS = 8_000_000;

    static final int MAX_PAGE_ELEMENTS = 4_000_000;

    static final int MAX_GLYPHS_PER_RUN = 100_000;

    static final int MAX_PERIODS = 256;

    static final int MAX_LINKS = 10_000;

    static final double MAX_PAGE_UNITS = 96 * 200;

    static final double ITALIC_SHEAR = 0.3640;

    static final double BOLD_STROKE = 0.02;

    static final String LONG_PATH = "Paths of more than " + XpsGeometry.MAX_SEGMENTS + " segments were left out";

    private final XpsPackage pkg;

    private final PdfOutput output;

    private final PDDocument document;

    final XpsFonts fonts;

    final XpsImages images;

    private final XpsProfiles profiles;

    private final Map<Long, COSDictionary> alphas = new HashMap<>();

    private final Map<String, Map<String, XpsElement>> dictionaries = new HashMap<>();

    private record VisualKey(XpsElement element, double x, double y, double w, double h) {}

    private final Map<VisualKey, COSStream> visuals = new HashMap<>();

    private final Set<XpsElement> drawing = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    private final Map<XpsElement, String> owners = new IdentityHashMap<>();

    private Map<String, Integer> firstPages;

    private int pageLinks;

    private final Map<String, Integer> pageIndex;

    private final Set<String> warnings = new LinkedHashSet<>();

    private int missingGlyphRuns;

    private String currentDocument = "/";

    private String language;

    private int skippedPages;

    private long segments;

    private int elements;

    XpsRenderer(XpsPackage pkg, PdfOutput output, FontLibrary library) {
        this.pkg = pkg;
        this.output = output;
        this.document = output.document();
        this.fonts = new XpsFonts(document, pkg, library);
        this.images = new XpsImages(document, pkg);
        this.profiles = new XpsProfiles(pkg, document);
        this.pageIndex = pkg.pageIndex();
    }

    Set<String> warnings() {
        return warnings;
    }

    String language() {
        return language;
    }

    int missingGlyphRuns() {
        return missingGlyphRuns;
    }

    record Scope(Scope parent, Map<String, XpsElement> map) {
        XpsElement find(String key) {
            Scope s = owner(key);
            return s == null ? null : s.map.get(key);
        }

        Scope owner(String key) {
            for (Scope s = this; s != null; s = s.parent) {
                if (s.map.get(key) != null) {
                    return s;
                }
            }
            return null;
        }
    }

    final class Ctx {
        final XpsOps ops;

        final PdfCanvas canvas;

        final AffineTransform toPage;

        final int depth;

        final String part;

        final List<Rectangle2D> accumulators;

        final Map<String, double[]> names;

        Ctx(XpsOps ops, PdfCanvas canvas, AffineTransform toPage, int depth, String part,
                List<Rectangle2D> accumulators, Map<String, double[]> names) {
            this.ops = ops;
            this.canvas = canvas;
            this.toPage = toPage;
            this.depth = depth;
            this.part = part;
            this.accumulators = accumulators;
            this.names = names;
        }

        AffineTransform pageTransform() {
            if (toPage == null) {
                return null;
            }
            AffineTransform t = new AffineTransform(toPage);
            t.concatenate(ops.ctm);
            return t;
        }
    }

    void page(int index) throws IOException {
        XpsPackage.Page p = pkg.pages().get(index);
        currentDocument = pkg.documents().get(p.document());
        XpsElement dom = pkg.page(p.part());
        owners.clear();
        props.clear();
        owners.put(dom, p.part());
        pageLinks = 0;
        XpsElement root = dom;
        if (!"FixedPage".equals(root.getLocalName())) {
            throw new IOException("The XPS page " + p.part() + " is not a fixed page");
        }
        String lang = XpsXml.lang(root);
        if (language == null && lang != null && !lang.equalsIgnoreCase("und") && lang.length() <= 35) {
            language = lang;
        }
        double w = size(XpsXml.attr(root, "Width"), p.width(), 816);
        double h = size(XpsXml.attr(root, "Height"), p.height(), 1056);
        PdfCanvas canvas = output.newPage((float) (w * POINTS), (float) (h * POINTS));
        PDPage page = canvas.page();
        boxes(page, root, w, h);
        COSStream stream = document.getDocument().createCOSStream();
        COSBase existing = page.getCOSObject().getDictionaryObject(COSName.CONTENTS);
        COSArray contents = new COSArray();
        if (existing != null) {
            contents.add(existing);
        }
        contents.add(stream);
        page.getCOSObject().setItem(COSName.CONTENTS, contents);
        PDResources resources = page.getResources();
        OutputStream os = XpsOps.flate(stream);
        XpsOps ops = new XpsOps(os, resources, new Rectangle2D.Double(0, 0, w, h));
        segments = 0;
        elements = 0;
        Map<String, double[]> names = new LinkedHashMap<>();
        try {
            ops.q();
            ops.cm(new AffineTransform(POINTS, 0, 0, -POINTS, 0, h * POINTS));
            AffineTransform toPage = inverseOrIdentity(ops.ctm);
            Ctx ctx = new Ctx(ops, canvas, toPage, 0, p.part(), new ArrayList<>(), names);
            Scope scope = resources(root, null, p.part());
            children(root, scope, ctx);
        } catch (Budget e) {
            warnings.add("Some XPS pages hold more drawing than the converter draws on one page; the rest of"
                    + " those pages was left out");
        } finally {
            ops.close();
        }
        float shift = Math.max(0, page.getMediaBox().getHeight() - (float) (h * POINTS));
        destinations(index, p, names, shift);
    }

    private static final class Budget extends IOException {
        Budget() {
            super("page budget");
        }
    }

    private void destinations(int index, XpsPackage.Page p, Map<String, double[]> names, float shift) {
        String doc = pkg.documents().get(p.document()).toLowerCase(Locale.ROOT);
        String pagePart = p.part().toLowerCase(Locale.ROOT);
        output.destination(pageKey(index), index, 0, shift);
        for (Map.Entry<String, double[]> e : names.entrySet()) {
            double[] at = e.getValue();
            float y = (float) (at[1] * POINTS) + shift;
            output.destination(doc + "#" + e.getKey(), index, (float) (at[0] * POINTS), y);
            output.destination(pagePart + "#" + e.getKey(), index, (float) (at[0] * POINTS), y);
        }
        for (String t : p.targets()) {
            output.destination(doc + "#" + t, index, 0, shift);
            output.destination(pagePart + "#" + t, index, 0, shift);
        }
    }

    private static String pageKey(int index) {
        return "\u0000" + index;
    }

    void outline() {
        for (int i = 0; i < output.pageCount(); i++) {
            output.destination(pageKey(i), i, 0, 0);
        }
        for (XpsPackage.OutlineEntry e : pkg.outline()) {
            String target = e.target();
            int hash = target.indexOf('#');
            if (hash < 0) {
                Integer page = pageIndex.get(target);
                if (page == null) {
                    page = firstPageOf(target);
                }
                if (page != null && page < output.pageCount()) {
                    output.outline(e.title(), e.level(), pageKey(page));
                }
            } else {
                String part = target.substring(0, hash);
                Integer page = pageIndex.get(part);
                if (page != null && output.destinationPage(target) < 0 && page < output.pageCount()) {
                    output.outline(e.title(), e.level(), pageKey(page));
                } else {
                    output.outline(e.title(), e.level(), target);
                }
            }
        }
    }

    private Integer firstPageOf(String docPart) {
        if (firstPages == null) {
            firstPages = new HashMap<>();
            List<XpsPackage.Page> pages = pkg.pages();
            for (int i = 0; i < pages.size(); i++) {
                firstPages.putIfAbsent(pkg.documents().get(pages.get(i).document()).toLowerCase(Locale.ROOT), i);
            }
        }
        return firstPages.get(docPart);
    }

    private static double size(String attr, double hint, double otherwise) {
        double v = XpsXml.number(attr, hint > 0 ? hint : otherwise);
        if (!(v >= 1)) {
            v = hint >= 1 ? hint : otherwise;
        }
        return Math.min(v, MAX_PAGE_UNITS);
    }

    private static void boxes(PDPage page, XpsElement root, double w, double h) {
        float hp = (float) (h * POINTS);
        double[] bleed = XpsXml.rect(XpsXml.attr(root, "BleedBox"));
        double[] content = XpsXml.rect(XpsXml.attr(root, "ContentBox"));
        PDRectangle trim = new PDRectangle(0, 0, (float) (w * POINTS), hp);
        if (bleed != null && bleed[2] > 0 && bleed[3] > 0) {
            double x0 = Math.max(-MAX_PAGE_UNITS, bleed[0]);
            double y0 = Math.max(-MAX_PAGE_UNITS, bleed[1]);
            double x1 = Math.min(2 * MAX_PAGE_UNITS, bleed[0] + bleed[2]);
            double y1 = Math.min(2 * MAX_PAGE_UNITS, bleed[1] + bleed[3]);
            PDRectangle b = box(x0, y0, x1, y1, hp);
            page.setBleedBox(b);
            float mx0 = Math.min(0, b.getLowerLeftX());
            float my0 = Math.min(0, b.getLowerLeftY());
            float mx1 = Math.max(trim.getUpperRightX(), b.getUpperRightX());
            float my1 = Math.max(hp, b.getUpperRightY());
            if (mx0 < 0 || my0 < 0 || mx1 > trim.getUpperRightX() || my1 > hp) {
                page.setMediaBox(new PDRectangle(mx0, my0, mx1 - mx0, my1 - my0));
                page.setCropBox(trim);
            }
            page.setTrimBox(trim);
        }
        if (content != null && content[2] > 0 && content[3] > 0) {
            page.setArtBox(box(content[0], content[1], content[0] + content[2], content[1] + content[3], hp));
        }
    }

    private static PDRectangle box(double x0, double y0, double x1, double y1, float hp) {
        float lx = (float) (x0 * POINTS);
        float ux = (float) (x1 * POINTS);
        float ly = (float) (hp - y1 * POINTS);
        float uy = (float) (hp - y0 * POINTS);
        return new PDRectangle(lx, ly, ux - lx, uy - ly);
    }

    private static AffineTransform inverseOrIdentity(AffineTransform t) {
        try {
            return t.createInverse();
        } catch (NoninvertibleTransformException e) {
            return new AffineTransform();
        }
    }

    private Scope resources(XpsElement holder, Scope parent, String part) throws IOException {
        XpsElement prop = child(holder, holder.getLocalName() + ".Resources");
        if (prop == null) {
            return parent;
        }
        XpsElement dict = XpsXml.child(prop, "ResourceDictionary");
        if (dict == null) {
            return parent;
        }
        Map<String, XpsElement> map = new HashMap<>();
        String source = XpsXml.attr(dict, "Source");
        if (source != null) {
            String target = OfficeZip.resolve(part, source);
            if (target != null) {
                map.putAll(remote(target));
            }
        }
        for (XpsElement e : XpsXml.elements(dict)) {
            String key = XpsXml.key(e);
            if (key != null) {
                map.put(key, e);
            }
        }
        return map.isEmpty() ? parent : new Scope(parent, map);
    }

    private Map<String, XpsElement> remote(String part) throws IOException {
        String key = part.toLowerCase(Locale.ROOT);
        Map<String, XpsElement> known = dictionaries.get(key);
        if (known != null) {
            for (XpsElement e : known.values()) {
                owners.putIfAbsent(e.root(), part);
                break;
            }
            return known;
        }
        Map<String, XpsElement> map = new HashMap<>();
        dictionaries.put(key, map);
        if (dictionaries.size() > 10_000 || !pkg.exists(part)) {
            return map;
        }
        try {
            XpsElement dom = pkg.xml(part);
            owners.put(dom, part);
            XpsElement root = dom;
            for (XpsElement e : XpsXml.elements(root)) {
                String k = XpsXml.key(e);
                if (k != null) {
                    map.put(k, e);
                }
            }
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            warnings.add("A resource dictionary of the XPS document is damaged; what it holds was left out");
        }
        return map;
    }

    private void children(XpsElement parent, Scope scope, Ctx ctx) throws IOException {
        XpsXml.each(parent, e -> element(e, scope, ctx));
    }

    private void element(XpsElement e, Scope scope, Ctx ctx) throws IOException {
        String n = e.getLocalName();
        if (n == null || n.indexOf('.') >= 0) {
            return;
        }
        if (++elements > MAX_PAGE_ELEMENTS) {
            throw new Budget();
        }
        if ((elements & 1023) == 0) {
            OfficeZip.checkNotInterrupted();
            Admission.checkpoint();
        }
        switch (n) {
            case "Canvas" -> canvas(e, scope, ctx);
            case "Path" -> path(e, scope, ctx);
            case "Glyphs" -> glyphs(e, scope, ctx);
            default -> {}
        }
    }

    private static double opacity(XpsElement e) {
        double o = XpsXml.number(XpsXml.attr(e, "Opacity"), 1);
        return Math.max(0, Math.min(1, o));
    }

    private final Map<XpsElement, Map<String, XpsElement>> props = new IdentityHashMap<>();

    private XpsElement child(XpsElement e, String name) {
        String n = e.getLocalName();
        if (!"Canvas".equals(n) && !"FixedPage".equals(n)) {
            return XpsXml.child(e, name);
        }
        return props.computeIfAbsent(e, XpsXml::properties).get(name);
    }

    private void fill(XpsOps ops, XpsColor c) throws IOException {
        COSArray space = profiles.space(c);
        if (space == null) {
            ops.fillColor(c.rgb());
        } else {
            ops.fillColor(ops.resource(COSName.COLORSPACE, "Cs", space), c.components());
        }
    }

    private void stroke(XpsOps ops, XpsColor c) throws IOException {
        COSArray space = profiles.space(c);
        if (space == null) {
            ops.strokeColor(c.rgb());
        } else {
            ops.strokeColor(ops.resource(COSName.COLORSPACE, "Cs", space), c.components());
        }
    }

    private final Map<String, Map<String, String>> propertyNames = new HashMap<>();

    private XpsElement property(XpsElement e, String name) {
        String owner = e.getLocalName();
        Map<String, String> known = propertyNames.get(owner);
        if (known == null) {
            known = new HashMap<>();
            propertyNames.put(owner, known);
        }
        String full = known.get(name);
        if (full == null) {
            full = owner + "." + name;
            known.put(name, full);
        }
        XpsElement holder = child(e, full);
        return holder == null ? null : XpsXml.firstElement(holder);
    }

    private AffineTransform transform(XpsElement e, String name, Scope scope) {
        XpsElement own = property(e, name);
        String v = own == null ? XpsXml.attr(e, name) : null;
        XpsElement t = null;
        if (v != null) {
            String key = XpsXml.staticResource(v);
            if (key == null) {
                return XpsXml.matrix(v);
            }
            t = scope == null ? null : scope.find(key);
        } else {
            t = own;
        }
        if (t == null || !"MatrixTransform".equals(t.getLocalName())) {
            return null;
        }
        return XpsXml.matrix(XpsXml.attr(t, "Matrix"));
    }

    private final XpsGeometry.Budget spend = n -> {
        segments += n;
        if (segments > MAX_PAGE_SEGMENTS) {
            throw new Budget();
        }
    };

    private XpsGeometry geometry(XpsElement e, String name, Scope scope) throws IOException {
        XpsElement own = property(e, name);
        String v = own == null ? XpsXml.attr(e, name) : null;
        XpsElement g;
        if (v != null) {
            String key = XpsXml.staticResource(v);
            if (key == null) {
                try {
                    return XpsGeometry.abbreviated(v, spend);
                } catch (Budget b) {
                    throw b;
                } catch (XpsGeometry.TooLong t) {
                    warnings.add(LONG_PATH);
                    return null;
                } catch (IOException x) {
                    return null;
                }
            }
            Scope where = scope == null ? null : scope.owner(key);
            g = where == null ? null : where.find(key);
            scope = where;
        } else {
            g = own;
        }
        if (g == null || !"PathGeometry".equals(g.getLocalName())) {
            return null;
        }
        try {
            return XpsGeometry.pathGeometry(g, XpsXml.attr(g, "Figures"), XpsXml.attr(g, "FillRule"),
                    transform(g, "Transform", scope), spend);
        } catch (Budget b) {
            throw b;
        } catch (XpsGeometry.TooLong t) {
            warnings.add(LONG_PATH);
            return null;
        } catch (IOException x) {
            return null;
        }
    }

    record Brush(String kind, XpsColor color, XpsElement element, double opacity, Scope scope) {}

    private final Map<String, XpsColor> colors = new HashMap<>();

    private XpsColor color(String v, XpsElement e) {
        if (v == null || !v.startsWith("#")) {
            return XpsColor.parse(v, owner(e), profiles);
        }
        XpsColor known = colors.get(v);
        if (known == null) {
            known = XpsColor.parse(v, owner(e), profiles);
            if (known != null && colors.size() < 4096) {
                colors.put(v, known);
            }
        }
        return known;
    }

    private Brush brush(XpsElement e, String name, Scope scope) {
        XpsElement own = property(e, name);
        String v = own == null ? XpsXml.attr(e, name) : null;
        XpsElement b;
        if (v != null) {
            String key = XpsXml.staticResource(v);
            if (key == null) {
                XpsColor c = color(v, e);
                return c == null ? null : new Brush("SolidColorBrush", c, null, 1, scope);
            }
            Scope where = scope == null ? null : scope.owner(key);
            b = where == null ? null : where.find(key);
            scope = where;
        } else {
            b = own;
        }
        if (b == null) {
            return null;
        }
        double op = opacity(b);
        return switch (b.getLocalName()) {
            case "SolidColorBrush" -> {
                XpsColor c = color(XpsXml.attr(b, "Color"), b);
                yield c == null ? null : new Brush("SolidColorBrush", c, b, op, scope);
            }
            case "LinearGradientBrush", "RadialGradientBrush", "ImageBrush", "VisualBrush" ->
                new Brush(b.getLocalName(), null, b, op, scope);
            default -> null;
        };
    }

    private boolean clip(XpsElement e, Scope scope, Ctx ctx) throws IOException {
        XpsGeometry g = geometry(e, "Clip", scope);
        if (g == null) {
            return true;
        }
        if (!ctx.ops.path(g.fill, null)) {
            return false;
        }
        ctx.ops.op(g.fill.getWindingRule() == Path2D.WIND_EVEN_ODD ? "W* n\n" : "W n\n");
        return true;
    }

    private void canvas(XpsElement e, Scope scope, Ctx ctx) throws IOException {
        Scope local = resources(e, scope, ctx.part);
        double opacity = opacity(e);
        if (opacity <= 0) {
            return;
        }
        XpsOps ops = ctx.ops;
        ops.q();
        try {
            ops.cm(transform(e, "RenderTransform", local));
            if (!clip(e, local, ctx)) {
                return;
            }
            Brush mask = brush(e, "OpacityMask", local);
            boolean masked = false;
            if (mask != null) {
                if (mask.kind().equals("SolidColorBrush")) {
                    opacity *= mask.color().alpha() * mask.opacity();
                    if (opacity <= 0) {
                        return;
                    }
                } else {
                    mask(mask, localBounds(ops), ctx);
                    masked = true;
                }
            }
            String uri = XpsXml.attr(e, "FixedPage.NavigateUri");
            String name = XpsXml.attr(e, "Name");
            Rectangle2D acc = null;
            if ((uri != null || name != null) && ctx.toPage != null) {
                acc = new Rectangle2D.Double(Double.NaN, 0, 0, 0);
                ctx.accumulators.add(acc);
            }
            try {
                if (opacity < 1 || masked) {
                    group(opacity, ctx, sub -> children(e, local, sub));
                } else {
                    children(e, local, ctx);
                }
            } finally {
                if (acc != null) {
                    ctx.accumulators.remove(acc);
                }
            }
            if (acc != null && !Double.isNaN(acc.getX())) {
                navigate(uri, name, acc, ctx);
            }
        } finally {
            ops.Q();
        }
    }

    interface Drawing {
        void draw(Ctx ctx) throws IOException;
    }

    private Rectangle2D localBounds(XpsOps ops) {
        AffineTransform inv = inverseOrIdentity(ops.ctm);
        Rectangle2D r = inv.createTransformedShape(streamBounds(ops)).getBounds2D();
        return clampRect(r);
    }

    private static Rectangle2D streamBounds(XpsOps ops) {
        return ops.bounds;
    }

    private static Rectangle2D clampRect(Rectangle2D r) {
        double lim = 1e7;
        double x0 = Math.max(-lim, r.getMinX());
        double y0 = Math.max(-lim, r.getMinY());
        double x1 = Math.min(lim, r.getMaxX());
        double y1 = Math.min(lim, r.getMaxY());
        return new Rectangle2D.Double(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
    }

    private void group(double opacity, Ctx ctx, Drawing drawing) throws IOException {
        Rectangle2D bbox = localBounds(ctx.ops);
        COSStream form = form(bbox, true, null);
        PDResources res = new PDResources();
        form.setItem(COSName.RESOURCES, res.getCOSObject());
        XpsOps sub = new XpsOps(XpsOps.flate(form), res, bbox);
        AffineTransform toPage = ctx.toPage == null ? null : ctx.pageTransform();
        Ctx inner = new Ctx(sub, ctx.canvas, toPage, ctx.depth, ctx.part, ctx.accumulators, ctx.names);
        Budget stopped = null;
        try {
            drawing.draw(inner);
        } catch (Budget b) {
            stopped = b;
        } finally {
            sub.close();
        }
        alpha(ctx.ops, opacity, opacity);
        ctx.ops.name(ctx.ops.resource(COSName.XOBJECT, "Fm", form)).op("Do\n");
        if (stopped != null) {
            throw stopped;
        }
    }

    private COSStream form(Rectangle2D bbox, boolean groupDict, COSName colorSpace) {
        COSStream form = document.getDocument().createCOSStream();
        form.setItem(COSName.TYPE, COSName.XOBJECT);
        form.setItem(COSName.SUBTYPE, COSName.FORM);
        form.setItem(COSName.BBOX, rectArray(bbox));
        if (groupDict) {
            COSDictionary g = new COSDictionary();
            g.setItem(COSName.TYPE, COSName.GROUP);
            g.setItem(COSName.S, COSName.TRANSPARENCY);
            if (colorSpace != null) {
                g.setItem(COSName.CS, colorSpace);
            }
            form.setItem(COSName.GROUP, g);
        }
        return form;
    }

    private static COSArray rectArray(Rectangle2D r) {
        COSArray a = new COSArray();
        a.add(new COSFloat((float) r.getMinX()));
        a.add(new COSFloat((float) r.getMinY()));
        a.add(new COSFloat((float) r.getMaxX()));
        a.add(new COSFloat((float) r.getMaxY()));
        return a;
    }

    private void alpha(XpsOps ops, double fill, double stroke) throws IOException {
        float f = (float) Math.max(0, Math.min(1, fill));
        float s = (float) Math.max(0, Math.min(1, stroke));
        long key = (long) Float.floatToIntBits(f) << 32 | (Float.floatToIntBits(s) & 0xFFFFFFFFL);
        COSDictionary gs = alphas.computeIfAbsent(key, k -> {
            COSDictionary d = new COSDictionary();
            d.setItem(COSName.TYPE, COSName.EXT_G_STATE);
            d.setItem(COSName.CA_NS, new COSFloat(f));
            d.setItem(COSName.CA, new COSFloat(s));
            return d;
        });
        ops.name(ops.resource(COSName.EXT_G_STATE, "Gs", gs)).op("gs\n");
    }

    private void mask(Brush mask, Rectangle2D bounds, Ctx ctx) throws IOException {
        COSStream g = form(bounds, true, null);
        PDResources res = new PDResources();
        g.setItem(COSName.RESOURCES, res.getCOSObject());
        XpsOps sub = new XpsOps(XpsOps.flate(g), res, bounds);
        Ctx inner = new Ctx(sub, null, null, ctx.depth, ctx.part, new ArrayList<>(), new HashMap<>());
        try {
            paint(mask, new Rectangle2D.Double(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight()),
                    1, inner, false);
        } finally {
            sub.close();
        }
        COSDictionary smask = new COSDictionary();
        smask.setItem(COSName.TYPE, COSName.MASK);
        smask.setItem(COSName.S, COSName.ALPHA);
        smask.setItem(COSName.G, g);
        COSDictionary gs = new COSDictionary();
        gs.setItem(COSName.TYPE, COSName.EXT_G_STATE);
        gs.setItem(COSName.SMASK, smask);
        ctx.ops.name(ctx.ops.resource(COSName.EXT_G_STATE, "Gs", gs)).op("gs\n");
    }

    private void path(XpsElement e, Scope scope, Ctx ctx) throws IOException {
        double opacity = opacity(e);
        if (opacity <= 0) {
            return;
        }
        Brush fill = brush(e, "Fill", scope);
        Brush stroke = brush(e, "Stroke", scope);
        String uri = XpsXml.attr(e, "FixedPage.NavigateUri");
        String name = XpsXml.attr(e, "Name");
        if (fill == null && stroke == null && uri == null && name == null) {
            return;
        }
        XpsGeometry g = geometry(e, "Data", scope);
        if (g == null || g.isEmpty()) {
            return;
        }
        XpsOps ops = ctx.ops;
        ops.q();
        try {
            ops.cm(transform(e, "RenderTransform", scope));
            if (!clip(e, scope, ctx)) {
                return;
            }
            Brush mask = brush(e, "OpacityMask", scope);
            boolean masked = false;
            if (mask != null) {
                if (mask.kind().equals("SolidColorBrush")) {
                    opacity *= mask.color().alpha() * mask.opacity();
                    if (opacity <= 0) {
                        return;
                    }
                } else {
                    masked = true;
                    mask(mask, clampRect(painted(g, e, stroke != null, 1)), ctx);
                }
            }
            boolean evenOdd = g.fill.getWindingRule() == Path2D.WIND_EVEN_ODD;
            if (fill != null && fill.kind().equals("SolidColorBrush") && stroke != null
                    && stroke.kind().equals("SolidColorBrush") && !g.strokeDiffers() && !ownCaps(e)
                    && clippedMiters(e, g.fill) == null) {
                double fa = fill.color().alpha() * fill.opacity() * opacity;
                double sa = stroke.color().alpha() * stroke.opacity() * opacity;
                if (fa < 1 || sa < 1) {
                    alpha(ops, fa, sa);
                }
                fill(ops, fill.color());
                stroke(ops, stroke.color());
                boolean stroking = sa > 0 && strokeStyle(e, ops) != null;
                boolean filling = fa > 0;
                if ((stroking || filling) && ops.path(g.fill, null)) {
                    ops.op(stroking && filling ? (evenOdd ? "B*\n" : "B\n") : stroking ? "S\n"
                            : evenOdd ? "f*\n" : "f\n");
                }
            } else if (fill != null && stroke != null && opacity < 1 || masked) {
                group(opacity, ctx, sub -> {
                    if (fill != null) {
                        paint(fill, g.fill, 1, sub, false);
                    }
                    if (stroke != null) {
                        stroke(e, stroke, g.strokePath(), 1, sub);
                    }
                });
            } else {
                if (fill != null) {
                    paint(fill, g.fill, opacity, ctx, false);
                }
                if (stroke != null) {
                    stroke(e, stroke, g.strokePath(), opacity, ctx);
                }
            }
            if ((uri != null || name != null || !ctx.accumulators.isEmpty()) && ctx.toPage != null) {
                Rectangle2D b = painted(g, e, stroke != null, 0);
                Rectangle2D onPage = ctx.pageTransform().createTransformedShape(b).getBounds2D();
                accumulate(ctx, onPage);
                navigate(uri, name, onPage, ctx);
            }
        } finally {
            ops.Q();
        }
    }

    private static Rectangle2D painted(XpsGeometry g, XpsElement e, boolean stroked, double pad) {
        Rectangle2D b = g.fill.getCurrentPoint() == null ? null : g.fill.getBounds2D();
        if (stroked && g.strokeDiffers() && g.stroke.getCurrentPoint() != null) {
            Rectangle2D s = g.stroke.getBounds2D();
            if (b == null) {
                b = s;
            } else {
                b.add(s);
            }
        }
        if (b == null) {
            b = new Rectangle2D.Double();
        }
        if (stroked) {
            double t = pad > 0
                    ? thickness(e) * Math.max(1, Math.min(1000, XpsXml.number(XpsXml.attr(e, "StrokeMiterLimit"), 10))) + pad
                    : thickness(e) / 2;
            b = new Rectangle2D.Double(b.getX() - t, b.getY() - t, b.getWidth() + 2 * t, b.getHeight() + 2 * t);
        }
        return b;
    }

    private static void accumulate(Ctx ctx, Rectangle2D r) {
        for (Rectangle2D acc : ctx.accumulators) {
            if (Double.isNaN(acc.getX())) {
                acc.setRect(r);
            } else {
                acc.add(r);
            }
        }
    }

    private void navigate(String uri, String name, Rectangle2D onPage, Ctx ctx) throws IOException {
        if (ctx.canvas == null || onPage.isEmpty() && onPage.getWidth() <= 0 && onPage.getHeight() <= 0) {
            return;
        }
        if (name != null && !ctx.names.containsKey(name) && ctx.names.size() < 100_000) {
            ctx.names.put(name, new double[] {Math.max(0, onPage.getX()), Math.max(0, onPage.getY())});
        }
        if (uri == null || pageLinks >= MAX_LINKS) {
            return;
        }
        pageLinks++;
        float x = (float) (onPage.getX() * POINTS);
        float y = (float) (onPage.getY() * POINTS);
        float w = (float) (onPage.getWidth() * POINTS);
        float h = (float) (onPage.getHeight() * POINTS);
        if (!(w > 0 && h > 0)) {
            return;
        }
        String u = uri.strip();
        if (u.matches("^[A-Za-z][A-Za-z0-9+.\\-]*:.*")) {
            ctx.canvas.link(x, y, w, h, u);
            return;
        }
        String key = XpsPackage.destination(ctx.part, u);
        if (key == null) {
            return;
        }
        key = pkg.known(key, currentDocument);
        int hash = key.indexOf('#');
        if (hash >= 0) {
            ctx.canvas.linkTo(x, y, w, h, key);
            return;
        }
        Integer page = pageIndex.get(key);
        if (page == null) {
            page = firstPageOf(key);
        }
        if (page != null) {
            ctx.canvas.linkTo(x, y, w, h, pageKey(page));
        }
    }

    private static double thickness(XpsElement e) {
        double t = XpsXml.number(XpsXml.attr(e, "StrokeThickness"), 1);
        return t >= 0 && t < 1e6 ? t : 1;
    }

    record Caps(int start, int end, double thickness) {
        boolean drawn() {
            return start != end || start == 3;
        }
    }

    private static int cap(String name) {
        return switch (name) {
            case "Round" -> 1;
            case "Square" -> 2;
            case "Triangle" -> 3;
            default -> 0;
        };
    }

    private static boolean dashed(XpsElement e, double t) {
        double[] dash = XpsXml.numbers(XpsXml.attr(e, "StrokeDashArray"));
        if (dash.length == 0 || dash.length > 512) {
            return false;
        }
        boolean anyPositive = false;
        double sum = 0;
        for (double d : dash) {
            if (d < 0) {
                return false;
            }
            if (d > 0) {
                anyPositive = true;
            }
            sum += d;
        }
        return anyPositive && sum * t >= 0.05;
    }

    private static boolean ownCaps(XpsElement e) {
        double t = thickness(e);
        if (t <= 0 || dashed(e, t)) {
            return false;
        }
        int start = cap(e.getAttribute("StrokeStartLineCap").strip());
        int end = cap(e.getAttribute("StrokeEndLineCap").strip());
        return start != end || start == 3;
    }

    private Caps strokeStyle(XpsElement e, XpsOps ops) throws IOException {
        double t = thickness(e);
        if (t <= 0) {
            return null;
        }
        ops.num(t).op("w\n");
        boolean dashed = dashed(e, t);
        if (dashed) {
            double[] dash = XpsXml.numbers(XpsXml.attr(e, "StrokeDashArray"));
            double[] use = dash.length % 2 == 1 ? doubled(dash) : dash;
            ops.op("[");
            for (int i = 0; i < use.length && i < 512; i++) {
                ops.num(use[i] * t);
            }
            ops.op("] ");
            ops.num(XpsXml.number(XpsXml.attr(e, "StrokeDashOffset"), 0) * t).op("d\n");
        }
        int start = cap(e.getAttribute("StrokeStartLineCap").strip());
        int end = cap(e.getAttribute("StrokeEndLineCap").strip());
        Caps caps = new Caps(start, end, t);
        int pdfCap;
        if (dashed) {
            String dashCap = e.getAttribute("StrokeDashCap").strip();
            pdfCap = dashCap.isEmpty() ? 0 : cap(dashCap);
            caps = null;
        } else if (caps.drawn()) {
            pdfCap = 0;
        } else {
            pdfCap = start;
            caps = null;
        }
        ops.integer(pdfCap == 3 ? 1 : pdfCap).op("J\n");
        String join = e.getAttribute("StrokeLineJoin").strip();
        ops.integer(switch (join) {
            case "Round" -> 1;
            case "Bevel" -> 2;
            default -> 0;
        }).op("j\n");
        ops.num(miterLimit(e)).op("M\n");
        return caps == null ? NO_CAPS : caps;
    }

    private static final Caps NO_CAPS = new Caps(0, 0, 0);

    private static double miterLimit(XpsElement e) {
        return Math.max(1, Math.min(1000, XpsXml.number(XpsXml.attr(e, "StrokeMiterLimit"), 10)));
    }

    static Path2D clippedMiters(XpsElement e, Shape shape) {
        double t = thickness(e);
        String join = e.getAttribute("StrokeLineJoin").strip();
        if (t <= 0 || !(join.isEmpty() || join.equals("Miter")) || dashed(e, t)) {
            return null;
        }
        return clippedMiters(shape, t / 2, miterLimit(e));
    }

    static Path2D clippedMiters(Shape shape, double h, double limit) {
        Path2D out = null;
        double[] c = new double[6];
        double sx = 0;
        double sy = 0;
        double px = 0;
        double py = 0;
        double ix = 0;
        double iy = 0;
        double fx = 0;
        double fy = 0;
        boolean in = false;
        boolean first = false;
        for (PathIterator it = shape.getPathIterator(null); !it.isDone(); it.next()) {
            int type = it.currentSegment(c);
            double x;
            double y;
            double ax;
            double ay;
            double bx;
            double by;
            switch (type) {
                case PathIterator.SEG_MOVETO -> {
                    sx = px = c[0];
                    sy = py = c[1];
                    in = false;
                    first = false;
                    continue;
                }
                case PathIterator.SEG_LINETO -> {
                    x = c[0];
                    y = c[1];
                    ax = bx = x - px;
                    ay = by = y - py;
                }
                case PathIterator.SEG_QUADTO -> {
                    x = c[2];
                    y = c[3];
                    ax = c[0] - px;
                    ay = c[1] - py;
                    if (Math.hypot(ax, ay) < 1e-9) {
                        ax = x - px;
                        ay = y - py;
                    }
                    bx = x - c[0];
                    by = y - c[1];
                    if (Math.hypot(bx, by) < 1e-9) {
                        bx = x - px;
                        by = y - py;
                    }
                }
                case PathIterator.SEG_CUBICTO -> {
                    x = c[4];
                    y = c[5];
                    ax = c[0] - px;
                    ay = c[1] - py;
                    if (Math.hypot(ax, ay) < 1e-9) {
                        ax = c[2] - px;
                        ay = c[3] - py;
                    }
                    if (Math.hypot(ax, ay) < 1e-9) {
                        ax = x - px;
                        ay = y - py;
                    }
                    bx = x - c[2];
                    by = y - c[3];
                    if (Math.hypot(bx, by) < 1e-9) {
                        bx = x - c[0];
                        by = y - c[1];
                    }
                    if (Math.hypot(bx, by) < 1e-9) {
                        bx = x - px;
                        by = y - py;
                    }
                }
                default -> {
                    x = sx;
                    y = sy;
                    ax = bx = x - px;
                    ay = by = y - py;
                }
            }
            if (Math.hypot(ax, ay) >= 1e-9 && Math.hypot(bx, by) >= 1e-9) {
                if (in) {
                    out = miter(out, px, py, ix, iy, ax, ay, h, limit);
                }
                if (!first) {
                    fx = ax;
                    fy = ay;
                    first = true;
                }
                ix = bx;
                iy = by;
                in = true;
                px = x;
                py = y;
            }
            if (type == PathIterator.SEG_CLOSE) {
                if (in && first) {
                    out = miter(out, sx, sy, ix, iy, fx, fy, h, limit);
                }
                px = sx;
                py = sy;
                in = false;
                first = false;
            }
        }
        return out;
    }

    private static Path2D miter(Path2D out, double px, double py, double x1, double y1, double x2, double y2, double h,
            double limit) {
        double l1 = Math.hypot(x1, y1);
        double l2 = Math.hypot(x2, y2);
        x1 /= l1;
        y1 /= l1;
        x2 /= l2;
        y2 /= l2;
        double cosHalf = Math.sqrt(Math.max(0, (1 + x1 * x2 + y1 * y2) / 2));
        if (cosHalf * limit >= 1) {
            return out;
        }
        double sign = x1 * y2 - y1 * x2 > 0 ? -1 : 1;
        double n1x = -y1 * sign;
        double n1y = x1 * sign;
        double n2x = -y2 * sign;
        double n2y = x2 * sign;
        double bx = n1x + n2x;
        double by = n1y + n2y;
        double bl = Math.hypot(bx, by);
        if (bl < 1e-9) {
            bx = x1;
            by = y1;
        } else {
            bx /= bl;
            by /= bl;
        }
        double k = x1 * bx + y1 * by;
        double s = (limit * h - h * cosHalf) / k;
        if (!(k > 1e-9) || !(s > 0)) {
            return out;
        }
        double ax = px + h * n1x;
        double ay = py + h * n1y;
        double cx = px + h * n2x;
        double cy = py + h * n2y;
        double[] q = {ax, ay, ax + s * x1, ay + s * y1, cx - s * x2, cy - s * y2, cx, cy};
        double area = 0;
        for (int i = 0; i < 8; i += 2) {
            int j = (i + 2) % 8;
            area += q[i] * q[j + 1] - q[j] * q[i + 1];
        }
        if (out == null) {
            out = new Path2D.Double(Path2D.WIND_NON_ZERO);
        }
        if (area >= 0) {
            out.moveTo(q[0], q[1]);
            out.lineTo(q[2], q[3]);
            out.lineTo(q[4], q[5]);
            out.lineTo(q[6], q[7]);
        } else {
            out.moveTo(q[6], q[7]);
            out.lineTo(q[4], q[5]);
            out.lineTo(q[2], q[3]);
            out.lineTo(q[0], q[1]);
        }
        out.closePath();
        return out;
    }

    static Path2D capShapes(Shape shape, Caps caps) {
        Path2D out = new Path2D.Double(Path2D.WIND_NON_ZERO);
        double h = caps.thickness() / 2;
        double[] c = new double[6];
        double sx = 0;
        double sy = 0;
        double px = 0;
        double py = 0;
        double[] first = null;
        double[] last = null;
        boolean open = false;
        for (PathIterator it = shape.getPathIterator(null); !it.isDone(); it.next()) {
            int type = it.currentSegment(c);
            switch (type) {
                case PathIterator.SEG_MOVETO -> {
                    if (open) {
                        ends(out, sx, sy, first, px, py, last, caps, h);
                    }
                    sx = c[0];
                    sy = c[1];
                    px = sx;
                    py = sy;
                    first = null;
                    last = null;
                    open = false;
                }
                case PathIterator.SEG_CLOSE -> {
                    open = false;
                    px = sx;
                    py = sy;
                }
                default -> {
                    int n = type == PathIterator.SEG_LINETO ? 1 : type == PathIterator.SEG_QUADTO ? 2 : 3;
                    double[] pts = {px, py, c[0], c[1], c[2], c[3], c[4], c[5]};
                    if (first == null) {
                        for (int i = 1; i <= n && first == null; i++) {
                            first = unit(pts[2 * i] - px, pts[2 * i + 1] - py);
                        }
                    }
                    double ex = pts[2 * n];
                    double ey = pts[2 * n + 1];
                    double[] d = null;
                    for (int i = n - 1; i >= 0 && d == null; i--) {
                        d = unit(ex - pts[2 * i], ey - pts[2 * i + 1]);
                    }
                    if (d != null) {
                        last = d;
                    }
                    px = ex;
                    py = ey;
                    open = true;
                }
            }
        }
        if (open) {
            ends(out, sx, sy, first, px, py, last, caps, h);
        }
        return out;
    }

    private static double[] unit(double dx, double dy) {
        double len = Math.hypot(dx, dy);
        return len > 1e-12 && Double.isFinite(len) ? new double[] {dx / len, dy / len} : null;
    }

    private static void ends(Path2D out, double sx, double sy, double[] first, double ex, double ey, double[] last,
            Caps caps, double h) {
        if (first == null) {
            first = new double[] {1, 0};
            last = first;
        }
        capShape(out, sx, sy, -first[0], -first[1], caps.start(), h);
        capShape(out, ex, ey, last[0], last[1], caps.end(), h);
    }

    private static void capShape(Path2D out, double x, double y, double dx, double dy, int cap, double h) {
        double nx = -dy * h;
        double ny = dx * h;
        double fx = dx * h;
        double fy = dy * h;
        switch (cap) {
            case 1 -> {
                double k = 0.5522847498;
                out.moveTo(x + nx, y + ny);
                out.curveTo(x + nx + fx * k, y + ny + fy * k, x + fx + nx * k, y + fy + ny * k, x + fx, y + fy);
                out.curveTo(x + fx - nx * k, y + fy - ny * k, x - nx + fx * k, y - ny + fy * k, x - nx, y - ny);
                out.closePath();
            }
            case 2 -> {
                out.moveTo(x + nx, y + ny);
                out.lineTo(x + nx + fx, y + ny + fy);
                out.lineTo(x - nx + fx, y - ny + fy);
                out.lineTo(x - nx, y - ny);
                out.closePath();
            }
            case 3 -> {
                out.moveTo(x + nx, y + ny);
                out.lineTo(x + fx, y + fy);
                out.lineTo(x - nx, y - ny);
                out.closePath();
            }
            default -> {}
        }
    }

    private static double[] doubled(double[] d) {
        double[] out = new double[d.length * 2];
        System.arraycopy(d, 0, out, 0, d.length);
        System.arraycopy(d, 0, out, d.length, d.length);
        return out;
    }

    private void stroke(XpsElement e, Brush brush, Shape shape, double opacity, Ctx ctx) throws IOException {
        XpsOps ops = ctx.ops;
        if (brush.kind().equals("SolidColorBrush")) {
            double a = brush.color().alpha() * brush.opacity() * opacity;
            if (a <= 0) {
                return;
            }
            ops.q();
            if (a < 1) {
                alpha(ops, a, a);
            }
            stroke(ops, brush.color());
            Caps caps = strokeStyle(e, ops);
            if (caps != null && ops.path(shape, null)) {
                ops.op("S\n");
                if (caps.drawn()) {
                    fill(ops, brush.color());
                    if (ops.path(capShapes(shape, caps), null)) {
                        ops.op("f\n");
                    }
                }
                Path2D miters = clippedMiters(e, shape);
                if (miters != null) {
                    fill(ops, brush.color());
                    if (ops.path(miters, null)) {
                        ops.op("f\n");
                    }
                }
            }
            ops.Q();
            return;
        }
        double t = thickness(e);
        if (t <= 0) {
            return;
        }
        Rectangle2D b = shape.getBounds2D();
        double pad = t * Math.max(1, Math.min(1000, XpsXml.number(XpsXml.attr(e, "StrokeMiterLimit"), 10))) + 1;
        Rectangle2D area = clampRect(new Rectangle2D.Double(b.getX() - pad, b.getY() - pad, b.getWidth() + 2 * pad,
                b.getHeight() + 2 * pad));
        COSBase pattern;
        Shade shade = null;
        double a;
        if (brush.kind().equals("LinearGradientBrush") || brush.kind().equals("RadialGradientBrush")) {
            shade = shade(brush, area, opacity);
            if (shade == null) {
                return;
            }
            if (shade.solid() != null) {
                stroke(e, shade.solid(), shape, opacity, ctx);
                return;
            }
            COSDictionary sp = new COSDictionary();
            sp.setItem(COSName.TYPE, COSName.PATTERN);
            sp.setInt(COSName.PATTERN_TYPE, 2);
            sp.setItem(COSName.SHADING, shade.shading());
            AffineTransform pm = new AffineTransform(ops.ctm);
            pm.concatenate(shade.transform());
            double[] m = new double[6];
            pm.getMatrix(m);
            sp.setItem(COSName.MATRIX, floats(m));
            pattern = sp;
            a = shade.alpha();
        } else {
            Tile tile = tileSource(brush, opacity, ctx);
            if (tile == null) {
                return;
            }
            pattern = pattern(tile, ops, tile.mode().isEmpty() || tile.mode().equals("None"));
            a = tile.alpha();
        }
        ops.q();
        try {
            if (shade != null && shade.alphaShading() != null) {
                softMask(ops, shade, area);
            } else if (a < 1) {
                alpha(ops, a, a);
            }
            COSName p = ops.resource(COSName.PATTERN, "P", pattern);
            ops.op("/Pattern CS ").name(p).op("SCN\n");
            ops.forgetColors();
            Caps caps = strokeStyle(e, ops);
            if (caps != null && ops.path(shape, null)) {
                ops.op("S\n");
                Path2D miters = clippedMiters(e, shape);
                if (caps.drawn() || miters != null) {
                    ops.op("/Pattern cs ").name(p).op("scn\n");
                }
                if (caps.drawn()) {
                    if (ops.path(capShapes(shape, caps), null)) {
                        ops.op("f\n");
                    }
                }
                if (miters != null && ops.path(miters, null)) {
                    ops.op("f\n");
                }
            }
        } finally {
            ops.Q();
        }
    }

    private void paint(Brush brush, Shape shape, double opacity, Ctx ctx, boolean clipped) throws IOException {
        XpsOps ops = ctx.ops;
        boolean evenOdd = shape instanceof Path2D p && p.getWindingRule() == Path2D.WIND_EVEN_ODD;
        switch (brush.kind()) {
            case "SolidColorBrush" -> {
                double a = brush.color().alpha() * brush.opacity() * opacity;
                if (a <= 0) {
                    return;
                }
                ops.q();
                if (a < 1) {
                    alpha(ops, a, 1);
                }
                fill(ops, brush.color());
                if (ops.path(shape, null)) {
                    ops.op(evenOdd ? "f*\n" : "f\n");
                }
                ops.Q();
            }
            case "LinearGradientBrush", "RadialGradientBrush" -> gradient(brush, shape, evenOdd, opacity, ctx, clipped);
            case "ImageBrush", "VisualBrush" -> tile(brush, shape, evenOdd, opacity, ctx, clipped);
            default -> {}
        }
    }

    private boolean clipTo(XpsOps ops, Shape shape, boolean evenOdd, boolean clipped) throws IOException {
        if (clipped) {
            return true;
        }
        if (!ops.path(shape, null)) {
            return false;
        }
        ops.op(evenOdd ? "W* n\n" : "W n\n");
        return true;
    }

    record Stop(double offset, float[] rgb, float alpha) {}

    private List<Stop> stops(XpsElement brush) {
        String n = brush.getLocalName();
        XpsElement holder = XpsXml.child(brush, n + ".GradientStops");
        List<Stop> raw = new ArrayList<>();
        if (holder != null) {
            for (XpsElement s : XpsXml.children(holder, "GradientStop")) {
                XpsColor c = XpsColor.parse(XpsXml.attr(s, "Color"), owner(s), profiles);
                if (c == null) {
                    continue;
                }
                double o = XpsXml.number(XpsXml.attr(s, "Offset"), 0);
                raw.add(new Stop(o, c.rgb(), c.alpha()));
                if (raw.size() >= 1024) {
                    break;
                }
            }
        }
        raw.sort((a, b) -> Double.compare(a.offset(), b.offset()));
        if (raw.isEmpty()) {
            return raw;
        }
        List<Stop> out = new ArrayList<>();
        Stop at0 = colorAt(raw, 0);
        Stop at1 = colorAt(raw, 1);
        out.add(at0);
        for (Stop s : raw) {
            if (s.offset() > 0 && s.offset() < 1) {
                out.add(s);
            }
        }
        out.add(at1);
        return out;
    }

    private static Stop colorAt(List<Stop> stops, double t) {
        Stop prev = null;
        for (Stop s : stops) {
            if (s.offset() >= t) {
                if (prev == null || s.offset() == prev.offset()) {
                    return new Stop(t, s.rgb(), s.alpha());
                }
                double f = (t - prev.offset()) / (s.offset() - prev.offset());
                return new Stop(t, lerp(prev.rgb(), s.rgb(), f), (float) (prev.alpha() + (s.alpha() - prev.alpha()) * f));
            }
            prev = s;
        }
        Stop last = stops.get(stops.size() - 1);
        return new Stop(t, last.rgb(), last.alpha());
    }

    private static float[] lerp(float[] a, float[] b, double f) {
        return new float[] {(float) (a[0] + (b[0] - a[0]) * f), (float) (a[1] + (b[1] - a[1]) * f),
            (float) (a[2] + (b[2] - a[2]) * f)};
    }

    private static List<Stop> linearLight(List<Stop> stops) {
        List<Stop> out = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            Stop s = stops.get(i);
            out.add(s);
            if (i + 1 < stops.size()) {
                Stop b = stops.get(i + 1);
                if (b.offset() <= s.offset()) {
                    continue;
                }
                for (int k = 1; k < 8; k++) {
                    double f = k / 8.0;
                    float[] c = new float[3];
                    for (int j = 0; j < 3; j++) {
                        double la = XpsColor.linear(s.rgb()[j]);
                        double lb = XpsColor.linear(b.rgb()[j]);
                        c[j] = XpsColor.gamma(la + (lb - la) * f);
                    }
                    out.add(new Stop(s.offset() + (b.offset() - s.offset()) * f, c,
                            (float) (s.alpha() + (b.alpha() - s.alpha()) * f)));
                }
            }
        }
        return out;
    }

    private static COSDictionary function(List<Stop> stops, boolean alpha) {
        List<COSDictionary> parts = new ArrayList<>();
        COSArray bounds = new COSArray();
        COSArray encode = new COSArray();
        for (int i = 0; i + 1 < stops.size(); i++) {
            Stop a = stops.get(i);
            Stop b = stops.get(i + 1);
            COSDictionary f = new COSDictionary();
            f.setInt(COSName.FUNCTION_TYPE, 2);
            f.setItem(COSName.DOMAIN, floats(0, 1));
            f.setItem(COSName.C0, alpha ? floats(a.alpha()) : floats(a.rgb()[0], a.rgb()[1], a.rgb()[2]));
            f.setItem(COSName.C1, alpha ? floats(b.alpha()) : floats(b.rgb()[0], b.rgb()[1], b.rgb()[2]));
            f.setInt(COSName.N, 1);
            parts.add(f);
            if (i > 0) {
                bounds.add(new COSFloat((float) a.offset()));
            }
            encode.add(COSInteger.get(0));
            encode.add(COSInteger.get(1));
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        COSDictionary st = new COSDictionary();
        st.setInt(COSName.FUNCTION_TYPE, 3);
        st.setItem(COSName.DOMAIN, floats(0, 1));
        COSArray fs = new COSArray();
        for (COSDictionary p : parts) {
            fs.add(p);
        }
        st.setItem(COSName.FUNCTIONS, fs);
        st.setItem(COSName.BOUNDS, bounds);
        st.setItem(COSName.ENCODE, encode);
        return st;
    }

    private static COSDictionary periodic(COSDictionary f, int k0, int k1, boolean reflect) {
        if (k0 == 0 && k1 == 1) {
            return f;
        }
        COSDictionary st = new COSDictionary();
        st.setInt(COSName.FUNCTION_TYPE, 3);
        st.setItem(COSName.DOMAIN, floats(k0, k1));
        COSArray fs = new COSArray();
        COSArray bounds = new COSArray();
        COSArray encode = new COSArray();
        for (int k = k0; k < k1; k++) {
            fs.add(f);
            if (k > k0) {
                bounds.add(COSInteger.get(k));
            }
            boolean flip = reflect && Math.floorMod(k, 2) == 1;
            encode.add(COSInteger.get(flip ? 1 : 0));
            encode.add(COSInteger.get(flip ? 0 : 1));
        }
        st.setItem(COSName.FUNCTIONS, fs);
        st.setItem(COSName.BOUNDS, bounds);
        st.setItem(COSName.ENCODE, encode);
        return st;
    }

    private static COSArray floats(double... v) {
        COSArray a = new COSArray();
        for (double d : v) {
            a.add(new COSFloat((float) d));
        }
        return a;
    }

    record Shade(COSDictionary shading, COSDictionary alphaShading, AffineTransform transform, double alpha,
            Brush solid) {}

    private Shade shade(Brush brush, Rectangle2D sb, double opacity) {
        XpsElement b = brush.element();
        List<Stop> stops = stops(b);
        if (stops.isEmpty()) {
            return null;
        }
        if ("ScRgbLinearInterpolation".equals(b.getAttribute("ColorInterpolationMode").strip())) {
            stops = linearLight(stops);
        }
        AffineTransform bt = transform(b, "Transform", brush.scope());
        if (bt == null) {
            bt = new AffineTransform();
        }
        Rectangle2D inBrush;
        try {
            inBrush = bt.createInverse().createTransformedShape(sb).getBounds2D();
        } catch (NoninvertibleTransformException e) {
            return null;
        }
        String spread = b.getAttribute("SpreadMethod").strip();
        boolean repeat = spread.equals("Repeat");
        boolean reflect = spread.equals("Reflect");
        COSDictionary shading = new COSDictionary();
        COSDictionary alphaShading = null;
        boolean linear = b.getLocalName().equals("LinearGradientBrush");
        AffineTransform extra = new AffineTransform();
        int k0 = 0;
        int k1 = 1;
        COSArray coords;
        if (linear) {
            double[] p0 = XpsXml.point(XpsXml.attr(b, "StartPoint"));
            double[] p1 = XpsXml.point(XpsXml.attr(b, "EndPoint"));
            if (p0 == null) {
                p0 = new double[] {0, 0};
            }
            if (p1 == null) {
                p1 = new double[] {1, 1};
            }
            double dx = p1[0] - p0[0];
            double dy = p1[1] - p0[1];
            double len2 = dx * dx + dy * dy;
            if (len2 < 1e-12) {
                return new Shade(null, null, null, 0, new Brush("SolidColorBrush", new XpsColor(
                        stops.get(stops.size() - 1).alpha(), stops.get(stops.size() - 1).rgb(), null, null), null,
                        brush.opacity(), brush.scope()));
            }
            if (repeat || reflect) {
                double tmin = Double.MAX_VALUE;
                double tmax = -Double.MAX_VALUE;
                for (double[] c : corners(inBrush)) {
                    double t = ((c[0] - p0[0]) * dx + (c[1] - p0[1]) * dy) / len2;
                    tmin = Math.min(tmin, t);
                    tmax = Math.max(tmax, t);
                }
                if (Math.abs(tmin) < 1e9 && Math.abs(tmax) < 1e9) {
                    k0 = (int) Math.floor(tmin);
                    k1 = (int) Math.min(Math.ceil(tmax), (double) k0 + MAX_PERIODS);
                    if (k1 <= k0) {
                        k1 = k0 + 1;
                    }
                }
            }
            coords = floats(p0[0] + k0 * dx, p0[1] + k0 * dy, p0[0] + k1 * dx, p0[1] + k1 * dy);
            shading.setInt(COSName.SHADING_TYPE, 2);
        } else {
            double[] c = XpsXml.point(XpsXml.attr(b, "Center"));
            double[] o = XpsXml.point(XpsXml.attr(b, "GradientOrigin"));
            double rx = XpsXml.number(XpsXml.attr(b, "RadiusX"), 0);
            double ry = XpsXml.number(XpsXml.attr(b, "RadiusY"), 0);
            if (c == null) {
                c = new double[] {0, 0};
            }
            if (o == null) {
                o = c;
            }
            if (!(rx > 0 && ry > 0)) {
                return new Shade(null, null, null, 0, new Brush("SolidColorBrush", new XpsColor(
                        stops.get(stops.size() - 1).alpha(), stops.get(stops.size() - 1).rgb(), null, null), null,
                        brush.opacity(), brush.scope()));
            }
            double k = ry / rx;
            extra.translate(c[0], c[1]);
            extra.scale(1, k);
            extra.translate(-c[0], -c[1]);
            double ox = o[0];
            double oy = c[1] + (o[1] - c[1]) / k;
            int periods = 1;
            if (repeat || reflect) {
                double max = 0;
                Rectangle2D norm;
                try {
                    norm = extra.createInverse().createTransformedShape(inBrush).getBounds2D();
                } catch (NoninvertibleTransformException x) {
                    norm = inBrush;
                }
                for (double[] p : corners(norm)) {
                    max = Math.max(max, Math.hypot(p[0] - ox, p[1] - oy));
                }
                double focal = Math.hypot(c[0] - ox, c[1] - oy);
                double grow = rx - focal;
                periods = grow > rx * 0.01 ? (int) Math.ceil(max / grow) + 1 : MAX_PERIODS;
                periods = Math.max(1, Math.min(MAX_PERIODS, periods));
            }
            k1 = periods;
            coords = floats(ox, oy, 0, ox + periods * (c[0] - ox), oy + periods * (c[1] - oy), periods * rx);
            shading.setInt(COSName.SHADING_TYPE, 3);
        }
        COSDictionary colorFn = periodic(function(stops, false), k0, k1, reflect);
        shading.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
        shading.setItem(COSName.COORDS, coords);
        shading.setItem(COSName.DOMAIN, floats(k0, k1));
        shading.setItem(COSName.FUNCTION, colorFn);
        COSArray extend = new COSArray();
        extend.add(COSBoolean.TRUE);
        extend.add(COSBoolean.TRUE);
        shading.setItem(COSName.EXTEND, extend);
        boolean alphaVaries = false;
        float first = stops.get(0).alpha();
        for (Stop s : stops) {
            if (Math.abs(s.alpha() - first) > 1e-4) {
                alphaVaries = true;
                break;
            }
        }
        double a = brush.opacity() * opacity * (alphaVaries ? 1 : first);
        if (a <= 0) {
            return null;
        }
        if (alphaVaries) {
            List<Stop> scaled = new ArrayList<>();
            for (Stop s : stops) {
                scaled.add(new Stop(s.offset(), s.rgb(), (float) (s.alpha() * a)));
            }
            alphaShading = new COSDictionary(shading);
            alphaShading.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
            alphaShading.setItem(COSName.FUNCTION, periodic(function(scaled, true), k0, k1, reflect));
        }
        AffineTransform full = new AffineTransform(bt);
        full.concatenate(extra);
        return new Shade(shading, alphaShading, full, alphaVaries ? 1 : a, null);
    }

    private void softMask(XpsOps ops, Shade shade, Rectangle2D area) throws IOException {
        Rectangle2D bounds = clampRect(area);
        COSStream g = form(bounds, true, COSName.DEVICEGRAY);
        PDResources res = new PDResources();
        g.setItem(COSName.RESOURCES, res.getCOSObject());
        XpsOps sub = new XpsOps(XpsOps.flate(g), res, bounds);
        sub.q();
        sub.cm(shade.transform());
        sub.name(sub.resource(COSName.SHADING, "Sh", shade.alphaShading())).op("sh\n");
        sub.close();
        COSDictionary smask = new COSDictionary();
        smask.setItem(COSName.TYPE, COSName.MASK);
        smask.setItem(COSName.S, COSName.LUMINOSITY);
        smask.setItem(COSName.G, g);
        COSDictionary gs = new COSDictionary();
        gs.setItem(COSName.TYPE, COSName.EXT_G_STATE);
        gs.setItem(COSName.SMASK, smask);
        ops.name(ops.resource(COSName.EXT_G_STATE, "Gs", gs)).op("gs\n");
    }

    private void gradient(Brush brush, Shape shape, boolean evenOdd, double opacity, Ctx ctx, boolean clipped)
            throws IOException {
        Rectangle2D sb = shape.getBounds2D();
        Shade shade = shade(brush, sb, opacity);
        if (shade == null) {
            return;
        }
        if (shade.solid() != null) {
            paint(shade.solid(), shape, opacity, ctx, clipped);
            return;
        }
        XpsOps ops = ctx.ops;
        ops.q();
        try {
            if (!clipTo(ops, shape, evenOdd, clipped)) {
                return;
            }
            if (shade.alphaShading() != null) {
                softMask(ops, shade, sb);
            } else if (shade.alpha() < 1) {
                alpha(ops, shade.alpha(), shade.alpha());
            }
            ops.cm(shade.transform());
            ops.name(ops.resource(COSName.SHADING, "Sh", shade.shading())).op("sh\n");
        } finally {
            ops.Q();
        }
    }

    private static double[][] corners(Rectangle2D r) {
        return new double[][] {{r.getMinX(), r.getMinY()}, {r.getMaxX(), r.getMinY()}, {r.getMinX(), r.getMaxY()},
            {r.getMaxX(), r.getMaxY()}};
    }

    record Tile(COSStream content, AffineTransform placement, AffineTransform transform, double[] viewbox,
            double[] viewport, String mode, double alpha) {}

    private Tile tileSource(Brush brush, double opacity, Ctx ctx) throws IOException {
        XpsElement b = brush.element();
        double[] viewbox = XpsXml.rect(XpsXml.attr(b, "Viewbox"));
        double[] viewport = XpsXml.rect(XpsXml.attr(b, "Viewport"));
        if (viewbox == null || viewport == null || !(viewbox[2] > 0 && viewbox[3] > 0 && viewport[2] > 0
                && viewport[3] > 0)) {
            return null;
        }
        double a = brush.opacity() * opacity;
        if (a <= 0) {
            return null;
        }
        COSStream content;
        AffineTransform contentPlacement;
        if (b.getLocalName().equals("ImageBrush")) {
            String src = XpsImages.source(XpsXml.attr(b, "ImageSource"));
            String part = src == null ? null : OfficeZip.resolve(base(b, ctx), src);
            XpsImages.Image img = part == null ? null : images.image(part);
            if (img == null) {
                return null;
            }
            content = img.image().getCOSObject();
            contentPlacement = new AffineTransform(img.width(), 0, 0, -img.height(), 0, img.height());
        } else {
            if (ctx.depth >= MAX_VISUAL_DEPTH) {
                return null;
            }
            XpsElement visual;
            Scope visualScope = brush.scope();
            String v = XpsXml.attr(b, "Visual");
            if (v != null) {
                String key = XpsXml.staticResource(v);
                visualScope = key == null || brush.scope() == null ? null : brush.scope().owner(key);
                visual = visualScope == null ? null : visualScope.map().get(key);
            } else {
                visual = property(b, "Visual");
            }
            if (visual == null) {
                return null;
            }
            content = visual(visual, visualScope, viewbox, ctx);
            if (content == null) {
                return null;
            }
            contentPlacement = new AffineTransform();
        }
        AffineTransform bt = transform(b, "Transform", brush.scope());
        return new Tile(content, contentPlacement, bt == null ? new AffineTransform() : bt, viewbox, viewport,
                b.getAttribute("TileMode").strip(), a);
    }

    private COSStream pattern(Tile t, XpsOps ops, boolean single) throws IOException {
        double[] viewbox = t.viewbox();
        double[] viewport = t.viewport();
        String mode = t.mode();
        boolean flipX = !single && (mode.equals("FlipX") || mode.equals("FlipXY"));
        boolean flipY = !single && (mode.equals("FlipY") || mode.equals("FlipXY"));
        double cw = viewport[2] * (flipX ? 2 : 1);
        double ch = viewport[3] * (flipY ? 2 : 1);
        double sx = viewport[2] / viewbox[2];
        double sy = viewport[3] / viewbox[3];
        COSStream pattern = document.getDocument().createCOSStream();
        pattern.setItem(COSName.TYPE, COSName.PATTERN);
        pattern.setInt(COSName.PATTERN_TYPE, 1);
        pattern.setInt(COSName.PAINT_TYPE, 1);
        pattern.setInt(COSName.TILING_TYPE, 1);
        pattern.setItem(COSName.BBOX, rectArray(new Rectangle2D.Double(0, 0, cw, ch)));
        double step = Math.max(1e6, 1000 * Math.max(cw, ch));
        pattern.setItem(COSName.X_STEP, new COSFloat((float) (single ? step : cw)));
        pattern.setItem(COSName.Y_STEP, new COSFloat((float) (single ? step : ch)));
        AffineTransform pm = new AffineTransform(ops.ctm);
        pm.concatenate(t.transform());
        pm.translate(viewport[0], viewport[1]);
        double[] m = new double[6];
        pm.getMatrix(m);
        pattern.setItem(COSName.MATRIX, floats(m));
        PDResources res = new PDResources();
        pattern.setItem(COSName.RESOURCES, res.getCOSObject());
        XpsOps sub = new XpsOps(XpsOps.flate(pattern), res,
                new Rectangle2D.Double(0, 0, cw, ch));
        AffineTransform cell = new AffineTransform(sx, 0, 0, sy, -viewbox[0] * sx, -viewbox[1] * sy);
        for (int fy = 0; fy < (flipY ? 2 : 1); fy++) {
            for (int fx = 0; fx < (flipX ? 2 : 1); fx++) {
                sub.q();
                AffineTransform place = new AffineTransform();
                if (fx == 1) {
                    place.translate(2 * viewport[2], 0);
                    place.scale(-1, 1);
                }
                if (fy == 1) {
                    place.translate(0, 2 * viewport[3]);
                    place.scale(1, -1);
                }
                sub.cm(place);
                sub.rect(new Rectangle2D.Double(0, 0, viewport[2], viewport[3]));
                sub.op("W n\n");
                sub.cm(cell);
                sub.cm(t.placement());
                sub.name(sub.resource(COSName.XOBJECT, "X", t.content())).op("Do\n");
                sub.Q();
            }
        }
        sub.close();
        return pattern;
    }

    private void tile(Brush brush, Shape shape, boolean evenOdd, double opacity, Ctx ctx, boolean clipped)
            throws IOException {
        Tile t = tileSource(brush, opacity, ctx);
        if (t == null) {
            return;
        }
        double[] viewbox = t.viewbox();
        double[] viewport = t.viewport();
        double sx = viewport[2] / viewbox[2];
        double sy = viewport[3] / viewbox[3];
        XpsOps ops = ctx.ops;
        ops.q();
        try {
            if (t.alpha() < 1) {
                alpha(ops, t.alpha(), t.alpha());
            }
            if (t.mode().isEmpty() || t.mode().equals("None")) {
                if (!clipTo(ops, shape, evenOdd, clipped)) {
                    return;
                }
                ops.cm(t.transform());
                ops.rect(new Rectangle2D.Double(viewport[0], viewport[1], viewport[2], viewport[3]));
                ops.op("W n\n");
                ops.cm(new AffineTransform(sx, 0, 0, sy, viewport[0] - viewbox[0] * sx,
                        viewport[1] - viewbox[1] * sy));
                ops.cm(t.placement());
                ops.name(ops.resource(COSName.XOBJECT, "X", t.content())).op("Do\n");
                return;
            }
            COSName p = ops.resource(COSName.PATTERN, "P", pattern(t, ops, false));
            ops.op("/Pattern cs ").name(p).op("scn\n");
            ops.forgetColors();
            if (clipped) {
                ops.rect(clampRect(shape.getBounds2D()));
                ops.op("f\n");
            } else if (ops.path(shape, null)) {
                ops.op(evenOdd ? "f*\n" : "f\n");
            }
        } finally {
            ops.Q();
        }
    }

    private COSStream visual(XpsElement visual, Scope scope, double[] viewbox, Ctx ctx) throws IOException {
        VisualKey key = new VisualKey(visual, viewbox[0], viewbox[1], viewbox[2], viewbox[3]);
        COSStream known = visuals.get(key);
        if (known != null) {
            return known;
        }
        if (visuals.size() > 10_000 || drawing.contains(visual)) {
            return null;
        }
        Rectangle2D bbox = new Rectangle2D.Double(viewbox[0], viewbox[1], viewbox[2], viewbox[3]);
        COSStream form = form(bbox, false, null);
        PDResources res = new PDResources();
        form.setItem(COSName.RESOURCES, res.getCOSObject());
        XpsOps sub = new XpsOps(XpsOps.flate(form), res, bbox);
        Ctx inner = new Ctx(sub, null, null, ctx.depth + 1, ctx.part, new ArrayList<>(), new HashMap<>());
        drawing.add(visual);
        try {
            element(visual, scope, inner);
        } finally {
            drawing.remove(visual);
            sub.close();
        }
        visuals.put(key, form);
        return form;
    }

    private record Placed(int gid, double x, double y, double advance, String text) {}

    private void glyphs(XpsElement e, Scope scope, Ctx ctx) throws IOException {
        double opacity = opacity(e);
        if (opacity <= 0) {
            return;
        }
        Brush fill = brush(e, "Fill", scope);
        if (fill == null) {
            return;
        }
        double em = XpsXml.number(XpsXml.attr(e, "FontRenderingEmSize"), 0);
        if (!(em > 0) || em > 1e6) {
            return;
        }
        String uri = XpsXml.attr(e, "FontUri");
        String part = uri == null ? null : resolveFont(base(e, ctx), uri);
        XpsFonts.Font font = part == null ? null : fonts.font(part);
        String unicode = e.getAttribute("UnicodeString");
        if (font == null) {
            missingGlyphRuns++;
            font = unicode.isBlank() ? null : fonts.fallback();
            if (font == null) {
                return;
            }
        }
        if (unicode.startsWith("{}")) {
            unicode = unicode.substring(2);
        }
        String indices = XpsXml.attr(e, "Indices");
        boolean sideways = "true".equalsIgnoreCase(e.getAttribute("IsSideways").strip());
        boolean rtl = !sideways && (int) XpsXml.number(XpsXml.attr(e, "BidiLevel"), 0) % 2 == 1;
        double ox = XpsXml.number(XpsXml.attr(e, "OriginX"), 0);
        double oy = XpsXml.number(XpsXml.attr(e, "OriginY"), 0);
        String sim = e.getAttribute("StyleSimulations").strip();
        boolean bold = sim.equals("BoldSimulation") || sim.equals("BoldItalicSimulation");
        List<Placed> placed = layout(font, unicode, indices, em, ox, oy, rtl, sideways, bold);
        if (placed.isEmpty()) {
            return;
        }
        boolean italic = sim.equals("ItalicSimulation") || sim.equals("BoldItalicSimulation");
        boolean consistent = !rtl && !sideways && !font.substitute;
        double baseline = placed.get(0).y();
        for (Placed p : placed) {
            consistent &= font.map(p.gid(), p.text()) && Math.abs(p.y() - baseline) < 1e-6;
        }
        XpsOps ops = ctx.ops;
        AffineTransform runTransform = transform(e, "RenderTransform", scope);
        AffineTransform full = new AffineTransform(ops.ctm);
        if (runTransform != null) {
            full.concatenate(runTransform);
        }
        consistent &= Math.abs(full.getShearX()) < 1e-9 && Math.abs(full.getShearY()) < 1e-9;
        ops.q();
        try {
            ops.cm(runTransform);
            if (!clip(e, scope, ctx)) {
                return;
            }
            Rectangle2D box = runBounds(font, placed, em, sideways);
            Brush mask = brush(e, "OpacityMask", scope);
            if (mask != null) {
                if (mask.kind().equals("SolidColorBrush")) {
                    opacity *= mask.color().alpha() * mask.opacity();
                    if (opacity <= 0) {
                        return;
                    }
                } else {
                    mask(mask, clampRect(box), ctx);
                }
            }
            boolean solid = fill.kind().equals("SolidColorBrush");
            double a = solid ? fill.color().alpha() * fill.opacity() * opacity : 1;
            if (solid && a <= 0) {
                return;
            }
            if (!consistent && !unicode.isEmpty()) {
                ops.op("/Span <</ActualText <FEFF");
                for (int i = 0; i < unicode.length() && i < 10_000; i++) {
                    ops.hex4(unicode.charAt(i));
                }
                ops.op(">>> BDC\n");
            }
            if (solid) {
                if (a < 1) {
                    alpha(ops, a, a);
                }
                fill(ops, fill.color());
                if (bold) {
                    stroke(ops, fill.color());
                    ops.num(em * BOLD_STROKE).op("w 1 j\n");
                }
            }
            ops.op("BT\n");
            ops.name(ops.resource(COSName.FONT, "F", font.dict)).op("1 Tf\n");
            if (!solid) {
                ops.op("7 Tr\n");
            } else if (bold) {
                ops.op("2 Tr\n");
            }
            text(ops, font, placed, em, italic, sideways);
            ops.op("ET\n");
            if (!solid) {
                paint(fill, box, opacity, ctx, true);
            }
            if (!consistent && !unicode.isEmpty()) {
                ops.op("EMC\n");
            }
            String link = XpsXml.attr(e, "FixedPage.NavigateUri");
            String name = XpsXml.attr(e, "Name");
            if ((link != null || name != null || !ctx.accumulators.isEmpty()) && ctx.toPage != null) {
                Rectangle2D onPage = ctx.pageTransform().createTransformedShape(box).getBounds2D();
                accumulate(ctx, onPage);
                navigate(link, name, onPage, ctx);
            }
        } finally {
            ops.Q();
        }
    }

    private String owner(XpsElement e) {
        return owners.get(e.root());
    }

    private String base(XpsElement e, Ctx ctx) {
        String owner = owners.get(e.root());
        return owner != null ? owner : ctx.part;
    }

    private String resolveFont(String part, String uri) {
        int hash = uri.indexOf('#');
        String path = hash < 0 ? uri : uri.substring(0, hash);
        String resolved = OfficeZip.resolve(part, path);
        if (resolved == null) {
            return null;
        }
        return hash < 0 ? resolved : resolved + uri.substring(hash);
    }

    private static Rectangle2D runBounds(XpsFonts.Font font, List<Placed> placed, double em, boolean sideways) {
        double x0 = Double.MAX_VALUE;
        double x1 = -Double.MAX_VALUE;
        double y0 = Double.MAX_VALUE;
        double y1 = -Double.MAX_VALUE;
        for (Placed p : placed) {
            double w = font.advance(p.gid()) * em;
            if (sideways) {
                x0 = Math.min(x0, p.x() - font.ascent * em);
                x1 = Math.max(x1, p.x() - font.descent * em);
                y0 = Math.min(y0, p.y() - w);
                y1 = Math.max(y1, p.y());
            } else {
                x0 = Math.min(x0, p.x());
                x1 = Math.max(x1, p.x() + w);
                y0 = Math.min(y0, p.y() - font.ascent * em);
                y1 = Math.max(y1, p.y() - font.descent * em);
            }
        }
        return new Rectangle2D.Double(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
    }

    private void text(XpsOps ops, XpsFonts.Font font, List<Placed> placed, double em, boolean italic,
            boolean sideways) throws IOException {
        double shear = italic ? ITALIC_SHEAR : 0;
        int i = 0;
        int n = placed.size();
        while (i < n) {
            Placed start = placed.get(i);
            if (sideways) {
                ops.num(0).num(-em).num(-em).num(-shear * em).num(start.x()).num(start.y()).op("Tm\n<");
                ops.hex4(font.code(start.gid()));
                ops.op("> Tj\n");
                i++;
                continue;
            }
            ops.num(em).num(0).num(shear * em).num(-em).num(start.x()).num(start.y()).op("Tm\n[<");
            ops.hex4(font.code(start.gid()));
            double pen = start.x() + rounded(font, start.gid()) * em;
            int j = i + 1;
            while (j < n && Math.abs(placed.get(j).y() - start.y()) < 1e-6) {
                Placed g = placed.get(j);
                double adjust = (pen - g.x()) / em * 1000;
                if (Math.abs(adjust) > 0.05) {
                    ops.op("> ");
                    ops.num(adjust);
                    ops.op("<");
                    pen = g.x();
                }
                ops.hex4(font.code(g.gid()));
                pen += rounded(font, g.gid()) * em;
                j++;
            }
            ops.op(">] TJ\n");
            i = j;
        }
    }

    private static double rounded(XpsFonts.Font font, int gid) {
        return font.width(gid) / 1000.0;
    }

    private List<Placed> layout(XpsFonts.Font font, String unicode, String indices, double em, double ox, double oy,
            boolean rtl, boolean sideways, boolean bold) {
        List<Placed> out = new ArrayList<>();
        int[] cps = unicode.codePoints().limit(MAX_GLYPHS_PER_RUN).toArray();
        int[] unitStart = new int[cps.length + 1];
        for (int i = 0, u = 0; i < cps.length; i++) {
            unitStart[i] = u;
            u += Character.charCount(cps[i]);
            unitStart[i + 1] = u;
        }
        int units = cps.length == 0 ? 0 : unitStart[cps.length];
        double pen = ox;
        int unit = 0;
        List<String> list = new ArrayList<>();
        if (indices != null) {
            int from = 0;
            while (list.size() <= MAX_GLYPHS_PER_RUN) {
                int semi = indices.indexOf(';', from);
                list.add(indices.substring(from, semi < 0 ? indices.length() : semi));
                if (semi < 0) {
                    break;
                }
                from = semi + 1;
            }
        }
        String[] entries = list.toArray(String[]::new);
        int e = 0;
        int clusterGlyphsLeft = 0;
        while (out.size() < MAX_GLYPHS_PER_RUN) {
            String entry = e < entries.length ? entries[e].strip() : null;
            if (entry == null && unit >= units) {
                break;
            }
            int codeUnits = 1;
            int glyphCount = 1;
            String rest = entry == null ? "" : entry;
            boolean cluster = false;
            if (rest.startsWith("(")) {
                int close = rest.indexOf(')');
                if (close > 0) {
                    String spec = rest.substring(1, close);
                    int colon = spec.indexOf(':');
                    codeUnits = (int) XpsXml.number(colon < 0 ? spec : spec.substring(0, colon), 1);
                    glyphCount = colon < 0 ? 1 : (int) XpsXml.number(spec.substring(colon + 1), 1);
                    codeUnits = Math.max(0, Math.min(codeUnits, 1000));
                    glyphCount = Math.max(1, Math.min(glyphCount, 1000));
                    cluster = true;
                    rest = rest.substring(close + 1);
                }
            }
            String text;
            int firstCp = -1;
            if (clusterGlyphsLeft > 0) {
                text = "";
                clusterGlyphsLeft--;
            } else {
                int from = unit;
                if (!cluster && unit < units) {
                    int idx = indexOfUnit(unitStart, cps.length, unit);
                    codeUnits = idx >= 0 ? Character.charCount(cps[idx]) : 1;
                }
                int to = Math.min(units, from + codeUnits);
                text = from < to ? unicode.substring(from, to) : "";
                if (!text.isEmpty()) {
                    firstCp = text.codePointAt(0);
                }
                unit = to;
                if (cluster) {
                    clusterGlyphsLeft = glyphCount - 1;
                }
            }
            String[] fields = rest.split(",", -1);
            int gid = -1;
            if (fields.length > 0 && !fields[0].isBlank()) {
                gid = (int) XpsXml.number(fields[0], -1);
            }
            if (gid < 0 || gid >= font.glyphs || font.substitute) {
                gid = firstCp >= 0 ? font.glyph(firstCp) : 0;
            }
            double natural = (sideways ? font.verticalAdvance(gid) : font.advance(gid))
                    + (bold ? BOLD_STROKE / 2 : 0);
            double adv = fields.length > 1 && !fields[1].isBlank() ? XpsXml.number(fields[1], natural * 100) / 100
                    : natural;
            double u = fields.length > 2 && !fields[2].isBlank() ? XpsXml.number(fields[2], 0) / 100 : 0;
            double v = fields.length > 3 && !fields[3].isBlank() ? XpsXml.number(fields[3], 0) / 100 : 0;
            double x;
            double y;
            if (sideways) {
                double drawPen = rtl ? pen - adv * em : pen;
                x = drawPen + (rtl ? -u : u) * em + font.ascent * em;
                y = oy - v * em + font.advance(gid) * em / 2;
            } else if (rtl) {
                pen -= adv * em;
                x = pen - u * em;
                y = oy - v * em;
            } else {
                x = pen + u * em;
                y = oy - v * em;
            }
            if (sideways) {
                pen += rtl ? -adv * em : adv * em;
            } else if (!rtl) {
                pen += adv * em;
            }
            out.add(new Placed(gid, x, y, adv * em, text));
            e++;
            if (entry == null && unit >= units) {
                break;
            }
            if (e >= entries.length && unit >= units && clusterGlyphsLeft == 0) {
                break;
            }
        }
        return out;
    }

    private static int indexOfUnit(int[] unitStart, int count, int unit) {
        int lo = 0;
        int hi = count - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (unitStart[mid] == unit) {
                return mid;
            }
            if (unitStart[mid] < unit) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return -1;
    }

    void finish() throws IOException {
        fonts.finish();
        if (missingGlyphRuns > 0 || !fonts.failed().isEmpty()) {
            int n = Math.max(1, fonts.failed().size());
            XpsFonts.Font stand = fonts.fallback();
            warnings.add(n + (n == 1 ? " font" : " fonts") + " of the XPS document could not be read; "
                    + (n == 1 ? "its" : "their") + " text " + (stand == null ? "is missing from the PDF"
                            : "uses " + stand.part.substring(1) + " instead"));
        }
        if (images.hdPhotos() > 0) {
            warnings.add(images.hdPhotos() + " HD Photo (JPEG XR) picture" + (images.hdPhotos() == 1 ? " was" : "s were")
                    + " left out: that format is not supported");
        }
        if (images.unreadable() > 0) {
            warnings.add(images.unreadable() + " picture" + (images.unreadable() == 1 ? "" : "s")
                    + " of the XPS document could not be read and " + (images.unreadable() == 1 ? "was" : "were")
                    + " left out");
        }
    }

}
