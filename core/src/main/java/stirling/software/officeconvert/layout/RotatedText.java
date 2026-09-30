package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import stirling.software.officeconvert.extract.Glyph;

final class RotatedText {

    record Block(int direction, List<Line> lines, Box onPage, boolean upright) {

        float size() {
            return lines.getFirst().size;
        }

        String text() {
            StringBuilder sb = new StringBuilder();
            for (Line l : lines) {
                sb.append(sb.isEmpty() ? "" : " ").append(l.text());
            }
            return sb.toString();
        }
    }

    private RotatedText() {}

    static List<Block> blocks(List<Glyph> rotated, float pageW, float pageH) {
        List<Block> out = new ArrayList<>();
        for (boolean upright : new boolean[] {false, true}) {
            Map<Integer, List<Glyph>> byDirection = new TreeMap<>();
            for (Glyph g : rotated) {
                if ((g.vertAlign == 90 || g.vertAlign == 180 || g.vertAlign == 270) && g.upright == upright) {
                    byDirection.computeIfAbsent(g.vertAlign, k -> new ArrayList<>()).add(upright(g, pageW, pageH));
                }
            }
            byDirection.forEach((direction, glyphs) -> {
                List<Line> rows = LineGroups.joinRows(new ArrayList<>(LineBuilder.build(glyphs)));
                for (List<Line> stack : stacks(rows, upright ? UPRIGHT_GAP : GAP)) {
                    out.add(new Block(direction, stack, onPage(bounds(stack), direction, pageW, pageH), upright));
                }
            });
        }
        return out;
    }

    static float uprightWidth(int direction, float pageW, float pageH) {
        return direction == 180 ? pageW : pageH;
    }

    static Box onPage(Box up, int direction, float pageW, float pageH) {
        return switch (direction) {
            case 90 -> new Box(up.top(), pageH - up.right(), up.bottom(), pageH - up.x());
            case 270 -> new Box(pageW - up.bottom(), up.x(), pageW - up.top(), up.right());
            default -> new Box(pageW - up.right(), pageH - up.bottom(), pageW - up.x(), pageH - up.top());
        };
    }

    private static Glyph upright(Glyph g, float pageW, float pageH) {
        float x = switch (g.vertAlign) {
            case 90 -> pageH - g.baseline;
            case 270 -> g.baseline;
            default -> pageW - g.x;
        };
        float baseline = switch (g.vertAlign) {
            case 90 -> g.x;
            case 270 -> pageW - g.x;
            default -> pageH - g.baseline;
        };
        Glyph up = new Glyph(g.text, x, g.width, baseline, g.size, g.ascent, g.descent, g.font, g.rgb, g.seq,
                g.spaceWidth, g.bold, g.italic);
        up.hscale = g.hscale;
        return up;
    }

    private static final float GAP = 0.8f;

    private static final float UPRIGHT_GAP = 1.3f;

    private static List<List<Line>> stacks(List<Line> rows, float gap) {
        List<List<Line>> out = new ArrayList<>();
        for (Line row : rows) {
            List<Line> last = out.isEmpty() ? null : out.getLast();
            Line prev = last == null ? null : last.getLast();
            boolean joins = prev != null
                    && row.top - prev.bottom < gap * row.size
                    && row.x < prev.right && prev.x < row.right
                    && Math.abs(row.size - prev.size) < 0.2f * prev.size;
            if (joins) {
                last.add(row);
            } else {
                out.add(new ArrayList<>(List.of(row)));
            }
        }
        return out;
    }

    private static Box bounds(List<Line> lines) {
        Box b = null;
        for (Line l : lines) {
            Box lb = new Box(l.x, l.top, l.right, l.bottom);
            b = b == null ? lb : b.union(lb);
        }
        return b;
    }
}
