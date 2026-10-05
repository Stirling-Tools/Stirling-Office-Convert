package stirling.software.officeconvert.layout;

import java.util.List;

public final class Protrusion {

    private static final String PROTRUDING = "-\u2010\u2013\u2014,.;:!?)\u201D\u2019'\"";

    private static final float HANG_EM = 0.25f;

    private Protrusion() {}

    public static float edge(List<Line> lines, float maxRight) {
        float edge = -Float.MAX_VALUE;
        for (int i = 0; i + 1 < lines.size(); i++) {
            Line l = lines.get(i);
            if (!protrudes(l)) {
                edge = Math.max(edge, ParagraphMeasure.textRight(l));
            }
        }
        return maxRight - edge <= HANG_EM * lines.getFirst().size ? edge : maxRight;
    }

    private static boolean protrudes(Line l) {
        String t = l.words.getLast().text;
        return t.length() >= 2 && PROTRUDING.indexOf(t.charAt(t.length() - 1)) >= 0;
    }
}
