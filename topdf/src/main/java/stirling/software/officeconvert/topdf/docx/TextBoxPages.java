package stirling.software.officeconvert.topdf.docx;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class TextBoxPages {

    private record Line(float x, float y, float height, List<Op> ops, Object flow) {}

    private TextBoxPages() {}

    static void apply(List<PageBox> pages, Ctx ctx, Consumer<PageBox> prepare) {
        int originals = pages.size();
        List<List<PageBox.FloatBox>> originalFloats = pages.stream().map(page -> List.copyOf(page.floats)).toList();
        for (int index = 0; index < originals; index++) {
            PageBox page = pages.get(index);
            float top = page.bodyTop;
            float bottom = page.bodyBottom;
            float room = bottom - top;
            if (room < 36) {
                continue;
            }
            for (PageBox.FloatBox floating : originalFloats.get(index)) {
                Rectangle2D.Float box = floating.box();
                if (box.y < top) {
                    continue;
                }
                List<Line> lines = new ArrayList<>();
                if (!collect(floating.drawing(), box.x, box.y, lines, ctx, 0)
                        || lines.stream().anyMatch(line -> line.height() > room || line.y() < top)
                        || lines.stream().noneMatch(line -> line.y() + line.height() > bottom)) {
                    continue;
                }
                Map<Object, Float> shifts = new HashMap<>();
                List<PageBox> planned = new ArrayList<>(pages);
                List<Integer> targets = new ArrayList<>();
                List<Float> positions = new ArrayList<>();
                boolean moved = false;
                boolean compatible = true;
                int limit = ctx.job.maxPages() > 0 ? Math.min(10_000, ctx.job.maxPages()) : 10_000;
                for (Line line : lines) {
                    float shift = shifts.getOrDefault(line.flow(), 0f);
                    float logical = line.y() - top + shift;
                    int target = index;
                    PageBox destination = page;
                    while (true) {
                        if (target >= limit) {
                            target = -1;
                            break;
                        }
                        destination = destination(planned, target, page, prepare);
                        if (destination == null) {
                            compatible = false;
                            break;
                        }
                        float available = destination.bodyBottom - destination.bodyTop;
                        if (available < 36 || line.height() > available) {
                            compatible = false;
                            break;
                        }
                        if (logical >= available) {
                            logical -= available;
                            target++;
                        } else if (logical + line.height() > available + 0.01f) {
                            shift += available - logical;
                            shifts.put(line.flow(), shift);
                            logical = 0;
                            target++;
                        } else {
                            break;
                        }
                    }
                    if (!compatible) {
                        break;
                    }
                    targets.add(target);
                    positions.add(destination.bodyTop + logical);
                    moved |= target != index;
                }
                if (!compatible || !moved) {
                    continue;
                }
                page.floats.remove(floating);
                pages.addAll(planned.subList(pages.size(), planned.size()));
                for (int i = 0; i < lines.size(); i++) {
                    checkpoint(ctx);
                    int target = targets.get(i);
                    if (target < 0) {
                        ctx.job.truncate();
                        continue;
                    }
                    Line line = lines.get(i);
                    float y = positions.get(i);
                    List<Op> ops = List.of(new Op.Group(line.x(), y, null, null, line.ops()));
                    pages.get(target).floats.add(new PageBox.FloatBox(floating.drawing(),
                            new Rectangle2D.Float(line.x(), y, box.width, line.height()), ops,
                            floating.behind(), floating.z()));
                }
            }
        }
    }

    private static PageBox destination(List<PageBox> pages, int index, PageBox source, Consumer<PageBox> prepare) {
        while (pages.size() <= index) {
            PageBox last = pages.getLast();
            if (last.sect != source.sect) {
                return null;
            }
            PageBox next = new PageBox(source.sect, source.section);
            next.number = last.number + 1;
            prepare.accept(next);
            pages.add(next);
        }
        PageBox page = pages.get(index);
        return page.sect == source.sect ? page : null;
    }

    private static boolean collect(Drawing drawing, float x, float y, List<Line> lines, Ctx ctx, int depth) {
        if (depth >= 8 || !(drawing.graphic instanceof Drawing.Shape shape) || shape.fill() != null
                || shape.line() != null || shape.shade() != null || shape.rotation() != 0 || shape.flipH()
                || shape.flipV() || shape.text() == null) {
            return false;
        }
        Drawing.TextBox text = shape.text();
        if (!text.grow() || text.clip() || text.noWrap() || text.vertical() || text.form() != null
                || !"t".equals(text.anchor())) {
            return false;
        }
        checkpoint(ctx);
        float width = Math.max(1, drawing.width - text.left() - text.right());
        StackLayout.Result laid;
        ctx.depth++;
        try {
            laid = StackLayout.layout(text.blocks(), width, ctx);
        } finally {
            ctx.depth--;
        }
        if (!laid.notes().isEmpty() || !laid.pageOps().isEmpty()) {
            return false;
        }
        Object flow = new Object();
        for (Placed placed : laid.placed()) {
            lines.add(new Line(x + text.left() + placed.x, y + text.top() + placed.y, placed.strip.height,
                    placed.strip.ops, flow));
        }
        float height = Math.max(1, drawing.height - text.top() - text.bottom());
        FloatLayout.Frame frame = new FloatLayout.Frame(width, height, 0, 0, 0, 0, 0, width);
        for (Strip.Anchor anchor : laid.anchors()) {
            Drawing child = anchor.drawing();
            Rectangle2D.Float box = FloatLayout.position(child, frame, anchor.paraX(), anchor.paraTop());
            if (!collect(child, x + text.left() + box.x, y + text.top() + box.y, lines, ctx, depth + 1)) {
                return false;
            }
        }
        return true;
    }

    private static void checkpoint(Ctx ctx) {
        try {
            ctx.job.checkpoint();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
