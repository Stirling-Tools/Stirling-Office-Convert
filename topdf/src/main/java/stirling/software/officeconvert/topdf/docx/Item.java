package stirling.software.officeconvert.topdf.docx;

import stirling.software.officeconvert.topdf.font.GlyphRun;

final class Item {

    enum Kind {
        TEXT,
        TAB,
        BREAK,
        OBJECT,
        ANCHOR,
        BOOKMARK,
        NOTE
    }

    final Kind kind;

    String text = "";

    Look look;

    Drawing drawing;

    String breakType;

    String bookmark;

    Inline.NoteRef note;

    Inline.PTab ptab;

    Inline.Link link;

    String field;

    boolean label;

    String lang;

    boolean rtl;

    boolean shaped;

    int start;

    float objectWidth;

    float objectHeight;

    float extra;

    private float fullWidth = -1;

    Item(Kind kind) {
        this.kind = kind;
    }

    int length() {
        return text.length();
    }

    int end() {
        return start + text.length();
    }

    float width(int from, int to) {
        if (kind == Kind.OBJECT) {
            return from == to ? 0 : objectWidth;
        }
        if (kind != Kind.TEXT || from >= to) {
            return 0;
        }
        float tail = to == text.length() ? extra : 0;
        if (from == 0 && to == text.length()) {
            if (fullWidth < 0) {
                fullWidth = measure(text);
            }
            return fullWidth + tail;
        }
        return measure(text.substring(from, to)) + tail;
    }

    private float measure(String s) {
        if (shaped) {
            GlyphRun run = look.face().shape(s, rtl);
            return run.width(look.size()) * look.style().horizontalScale() / 100f
                    + look.style().charSpacing() * s.codePointCount(0, s.length());
        }
        return look.style().width(s);
    }

    float width() {
        return width(0, text.length());
    }
}
