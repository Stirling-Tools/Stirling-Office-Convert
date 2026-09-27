package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;

final class MathFinder {

    private static final Pattern MATH_FONT = Pattern.compile(
            "(cmmi|cmsy|cmex|cmbsy|msbm|msam|rsfs|eufm|eusm|stmary|wasy|lmmath|latinmodernmath|stix.*math"
                    + "|xits.*math|cambriamath|asanamath|standardsyml.*slant|mathematicalpi|txsy|txex|pxsy|pxex).*");

    private MathFinder() {}

    private static final Pattern EQUATION_NUMBER = Pattern.compile("\\(\\d+(\\.\\d+)?[a-z]?\\)");

    private static boolean isMathGlyph(Glyph g, Map<FontInfo, Boolean> known) {
        return known.computeIfAbsent(g.font, MathFinder::isMathFont);
    }

    private static boolean isMathFont(FontInfo font) {
        String ps = font.postScriptName() == null ? "" : font.postScriptName().toLowerCase(Locale.ROOT);
        return MATH_FONT.matcher(ps).matches() || "Cambria Math".equals(font.family());
    }

    static List<Box> displayMath(List<Line> segments, float bodySize) {
        Map<FontInfo, Boolean> known = new IdentityHashMap<>();
        List<Line> math = new ArrayList<>();
        for (Line l : segments) {
            int m = 0;
            int n = 0;
            boolean stackedScript = false;
            for (Word w : l.words) {
                for (Glyph g : w.glyphs) {
                    n++;
                    if (isMathGlyph(g, known)) {
                        m++;
                    }
                    stackedScript |= g.vertAlign != 0;
                }
            }
            boolean equationNumber = EQUATION_NUMBER.matcher(l.text()).matches();
            if (n > 0 && (m * 3 >= n || m >= 2 && stackedScript && n <= 60) && !equationNumber) {
                math.add(l);
            }
        }
        if (math.isEmpty()) {
            return List.of();
        }
        List<Box> boxes = new ArrayList<>();
        for (Line l : math) {
            boxes.add(new Box(l.x, l.top, l.right, l.bottom));
        }
        List<Box> clusters = FigureFinder.cluster(boxes, bodySize * 0.35f);
        List<Box> out = new ArrayList<>();
        for (Box c : clusters) {
            Box grown = c;
            for (Line l : segments) {
                boolean inside = l.top >= c.top() - 1 && l.bottom <= c.bottom() + 1
                        && l.x >= c.x() - bodySize && l.right <= c.right() + bodySize;
                float cy = (l.top + l.bottom) / 2f;
                boolean number = l.text().matches("\\(\\d+(\\.\\d+)?[a-z]?\\)") && cy > c.top() && cy < c.bottom()
                        && l.x > c.right() && l.x - c.right() < 200;
                if (inside || number) {
                    grown = grown.union(new Box(l.x, l.top, l.right, l.bottom));
                }
            }
            if (grown.width() > 20 && proseInside(grown, segments, known) < 2) {
                out.add(grown.grow(1.5f));
            }
        }
        return out;
    }

    private static int proseInside(Box b, List<Line> segments, Map<FontInfo, Boolean> known) {
        int n = 0;
        for (Line l : segments) {
            if (b.contains(l.centre(), (l.top + l.bottom) / 2f) && l.words.size() >= 8) {
                int math = 0;
                int all = 0;
                for (Word w : l.words) {
                    for (Glyph g : w.glyphs) {
                        all++;
                        if (isMathGlyph(g, known)) {
                            math++;
                        }
                    }
                }
                if (math * 5 < all) {
                    n++;
                }
            }
        }
        return n;
    }
}
