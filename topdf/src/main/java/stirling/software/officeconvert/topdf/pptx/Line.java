package stirling.software.officeconvert.topdf.pptx;

import stirling.software.officeconvert.topdf.font.FontFace;

final class Line {

    final Para para;

    final Chars chars;

    final int start;

    final int end;

    final int contentEnd;

    final boolean first;

    final boolean forced;

    boolean last;

    float x;

    float right;

    float width;

    float ascent;

    float descent;

    float tail;

    float size;

    float baseline;

    float shift;

    float extraPerSpace;

    float column;

    Line(Para para, Chars chars, int start, int end, boolean first, boolean forced) {
        this.para = para;
        this.chars = chars;
        this.start = start;
        this.end = end;
        this.first = first;
        this.forced = forced;
        int c = end;
        while (c > start && (chars.codePoints[c - 1] == '\n' || Chars.space(chars.codePoints[c - 1])
                && chars.codePoints[c - 1] != '\t')) {
            c--;
        }
        this.contentEnd = c;
    }

    boolean hyphenated() {
        return contentEnd > start && contentEnd == end && !last
                && chars.codePoints[contentEnd - 1] == FontFace.SOFT_HYPHEN;
    }

    boolean empty() {
        return contentEnd <= start;
    }

    int spaces() {
        int n = 0;
        for (int i = start; i < contentEnd; i++) {
            if (chars.codePoints[i] == ' ') {
                n++;
            }
        }
        return n;
    }
}
