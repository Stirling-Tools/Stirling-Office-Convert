package stirling.software.officeconvert.topdf.vsdx;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import stirling.software.officeconvert.topdf.io.Relationship;
import stirling.software.officeconvert.topdf.xls.Xml;

final class PageWriter {

    static final double EMU = 914_400;

    private static final int MAX_DEPTH = 48;

    private static final int MAX_VISITS = 250_000;

    private int visits;

    boolean cut;

    private final Drawing drawing;

    private final Cells cells;

    private final Media media;

    private final Slide slide;

    private final Set<Integer> hiddenLayers = new HashSet<>();

    private Look look;

    PageWriter(Drawing drawing, Media media, Slide slide) {
        this.drawing = drawing;
        this.media = media;
        this.cells = drawing.cells;
        this.slide = slide;
    }

    void page(Drawing.Page page, Affine toSlide, int depth) throws IOException {
        if (page == null || depth > 4) {
            return;
        }
        if (page.backPage() != null) {
            page(drawing.page(page.backPage()), toSlide, depth + 1);
        }
        hiddenLayers.clear();
        look = new Look(cells, drawing.theme, page.sheet() == null ? 0
                : (int) cells.number(page.sheet(), "VariationColorIndex", 0), page.sheet() == null ? 0
                : (int) cells.number(page.sheet(), "VariationStyleIndex", 0));
        if (page.sheet() != null) {
            Sheet.Section layers = page.sheet().section("Layer");
            if (layers != null) {
                for (var e : layers.rows.entrySet()) {
                    var c = e.getValue().cells();
                    if ("0".equals(c.get("Print")) || "0".equals(c.get("Visible"))) {
                        hiddenLayers.add((int) Cells.parse(e.getKey(), -1));
                    }
                }
            }
        }
        for (Sheet s : page.shapes()) {
            shape(s, null, toSlide, 0);
        }
    }

    private void shape(Sheet s, Drawing.Master master, Affine parent, int depth) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Conversion interrupted");
        }
        if (depth > MAX_DEPTH || ++visits > MAX_VISITS) {
            cut = true;
            return;
        }
        Drawing.Master own = master;
        if (s.master != null) {
            own = drawing.master(s.master);
            if (s.base == null && own != null) {
                s.base = s.masterShape != null ? own.byId().get(s.masterShape)
                        : own.top().size() == 1 ? own.top().get(0) : null;
            }
        } else if (s.masterShape != null && master != null && s.base == null) {
            s.base = master.byId().get(s.masterShape);
        }
        if (s.base == s) {
            s.base = null;
        }
        if ("Guide".equals(s.type) || "1".equals(cells.get(s, "NonPrinting")) || onHiddenLayer(s)) {
            return;
        }
        double w = cells.number(s, "Width", 0);
        double h = cells.number(s, "Height", 0);
        Affine local = Affine.of(cells.number(s, "PinX", 0), cells.number(s, "PinY", 0),
                cells.number(s, "LocPinX", w / 2), cells.number(s, "LocPinY", h / 2), cells.number(s, "Angle", 0),
                "1".equals(cells.get(s, "FlipX")), "1".equals(cells.get(s, "FlipY")));
        Affine m = local.then(parent);
        if (!m.finite()) {
            return;
        }
        List<Sheet> kids = !s.children.isEmpty() ? s.children : s.base != null ? s.base.children : List.of();
        boolean group = !kids.isEmpty();
        double mode = cells.number(s, "DisplayMode", 2);
        if (!group || mode == 1) {
            draw(s, m, w, h);
        }
        Affine inner = m;
        if (s.children.isEmpty() && s.base != null && group) {
            double bw = Cells.parse(s.base.cells.get("Width"), w);
            double bh = Cells.parse(s.base.cells.get("Height"), h);
            double sx = bw > 1e-9 ? w / bw : 1;
            double sy = bh > 1e-9 ? h / bh : 1;
            inner = new Affine(sx, 0, 0, sy, 0, 0).then(m);
        }
        for (Sheet k : kids) {
            shape(k, own, inner, depth + 1);
        }
        if (group && mode == 2) {
            draw(s, m, w, h);
        }
    }

    private boolean onHiddenLayer(Sheet s) {
        String members = cells.get(s, "LayerMember");
        if (members == null || members.isBlank() || hiddenLayers.isEmpty()) {
            return false;
        }
        for (String p : members.split(";")) {
            double ix = Cells.parse(p, -1);
            if (ix >= 0 && !hiddenLayers.contains((int) ix)) {
                return false;
            }
        }
        return true;
    }

    private void draw(Sheet s, Affine m, double w, double h) throws IOException {
        if (s.foreignRel != null || s.base != null && s.base.foreignRel != null) {
            picture(s, m, w, h);
        }
        List<Paths.Path> paths = new java.util.ArrayList<>();
        for (Cells.Geometry g : cells.geometry(s)) {
            paths.addAll(Paths.build(g, w, h));
        }
        if (!paths.isEmpty()) {
            String rounding = cells.get(s, "Rounding");
            double radius = rounding == null || rounding.trim().equalsIgnoreCase("Themed") ? 0
                    : Cells.parse(rounding, 0);
            for (Paths.Path p : paths) {
                Rounding.apply(p, radius);
            }
            Look.Stroke stroke = look.line(s);
            slide.geometry(paths, m, look.fill(s), stroke.xml());
            Arrows.draw(slide, paths, m, stroke);
        }
        if (!"1".equals(cells.get(s, "HideText"))) {
            TextOut.text(slide, cells, look, drawing.minorFont, s, m, w, h);
        }
    }

    private void picture(Sheet s, Affine m, double w, double h) throws IOException {
        Sheet owner = s.foreignRel != null ? s : s.base;
        Relationship r = drawing.zip.relationships(owner.part).get(owner.foreignRel);
        if (r == null || r.external() || r.part() == null || !drawing.zip.exists(r.part())) {
            return;
        }
        String target = media.add(drawing.zip, r.part());
        if (target == null) {
            return;
        }
        double x = cells.number(s, "ImgOffsetX", 0);
        double y = cells.number(s, "ImgOffsetY", 0);
        double iw = cells.number(s, "ImgWidth", w);
        double ih = cells.number(s, "ImgHeight", h);
        slide.picture(target, m, x, y, iw, ih);
    }

    static String esc(String s) {
        return Xml.attr(s);
    }
}
