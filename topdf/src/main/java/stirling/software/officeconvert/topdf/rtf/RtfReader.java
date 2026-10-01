package stirling.software.officeconvert.topdf.rtf;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class RtfReader {

    static final int MAX_GROUPS = 2048;

    private static final Set<String> SKIPPED = Set.of("nonesttables", "pn", "pnseclvl", "xe", "tc", "tcn", "txe",
            "bxe", "rxe", "pxe", "annotation", "atnid", "atnauthor", "atrfstart", "atrfend", "atntime", "atnref",
            "atndate", "atnicn", "atnparent", "bkmkstart", "bkmkend", "template", "revtbl", "rsidtbl", "xmlnstbl",
            "datastore", "themedata", "colorschememapping", "latentstyles", "userprops", "docvar",
            "ftnsep", "ftnsepc", "ftncn", "aftnsep", "aftnsepc", "aftncn", "pgdsctbl", "comment", "doccomm",
            "operator", "company", "manager", "category", "hlinkbase", "fchars", "lchars", "protusertbl",
            "password", "passwordhash", "wgrffmtfilter", "do", "panose", "fname", "file", "filetbl",
            "blipuid", "picprop", "mhtmltag", "htmltag", "mmath", "mmathPr", "formfield", "datafield",
            "levelnumbers", "listname", "listpicture", "pntxta", "pntxtb", "objdata", "objclass", "objname",
            "objalias", "objsect", "objitem", "objtopic", "oleclsid", "nonshppict", "shprslt", "nextfile",
            "private", "ebcstart", "ebcend", "bkmkcolf", "bkmkcoll", "fldtype", "ffdeftext",
            "ffformat", "ffhelptext", "ffstattext", "ffentrymcr", "ffexitmcr", "ffname", "ffl", "pgptbl",
            "oldcprops", "oldpprops", "oldtprops", "oldsprops", "factoidname", "svb", "gridtbl", "trackmoves", "trackformatting", "mvfmf", "mvfml", "mvtof", "mvtol", "dptxbxtext",
            "keycode", "macrocode", "jexpand", "xform", "linkval",
            "propname", "staticval", "pnfont", "fontemb", "fontfile", "rsidroot", "ilfomacatclnup",
            "wpjst", "wptab");

    private final RtfTokenizer tok;

    private final Doc doc;

    private final Content content;

    private final Definitions defs;

    private final Embeds embeds;

    private final List<Group> stack = new ArrayList<>();

    private Group g;

    private int skipDepth;

    private int overflow;

    private boolean starred;

    private int ucSkip;

    private byte[] pending = new byte[256];

    private int pendingLength;

    private long ticks;

    private boolean stopped;

    final Set<String> warnings = new LinkedHashSet<>();

    boolean lost;

    RtfReader(InputStream in, Doc doc, Content content) {
        this.tok = new RtfTokenizer(in);
        this.doc = doc;
        this.content = content;
        this.defs = new Definitions(doc);
        this.embeds = new Embeds(this, doc);
        Group root = new Group();
        root.chp = new CharProps();
        root.pap = new ParaProps();
        root.story = content.body;
        root.dest = Dest.NORMAL;
        stack.add(root);
        g = root;
    }

    Content content() {
        return content;
    }

    void lost(String warning) {
        lost = true;
        warnings.add(warning);
    }

    void read() throws IOException {
        int t;
        try {
            while (!stopped && (t = tok.next()) != RtfTokenizer.EOF) {
                if ((++ticks & 0x3FFF) == 0 && Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Conversion interrupted");
                }
                if (skipDepth > 0) {
                    skipped(t);
                    continue;
                }
                switch (t) {
                    case RtfTokenizer.OPEN -> open();
                    case RtfTokenizer.CLOSE -> close();
                    case RtfTokenizer.WORD -> word(tok.word, tok.param, tok.hasParam);
                    case RtfTokenizer.SYMBOL -> symbol(tok.symbol);
                    case RtfTokenizer.TEXT -> text(tok.value, false);
                    case RtfTokenizer.HEX -> text(tok.value, true);
                    default -> {
                    }
                }
            }
            flush();
            skipDepth = 0;
            while (stack.size() > 1) {
                pop();
            }
            content.finishBody(g);
        } catch (RtfPackage.TooLarge e) {
            lost("The document is too large; only its beginning was converted");
            stopped = true;
        }
    }

    boolean stopped() {
        return stopped;
    }

    private void skipped(int t) throws IOException {
        switch (t) {
            case RtfTokenizer.OPEN -> skipDepth++;
            case RtfTokenizer.CLOSE -> {
                skipDepth--;
                if (skipDepth == 0) {
                    pop();
                }
            }
            case RtfTokenizer.WORD -> {
                if ("bin".equals(tok.word) && tok.param > 0) {
                    tok.skipBinary(tok.param);
                }
            }
            default -> {
            }
        }
    }

    private void open() throws IOException {
        flush();
        ucSkip = 0;
        starred = false;
        if (stack.size() >= MAX_GROUPS) {
            overflow++;
            return;
        }
        Group parent = g;
        Group child = parent.child();
        if (parent.upr) {
            if (!parent.uprSkipped) {
                parent.uprSkipped = true;
                push(child);
                skip();
                return;
            }
            child.dest = parent.uprDest;
        } else if (parent.dest == Dest.STYLESHEET) {
            defs.openStyle(child);
        }
        push(child);
    }

    private void push(Group child) {
        stack.add(child);
        g = child;
    }

    private void close() throws IOException {
        flush();
        ucSkip = 0;
        starred = false;
        if (overflow > 0) {
            overflow--;
            return;
        }
        if (stack.size() <= 1) {
            return;
        }
        pop();
    }

    private void pop() throws IOException {
        Group done = stack.remove(stack.size() - 1);
        g = stack.get(stack.size() - 1);
        closed(done, g);
    }

    private void closed(Group done, Group parent) throws IOException {
        boolean own = done.payload != parent.payload;
        switch (done.dest) {
            case STYLE -> {
                if (own) {
                    defs.closeStyle(done);
                }
            }
            case FONTTBL -> defs.endFont();
            case FALT -> defs.closeAlt(done);
            case LIST -> {
                if (own) {
                    defs.closeList(done);
                }
            }
            case LISTLEVEL -> {
                if (own) {
                    defs.closeLevel(done);
                }
            }
            case OVERRIDE -> {
                if (own) {
                    defs.closeOverride(done);
                }
            }
            case LFOLEVEL -> {
                if (own) {
                    defs.closeLfo(done);
                }
            }
            case INFOTEXT -> defs.infoText(done);
            case DEFCHP -> doc.defChp.inherit(done.chp);
            case DEFPAP -> doc.defPap.inherit(done.pap);
            case PICT -> {
                if (own) {
                    embeds.closePict(done, parent);
                }
            }
            case SP -> {
                if (done.sp != parent.sp) {
                    embeds.closeSp(done);
                }
            }
            case SHP -> {
                if (done.shape != parent.shape) {
                    embeds.closeShape(done, parent);
                }
            }
            case NORMAL -> {
                if (done.story != parent.story) {
                    if (done.note != null && done.note != parent.note) {
                        content.closeNote(done);
                    } else if (done.header != null) {
                        content.closeHeader(done);
                    } else if (done.textbox) {
                        content.closeTextbox(done);
                    }
                }
            }
            default -> {
            }
        }
    }

    private void skip() {
        g.dest = Dest.SKIP;
        skipDepth = 1;
    }

    Group noteGroup() {
        for (int i = stack.size() - 1; i >= 0; i--) {
            if (stack.get(i).note != null) {
                return stack.get(i);
            }
        }
        return null;
    }

    private void word(String w, int p, boolean has) throws IOException {
        boolean star = starred;
        starred = false;
        if ("bin".equals(w)) {
            binary(p);
            return;
        }
        if (ucSkip > 0) {
            ucSkip--;
            return;
        }
        if ("u".equals(w)) {
            unicode(p);
            return;
        }
        if ("uc".equals(w)) {
            g.uc = Math.max(0, Math.min(10, p));
            return;
        }
        flush();
        if (destination(w, star)) {
            return;
        }
        if (star && !"cs".equals(w) && !"ts".equals(w) && !"ds".equals(w)) {
            skip();
            return;
        }
        switch (g.dest) {
            case NORMAL -> content.word(this, g, w, p, has);
            case FONTTBL, FALT -> defs.fontWord(g, w, p);
            case COLORTBL -> doc.colors.word(w, p);
            case STYLE -> defs.styleWord(g, w, p, has);
            case LIST -> defs.listWord(g, w, p);
            case LISTLEVEL -> defs.levelWord(g, w, p, has);
            case OVERRIDE, LFOLEVEL -> defs.overrideWord(g, w, p);
            case PICT -> embeds.pictWord(g, w, p);
            case SHP, SHPINST -> embeds.shapeWord(g, w, p);
            case DEFCHP -> CharWords.apply(g.chp, w, p, has);
            case DEFPAP -> ParaWords.apply(g.pap, w, p, has);
            case FIELD, OBJECT -> CharWords.apply(g.chp, w, p, has);
            default -> {
            }
        }
    }

    private boolean destination(String w, boolean star) throws IOException {
        switch (w) {
            case "fonttbl" -> set(Dest.FONTTBL);
            case "colortbl" -> set(Dest.COLORTBL);
            case "stylesheet" -> set(Dest.STYLESHEET);
            case "listtable" -> set(Dest.LISTTABLE);
            case "listoverridetable" -> set(Dest.OVERRIDETABLE);
            case "list" -> {
                if (g.dest != Dest.LISTTABLE) {
                    return false;
                }
                defs.openList(g);
            }
            case "listlevel" -> {
                if (g.dest != Dest.LIST) {
                    return false;
                }
                defs.openLevel(g);
            }
            case "leveltext" -> {
                if (g.dest != Dest.LISTLEVEL) {
                    return false;
                }
                g.dest = Dest.LEVELTEXT;
            }
            case "listoverride" -> {
                if (g.dest != Dest.OVERRIDETABLE) {
                    return false;
                }
                defs.openOverride(g);
            }
            case "lfolevel" -> {
                if (g.dest != Dest.OVERRIDE) {
                    return false;
                }
                defs.openLfo(g);
            }
            case "info" -> set(Dest.INFO);
            case "generator" -> {
                set(Dest.INFOTEXT);
                g.key = w;
            }
            case "title", "subject", "author", "keywords" -> {
                if (g.dest != Dest.INFO) {
                    return false;
                }
                set(Dest.INFOTEXT);
                g.key = w;
            }
            case "falt" -> {
                if (g.dest != Dest.FONTTBL) {
                    return false;
                }
                set(Dest.FALT);
            }
            case "pict" -> {
                if (!content(g.dest) && g.dest != Dest.SV) {
                    skip();
                    return true;
                }
                embeds.openPict(g);
            }
            case "shppict", "ud", "shpinst" -> {
                if ("shpinst".equals(w) && g.dest == Dest.SHP) {
                    g.dest = Dest.SHPINST;
                }
            }
            case "field" -> {
                if (!content(g.dest)) {
                    skip();
                    return true;
                }
                embeds.openField(g);
            }
            case "fldinst" -> embeds.openInstruction(g);
            case "fldrslt" -> {
                if (g.dest != Dest.FIELD) {
                    skip();
                    return true;
                }
                embeds.openResult(g);
            }
            case "shp", "shpgrp" -> {
                if (!content(g.dest) && g.dest != Dest.SHP) {
                    skip();
                    return true;
                }
                embeds.openShape(g, "shpgrp".equals(w));
            }
            case "sp" -> {
                if (g.dest != Dest.SHPINST && g.dest != Dest.SHP) {
                    skip();
                    return true;
                }
                embeds.openSp(g);
            }
            case "sn" -> embeds.openSn(g);
            case "sv" -> embeds.openSv(g);
            case "shptxt" -> {
                if (g.shape == null) {
                    skip();
                    return true;
                }
                content.openTextbox(g);
            }
            case "header", "headerl", "headerr", "headerf", "footer", "footerl", "footerr", "footerf" -> {
                if (g.dest != Dest.NORMAL) {
                    skip();
                    return true;
                }
                content.openHeader(g, w);
                if (g.dest == Dest.SKIP) {
                    skipDepth = 1;
                }
            }
            case "footnote" -> {
                if (g.dest != Dest.NORMAL) {
                    skip();
                    return true;
                }
                content.openNote(g);
                if (g.dest == Dest.SKIP) {
                    skipDepth = 1;
                }
            }
            case "listtext", "pntext" -> {
                if (g.dest != Dest.NORMAL) {
                    skip();
                    return true;
                }
                g.listText = true;
            }
            case "defchp" -> {
                set(Dest.DEFCHP);
                g.chp = new CharProps();
            }
            case "defpap" -> {
                set(Dest.DEFPAP);
                g.pap = new ParaProps();
            }
            case "upr" -> {
                g.upr = true;
                g.uprDest = stack.size() > 1 ? stack.get(stack.size() - 2).dest : Dest.NORMAL;
                g.dest = g.uprDest;
            }
            case "object" -> {
                if (!content(g.dest)) {
                    skip();
                    return true;
                }
                set(Dest.OBJECT);
            }
            case "result" -> {
                if (g.dest != Dest.OBJECT) {
                    return false;
                }
                g.dest = Dest.NORMAL;
            }
            case "nesttableprops" -> g.nestProps = true;
            case "background" -> {
                g.background = true;
                g.story = null;
            }
            default -> {
                if (SKIPPED.contains(w)) {
                    skip();
                    return true;
                }
                return false;
            }
        }
        return true;
    }

    private static boolean content(Dest d) {
        return d == Dest.NORMAL || d == Dest.OBJECT || d == Dest.FIELD;
    }

    private void set(Dest d) {
        g.dest = d;
        g.text = new StringBuilder();
    }

    private void symbol(char c) throws IOException {
        if (c == '*') {
            starred = true;
            return;
        }
        if (ucSkip > 0) {
            ucSkip--;
            return;
        }
        switch (c) {
            case '~' -> chr(' ');
            case '-' -> chr('­');
            case '_' -> chr('‑');
            case '{', '}', '\\' -> text(c, true);
            default -> {
            }
        }
    }

    private void binary(int n) throws IOException {
        if (n <= 0) {
            return;
        }
        flush();
        if (g.dest == Dest.PICT) {
            embeds.binary(g, n, tok);
        } else {
            tok.skipBinary(n);
        }
    }

    private void unicode(int p) throws IOException {
        int c = p < 0 ? p + 65536 : p;
        if (c < 0 || c > 0xFFFF) {
            c = 0xFFFD;
        }
        if (g.dest == Dest.LEVELTEXT) {
            defs.levelText(g, 0x10000 | c);
        } else {
            chr((char) c);
        }
        ucSkip = g.uc;
    }

    void chr(char c) throws IOException {
        flush();
        if (g.dest == Dest.PICT || g.dest == Dest.SKIP) {
            return;
        }
        if (c == '\t' && g.dest == Dest.NORMAL) {
            content.item(g, "<w:tab/>");
            return;
        }
        deliver(String.valueOf(c));
    }

    private void text(int b, boolean hex) throws IOException {
        if (ucSkip > 0) {
            ucSkip--;
            return;
        }
        switch (g.dest) {
            case PICT -> {
                if (!hex) {
                    embeds.hex(g, b);
                }
                return;
            }
            case LEVELTEXT -> {
                if (!hex && b == ';') {
                    return;
                }
                defs.levelText(g, b);
                return;
            }
            case SKIP -> {
                return;
            }
            case FONTTBL, COLORTBL, STYLE -> {
                if (!hex && b == ';') {
                    flush();
                    defs.semicolon(g);
                    return;
                }
            }
            default -> {
            }
        }
        if (b == '\t' && !hex) {
            chr('\t');
            return;
        }
        if (b < 0x20 && !hex) {
            return;
        }
        if (pendingLength == pending.length) {
            if (pending.length >= 1 << 16) {
                flush();
            } else {
                pending = Arrays.copyOf(pending, pending.length * 2);
            }
        }
        pending[pendingLength++] = (byte) b;
    }

    private void flush() throws IOException {
        if (pendingLength == 0) {
            return;
        }
        String s = decode();
        pendingLength = 0;
        deliver(s);
    }

    private String decode() {
        boolean symbol;
        Charset cs;
        if (g.dest == Dest.FONTTBL || g.dest == Dest.FALT) {
            symbol = false;
            cs = defs.fontCharset();
        } else {
            int font = doc.effectiveFont(g.chp, g.pap);
            symbol = doc.fonts.symbol(font);
            cs = doc.charset(font);
        }
        if (symbol) {
            char[] out = new char[pendingLength];
            for (int i = 0; i < pendingLength; i++) {
                int b = pending[i] & 0xFF;
                out[i] = (char) (b >= 0x20 ? 0xF000 + b : b);
            }
            return new String(out);
        }
        return new String(pending, 0, pendingLength, cs);
    }

    private void deliver(String s) {
        switch (g.dest) {
            case NORMAL -> content.text(g, s);
            case FONTTBL -> defs.fontText(s);
            case FALT -> defs.fontAlt(g, s);
            case STYLE, INFOTEXT, FLDINST, SN, SV -> {
                if (g.text != null && g.text.length() < 65_536) {
                    g.text.append(s);
                }
            }
            default -> {
            }
        }
    }
}
