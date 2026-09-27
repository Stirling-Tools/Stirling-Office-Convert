package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.model.Paragraph.Align;

public final class ParaDraft {

    public enum Role {
        BODY,
        TITLE,
        HEADING,
        LIST,
        CAPTION
    }

    public final List<Line> lines = new ArrayList<>();
    public final BitSet hardBreaks = new BitSet();
    public final BitSet pageBreaks = new BitSet();
    public final float colLeft;
    public final float colRight;

    public Align align = Align.LEFT;
    public float left;
    public float first;
    public float right;
    public float pitch;
    public float justifySlack = Float.NaN;
    public Role role = Role.BODY;
    public int headingLevel;
    public Marker marker;
    public float markerTextX;
    public int listLevel;
    public int numId = -1;
    public boolean continuation;
    public boolean endsHyphenated;

    public ParaDraft(float colLeft, float colRight) {
        this.colLeft = colLeft;
        this.colRight = colRight;
    }

    public Line first() {
        return lines.getFirst();
    }

    public Line last() {
        return lines.getLast();
    }

    public float top() {
        float t = Float.MAX_VALUE;
        for (Line l : lines) {
            t = Math.min(t, l.top);
        }
        return t;
    }

    public float bottom() {
        float b = -Float.MAX_VALUE;
        for (Line l : lines) {
            b = Math.max(b, l.bottom);
        }
        return b;
    }

    public float x() {
        float x = Float.MAX_VALUE;
        for (Line l : lines) {
            x = Math.min(x, l.x);
        }
        return x;
    }

    public float rightEdge() {
        float r = -Float.MAX_VALUE;
        for (Line l : lines) {
            r = Math.max(r, l.right);
        }
        return r;
    }

    public float medianPitch() {
        float[] d = new float[lines.size() - 1];
        for (int i = 1; i < lines.size(); i++) {
            d[i - 1] = lines.get(i).baseline - lines.get(i - 1).baseline;
        }
        Arrays.sort(d);
        return d[d.length / 2];
    }

    public float size() {
        Map<Float, Integer> sizes = new HashMap<>();
        for (Line l : lines) {
            sizes.merge(l.size, l.chars, Integer::sum);
        }
        return Line.mode(sizes, lines.getFirst().size);
    }

    public boolean allBold() {
        for (Line l : lines) {
            if (!l.bold) {
                return false;
            }
        }
        return true;
    }

    public int chars() {
        int n = 0;
        for (Line l : lines) {
            n += l.chars;
        }
        return n;
    }

    public String text() {
        StringBuilder sb = new StringBuilder();
        for (Line l : lines) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(l.text());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return role + " " + text();
    }
}
