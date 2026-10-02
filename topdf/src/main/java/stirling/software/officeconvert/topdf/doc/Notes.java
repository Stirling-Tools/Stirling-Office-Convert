package stirling.software.officeconvert.topdf.doc;

import java.io.IOException;

import org.apache.poi.hwpf.usermodel.Range;

final class Notes {

    static final int MAX_NOTES = 50_000;

    private final Conv c;

    private final org.apache.poi.hwpf.usermodel.Notes footnotes;

    private final org.apache.poi.hwpf.usermodel.Notes endnotes;

    Notes(Conv c) {
        this.c = c;
        this.footnotes = safe(() -> c.src.doc.getFootnotes());
        this.endnotes = safe(() -> c.src.doc.getEndnotes());
    }

    private interface Get {
        org.apache.poi.hwpf.usermodel.Notes get();
    }

    private static org.apache.poi.hwpf.usermodel.Notes safe(Get g) {
        try {
            org.apache.poi.hwpf.usermodel.Notes n = g.get();
            return n == null || n.getNotesCount() == 0 ? null : n;
        } catch (RuntimeException e) {
            return null;
        }
    }

    boolean hasFootnotes() {
        return footnotes != null;
    }

    boolean hasEndnotes() {
        return endnotes != null;
    }

    String reference(Story.Kind kind, int cp) {
        switch (kind) {
            case FOOTNOTE -> {
                return "<w:footnoteRef/>";
            }
            case ENDNOTE -> {
                return "<w:endnoteRef/>";
            }
            case MAIN -> {
                int f = index(footnotes, cp);
                if (f >= 0) {
                    return "<w:footnoteReference w:id=\"" + (f + 1) + "\"/>";
                }
                int e = index(endnotes, cp);
                if (e >= 0) {
                    return "<w:endnoteReference w:id=\"" + (e + 1) + "\"/>";
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private static int index(org.apache.poi.hwpf.usermodel.Notes notes, int cp) {
        if (notes == null) {
            return -1;
        }
        try {
            int i = notes.getNoteIndexByAnchorPosition(cp);
            return i < MAX_NOTES ? i : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    void write(boolean foot) throws IOException {
        org.apache.poi.hwpf.usermodel.Notes notes = foot ? footnotes : endnotes;
        if (notes == null) {
            return;
        }
        String tag = foot ? "footnote" : "endnote";
        Rels rels = new Rels();
        StringBuilder b = new StringBuilder(Xml.HEAD).append("<w:").append(tag).append('s').append(Xml.NAMESPACES)
                .append('>');
        b.append("<w:").append(tag).append(" w:type=\"separator\" w:id=\"-1\"><w:p><w:pPr><w:spacing w:after=\"0\"")
                .append(" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr><w:r><w:separator/></w:r></w:p></w:").append(tag)
                .append('>');
        b.append("<w:").append(tag).append(" w:type=\"continuationSeparator\" w:id=\"0\"><w:p><w:pPr><w:spacing")
                .append(" w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr><w:r><w:continuationSeparator/>")
                .append("</w:r></w:p></w:").append(tag).append('>');
        Range story;
        try {
            story = foot ? c.src.doc.getFootnoteRange() : c.src.doc.getEndnoteRange();
        } catch (RuntimeException e) {
            return;
        }
        int base = story.getStartOffset();
        Story s = new Story(c, rels, foot ? Story.Kind.FOOTNOTE : Story.Kind.ENDNOTE, base, null);
        int n = Math.min(MAX_NOTES, notes.getNotesCount());
        for (int i = 0; i < n; i++) {
            c.checkpoint();
            int start;
            int end;
            try {
                start = base + notes.getNoteTextStartOffset(i);
                end = Math.min(story.getEndOffset(), base + notes.getNoteTextEndOffset(i));
            } catch (RuntimeException e) {
                continue;
            }
            b.append("<w:").append(tag).append(" w:id=\"").append(i + 1).append("\">");
            int mark = b.length();
            s.write(start, Stories.trim(c.src.text, start, end), b);
            if (b.length() == mark) {
                b.append("<w:p/>");
            }
            b.append("</w:").append(tag).append('>');
            if (c.full(b)) {
                break;
            }
        }
        b.append("</w:").append(tag).append("s>");
        c.zip.put("word/" + tag + "s.xml", b);
        if (!rels.isEmpty()) {
            c.zip.put("word/_rels/" + tag + "s.xml.rels", rels.part());
        }
    }
}
