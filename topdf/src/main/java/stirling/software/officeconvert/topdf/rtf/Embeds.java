package stirling.software.officeconvert.topdf.rtf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Embeds {

    private final RtfReader reader;

    private final Doc doc;

    Embeds(RtfReader reader, Doc doc) {
        this.reader = reader;
        this.doc = doc;
    }

    void openPict(Group g) {
        g.dest = Dest.PICT;
        g.payload = new Picture();
    }

    void pictWord(Group g, String w, int p) {
        ((Picture) g.payload).word(w, p);
    }

    void hex(Group g, int b) {
        ((Picture) g.payload).hex(b);
    }

    void binary(Group g, long n, RtfTokenizer tok) throws IOException {
        Picture p = (Picture) g.payload;
        long copied = tok.copyBinary(n, p.data, p.room());
        if (copied < n) {
            p.truncated = true;
        }
    }

    void closePict(Group done, Group parent) throws IOException {
        Picture p = (Picture) done.payload;
        if (p.truncated) {
            reader.lost("A picture was too large and was left out");
            return;
        }
        if (parent.dest == Dest.SV || done.sp != null) {
            Group.Sp sp = done.sp;
            if (sp != null && done.shape != null && "pib".equals(sp.name.toString().strip())) {
                PictureXml.Image img = PictureXml.image(p, doc.media);
                if (img != null) {
                    done.shape.picture = img;
                }
            }
            return;
        }
        if (parent.dest != Dest.NORMAL || parent.story == null) {
            return;
        }
        PictureXml.Image img = PictureXml.image(p, doc.media);
        if (img == null) {
            if (doc.media.lost) {
                reader.lost("Some pictures were left out: the document holds too many or too large pictures");
            }
            return;
        }
        String rid = parent.story.rels.add("image", img.target(), false);
        reader.content().item(parent, PictureXml.inline(img, rid, doc.media.nextId()));
    }

    void openField(Group g) {
        g.dest = Dest.FIELD;
        g.field = new Group.Field();
    }

    void openInstruction(Group g) {
        g.dest = Dest.FLDINST;
        g.text = g.field == null ? new StringBuilder() : g.field.inst;
    }

    void openResult(Group g) {
        g.dest = Dest.NORMAL;
        if (g.field == null || g.story == null) {
            return;
        }
        Wrap w = wrap(g.field.inst.toString(), g.story.rels);
        if (w != null) {
            g.wrap = w;
        }
    }

    static Wrap wrap(String instruction, Rels rels) {
        List<String> args = arguments(instruction);
        if (args.isEmpty()) {
            return null;
        }
        String kind = args.get(0).toUpperCase(Locale.ROOT);
        switch (kind) {
            case "HYPERLINK" -> {
                String target = null;
                String anchor = null;
                for (int i = 1; i < args.size(); i++) {
                    String a = args.get(i);
                    if (a.equalsIgnoreCase("\\l") && i + 1 < args.size()) {
                        anchor = args.get(++i);
                    } else if (a.startsWith("\\")) {
                        if (a.length() == 2 && "otm".indexOf(Character.toLowerCase(a.charAt(1))) >= 0
                                && i + 1 < args.size() && !"\\m".equalsIgnoreCase(a)) {
                            i++;
                        }
                    } else if (target == null) {
                        target = a;
                    }
                }
                if (target != null && safe(target)) {
                    return Wrap.link(rels.add("hyperlink", target.strip(), true));
                }
                if (target == null && anchor != null && !anchor.isBlank()) {
                    return Wrap.anchor(anchor);
                }
                return null;
            }
            case "PAGE", "NUMPAGES", "SECTIONPAGES", "SECTION" -> {
                return Wrap.field(kind);
            }
            default -> {
                return null;
            }
        }
    }

    static boolean safe(String url) {
        String u = url.strip().toLowerCase(Locale.ROOT);
        return u.startsWith("http://") || u.startsWith("https://") || u.startsWith("mailto:");
    }

    static List<String> arguments(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = Math.min(s.length(), 8192);
        while (i < n && out.size() < 64) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            StringBuilder b = new StringBuilder();
            if (c == '"') {
                i++;
                while (i < n && s.charAt(i) != '"') {
                    char k = s.charAt(i);
                    if (k == '\\' && i + 1 < n && (s.charAt(i + 1) == '"' || s.charAt(i + 1) == '\\')) {
                        k = s.charAt(++i);
                    }
                    b.append(k);
                    i++;
                }
                i++;
            } else {
                while (i < n && !Character.isWhitespace(s.charAt(i))) {
                    b.append(s.charAt(i++));
                }
            }
            out.add(b.toString());
        }
        return out;
    }

    void openShape(Group g, boolean group) {
        g.dest = Dest.SHP;
        g.shape = new Shape(group);
        g.sp = null;
    }

    void shapeWord(Group g, String w, int p) {
        if (g.shape != null) {
            g.shape.word(w, p);
        }
    }

    void openSp(Group g) {
        g.dest = Dest.SP;
        g.sp = new Group.Sp();
    }

    void openSn(Group g) {
        g.dest = Dest.SN;
        g.text = g.sp == null ? new StringBuilder() : g.sp.name;
    }

    void openSv(Group g) {
        g.dest = Dest.SV;
        g.text = g.sp == null ? new StringBuilder() : g.sp.value;
    }

    void closeSp(Group done) {
        if (done.shape != null && done.sp != null && !"pib".equals(done.sp.name.toString().strip())) {
            done.shape.prop(done.sp.name.toString(), limit(done.sp.value.toString()));
        }
    }

    private static String limit(String v) {
        return v.length() > 256 ? v.substring(0, 256) : v;
    }

    void closeShape(Group done, Group parent) throws IOException {
        Shape s = done.shape;
        if (s == null) {
            return;
        }
        if (parent.dest == Dest.SHP && parent.shape != null && parent.shape.group && parent.shape != s) {
            parent.shape.add(s);
            return;
        }
        if (parent.story == null || parent.dest != Dest.NORMAL && parent.dest != Dest.SHP
                && parent.dest != Dest.OBJECT) {
            return;
        }
        List<String> drawings = new ArrayList<>();
        ShapeXml.drawings(s, parent.story.rels, doc.media, drawings);
        for (String d : drawings) {
            reader.content().item(parent, d);
        }
    }
}
