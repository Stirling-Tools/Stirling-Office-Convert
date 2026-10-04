package stirling.software.officeconvert.layout;

import static stirling.software.officeconvert.layout.LineTraits.EDGE;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

final class LineRoom {

    private record Limits(float left, float right, boolean narrowed) {}

    final float colLeft;
    final float colRight;
    final float measure;
    private final Map<Line, Limits> limits = new IdentityHashMap<>();

    LineRoom(List<Line> lines, float colLeft, float colRight, List<ParagraphBuilder.Obstacle> obstacles) {
        this.colLeft = colLeft;
        this.colRight = colRight;
        for (Line l : lines) {
            limits.put(l, limitsOf(l, colLeft, colRight, obstacles));
        }
        this.measure = measure(lines, colLeft, colRight);
    }

    private static Limits limitsOf(Line l, float colLeft, float colRight, List<ParagraphBuilder.Obstacle> obstacles) {
        float left = colLeft;
        float right = colRight;
        boolean narrowed = false;
        for (ParagraphBuilder.Obstacle o : obstacles) {
            Box b = o.box();
            if (l.top >= b.bottom() - 1 || l.bottom <= b.top() + 1) {
                continue;
            }
            if (b.x() >= l.right - 1) {
                right = Math.min(right, b.x() - o.gapLeft());
                narrowed = true;
            } else if (b.right() <= l.x + 1) {
                left = Math.max(left, b.right() + o.gapRight());
                narrowed = true;
            }
        }
        return new Limits(left, right, narrowed);
    }

    float left(Line l) {
        return limits.get(l).left();
    }

    float right(Line l) {
        return limits.get(l).right();
    }

    float wrap(Line l) {
        Limits lim = limits.get(l);
        return lim.narrowed() ? Math.min(lim.right(), measure) : measure;
    }

    boolean narrowed(Line l) {
        return limits.get(l).narrowed();
    }

    float rel(Line l) {
        return l.x - left(l);
    }

    boolean centred(Line l) {
        return LineTraits.isCentred(l, colLeft, colRight);
    }

    private static float measure(List<Line> lines, float colLeft, float colRight) {
        float[] rights = new float[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            rights[i] = lines.get(i).right;
        }
        Arrays.sort(rights);
        float max = rights[rights.length - 1];
        int best = 0;
        float edge = max;
        for (int i = rights.length - 1; i >= 0; i--) {
            int n = 0;
            for (float r : rights) {
                if (Math.abs(r - rights[i]) <= EDGE) {
                    n++;
                }
            }
            if (n > best && rights[i] >= colLeft + (colRight - colLeft) * 0.5f) {
                best = n;
                edge = rights[i];
            }
        }
        return best >= 3 ? Math.max(edge, Math.min(max, colRight)) : Math.max(max, colRight);
    }
}
