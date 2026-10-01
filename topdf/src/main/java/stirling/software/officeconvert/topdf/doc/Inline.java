package stirling.software.officeconvert.topdf.doc;

import java.io.IOException;
import java.util.NavigableMap;

import org.apache.poi.hwpf.usermodel.CharacterProperties;

final class Inline {

    private final Story story;

    private final Fields fields = new Fields();

    private StringBuilder out;

    private String props;

    private final StringBuilder text = new StringBuilder();

    private Fields.Frame link;

    Inline(Story story) {
        this.story = story;
    }

    void write(int start, int end, int paragraphEnd, int istd, StringBuilder out) throws IOException {
        this.out = out;
        Conv c = story.c;
        CharSequence src = c.src.text;
        NavigableMap<Integer, StringBuilder> marks = story.kind == Story.Kind.MAIN ? c.marks().within(start,
                paragraphEnd) : null;
        for (Source.Segment seg : c.src.segments(start, end, istd)) {
            CharacterProperties chp = seg.chp();
            String rPr = c.src.runs.props(chp, seg.sprms());
            boolean special = chp.isFSpec();
            boolean deleted = chp.isFRMarkDel();
            for (int cp = seg.start(); cp < seg.end() && cp < src.length(); cp++) {
                if (marks != null && !marks.isEmpty()) {
                    StringBuilder m = marks.get(cp);
                    if (m != null) {
                        flush();
                        out.append(m);
                    }
                }
                char ch = src.charAt(cp);
                switch (ch) {
                    case '\u0013' -> fields.begin();
                    case '\u0014' -> separate(rPr);
                    case '\u0015' -> end(rPr);
                    default -> {
                        if (!fields.visible()) {
                            fields.code(ch);
                        } else if (!deleted) {
                            character(ch, cp, special, chp, rPr);
                        }
                    }
                }
            }
            c.checkpoint();
        }
        flush();
        if (marks != null && end < paragraphEnd) {
            for (StringBuilder m : c.marks().within(end, paragraphEnd).values()) {
                out.append(m);
            }
        }
        closeLink();
        for (Fields.Frame f : fields.openComplex()) {
            element(props == null ? "" : props, "<w:fldChar w:fldCharType=\"end\"/>");
            f.open = false;
        }
        flush();
        this.out = null;
    }

    private void character(char ch, int cp, boolean special, CharacterProperties chp, String rPr) throws IOException {
        Fields.Frame l = fields.link();
        if (l != link) {
            flush();
            closeLink();
            openLink(l);
        }
        switch (ch) {
            case '\t' -> element(rPr, "<w:tab/>");
            case '\u000B' -> element(rPr, "<w:br/>");
            case '\u000C' -> element(rPr, "<w:br w:type=\"page\"/>");
            case '\u000E' -> element(rPr, "<w:br w:type=\"column\"/>");
            case '\u001E' -> element(rPr, "<w:noBreakHyphen/>");
            case '\u001F' -> element(rPr, "<w:softHyphen/>");
            default -> {
                if (special) {
                    special(ch, cp, chp, rPr);
                } else if (ch >= 0x20) {
                    if (!rPr.equals(props)) {
                        flush();
                        props = rPr;
                    }
                    text.append(ch);
                }
            }
        }
    }

    private void special(char ch, int cp, CharacterProperties chp, String rPr) throws IOException {
        Conv c = story.c;
        switch (ch) {
            case '\u0001' -> {
                String xml = c.drawings.inline(chp, story.rels);
                if (xml != null) {
                    element(rPr, xml);
                }
            }
            case '\u0008' -> {
                String xml = c.drawings.anchor(story, cp);
                if (xml != null) {
                    element(rPr, xml);
                }
            }
            case '\u0002' -> {
                String xml = c.notes == null ? null : c.notes.reference(story.kind, cp);
                if (xml != null) {
                    element(rPr, xml);
                }
            }
            case '\u0003' -> element(rPr, "<w:separator/>");
            case '\u0004' -> element(rPr, "<w:continuationSeparator/>");
            case '(' -> {
                String font = c.src.runs.font(chp.getFtcSym());
                int code = chp.getXchSym();
                if (code != 0) {
                    element(rPr, "<w:sym" + (font == null ? "" : " w:font=\"" + Xml.esc(font) + "\"")
                            + " w:char=\"" + String.format("%04X", code & 0xFFFF) + "\"/>");
                } else {
                    plain(ch, rPr);
                }
            }
            default -> {
                if (ch >= 0x20) {
                    plain(ch, rPr);
                }
            }
        }
    }

    private void plain(char ch, String rPr) {
        if (!rPr.equals(props)) {
            flush();
            props = rPr;
        }
        text.append(ch);
    }

    private void separate(String rPr) {
        Fields.Frame f = fields.separate();
        if (f == null || f.complex == null || !fields.visible()) {
            return;
        }
        Fields.Frame l = fields.link();
        if (l != link) {
            flush();
            closeLink();
            openLink(l);
        }
        element(rPr, "<w:fldChar w:fldCharType=\"begin\"/>");
        element(rPr, "<w:instrText xml:space=\"preserve\"> " + Xml.esc(f.complex) + " </w:instrText>");
        element(rPr, "<w:fldChar w:fldCharType=\"separate\"/>");
        f.open = true;
    }

    private void end(String rPr) {
        Fields.Frame f = fields.end();
        if (f != null && f.open) {
            element(rPr, "<w:fldChar w:fldCharType=\"end\"/>");
            f.open = false;
        }
        if (fields.link() != link) {
            flush();
            closeLink();
        }
    }

    private void element(String rPr, String xml) {
        flush();
        out.append("<w:r>");
        if (!rPr.isEmpty()) {
            out.append("<w:rPr>").append(rPr).append("</w:rPr>");
        }
        out.append(xml).append("</w:r>");
    }

    private void flush() {
        if (text.isEmpty()) {
            return;
        }
        out.append("<w:r>");
        if (props != null && !props.isEmpty()) {
            out.append("<w:rPr>").append(props).append("</w:rPr>");
        }
        out.append("<w:t xml:space=\"preserve\">");
        Xml.text(out, text);
        out.append("</w:t></w:r>");
        text.setLength(0);
    }

    private void openLink(Fields.Frame l) {
        link = l;
        if (l == null) {
            return;
        }
        if (l.url != null) {
            out.append("<w:hyperlink r:id=\"").append(story.rels.link(l.url)).append("\">");
        } else {
            out.append("<w:hyperlink w:anchor=\"").append(Xml.esc(l.anchor)).append("\">");
        }
    }

    private void closeLink() {
        if (link != null) {
            out.append("</w:hyperlink>");
            link = null;
        }
    }
}
