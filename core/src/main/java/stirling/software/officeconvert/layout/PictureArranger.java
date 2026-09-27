package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import stirling.software.officeconvert.extract.PageData;

final class PictureArranger {

    private PictureArranger() {}

    static boolean pictureDominated(List<PageLayout.Item> blocks, PageData page) {
        float area = 0;
        for (PageLayout.Item b : blocks) {
            if (isPicture(b)) {
                float w = Math.min(b.right(), page.width()) - Math.max(b.x(), 0);
                float h = Math.min(b.bottom(), page.height()) - Math.max(b.top(), 0);
                area += Math.max(0, w) * Math.max(0, h);
            }
        }
        return area >= 0.4f * page.width() * page.height();
    }

    private static final float ICON_SIDE = 26f;

    private static final float OVERLAID = 0.2f;

    private static final float OUTSIDE_COLUMN = 2f;

    private static boolean isPicture(PageLayout.Item b) {
        return (b instanceof PageLayout.FigureItem || b instanceof PageLayout.ImageItem) && !b.behindText();
    }

    static void arrange(
            List<PageLayout.Item> blocks,
            List<Line> lines,
            List<PageLayout.Item> out,
            List<ParagraphBuilder.Obstacle> obstacles,
            boolean overlayAll,
            float marginEdge,
            Box sheet,
            float[] column) {
        List<PageLayout.Item> inFlow = new ArrayList<>();
        for (PageLayout.Item b : blocks) {
            if (b instanceof PageLayout.TextBoxItem tb) {
                obstacles.add(new ParagraphBuilder.Obstacle(tb.box(), tb.gap(), tb.gap()));
                out.add(tb);
            } else if (!isPicture(b)) {
                out.add(b);
            } else if (overlayAll || b.right() <= marginEdge + 2 || bleeds(b, sheet) || isMark(b) && !onPicture(b, blocks, lines)
                    || overlapsPicture(b, blocks, lines)) {
                out.add(new PageLayout.FloatItem(b, 0f, true));
            } else {
                place(b, lines, solids(blocks), out, obstacles, inFlow);
            }
        }
        for (PageLayout.Item row : rows(inFlow)) {
            boolean outside = !(row instanceof PageLayout.PictureRow)
                    && (row.x() < column[0] - OUTSIDE_COLUMN || row.right() > column[1] + OUTSIDE_COLUMN);
            out.add(outside ? new PageLayout.FloatItem(row, 0f, true) : row);
        }
    }

    private static boolean overlapsPicture(PageLayout.Item b, List<PageLayout.Item> blocks, List<Line> lines) {
        for (PageLayout.Item o : blocks) {
            if (o != b && isPicture(o) && stacked(b, o)) {
                boolean larger = box(b).area() >= box(o).area();
                PageLayout.Item small = larger ? o : b;
                if (!badge(small, larger ? b : o, lines) || larger && stackedOnAnother(small, b, blocks)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean onPicture(PageLayout.Item b, List<PageLayout.Item> blocks, List<Line> lines) {
        for (PageLayout.Item o : blocks) {
            if (o != b && isPicture(o) && box(o).area() > box(b).area() && badge(b, o, lines) && !stackedOnAnother(b, o, blocks)) {
                return true;
            }
        }
        return false;
    }

    private static Box box(PageLayout.Item i) {
        return new Box(i.x(), i.top(), i.right(), i.bottom());
    }

    private static boolean stacked(PageLayout.Item a, PageLayout.Item b) {
        return box(a).overlapArea(box(b)) > STACKED * Math.min(box(a).area(), box(b).area());
    }

    private static boolean badge(PageLayout.Item small, PageLayout.Item large, List<Line> lines) {
        Box s = box(small);
        Box l = box(large);
        return s.overlapArea(l) >= INSIDE * s.area() && s.area() <= BADGE * l.area() && !holdsText(l, lines)
                && besideText(l, lines);
    }

    private static boolean besideText(Box box, List<Line> lines) {
        for (Line l : lines) {
            if (l.top < box.bottom() && l.bottom > box.top() && (l.right <= box.x() || l.x >= box.right())) {
                return true;
            }
        }
        return false;
    }

    private static boolean stackedOnAnother(PageLayout.Item small, PageLayout.Item large, List<PageLayout.Item> blocks) {
        for (PageLayout.Item p : blocks) {
            if (p != small && p != large && isPicture(p) && stacked(small, p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean holdsText(Box box, List<Line> lines) {
        for (Line l : lines) {
            float middle = (l.top + l.bottom) / 2f;
            if (middle < box.top() || middle > box.bottom()) {
                continue;
            }
            for (Word w : l.words) {
                if (box.contains((w.x + w.right) / 2f, middle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final float STACKED = 0.25f;

    private static final float INSIDE = 0.9f;
    private static final float BADGE = 0.25f;

    static boolean isMark(PageLayout.Item b) {
        boolean small = b.right() - b.x() < FigureFinder.MIN_SIDE || b.bottom() - b.top() < FigureFinder.MIN_SIDE;
        boolean icon = b instanceof PageLayout.ImageItem && b.right() - b.x() < ICON_SIDE && b.bottom() - b.top() < ICON_SIDE;
        return b instanceof PageLayout.FigureItem && small || icon;
    }

    private static List<PageLayout.Item> solids(List<PageLayout.Item> blocks) {
        return blocks.stream().filter(b -> b instanceof PageLayout.TableItem || b instanceof PageLayout.TextBoxItem).toList();
    }

    private static void place(
            PageLayout.Item b,
            List<Line> lines,
            List<PageLayout.Item> solids,
            List<PageLayout.Item> out,
            List<ParagraphBuilder.Obstacle> obstacles,
            List<PageLayout.Item> inFlow) {
        for (PageLayout.Item t : solids) {
            if (b.x() < t.right() && t.x() < b.right() && b.top() < t.bottom() && t.top() < b.bottom()) {
                out.add(new PageLayout.FloatItem(b, 0f, true));
                return;
            }
        }
        float gapLeft = Float.MAX_VALUE;
        float gapRight = Float.MAX_VALUE;
        boolean inLineGap = false;
        for (Line l : lines) {
            if (l.top >= b.bottom() - 1 || l.bottom <= b.top() + 1) {
                continue;
            }
            if (l.right <= b.x() + 1) {
                gapLeft = Math.min(gapLeft, b.x() - l.right);
            } else if (l.x >= b.right() - 1) {
                gapRight = Math.min(gapRight, l.x - b.right());
            } else {
                inLineGap |= clearOfWords(l, b);
            }
        }
        for (PageLayout.Item t : solids) {
            float h = b.bottom() - b.top();
            boolean column = t.top() <= b.top() && t.bottom() >= b.bottom() && t.bottom() - t.top() > 3 * h;
            if (!column && t.top() < b.bottom() - 1 && t.bottom() > b.top() + 1) {
                if (t.right() <= b.x() + 1) {
                    gapLeft = Math.min(gapLeft, b.x() - t.right());
                } else if (t.x() >= b.right() - 1) {
                    gapRight = Math.min(gapRight, t.x() - b.right());
                }
            }
        }
        if (inLineGap) {
            out.add(new PageLayout.FloatItem(b, 0f, true));
        } else if (gapLeft == Float.MAX_VALUE && gapRight == Float.MAX_VALUE) {
            inFlow.add(b);
        } else {
            float gl = gapLeft == Float.MAX_VALUE ? 9f : Math.clamp(gapLeft, 0f, 36f);
            float gr = gapRight == Float.MAX_VALUE ? 9f : Math.clamp(gapRight, 0f, 36f);
            obstacles.add(new ParagraphBuilder.Obstacle(new Box(b.x(), b.top(), b.right(), b.bottom()), gl, gr));
            out.add(new PageLayout.FloatItem(b, Math.min(gl, gr), false));
        }
    }

    private static boolean bleeds(PageLayout.Item b, Box sheet) {
        return b.x() <= sheet.x() + 1 || b.right() >= sheet.right() - 1 || b.top() <= sheet.top() + 1
                || b.bottom() >= sheet.bottom() - 1;
    }

    private static boolean clearOfWords(Line l, PageLayout.Item b) {
        for (Word w : l.words) {
            if (w.x < b.right() + 1 && w.right > b.x() - 1) {
                return false;
            }
        }
        return l.x < b.x() && l.right > b.right();
    }

    private static List<PageLayout.Item> rows(List<PageLayout.Item> inFlow) {
        inFlow.sort(Comparator.comparingDouble(PageLayout.Item::top));
        List<List<PageLayout.Item>> rows = new ArrayList<>();
        for (PageLayout.Item b : inFlow) {
            List<PageLayout.Item> home = rows.stream().filter(row -> sharesRow(row, b)).findFirst().orElse(null);
            if (home == null) {
                home = new ArrayList<>();
                rows.add(home);
            }
            home.add(b);
        }
        List<PageLayout.Item> out = new ArrayList<>();
        for (List<PageLayout.Item> row : rows) {
            if (row.size() == 1) {
                out.add(row.getFirst());
            } else {
                row.sort(Comparator.comparingDouble(PageLayout.Item::x));
                out.add(new PageLayout.PictureRow(row));
            }
        }
        return out;
    }

    private static boolean sharesRow(List<PageLayout.Item> row, PageLayout.Item b) {
        boolean beside = false;
        for (PageLayout.Item o : row) {
            float overlap = Math.min(o.bottom(), b.bottom()) - Math.max(o.top(), b.top());
            float minH = Math.min(o.bottom() - o.top(), b.bottom() - b.top());
            boolean level = overlap > 0.5f * minH;
            float across = Math.min(o.right(), b.right()) - Math.max(o.x(), b.x());
            if (!level && across > 0 || level && across > OVERLAID * Math.min(o.right() - o.x(), b.right() - b.x())) {
                return false;
            }
            beside |= level;
        }
        return beside;
    }
}
