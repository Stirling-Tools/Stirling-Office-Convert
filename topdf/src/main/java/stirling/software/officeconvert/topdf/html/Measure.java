package stirling.software.officeconvert.topdf.html;

import java.util.List;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.RunStyle;

final class Measure {

    private Measure() {}

    static float advance(char c, RunStyle s) {
        float em;
        if (c == ' ' || c == ' ') {
            em = 0.278f;
        } else if ("iljtfI.,:;'|!".indexOf(c) >= 0) {
            em = 0.28f;
        } else if ("mwMW".indexOf(c) >= 0) {
            em = 0.86f;
        } else if (Character.isUpperCase(c)) {
            em = 0.68f;
        } else if (Character.isDigit(c)) {
            em = 0.556f;
        } else if (Character.isLetter(c) && c > 0x2E80) {
            em = 1f;
        } else {
            em = 0.52f;
        }
        return em * s.size() * (s.bold() ? 1.07f : 1f) + s.spacing();
    }

    static float width(String text, RunStyle s) {
        float w = 0;
        for (int i = 0; i < text.length(); i++) {
            w += advance(text.charAt(i), s);
        }
        return w;
    }

    static float unwrapped(List<Paragraph> paragraphs) {
        float widest = 0;
        for (Paragraph p : paragraphs) {
            float line = 0;
            for (Inline in : p.inlines) {
                if (in instanceof Inline.Text t) {
                    line += width(t.text(), t.style());
                } else if (in instanceof Inline.Image img && img.picture().wrap == Picture.Wrap.INLINE) {
                    line += img.picture().width;
                } else if (in instanceof Inline.Break) {
                    widest = Math.max(widest, line);
                    line = 0;
                }
            }
            widest = Math.max(widest, line + Math.max(0, p.indentLeft) + Math.max(0, p.indentRight)
                    + Math.max(0, p.indentFirst));
        }
        return widest;
    }

    static float longestWord(List<Paragraph> paragraphs) {
        float widest = 0;
        for (Paragraph p : paragraphs) {
            for (Inline in : p.inlines) {
                if (in instanceof Inline.Text t) {
                    for (String word : t.text().split("[ \u00a0\t\n]+")) {
                        widest = Math.max(widest, width(word, t.style()));
                    }
                } else if (in instanceof Inline.Image img && img.picture().wrap == Picture.Wrap.INLINE) {
                    widest = Math.max(widest, img.picture().width);
                }
            }
        }
        return widest;
    }

    static float height(List<Paragraph> paragraphs, float width) {
        float total = 0;
        for (Paragraph p : paragraphs) {
            total += p.spaceBefore + height(p, Math.max(12, width - p.indentLeft - p.indentRight));
        }
        return total;
    }

    private static float height(Paragraph p, float width) {
        float total = 0;
        float x = Math.max(0, p.indentFirst);
        float lineSize = 0;
        float tallest = 0;
        boolean any = false;
        for (Inline in : p.inlines) {
            if (in instanceof Inline.Text t) {
                lineSize = Math.max(lineSize, t.style().size());
                for (String word : t.text().split("(?<= )")) {
                    float w = width(word, t.style());
                    if (x + w > width && x > 0) {
                        total += line(p, lineSize, tallest);
                        tallest = 0;
                        x = 0;
                    }
                    x += w;
                    any = true;
                }
            } else if (in instanceof Inline.Image img && img.picture().wrap == Picture.Wrap.INLINE) {
                float w = img.picture().width;
                if (x + w > width && x > 0) {
                    total += line(p, lineSize, tallest);
                    tallest = 0;
                    x = 0;
                }
                x += w;
                tallest = Math.max(tallest, img.picture().height);
                any = true;
            } else if (in instanceof Inline.Break) {
                total += line(p, lineSize, tallest);
                tallest = 0;
                x = 0;
                any = false;
            }
        }
        if (any || total == 0) {
            total += line(p, lineSize, tallest);
        }
        return total;
    }

    private static float line(Paragraph p, float size, float tallest) {
        float natural = Math.max(tallest, (size > 0 ? size : 12) * 1.15f);
        return switch (p.lineRule) {
            case EXACT -> p.lineHeight;
            case AT_LEAST -> Math.max(p.lineHeight, natural);
            case AUTO -> natural;
        };
    }
}
