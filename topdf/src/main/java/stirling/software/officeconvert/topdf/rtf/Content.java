package stirling.software.officeconvert.topdf.rtf;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

final class Content {

    record Part(String name, boolean footer, Story story) {}

    record NoteOut(int id, boolean endnote, String xml) {}

    static final long STORY_LIMIT = 256L << 20;

    private static final String TOKEN = String.valueOf(ParaBuilder.TOKEN);

    private final Doc doc;

    private final PropsXml props;

    final Story body;

    final SectProps.Page docPage = new SectProps.Page();

    SectProps sect = new SectProps(docPage);

    private boolean sectStarted;

    private RowProps row = new RowProps();

    private RowProps nestRow = new RowProps();

    private Border target;

    private int noteIds = 1;

    private int pendingNote;

    private String lastSect;

    private String finalSect;

    final List<Part> parts = new ArrayList<>();

    final List<NoteOut> notes = new ArrayList<>();

    final Rels notesRels = new Rels("rId");

    Content(Doc doc, PropsXml props, Story body) {
        this.doc = doc;
        this.props = props;
        this.body = body;
    }

    private ParaBuilder para(Group g) {
        return g.listText ? g.story.listText : g.story.para;
    }

    void text(Group g, String s) {
        if (g.story != null) {
            para(g).text(s, props.rPr(g.chp), g.wrap);
        }
    }

    void item(Group g, String inner) {
        if (g.story != null) {
            para(g).item(inner, props.rPr(g.chp), g.wrap);
        }
    }

    void field(Group g, String instr) {
        if (g.story != null) {
            para(g).raw("<w:fldSimple w:instr=\"" + instr + "\"><w:r>" + props.rPr(g.chp)
                    + "<w:t>1</w:t></w:r></w:fldSimple>");
        }
    }

    boolean word(RtfReader r, Group g, String w, int p, boolean has) throws IOException {
        switch (w) {
            case "par" -> endPara(g, null, g.pap.tableDepth());
            case "sect" -> section(g);
            case "page" -> item(g, "<w:br w:type=\"page\"/>");
            case "column" -> item(g, "<w:br w:type=\"column\"/>");
            case "line" -> item(g, "<w:br/>");
            case "tab" -> item(g, "<w:tab/>");
            case "cell" -> cell(g, false);
            case "nestcell" -> cell(g, true);
            case "row" -> row(g, false);
            case "nestrow" -> row(g, true);
            case "pard" -> {
                g.pap.reset();
                target = null;
            }
            case "trowd" -> {
                if (g.nestProps) {
                    nestRow = new RowProps();
                } else {
                    row = new RowProps();
                }
                target = null;
            }
            case "sectd" -> {
                SectProps next = new SectProps(docPage);
                next.headers.putAll(sect.headers);
                next.footers.putAll(sect.footers);
                sect = next;
                sectStarted = true;
            }
            case "chftn" -> footnoteMark(r, g);
            case "chpgn" -> field(g, "PAGE");
            case "ftnalt" -> {
                Group n = r.noteGroup();
                if (n != null) {
                    n.note.endnote = true;
                }
            }
            case "emdash" -> r.chr('—');
            case "endash" -> r.chr('–');
            case "emspace" -> r.chr(' ');
            case "enspace" -> r.chr(' ');
            case "qmspace" -> r.chr(' ');
            case "bullet" -> r.chr('•');
            case "lquote" -> r.chr('‘');
            case "rquote" -> r.chr('’');
            case "ldblquote" -> r.chr('“');
            case "rdblquote" -> r.chr('”');
            case "zwj" -> r.chr('‍');
            case "zwnj" -> r.chr('‌');
            case "zwbo" -> r.chr('​');
            case "ltrmark" -> r.chr('‎');
            case "rtlmark" -> r.chr('‏');
            default -> {
                return property(g, w, p, has);
            }
        }
        return true;
    }

    private boolean property(Group g, String w, int p, boolean has) {
        if (CharWords.apply(g.chp, w, p, has)) {
            return true;
        }
        if (border(g, w, p, has)) {
            return true;
        }
        if (ParaWords.apply(g.pap, w, p, has)) {
            return true;
        }
        Border b = TableWords.apply(g.nestProps ? nestRow : row, w, p);
        if (b != null) {
            if (b != TableWords.HANDLED) {
                target = b;
            }
            return true;
        }
        if (sect.apply(w, p, has)) {
            return true;
        }
        return document(w, p, has);
    }

    private boolean border(Group g, String w, int p, boolean has) {
        Border b;
        switch (w) {
            case "brdrt" -> b = g.pap.top = new Border();
            case "brdrb" -> b = g.pap.bottom = new Border();
            case "brdrl" -> b = g.pap.leftBorder = new Border();
            case "brdrr" -> b = g.pap.rightBorder = new Border();
            case "brdrbtw" -> b = g.pap.between = new Border();
            case "box" -> {
                b = new Border();
                g.pap.top = b;
                g.pap.bottom = b;
                g.pap.leftBorder = b;
                g.pap.rightBorder = b;
            }
            case "brdrbar" -> {
                target = new Border();
                return true;
            }
            case "pgbrdrt", "pgbrdrb", "pgbrdrl", "pgbrdrr" -> {
                target = new Border();
                sect.pageBorders.put(w.charAt(6), target);
                return true;
            }
            case "chbrdr" -> {
                g.chp.border = new Border();
                g.chp.mark(CharProps.BORDER);
                target = g.chp.border;
                return true;
            }
            default -> {
                if (target != null && target.apply(w, p, has)) {
                    return true;
                }
                return Border.style(w) != null || "brdrw".equals(w) || "brdrcf".equals(w) || "brsp".equals(w);
            }
        }
        g.pap.mark(ParaProps.BORDERS);
        target = b;
        return true;
    }

    private boolean document(String w, int p, boolean has) {
        if (docPage.apply(w, p)) {
            if (!sectStarted) {
                sect.page.apply(w, p);
            }
            return true;
        }
        switch (w) {
            case "ansi" -> doc.ansi = CodePages.WINDOWS_1252;
            case "mac" -> doc.ansi = orAnsi(CodePages.forCodePage(10000));
            case "pc" -> doc.ansi = orAnsi(CodePages.forCodePage(437));
            case "pca" -> doc.ansi = orAnsi(CodePages.forCodePage(850));
            case "ansicpg" -> doc.ansi = orAnsi(CodePages.forCodePage(p));
            case "deff" -> doc.defaultFont = p;
            case "stshfloch" -> doc.loFont = p;
            case "stshfhich" -> doc.hiFont = p;
            case "stshfdbch" -> doc.eaFont = p;
            case "stshfbi" -> doc.biFont = p;
            case "deftab" -> doc.defaultTab = p > 0 ? p : doc.defaultTab;
            case "widowctrl" -> doc.widowControl = true;
            case "facingp" -> doc.facingPages = true;
            case "hyphauto" -> doc.autoHyphenation = !has || p != 0;
            case "aendnotes" -> doc.endnotesAtSectionEnd = true;
            default -> {
                return false;
            }
        }
        return true;
    }

    private Charset orAnsi(Charset c) {
        return c == null ? doc.ansi : c;
    }

    private boolean numbered(ParaProps p) {
        if (p.has(ParaProps.LIST)) {
            return p.list > 0 && doc.lists.numbered(p.list);
        }
        StyleSheet.Style s = doc.styles.paragraph(p.style);
        for (int i = 0; s != null && i < 32; i++) {
            if (s.pap.has(ParaProps.LIST)) {
                return s.pap.list > 0 && doc.lists.numbered(s.pap.list);
            }
            s = doc.styles.basedOn(s);
        }
        return false;
    }

    private ParaProps listIndent(ParaProps p) {
        if (!doc.libreOffice || !p.has(ParaProps.LEFT) || p.leftsAfterList != 1 || !numbered(p)) {
            return p;
        }
        StyleSheet.Style s = doc.styles.paragraph(p.style);
        for (int i = 0; s != null && i < 32; i++) {
            if (s.pap.has(ParaProps.LEFT)) {
                if (s.pap.left <= 0 || p.left < s.pap.left) {
                    return p;
                }
                ParaProps q = p.copy();
                q.left -= s.pap.left;
                return q;
            }
            s = doc.styles.basedOn(s);
        }
        return p;
    }

    void endPara(Group g, String extra, int depth) throws IOException {
        Story s = g.story;
        if (s == null || g.listText) {
            return;
        }
        String lt = s.listText.takeRuns();
        String prefix = numbered(g.pap) ? "" : lt;
        String pPr = props.pPr(listIndent(g.pap), g.chp, extra, true);
        s.block(s.para.finish(pPr, prefix), depth);
        if (s == body) {
            pendingNote = 0;
        }
    }

    private void cell(Group g, boolean nested) throws IOException {
        if (g.story == null) {
            return;
        }
        int d = Math.max(nested ? 2 : 1, g.pap.tableDepth());
        endPara(g, null, d);
        g.story.endCell(d);
    }

    private void row(Group g, boolean nested) throws IOException {
        Story s = g.story;
        if (s == null) {
            return;
        }
        if (!s.para.empty()) {
            cell(g, nested);
        }
        if (nested) {
            s.endRow(Math.max(2, s.openTables()), nestRow.copy());
        } else {
            s.endRow(1, row.copy());
        }
    }

    private void section(Group g) throws IOException {
        if (g.story != body) {
            endPara(g, null, g.pap.tableDepth());
            return;
        }
        String sectPr = sect.xml(doc.colors);
        ParaProps pap = listIndent(g.pap);
        String with = props.pPr(pap, g.chp, sectPr, true);
        String plain = props.pPr(pap, g.chp, null, true);
        String lt = body.listText.takeRuns();
        String xml = body.para.finish(with, numbered(g.pap) ? "" : lt);
        body.defer(xml, "<w:p>" + plain + xml.substring("<w:p>".length() + with.length()));
        pendingNote = 0;
        lastSect = sectPr;
        SectProps next = new SectProps(sect.page);
        next.columns = sect.columns;
        next.columnSpace = sect.columnSpace;
        next.headerY = sect.headerY;
        next.footerY = sect.footerY;
        sect = next;
    }

    private void footnoteMark(RtfReader r, Group g) {
        if (g.inNote) {
            Group n = r.noteGroup();
            item(g, n != null && n.note.endnote ? "<w:endnoteRef/>" : "<w:footnoteRef/>");
            return;
        }
        pendingNote = ++noteIds;
        item(g, TOKEN + "N" + pendingNote + TOKEN);
    }

    void openNote(Group g) {
        if (g.story == null || g.inNote) {
            g.dest = Dest.SKIP;
            return;
        }
        int id;
        boolean custom = false;
        if (pendingNote > 0) {
            id = pendingNote;
            pendingNote = 0;
        } else {
            id = ++noteIds;
            custom = g.chp.has(CharProps.VERTICAL) && g.chp.vertical == 1;
            g.story.para.item(TOKEN + "N" + id + TOKEN, custom ? props.rPr(g.chp) : superscript(g.chp), g.wrap);
        }
        g.note = new Group.Note(id, custom, g.story);
        g.inNote = true;
        g.story = new Story(notesRels, doc.colors, null, STORY_LIMIT);
        g.pap = new ParaProps();
        g.wrap = null;
        g.listText = false;
    }

    void closeNote(Group done) throws IOException {
        Story s = done.story;
        finishStory(done);
        Group.Note n = done.note;
        String content = s.content();
        String ref = n.endnote ? "<w:endnoteRef/>" : "<w:footnoteRef/>";
        if (!n.custom && !content.contains(ref)) {
            content = withMark(content, "<w:r><w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr>" + ref + "</w:r>");
        }
        notes.add(new NoteOut(n.id, n.endnote, content));
        String reference = "<w:" + (n.endnote ? "endnoteReference" : "footnoteReference")
                + (n.custom ? " w:customMarkFollows=\"1\"" : "") + " w:id=\"" + n.id + "\"/>";
        n.parent.para.replace(TOKEN + "N" + n.id + TOKEN, reference);
    }

    private String superscript(CharProps c) {
        CharProps s = c.copy();
        s.vertical = 1;
        s.mark(CharProps.VERTICAL);
        return props.rPr(s);
    }

    private static String withMark(String content, String run) {
        int p = content.indexOf("<w:p>");
        if (p < 0) {
            return "<w:p>" + run + "</w:p>" + content;
        }
        int at = p + "<w:p>".length();
        if (content.startsWith("<w:pPr>", at)) {
            at = content.indexOf("</w:pPr>", at) + "</w:pPr>".length();
        }
        return content.substring(0, at) + run + content.substring(at);
    }

    void openHeader(Group g, String w) {
        if (g.story != body) {
            g.dest = Dest.SKIP;
            return;
        }
        boolean footer = w.startsWith("footer");
        String type = switch (w.substring(6)) {
            case "l" -> "even";
            case "f" -> "first";
            default -> "default";
        };
        g.header = (footer ? "f" : "h") + type;
        g.story = new Story(new Rels("rId"), doc.colors, null, STORY_LIMIT);
        g.pap = new ParaProps();
        g.wrap = null;
        g.listText = false;
    }

    void closeHeader(Group done) throws IOException {
        finishStory(done);
        boolean footer = done.header.charAt(0) == 'f';
        String type = done.header.substring(1);
        String name = (footer ? "footer" : "header") + (parts.size() + 1) + ".xml";
        parts.add(new Part(name, footer, done.story));
        String rid = body.rels.add(footer ? "footer" : "header", name, false);
        (footer ? sect.footers : sect.headers).put(type, rid);
    }

    void openTextbox(Group g) {
        g.dest = Dest.NORMAL;
        g.textbox = true;
        Rels rels = g.story == null ? new Rels("rId") : g.story.rels;
        g.story = new Story(rels, doc.colors, null, STORY_LIMIT);
        g.pap = new ParaProps();
        g.wrap = null;
        g.listText = false;
    }

    void closeTextbox(Group done) throws IOException {
        finishStory(done);
        if (done.shape != null) {
            done.shape.text = done.story.content();
        }
    }

    private void finishStory(Group done) throws IOException {
        Story s = done.story;
        if (!s.para.empty()) {
            endPara(done, null, done.pap.tableDepth());
        }
        s.finish();
    }

    void finishBody(Group g) throws IOException {
        if (!body.para.empty()) {
            endPara(g, null, g.pap.tableDepth());
        }
        body.finish();
        if (body.endDeferred()) {
            finalSect = lastSect;
        }
    }

    String finalSectPr() {
        return finalSect != null ? finalSect : sect.xml(doc.colors);
    }
}
