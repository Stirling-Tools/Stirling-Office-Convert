package stirling.software.officeconvert.topdf.docx;

final class LineMetrics {

    private static final float LEADER_ROOM = 1f;

    private LineMetrics() {}

    static void measure(Line line, ParaItems pi, ParaProps pp, float gridPitch) {
        float asc = 0;
        float desc = 0;
        float textAsc = 0;
        float textDesc = 0;
        float labelAsc = 0;
        float lead = 0;
        boolean any = false;
        boolean content = false;
        float breakAsc = 0;
        float breakDesc = 0;
        boolean objects = false;
        float soloAsc = 0;
        float soloTextAsc = 0;
        float soloDesc = 0;
        float baseAsc = 0;
        float baseDesc = 0;
        float runAsc = 0;
        float runDesc = 0;
        int last = lastVisible(line);
        for (int k = 0; k < line.slices.size(); k++) {
            Line.Slice s = line.slices.get(k);
            Item it = s.item;
            Look look = it.look;
            switch (it.kind) {
                case TEXT -> {
                    if (s.to <= s.from) {
                        continue;
                    }
                    any = true;
                    if (k > last || !visible(s)) {
                        continue;
                    }
                    if (it.label) {
                        labelAsc = Math.max(labelAsc, look.ascent() + look.leading());
                    } else {
                        textAsc = Math.max(textAsc, look.ascent() + look.leading());
                        textDesc = Math.max(textDesc, look.descent());
                        content = true;
                    }
                }
                case OBJECT -> {
                    float d = look == null ? 0 : look.descent();
                    float rise = look == null ? 0 : look.rise();
                    float lift = rise;
                    if (it.drawing != null && it.drawing.baselineDepth >= 0) {
                        // An inline equation hangs its own depth below the baseline
                        soloDesc = Math.max(soloDesc, d);
                        d = it.drawing.baselineDepth;
                        lift = rise - d;
                    }
                    soloAsc = Math.max(soloAsc, it.objectHeight - d);
                    if (look != null) {
                        soloTextAsc = Math.max(soloTextAsc, look.ascent() + look.leading());
                    }
                    soloDesc = Math.max(soloDesc, d);
                    baseAsc = Math.max(baseAsc, it.objectHeight + lift);
                    baseDesc = Math.max(baseDesc, -lift);
                    if (look != null) {
                        runAsc = Math.max(runAsc, look.ascent() - rise + look.leading());
                        runDesc = Math.max(runDesc, look.descent() + rise);
                    }
                    objects = true;
                    any = true;
                }
                case TAB, BREAK -> {
                    any = true;
                    // A line break sizes only a line that holds nothing else
                    if (it.kind == Item.Kind.BREAK && !it.label) {
                        if (look != null && k <= last) {
                            breakAsc = Math.max(breakAsc, look.ascent() + look.leading());
                            breakDesc = Math.max(breakDesc, look.descent());
                        }
                        continue;
                    }
                    // A plain tab takes no part in the line height; only its leader, if any, is text
                    boolean sized = it.kind == Item.Kind.BREAK
                            || s.tab != null && s.tab.leader() != 0 && s.w > LEADER_ROOM || look != null && inked(look);
                    if (look != null && k <= last && sized) {
                        if (it.label) {
                            labelAsc = Math.max(labelAsc, look.ascent() + look.leading());
                        } else {
                            textAsc = Math.max(textAsc, look.ascent() + look.leading());
                            textDesc = Math.max(textDesc, look.descent());
                            content = true;
                        }
                    }
                }
                default -> {
                }
            }
        }
        line.objectsOnBaseline = objects && content;
        if (objects && content) {
            // Beside text an inline object stands on the baseline; its run's font still sizes the line
            asc = Math.max(asc, baseAsc);
            desc = Math.max(desc, baseDesc);
            textAsc = Math.max(textAsc, runAsc);
            textDesc = Math.max(textDesc, runDesc);
        } else if (objects) {
            asc = Math.max(asc, soloAsc);
            textAsc = Math.max(textAsc, soloTextAsc);
            textDesc = Math.max(textDesc, soloDesc);
            content = true;
        }
        if (!content && (breakAsc > 0 || breakDesc > 0)) {
            textAsc = Math.max(textAsc, breakAsc);
            textDesc = Math.max(textDesc, breakDesc);
            content = true;
        }
        if (!any || last < 0) {
            // Spaces and plain tabs do not size a line; a line of nothing else takes the paragraph mark
            Look m = pi.markLook;
            textAsc = Math.max(any ? 0 : Math.max(textAsc, labelAsc), m.ascent() + m.leading());
            textDesc = Math.max(any ? 0 : textDesc, m.descent());
            labelAsc = 0;
        } else if (!content) {
            // A line holding only a list label also takes the paragraph mark, as an empty numbered paragraph does
            Look m = pi.markLook;
            textAsc = Math.max(textAsc, m.ascent() + m.leading());
            textDesc = Math.max(textDesc, m.descent());
        }
        float plainAsc = Math.max(asc, textAsc);
        asc = Math.max(plainAsc, labelAsc);
        desc = Math.max(desc, textDesc);
        line.ascent = asc;
        line.descent = desc;
        line.leading = lead;
        float natural = asc + desc + lead;
        float h;
        ParaProps.Rule rule = pp.lineRule == null ? ParaProps.Rule.AUTO : pp.lineRule;
        float value = pp.line == null ? 1 : pp.line;
        switch (rule) {
            case EXACT -> h = value;
            case AT_LEAST -> h = Math.max(natural, value);
            default -> {
                // A list label's height is not multiplied with the text's
                h = multiple(natural, textAsc > 0 ? textAsc + textDesc : labelAsc + textDesc, value);
                if (gridPitch > 0 && !Boolean.FALSE.equals(pp.snapToGrid)) {
                    float lines = (float) Math.ceil(h / gridPitch - 0.05f);
                    h = Math.max(1, lines) * gridPitch;
                }
            }
        }
        line.height = Math.max(0.5f, h);
        boolean grid = gridPitch > 0 && !Boolean.FALSE.equals(pp.snapToGrid);
        line.slack = rule == ParaProps.Rule.AUTO && value > 1 && !grid ? Math.max(0, h - natural) : 0;
        if (rule == ParaProps.Rule.EXACT) {
            line.baseline = h * 0.8f;
        } else if (rule == ParaProps.Rule.AUTO && gridPitch > 0 && !Boolean.FALSE.equals(pp.snapToGrid)) {
            line.baseline = (h - natural) / 2 + lead + asc;
        } else if (rule == ParaProps.Rule.AUTO && value >= 1) {
            line.baseline = asc + lead;
        } else {
            line.baseline = h - desc;
        }
    }

    private static int lastVisible(Line line) {
        for (int k = line.slices.size() - 1; k >= 0; k--) {
            Line.Slice s = line.slices.get(k);
            Item it = s.item;
            boolean visible = switch (it.kind) {
                case TEXT -> visible(s);
                case TAB -> s.tab != null && s.tab.leader() != 0 || it.look != null && inked(it.look);
                case OBJECT, BREAK -> true;
                default -> false;
            };
            if (visible) {
                return k;
            }
        }
        return -1;
    }

    private static boolean visible(Line.Slice s) {
        Item it = s.item;
        return s.to > s.from && (it.label || !s.text().isBlank() || inked(it.look));
    }

    private static boolean inked(Look look) {
        return look.underline() != null || look.strike() || look.highlight() != null || look.shading() != null;
    }

    // Word widens a line by the multiple of its text height only; an inline picture taller than the text keeps its size
    static float multiple(float natural, float text, float value) {
        if (value > 1 && text > 0 && text < natural) {
            return natural + (value - 1) * text;
        }
        return natural * value;
    }
}
