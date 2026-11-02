package stirling.software.officeconvert.layout;

import stirling.software.officeconvert.extract.Glyph;

record MathToken(String text, int level, Glyph source, Object bar, float size, Kind kind, boolean linear) {

    enum Kind {
        TEXT,
        FRAC_OPEN,
        FRAC_MID,
        FRAC_CLOSE,
        ROOT_OPEN,
        ROOT_MID,
        ROOT_CLOSE,
        NARY_OPEN,
        NARY_SUB,
        NARY_SUP,
        NARY_CLOSE,
        LIM_OPEN,
        LIM_MID,
        LIM_CLOSE,
        STACK_OPEN,
        STACK_ROW,
        STACK_CLOSE
    }

    MathToken(String text, int level, Glyph source, Object bar, float size) {
        this(text, level, source, bar, size, Kind.TEXT, false);
    }

    static MathToken marker(Kind kind, float size) {
        return new MathToken("", 0, null, null, size, kind, false);
    }

    static MathToken linear(String text, Object bar, Glyph source, float size) {
        return new MathToken(text, 0, source, bar, size, Kind.TEXT, true);
    }

    boolean space() {
        return kind == Kind.TEXT && source == null && bar == null && text.equals(" ");
    }

    boolean marker() {
        return kind != Kind.TEXT;
    }

    MathToken withLevel(int l) {
        return new MathToken(text, l, source, bar, size, kind, linear);
    }
}
