package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.extract.PageGraphics.Fill;
import stirling.software.officeconvert.extract.PageGraphics;

final class TextBoxFinder {

    record Container(PageGraphics.VectorMark mark, Box box, int fill, int line, float lineWidth, boolean rounded) {}

    record Result(List<PageLayout.TextBoxItem> boxes, List<Container> claimed) {}

    private final ParagraphBuilder paragraphs;

    TextBoxFinder(ParagraphBuilder paragraphs) {
        this.paragraphs = paragraphs;
    }

    private static final float PANEL_WIDTH = 120f;
    private static final float PANEL_HEIGHT = 60f;

    static List<Container> containers(List<PageGraphics.VectorMark> marks, List<Line> segments, boolean tinted) {
        List<Container> out = new ArrayList<>();
        for (PageGraphics.VectorMark m : marks) {
            boolean panel = m.boxy() && m.width() >= PANEL_WIDTH && m.height() >= PANEL_HEIGHT
                    && FigureFinder.isProse(new Box(m.x(), m.top(), m.right(), m.bottom()), segments);
            if (m.shading() || m.sparse() || m.segments() > 180 || m.diagonal() && !m.curved() && !panel || m.width() < 40
                    || m.height() < 20) {
                continue;
            }
            Box b = new Box(m.x(), m.top(), m.right(), m.bottom());
            int inside = 0;
            for (Line l : segments) {
                if (l.x >= b.x() && l.right <= b.right() && l.top >= b.top() && l.bottom <= b.bottom()) {
                    inside++;
                }
            }
            if (inside == 0 || diagramPanel(m, b, marks, segments)) {
                continue;
            }
            int fill = m.filled() && (tinted || !Decorations.isWhite(m.rgb())) ? m.rgb() : -1;
            int line = m.stroked() && !Decorations.isWhite(m.strokeRgb()) ? m.strokeRgb() : -1;
            if (fill < 0 && line < 0 && !m.filled()) {
                continue;
            }
            out.add(new Container(m, b, fill, line, m.lineWidth(), m.curved()));
        }
        return out;
    }

    private static final int PANEL_ART = 8;

    private static boolean diagramPanel(PageGraphics.VectorMark m, Box b, List<PageGraphics.VectorMark> marks, List<Line> segments) {
        int art = 0;
        int connectors = 0;
        for (PageGraphics.VectorMark o : marks) {
            if (o != m && !o.round() && b.contains((o.x() + o.right()) / 2f, (o.top() + o.bottom()) / 2f)
                    && o.width() * o.height() < 0.5f * b.area()) {
                art++;
                connectors += o.diagonal() && !o.boxy() ? 1 : 0;
            }
        }
        return art >= PANEL_ART && connectors >= 2 && !FigureFinder.isProse(b, segments);
    }

    Result find(List<Fill> fills, List<Container> containers, List<Line> flow, float frameLeft, float frameRight) {
        List<PageLayout.TextBoxItem> out = new ArrayList<>();
        List<Container> claimed = new ArrayList<>();
        float colWidth = frameRight - frameLeft;
        List<Container> candidates = new ArrayList<>(containers);
        for (Fill f : fills) {
            if (!Decorations.isWhite(f.rgb())) {
                candidates.add(new Container(null, Regions.of(f), f.rgb(), -1, 0, false));
            }
        }
        candidates.removeIf(c -> c.box().width() > colWidth * 0.85f || c.box().width() < 36 || c.box().height() < 18
                || Backdrops.isBackdrop(c.box(), flow));
        for (Container cand : candidates) {
            Box box = cand.box();
            Fill f = cand.mark() == null ? new Fill(box.x(), box.top(), box.right(), box.bottom(), cand.fill()) : null;
            List<Line> inside = new ArrayList<>();
            boolean beside = false;
            float gap = Float.MAX_VALUE;
            for (Line l : flow) {
                boolean in = l.x >= box.x() - 1 && l.right <= box.right() + 1
                        && l.top >= box.top() - 2 && l.bottom <= box.bottom() + 2;
                if (in) {
                    inside.add(l);
                    continue;
                }
                boolean overlapsV = l.top < box.bottom() - 1 && l.bottom > box.top() + 1;
                if (overlapsV && (l.right <= box.x() + 1 || l.x >= box.right() - 1) && !insideOther(l, cand, candidates)) {
                    beside = true;
                    gap = Math.min(gap, l.right <= box.x() + 1 ? box.x() - l.right : l.x - box.right());
                }
            }
            if (inside.size() < 2 || !beside || gap < 6f || f != null && touchesSameFill(f, fills)) {
                continue;
            }
            flow.removeAll(inside);
            out.add(item(cand, inside, gap));
            claimed.add(cand);
        }
        return new Result(out, claimed);
    }

    private PageLayout.TextBoxItem item(Container cand, List<Line> inside, float gap) {
        Box box = cand.box();
        float textLeft = Float.MAX_VALUE;
        float textRight = -Float.MAX_VALUE;
        for (Line l : inside) {
            textLeft = Math.min(textLeft, l.x);
            textRight = Math.max(textRight, l.right);
        }
        float inset = Math.max(0, textLeft - box.x());
        float innerRight = Math.max(textRight, box.right() - inset);
        List<ParaDraft> paras = paragraphs.build(LineGroups.joinRows(inside), box.x() + inset, innerRight);
        return new PageLayout.TextBoxItem(box, cand.fill(), box.x() + inset, innerRight, paras,
                gap == Float.MAX_VALUE ? 9f : Math.clamp(gap, 0f, 36f), cand.line(), cand.lineWidth(), cand.rounded(), false);
    }

    private static boolean touchesSameFill(Fill f, List<Fill> fills) {
        for (Fill o : fills) {
            if (o == f) {
                continue;
            }
            boolean vOverlap = o.top() < f.bottom() - 1 && o.bottom() > f.top() + 1;
            boolean hOverlap = o.x() < f.right() - 1 && o.right() > f.x() + 1;
            boolean sideBySide = vOverlap && (Math.abs(o.x() - f.right()) < 3 || Math.abs(f.x() - o.right()) < 3);
            boolean stacked = hOverlap && (Math.abs(o.top() - f.bottom()) < 3 || Math.abs(f.top() - o.bottom()) < 3);
            if (sideBySide || stacked) {
                return true;
            }
        }
        return false;
    }

    private static boolean insideOther(Line l, Container self, List<Container> candidates) {
        for (Container c : candidates) {
            Box b = c.box();
            if (c != self && l.x >= b.x() - 1 && l.right <= b.right() + 1 && l.top >= b.top() - 2 && l.bottom <= b.bottom() + 2) {
                return true;
            }
        }
        return false;
    }
}
