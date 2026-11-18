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
            int all = 0;
            boolean stackedScript = false;
            for (Word w : l.words) {
                for (Glyph g : w.glyphs) {
                    all++;
                    if (g.math != null) {
                        continue;
                    }
                    n++;
                    if (isMathGlyph(g, known)) {
                        m++;
                    }
                    stackedScript |= g.vertAlign != 0;
                }
            }
            boolean equationNumber = EQUATION_NUMBER.matcher(l.text()).matches();
            boolean loneDisplay = all == 1 && m == 1 && l.bottom - l.top >= bodySize * 1.8f;
            if ((m >= 2 && (m * 3 >= n || stackedScript && n <= 60) || loneDisplay) && !equationNumber) {
                math.add(l);
            }
        }
        if (math.isEmpty()) {
            return List.of();
        }
        List<Box> boxes = new ArrayList<>();
        for (Line l : math) {
            boxes.add(unrebuilt(l));
        }
        List<Box> clusters = FigureFinder.cluster(boxes, bodySize * 0.35f);
        List<Box> out = new ArrayList<>();
        for (Box c : clusters) {
            Box grown = c;
            for (Line l : segments) {
                Box u = unrebuilt(l);
                if (u == null) {
                    continue;
                }
                boolean inside = u.top() >= c.top() - 1 && u.bottom() <= c.bottom() + 1
                        && u.x() >= c.x() - bodySize && u.right() <= c.right() + bodySize;
                float cy = (u.top() + u.bottom()) / 2f;
                boolean number = l.text().matches("\\(\\d+(\\.\\d+)?[a-z]?\\)") && cy > c.top() && cy < c.bottom()
                        && u.x() > c.right() && u.x() - c.right() < 200;
                if (inside || number) {
                    grown = grown.union(u);
                }
            }
            if (grown.width() > 20 && proseInside(grown, segments, known) < 2) {
                out.add(grown.grow(1.5f));
            }
        }
        return out;
    }

    private static Box unrebuilt(Line l) {
        Box box = null;
        boolean partial = false;
        for (Word w : l.words) {
            for (Glyph g : w.glyphs) {
                if (g.math != null) {
                    partial = true;
                    continue;
                }
                Box gb = new Box(g.x, g.top(), g.right(), g.bottom());
                box = box == null ? gb : box.union(gb);
            }
        }
        if (box == null || partial) {
            return box;
        }
        return new Box(l.x, l.top, l.right, l.bottom);
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
