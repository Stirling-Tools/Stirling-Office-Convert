package stirling.software.officeconvert.topdf.rtf;

import java.nio.charset.Charset;

final class Definitions {

    private final Doc doc;

    private FontTable.Font font;

    private final StringBuilder fontName = new StringBuilder();

    private boolean fontDone;

    Definitions(Doc doc) {
        this.doc = doc;
    }

    Charset fontCharset() {
        return font == null ? doc.ansi : doc.fonts.charset(font.id, doc.ansi);
    }

    void fontWord(Group g, String w, int p) {
        switch (w) {
            case "f" -> {
                endFont();
                font = doc.fonts.open(p);
                fontName.setLength(0);
                fontDone = false;
            }
            case "fcharset" -> {
                if (font != null) {
                    font.charset = p;
                }
            }
            case "cpg" -> {
                if (font != null) {
                    font.codePage = p;
                }
            }
            case "fprq" -> {
                if (font != null) {
                    font.pitch = p;
                }
            }
            case "fnil", "froman", "fswiss", "fmodern", "fscript", "fdecor", "ftech", "fbidi" -> {
                if (font != null) {
                    font.family = w.substring(1);
                }
            }
            default -> {
            }
        }
    }

    void fontText(String s) {
        if (!fontDone && fontName.length() < 256) {
            fontName.append(s);
        }
    }

    void fontAlt(Group g, String s) {
        if (font != null && g.text != null && g.text.length() < 256) {
            g.text.append(s);
        }
    }

    void closeAlt(Group g) {
        if (font != null && g.text != null && !g.text.toString().isBlank()) {
            font.alt = g.text.toString().strip();
        }
    }

    void semicolon(Group g) {
        switch (g.dest) {
            case FONTTBL -> endFont();
            case COLORTBL -> doc.colors.end();
            case STYLE -> {
                if (g.text != null) {
                    g.key = g.text.toString();
                }
            }
            default -> {
            }
        }
    }

    void endFont() {
        if (font != null && !fontDone) {
            String n = fontName.toString().strip();
            if (!n.isEmpty()) {
                font.name = n;
                if (font.charset < 0 && font.codePage <= 0) {
                    suffix(font);
                }
            }
            fontDone = true;
        }
    }

    private static final String[][] SUFFIXES = {{" CE", "238"}, {" Cyr", "204"}, {" Greek", "161"},
        {" Tur", "162"}, {" Baltic", "186"}, {" (Hebrew)", "177"}, {" (Arabic)", "178"}, {" (Vietnamese)", "163"}};

    private static void suffix(FontTable.Font f) {
        for (String[] s : SUFFIXES) {
            if (f.name.length() > s[0].length() && f.name.endsWith(s[0])) {
                f.name = f.name.substring(0, f.name.length() - s[0].length()).strip();
                f.charset = Integer.parseInt(s[1]);
                return;
            }
        }
    }

    void openStyle(Group g) {
        g.dest = Dest.STYLE;
        g.chp = new CharProps();
        g.pap = new ParaProps();
        g.text = new StringBuilder();
        g.key = null;
        g.payload = new StyleDraft();
    }

    void styleWord(Group g, String w, int p, boolean has) {
        StyleDraft s = (StyleDraft) g.payload;
        switch (w) {
            case "s" -> s.type('p', p);
            case "cs" -> s.type('c', p);
            case "ts" -> s.type('t', p);
            case "ds" -> s.type('d', p);
            case "sbasedon" -> s.basedOn = p;
            case "snext" -> s.next = p;
            default -> {
                if (!CharWords.apply(g.chp, w, p, has)) {
                    ParaWords.apply(g.pap, w, p, has);
                }
            }
        }
    }

    void closeStyle(Group g) {
        StyleDraft s = (StyleDraft) g.payload;
        if (s.type != 'p' && s.type != 'c') {
            return;
        }
        StyleSheet.Style style = doc.styles.open(s.type, s.id);
        String name = g.key != null ? g.key : g.text == null ? "" : g.text.toString();
        style.name = name.strip();
        style.basedOn = s.basedOn == 222 ? -1 : s.basedOn;
        style.next = s.next;
        style.chp.inherit(g.chp);
        style.pap.inherit(g.pap);
        style.pap.style = s.id;
    }

    private static final class StyleDraft {
        char type = 'p';
        int id;
        int basedOn = -1;
        int next = -1;

        void type(char t, int v) {
            type = t;
            id = v;
        }
    }

    void openList(Group g) {
        g.dest = Dest.LIST;
        g.payload = new ListTable.ListDef();
    }

    void listWord(Group g, String w, int p) {
        ListTable.ListDef d = (ListTable.ListDef) g.payload;
        switch (w) {
            case "listid" -> d.id = p;
            case "listhybrid" -> d.hybrid = true;
            default -> {
            }
        }
    }

    void closeList(Group g) {
        doc.lists.add((ListTable.ListDef) g.payload);
    }

    void openLevel(Group g) {
        ListTable.ListDef d = (ListTable.ListDef) g.payload;
        ListTable.Level l = new ListTable.Level();
        if (d.levels.size() < ListTable.MAX_LEVELS) {
            d.levels.add(l);
        }
        g.dest = Dest.LISTLEVEL;
        g.payload = l;
        g.chp = new CharProps();
        g.pap = new ParaProps();
    }

    void levelWord(Group g, String w, int p, boolean has) {
        ListTable.Level l = (ListTable.Level) g.payload;
        switch (w) {
            case "levelnfc", "levelnfcn" -> l.format = p;
            case "leveljc", "leveljcn" -> l.justify = p;
            case "levelstartat" -> l.start = p;
            case "levelfollow" -> l.follow = p;
            case "levellegal" -> l.legal = !has || p != 0;
            case "levelnorestart" -> l.noRestart = !has || p != 0;
            default -> {
                if (!CharWords.apply(g.chp, w, p, has)) {
                    ParaWords.apply(g.pap, w, p, has);
                }
            }
        }
    }

    void closeLevel(Group g) {
        ListTable.Level l = (ListTable.Level) g.payload;
        l.chp.inherit(g.chp);
        l.pap.inherit(g.pap);
    }

    void levelText(Group g, int value) {
        ListTable.Level l = (ListTable.Level) g.payload;
        if (l.text.size() < 256) {
            l.text.add(value);
        }
    }

    void openOverride(Group g) {
        g.dest = Dest.OVERRIDE;
        g.payload = new ListTable.Override();
    }

    void overrideWord(Group g, String w, int p) {
        if (g.payload instanceof ListTable.Override o) {
            switch (w) {
                case "listid" -> o.listId = p;
                case "ls" -> o.ls = p;
                default -> {
                }
            }
        } else if (g.payload instanceof Lfo lfo) {
            switch (w) {
                case "levelstartat" -> {
                    lfo.start = p;
                    lfo.hasStart = true;
                }
                case "listoverridestartat" -> lfo.restart = true;
                default -> {
                }
            }
        }
    }

    private static final class Lfo {
        final ListTable.Override owner;
        final int index;
        int start = 1;
        boolean hasStart;
        boolean restart;

        Lfo(ListTable.Override owner, int index) {
            this.owner = owner;
            this.index = index;
        }
    }

    void openLfo(Group g) {
        ListTable.Override o = (ListTable.Override) g.payload;
        g.dest = Dest.LFOLEVEL;
        g.payload = new Lfo(o, o.lfoCount++);
    }

    void closeLfo(Group g) {
        Lfo lfo = (Lfo) g.payload;
        if (lfo.hasStart && lfo.restart && lfo.index < ListTable.MAX_LEVELS) {
            lfo.owner.starts.put(lfo.index, lfo.start);
        }
    }

    void closeOverride(Group g) {
        doc.lists.add((ListTable.Override) g.payload);
    }

    void infoText(Group g) {
        if (g.text == null || g.key == null) {
            return;
        }
        String v = g.text.toString().strip();
        if (v.isEmpty()) {
            return;
        }
        switch (g.key) {
            case "title" -> doc.title = v;
            case "subject" -> doc.subject = v;
            case "author" -> doc.author = v;
            case "keywords" -> doc.keywords = v;
            case "generator" -> doc.libreOffice = v.startsWith("LibreOffice") || v.startsWith("OpenOffice");
            default -> {
            }
        }
    }
}
