package stirling.software.officeconvert.rtf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.Scripts;
import stirling.software.officeconvert.model.StyleSheet;
import stirling.software.officeconvert.sink.Borders;
import stirling.software.officeconvert.sink.Links;
import stirling.software.officeconvert.sink.NoteHold;

final class RtfBody {

    private static final int MAX_TABS = 64;

    private static final float JUSTIFY_ROOM = 3f;

    enum End {
        PAR,
        SECT,
        CELL,
        NONE
    }

    private final RtfTables tables;
    private final StyleSheet sheet;
    private final NoteHold notes;
    private final RtfShapes shapes;
    private final RtfTableWriter tableWriter;
    final Map<String, Integer> styleNumbers = new LinkedHashMap<>();

    RtfBody(RtfTables tables, StyleSheet sheet, NoteHold notes, RtfShapes.Images images, RtfShapes.Counter ids) {
        this.tables = tables;
        this.sheet = sheet;
        this.notes = notes;
        this.shapes = new RtfShapes(images, this, ids);
        this.tableWriter = new RtfTableWriter(tables, this);
        styleNumbers.put("Normal", 0);
        tables.font(sheet.normal.font());
    }

    RtfTableWriter tables() {
        return tableWriter;
    }

    int styleNumber(String id) {
        return styleNumbers.computeIfAbsent(id, k -> styleNumbers.size());
    }

    void paragraph(StringBuilder sb, Paragraph p, End end, boolean inCell, boolean flow) throws IOException {
        StyleSheet.Style style = sheet.get(p.style);
        sb.append("\\pard\\plain ");
        if (inCell) {
            sb.append("\\intbl");
        }
        int s = styleNumber(style.id());
        if (s != 0) {
            sb.append("\\s").append(s);
        }
        sb.append(p.bidi ? "\\rtlpar" : "\\ltrpar");
        if (p.noHangingPunctuation) {
            sb.append("\\nooverflow");
        }
        sb.append(switch (p.align) {
            case CENTER -> "\\qc";
            case RIGHT -> "\\qr";
            case JUSTIFY -> "\\qj";
            default -> "\\ql";
        });
        int left = RtfText.twips(p.indentLeft);
        int right = RtfText.twips(p.indentRight - (p.align == Paragraph.Align.JUSTIFY ? JUSTIFY_ROOM : 0));
        sb.append("\\li").append(left).append("\\ri").append(right).append("\\fi").append(RtfText.twips(p.indentFirst))
                .append("\\lin").append(p.bidi ? right : left).append("\\rin").append(p.bidi ? left : right);
        sb.append("\\sb").append(clamp(p.spaceBefore)).append("\\sa").append(clamp(p.spaceAfter));
        switch (p.lineRule) {
            case EXACT -> sb.append("\\sl-").append(Math.max(20, RtfText.twips(p.lineHeight))).append("\\slmult0");
            case AT_LEAST -> sb.append("\\sl").append(Math.max(20, RtfText.twips(p.lineHeight))).append("\\slmult0");
            default -> sb.append("\\sl240\\slmult1");
        }
        sb.append("\\nowidctlpar");
        if (p.keepNext) {
            sb.append("\\keepn");
        }
        if (p.pageBreakBefore) {
            sb.append("\\pagebb");
        }
        if (flow && style.outlineLevel() >= 0) {
            sb.append("\\outlinelevel").append(Math.min(8, style.outlineLevel()));
        }
        if (p.list != null && flow) {
            sb.append("\\ls").append(p.list.numId()).append("\\ilvl").append(Math.max(0, Math.min(8, p.list.level())));
        }
        if (p.shading >= 0) {
            sb.append("\\cbpat").append(tables.colour(p.shading));
        }
        if (p.borderBottom > 0) {
            sb.append("\\brdrb\\brdrs\\brdrw").append(borderWidth(p.borderBottom)).append("\\brsp20\\brdrcf")
                    .append(tables.colour(p.borderBottomRgb));
        }
        tabs(sb, p);
        sb.append(' ');
        if (p.bookmark != null) {
            String name = RtfText.text(p.bookmark);
            sb.append("{\\*\\bkmkstart ").append(name).append("}{\\*\\bkmkend ").append(name).append('}');
        }
        inlines(sb, p.inlines);
        RunStyle mark = p.markStyle != null ? p.markStyle : style.run();
        sb.append(props(mark.withVertAlign(0), false)).append(' ');
        switch (end) {
            case PAR -> sb.append("\\par\n");
            case SECT -> sb.append("\\sect\n");
            case CELL -> sb.append("\\cell\n");
            default -> { }
        }
    }

    private static int clamp(float pt) {
        return Math.max(0, Math.min(31680, RtfText.twips(pt)));
    }

    static int borderWidth(float pt) {
        return Math.max(2, Math.min(75, RtfText.twips(Borders.width(pt))));
    }

    private static void tabs(StringBuilder sb, Paragraph p) {
        for (Paragraph.TabStop t : p.tabs.subList(0, Math.min(p.tabs.size(), MAX_TABS))) {
            switch (t.kind()) {
                case CENTER -> sb.append("\\tqc");
                case RIGHT -> sb.append("\\tqr");
                case DECIMAL -> sb.append("\\tqdec");
                default -> { }
            }
            if (t.leader() != 0) {
                sb.append(switch (t.leader()) {
                    case '_' -> "\\tlul";
                    case '-' -> "\\tlhyph";
                    case '\u00B7' -> "\\tlmdot";
                    default -> "\\tldot";
                });
            }
            sb.append("\\tx").append(Math.max(0, RtfText.twips(t.pos())));
        }
    }

    private void inlines(StringBuilder sb, List<Inline> inlines) throws IOException {
        String openLink = null;
        for (Inline in : inlines) {
            String link = in instanceof Inline.Text t ? Links.target(t) : null;
            if (!Objects.equals(link, openLink)) {
                if (openLink != null) {
                    sb.append("}}}");
                }
                if (link != null) {
                    sb.append("{\\field{\\*\\fldinst {HYPERLINK ");
                    if (link.startsWith("#")) {
                        sb.append("\\\\l \"").append(fieldText(link.substring(1))).append('"');
                    } else {
                        sb.append('"').append(fieldText(link)).append('"');
                    }
                    sb.append(" }}{\\fldrslt {");
                }
                openLink = link;
            }
            switch (in) {
                case Inline.Text t -> text(sb, t.text(), t.style());
                case Inline.Tab tab -> sb.append('{').append(props(styleOr(tab.style()), false)).append("\\tab}");
                case Inline.Break br -> sb.append("\\line ");
                case Inline.ColumnBreak cb -> sb.append("\\column ");
                case Inline.PageBreak pb -> sb.append("\\page ");
                case Inline.PageNumber pn -> {
                    String props = props(styleOr(pn.style()), false);
                    sb.append("{\\field{\\*\\fldinst {").append(props).append(pn.total() ? " NUMPAGES " : " PAGE ")
                            .append("}}{\\fldrslt {").append(props).append(" 1}}}");
                }
                case Inline.Image img -> shapes.picture(sb, img.picture());
                case Inline.Shape shape -> shapes.shape(sb, shape);
                case Inline.TextBox box -> shapes.textBox(sb, box);
                case Inline.FootnoteRef ref -> note(sb, ref);
                case Inline.FootnoteMark mark -> {
                    RunStyle ms = styleOr(mark.style()).withVertAlign(1);
                    sb.append('{').append(props(ms, false));
                    if (mark.custom()) {
                        sb.append(' ');
                        RtfText.text(sb, mark.marker());
                    } else {
                        sb.append("\\chftn");
                    }
                    sb.append('}');
                }
            }
        }
        if (openLink != null) {
            sb.append("}}}");
        }
    }

    private RunStyle styleOr(RunStyle s) {
        return s != null ? s : sheet.normal;
    }

    private static String fieldText(String s) {
        return RtfText.text(s.replace("\\", "/").replace("\"", "%22"));
    }

    private void text(StringBuilder sb, String text, RunStyle style) {
        if (text.isEmpty()) {
            return;
        }
        RunStyle s = styleOr(style);
        for (String[] seg : directionSegments(text)) {
            sb.append('{').append(props(s, seg[1] != null, seg[0])).append(' ');
            RtfText.text(sb, seg[0]);
            sb.append('}');
        }
    }

    String props(RunStyle s, boolean rtl) {
        return props(s, rtl, null);
    }

    private String props(RunStyle s, boolean rtl, String text) {
        Scripts.Fonts slots = Scripts.fonts(s.font() != null ? s.font() : sheet.normal.font(), text, sheet.scripts);
        int f = tables.font(slots.latin());
        int ea = tables.font(slots.eastAsian());
        int complex = tables.font(slots.complex());
        int hp = RtfText.halfPoints(s.size());
        StringBuilder cs = new StringBuilder("\\rtlch\\fcs1\\af").append(complex).append("\\afs").append(hp);
        if (s.bold()) {
            cs.append("\\ab");
        }
        if (s.italic()) {
            cs.append("\\ai");
        }
        StringBuilder ls = new StringBuilder("\\ltrch\\fcs0\\f").append(f).append("\\fs").append(hp);
        if (s.bold()) {
            ls.append("\\b");
        }
        if (s.italic()) {
            ls.append("\\i");
        }
        StringBuilder common = new StringBuilder();
        if (s.underline()) {
            common.append("\\ul");
        }
        if (s.strike()) {
            common.append("\\strike");
        }
        if (s.smallCaps()) {
            common.append("\\scaps");
        }
        if ((s.rgb() & 0xFFFFFF) != 0) {
            common.append("\\cf").append(tables.colour(s.rgb()));
        }
        if (s.highlight() >= 0) {
            common.append("\\chshdng0\\chcfpat0\\chcbpat").append(tables.colour(s.highlight()));
        }
        if (s.vertAlign() > 0) {
            common.append("\\super");
        } else if (s.vertAlign() < 0) {
            common.append("\\sub");
        }
        if (Math.abs(s.spacing()) >= 0.05f) {
            common.append("\\expndtw").append(RtfText.twips(s.spacing()));
        }
        if (s.scale() != 100) {
            common.append("\\charscalex").append(Math.max(1, Math.min(600, s.scale())));
        }
        Scripts.Languages lang = Scripts.beyond(Scripts.languages(text, sheet.scripts), sheet.scripts);
        if (Scripts.lcid(lang.latin()) > 0) {
            common.append("\\lang").append(Scripts.lcid(lang.latin()));
        }
        if (Scripts.lcid(lang.eastAsian()) > 0) {
            common.append("\\langfe").append(Scripts.lcid(lang.eastAsian()));
        }
        if (Scripts.lcid(lang.complex()) > 0) {
            common.append("\\alang").append(Scripts.lcid(lang.complex()));
        }
        common.append("\\hich\\af").append(f).append("\\dbch\\af").append(ea).append("\\loch\\f").append(f);
        return rtl ? ls.toString() + common + cs : cs.toString() + ls + common;
    }

    private void note(StringBuilder sb, Inline.FootnoteRef ref) throws IOException {
        RunStyle s = styleOr(ref.style()).withVertAlign(1);
        List<Paragraph> body = notes == null ? null : notes.take(ref.id());
        sb.append('{').append(props(s, false));
        if (ref.custom() || body == null) {
            sb.append(' ');
            RtfText.text(sb, ref.marker());
        } else {
            sb.append("\\chftn");
        }
        if (body != null) {
            sb.append("{\\footnote ");
            List<Paragraph> paras = body.isEmpty() ? List.of(new Paragraph()) : body;
            for (int i = 0; i < paras.size(); i++) {
                paragraph(sb, paras.get(i), i + 1 < paras.size() ? End.PAR : End.NONE, false, false);
            }
            sb.append('}');
        }
        sb.append('}');
    }

    static List<String[]> directionSegments(String s) {
        int n = s.length();
        int[] cls = new int[n];
        boolean any = false;
        for (int i = 0; i < n; i++) {
            byte d = Character.getDirectionality(s.charAt(i));
            cls[i] = d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC ? 1
                    : d == Character.DIRECTIONALITY_LEFT_TO_RIGHT || d == Character.DIRECTIONALITY_EUROPEAN_NUMBER ? 2 : 0;
            any |= cls[i] == 1;
        }
        List<String[]> out = new ArrayList<>();
        if (!any) {
            out.add(new String[] {s, null});
            return out;
        }
        for (int i = 0; i < n; i++) {
            if (cls[i] != 0) {
                continue;
            }
            int prev = 0;
            for (int j = i - 1; j >= 0 && prev == 0; j--) {
                prev = cls[j] == 3 ? 0 : cls[j];
            }
            int next = 0;
            for (int j = i + 1; j < n && next == 0; j++) {
                next = cls[j];
            }
            cls[i] = prev == 1 && next == 1 ? 1 : 3;
        }
        int start = 0;
        for (int i = 1; i <= n; i++) {
            boolean rtl = cls[start] == 1;
            if (i == n || (cls[i] == 1) != rtl) {
                out.add(new String[] {s.substring(start, i), rtl ? "rtl" : null});
                start = i;
            }
        }
        return out;
    }
}
