package stirling.software.officeconvert.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import stirling.software.officeconvert.layout.Marker;
import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.RunStyle;

final class ListTracker {

    record Slot(int numId, int level) {}

    private static final float LEVEL_TOLERANCE = 3f;

    private Numbering.Instance current;
    private final List<Float> levelX = new ArrayList<>();
    private final List<Marker.Kind> levelKind = new ArrayList<>();
    private final List<String> levelBullet = new ArrayList<>();
    private final List<Integer> levelValue = new ArrayList<>();

    Slot place(Marker m, float markerX, Numbering numbering, RunStyle markerStyle, float textX, float unused) {
        int level = current == null ? -1 : levelFor(markerX);
        if (level < 0 && current != null && markerX > levelX.getLast() + LEVEL_TOLERANCE && levelX.size() < 9) {
            level = levelX.size();
        }
        if (current == null || level < 0) {
            start(numbering);
            level = 0;
        }
        Marker.Kind kind = m.kind();
        int value = m.value();
        if (level < levelKind.size()) {
            Marker.Kind defined = levelKind.get(level);
            if (defined != kind) {
                Marker re = reinterpret(m, defined);
                if (re == null) {
                    return restartWith(m, markerX, numbering, markerStyle, textX);
                }
                kind = re.kind();
                value = re.value();
            }
            if (kind == Marker.Kind.BULLET) {
                if (!levelBullet.get(level).equals(m.text())) {
                    return restartWith(m, markerX, numbering, markerStyle, textX);
                }
            } else {
                int last = levelValue.get(level);
                int expected = last == 0 ? current.starts[level] : last + 1;
                if (value != expected || last == 0 && !sameAffixes(level, m)) {
                    return restartWith(m, markerX, numbering, markerStyle, textX);
                }
            }
        } else {
            Marker fresh = firstOfLevel(m);
            kind = fresh.kind();
            value = fresh.value();
            define(level, fresh, markerX, markerStyle, textX);
        }
        levelValue.set(level, value);
        for (int l = level + 1; l < levelValue.size(); l++) {
            levelValue.set(l, 0);
        }
        return new Slot(current.numId, level);
    }

    void body(float x, boolean heading) {
        if (current == null) {
            return;
        }
        if (heading || x <= levelX.getFirst() + LEVEL_TOLERANCE) {
            current = null;
        }
    }

    private boolean sameAffixes(int level, Marker m) {
        Numbering.Level def = current.definition.levels[level];
        return def == null || def.text().equals(m.prefix() + "%" + (level + 1) + m.suffix());
    }

    private Slot restartWith(Marker m, float markerX, Numbering numbering, RunStyle style, float textX) {
        start(numbering);
        Marker fresh = firstOfLevel(m);
        if (fresh.kind() != Marker.Kind.BULLET) {
            current.starts[0] = fresh.value();
        }
        define(0, fresh, markerX, style, textX);
        levelValue.set(0, fresh.value());
        return new Slot(current.numId, 0);
    }

    private void start(Numbering numbering) {
        current = numbering.create();
        levelX.clear();
        levelKind.clear();
        levelBullet.clear();
        levelValue.clear();
    }

    private void define(int level, Marker m, float markerX, RunStyle style, float textX) {
        while (levelX.size() <= level) {
            levelX.add(markerX);
            levelKind.add(m.kind());
            levelBullet.add(m.text());
            levelValue.add(0);
        }
        levelX.set(level, markerX);
        levelKind.set(level, m.kind());
        levelBullet.set(level, m.text());
        String format = switch (m.kind()) {
            case BULLET -> "bullet";
            case DECIMAL -> "decimal";
            case LOWER_LETTER -> "lowerLetter";
            case UPPER_LETTER -> "upperLetter";
            case LOWER_ROMAN -> "lowerRoman";
            case UPPER_ROMAN -> "upperRoman";
        };
        String text = m.kind() == Marker.Kind.BULLET ? m.text() : m.prefix() + "%" + (level + 1) + m.suffix();
        if (m.kind() != Marker.Kind.BULLET && level > 0) {
            current.starts[level] = m.value();
        }
        if (level == 0 && m.kind() != Marker.Kind.BULLET) {
            current.starts[0] = m.value();
        }
        current.definition.levels[level] =
                new Numbering.Level(format, text, style, textX, Math.max(0, textX - markerX));
    }

    private int levelFor(float markerX) {
        for (int i = 0; i < levelX.size(); i++) {
            if (Math.abs(levelX.get(i) - markerX) <= LEVEL_TOLERANCE) {
                return i;
            }
        }
        return -1;
    }

    private static Marker firstOfLevel(Marker m) {
        if ((m.kind() == Marker.Kind.LOWER_ROMAN || m.kind() == Marker.Kind.UPPER_ROMAN)
                && letterBody(m).length() == 1 && !letterBody(m).equalsIgnoreCase("i")) {
            char c = letterBody(m).charAt(0);
            boolean lower = Character.isLowerCase(c);
            return new Marker(lower ? Marker.Kind.LOWER_LETTER : Marker.Kind.UPPER_LETTER, m.text(),
                    Character.toLowerCase(c) - 'a' + 1, m.prefix(), m.suffix());
        }
        return m;
    }

    private static Marker reinterpret(Marker m, Marker.Kind wanted) {
        String body = letterBody(m);
        boolean lower = !body.isEmpty() && Character.isLowerCase(body.charAt(0));
        if ((wanted == Marker.Kind.LOWER_LETTER || wanted == Marker.Kind.UPPER_LETTER) && body.length() == 1) {
            boolean wantLower = wanted == Marker.Kind.LOWER_LETTER;
            if (lower != wantLower) {
                return null;
            }
            return new Marker(wanted, m.text(), Character.toLowerCase(body.charAt(0)) - 'a' + 1, m.prefix(), m.suffix());
        }
        if (wanted == Marker.Kind.LOWER_ROMAN || wanted == Marker.Kind.UPPER_ROMAN) {
            boolean wantLower = wanted == Marker.Kind.LOWER_ROMAN;
            int v = Marker.parse(m.prefix() + body.toLowerCase(Locale.ROOT) + m.suffix()) != null
                    ? romanValue(body) : 0;
            if (v > 0 && lower == wantLower) {
                return new Marker(wanted, m.text(), v, m.prefix(), m.suffix());
            }
        }
        return null;
    }

    private static int romanValue(String body) {
        Marker p = Marker.parse(body.toLowerCase(Locale.ROOT) + ".");
        return p != null && p.kind() == Marker.Kind.LOWER_ROMAN ? p.value() : body.equalsIgnoreCase("i") ? 1 : 0;
    }

    private static String letterBody(Marker m) {
        String t = m.text();
        return t.substring(m.prefix().length(), t.length() - m.suffix().length());
    }
}
