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

    int level = -1;

    int start;

    float objectWidth;

    float objectHeight;

    float extra;

    private float fullWidth = -1;

    Item(Kind kind) {
        this.kind = kind;
    }

    // The part from..to of a text item, with its look and links; notes stay on the first part, spacing on the last
    Item piece(int from, int to) {
        Item p = new Item(kind);
        p.text = text.substring(from, to);
        p.look = look;
        p.link = link;
        p.field = field;
        p.label = label;
        p.lang = lang;
        p.rtl = rtl;
        p.shaped = shaped;
        p.level = level;
        p.start = start + from;
        p.note = from == 0 ? note : null;
        p.extra = to == text.length() ? extra : 0;
        return p;
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
        return (shaped ? measure(text.substring(from, to)) : look.style().width(text, from, to)) + tail;
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
