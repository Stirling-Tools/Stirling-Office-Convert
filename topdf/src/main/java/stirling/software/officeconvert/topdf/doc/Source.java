package stirling.software.officeconvert.topdf.doc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.model.CHPX;
import org.apache.poi.hwpf.model.LFO;
import org.apache.poi.hwpf.model.ListLevel;
import org.apache.poi.hwpf.model.ListTables;
import org.apache.poi.hwpf.model.PAPX;
import org.apache.poi.hwpf.model.PropertyNode;
import org.apache.poi.hwpf.model.StyleDescription;
import org.apache.poi.hwpf.model.StyleSheet;
import org.apache.poi.hwpf.sprm.ParagraphSprmUncompressor;
import org.apache.poi.hwpf.usermodel.CharacterProperties;
import org.apache.poi.hwpf.usermodel.ParagraphProperties;

final class Source {

    private static final int NO_STYLE = 0x0FFF;

    final HWPFDocument doc;

    final StyleSheet styles;

    final RunXml runs;

    final CharSequence text;

    private final List<CHPX> chpx;

    private final List<PAPX> papx;

    private final Map<Integer, List<Sprm>> styleSprms = new HashMap<>();

    private final ListTables lists;

    Source(HWPFDocument doc) {
        this.doc = doc;
        this.styles = doc.getStyleSheet();
        this.runs = new RunXml(doc.getFontTable());
        this.text = doc.getText();
        this.chpx = doc.getCharacterTable().getTextRuns();
        this.papx = ParagraphMarks.resolve(doc, doc.getParagraphTable().getParagraphs());
        ListTables lt;
        try {
            lt = doc.getListTables();
        } catch (RuntimeException e) {
            lt = null;
        }
        this.lists = lt;
    }

    String fontName(int ftc) {
        try {
            return doc.getFontTable() == null ? null : doc.getFontTable().getMainFont(ftc);
        } catch (RuntimeException e) {
            return null;
        }
    }

    record Segment(int start, int end, CharacterProperties chp, List<Sprm> sprms) {}

    List<Segment> segments(int start, int end, int istd) {
        List<Segment> out = new ArrayList<>();
        int i = index(chpx, start);
        int at = start;
        while (at < end) {
            CHPX x = i >= 0 && i < chpx.size() ? chpx.get(i) : null;
            if (x == null || x.getStart() > at) {
                int stop = x == null ? end : Math.min(end, x.getStart());
                out.add(new Segment(at, stop, styleChp(istd), characters(istd, List.of())));
                at = stop;
                continue;
            }
            int stop = Math.min(end, x.getEnd());
            if (stop > at) {
                CharacterProperties chp;
                try {
                    chp = x.getCharacterProperties(styles, (short) istd);
                } catch (RuntimeException e) {
                    chp = new CharacterProperties();
                }
                out.add(new Segment(at, stop, chp, characters(istd, Sprm.parse(x.getGrpprl(), 0))));
                at = stop;
            }
            i++;
        }
        return out;
    }

    private static <T extends PropertyNode<T>> int index(List<T> nodes, int cp) {
        int lo = 0;
        int hi = nodes.size() - 1;
        int best = nodes.isEmpty() ? -1 : 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            T n = nodes.get(mid);
            if (n.getEnd() <= cp) {
                lo = mid + 1;
                best = lo;
            } else if (n.getStart() > cp) {
                hi = mid - 1;
                best = mid;
            } else {
                return mid;
            }
        }
        return best;
    }

    @SuppressWarnings("deprecation")
    private CharacterProperties styleChp(int istd) {
        try {
            StyleDescription sd = styles == null ? null : styles.getStyleDescription(istd);
            CharacterProperties c = sd == null ? null : sd.getCHP();
            return c == null ? new CharacterProperties() : c;
        } catch (RuntimeException e) {
            return new CharacterProperties();
        }
    }

    private final Map<Integer, ParagraphProperties> stylePaps = new HashMap<>();

    List<PAPX> paragraphs(int start, int end) {
        List<PAPX> out = new ArrayList<>();
        int i = index(papx, start);
        for (; i >= 0 && i < papx.size(); i++) {
            PAPX x = papx.get(i);
            if (x.getStart() >= end) {
                break;
            }
            if (x.getEnd() > start) {
                out.add(x);
            }
        }
        return out;
    }

    ParagraphProperties pap(PAPX x) {
        int istd = x.getIstd();
        ParagraphProperties base = stylePaps.computeIfAbsent(istd, k -> apply(new ParagraphProperties(), style(k)));
        byte[] direct = Sprm.encode(Sprm.parse(x.getGrpprl(), 2));
        ParagraphProperties p = apply(base.copy(), direct);
        if (p.getIlfo() > 0 && lists != null) {
            byte[] level = levelPapx(p.getIlfo(), p.getIlvl());
            if (level != null) {
                p = apply(apply(p, Sprm.encode(Sprm.parse(level, 0))), direct);
            }
        }
        p.setIstd(istd);
        return p;
    }

    private byte[] levelPapx(int ilfo, int ilvl) {
        try {
            LFO lfo = lists.getLfo(ilfo);
            ListLevel l = lfo == null ? null : lists.getLevel(lfo.getLsid(), ilvl);
            return l == null ? null : l.getGrpprlPapx();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ParagraphProperties apply(ParagraphProperties p, List<Sprm> sprms) {
        return apply(p, Sprm.encode(sprms));
    }

    private static ParagraphProperties apply(ParagraphProperties p, byte[] grpprl) {
        if (grpprl.length == 0) {
            return p;
        }
        try {
            return ParagraphSprmUncompressor.uncompressPAP(p, grpprl, 0);
        } catch (RuntimeException e) {
            return p;
        }
    }

    List<Sprm> direct(int cp) {
        PAPX x = find(papx, cp);
        if (x == null) {
            return List.of();
        }
        return Sprm.parse(x.getGrpprl(), 2);
    }

    List<Sprm> resolved(int istd, int cp) {
        List<Sprm> out = new ArrayList<>(style(istd));
        out.addAll(direct(cp));
        return out;
    }

    private final Map<Integer, List<Sprm>> styleChpx = new HashMap<>();

    List<Sprm> characters(int istd, List<Sprm> direct) {
        List<Sprm> out = new ArrayList<>(styleRuns(istd));
        Sprm cs = Sprm.find(direct, 0x4A30);
        if (cs != null) {
            out.addAll(styleRuns(cs.u16()));
        }
        out.addAll(direct);
        return out;
    }

    private List<Sprm> styleRuns(int istd) {
        List<Sprm> cached = styleChpx.get(istd);
        if (cached != null) {
            return cached;
        }
        List<List<Sprm>> chain = new ArrayList<>();
        int at = istd;
        for (int depth = 0; depth < 16 && at != NO_STYLE && styles != null && at >= 0 && at < styles.numStyles();
                depth++) {
            StyleDescription sd = styles.getStyleDescription(at);
            if (sd == null) {
                break;
            }
            chain.add(0, Sprm.parse(sd.getCHPX(), 0));
            at = sd.getBaseStyle();
        }
        List<Sprm> out = new ArrayList<>();
        for (List<Sprm> l : chain) {
            out.addAll(l);
        }
        styleChpx.put(istd, out);
        return out;
    }

    List<Sprm> style(int istd) {
        List<Sprm> cached = styleSprms.get(istd);
        if (cached != null) {
            return cached;
        }
        List<List<Sprm>> chain = new ArrayList<>();
        int at = istd;
        for (int depth = 0; depth < 16 && at != NO_STYLE && styles != null && at >= 0 && at < styles.numStyles();
                depth++) {
            StyleDescription sd = styles.getStyleDescription(at);
            if (sd == null) {
                break;
            }
            chain.add(0, Sprm.parse(sd.getPAPX(), 2));
            at = sd.getBaseStyle();
        }
        List<Sprm> out = new ArrayList<>();
        for (List<Sprm> l : chain) {
            out.addAll(l);
        }
        styleSprms.put(istd, out);
        return out;
    }

    private static <T extends PropertyNode<T>> T find(List<T> nodes, int cp) {
        int lo = 0;
        int hi = nodes.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            T n = nodes.get(mid);
            if (n.getStart() > cp) {
                hi = mid - 1;
            } else if (n.getEnd() <= cp) {
                lo = mid + 1;
            } else {
                return n;
            }
        }
        return null;
    }
}
