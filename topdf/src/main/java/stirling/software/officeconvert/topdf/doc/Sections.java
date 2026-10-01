package stirling.software.officeconvert.topdf.doc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.hwpf.model.FileInformationBlock;
import org.apache.poi.hwpf.model.GenericPropertyNode;
import org.apache.poi.hwpf.model.PlexOfCps;
import org.apache.poi.hwpf.model.SEPX;
import org.apache.poi.hwpf.usermodel.SectionProperties;

final class Sections implements Story.Breaks {

    static final int MAX_SECTIONS = 4096;

    private static final String[] TYPES = {"even", "default", "even", "default", "first", "first"};

    private static final String[] PAGE_FORMATS = {"decimal", "upperRoman", "lowerRoman", "upperLetter",
        "lowerLetter"};

    private final Conv c;

    private final Rels docRels;

    private final List<SEPX> seps = new ArrayList<>();

    private final List<List<Sprm>> raw = new ArrayList<>();

    private final List<String> refs = new ArrayList<>();

    private final Map<Long, String> parts = new HashMap<>();

    final List<String> overrides = new ArrayList<>();

    private int next;

    private int headers;

    private int footers;

    Sections(Conv c, Rels docRels) {
        this.c = c;
        this.docRels = docRels;
        try {
            for (SEPX s : c.src.doc.getSectionTable().getSections()) {
                if (seps.size() >= MAX_SECTIONS) {
                    break;
                }
                seps.add(s);
                raw.add(Sprm.parse(s.getGrpprl(), 0));
            }
        } catch (RuntimeException e) {
            seps.clear();
            raw.clear();
        }
    }

    void headers() throws IOException {
        int[][] stories = stories();
        int base;
        try {
            base = c.src.doc.getHeaderStoryRange().getStartOffset();
        } catch (RuntimeException e) {
            base = 0;
        }
        for (int k = 0; k < Math.max(1, seps.size()); k++) {
            StringBuilder b = new StringBuilder();
            for (int t = 0; t < 6; t++) {
                int i = 6 + k * 6 + t;
                if (stories == null || i >= stories.length || stories[i][1] <= stories[i][0]) {
                    continue;
                }
                int start = base + stories[i][0];
                int end = base + stories[i][1];
                boolean footer = t == 2 || t == 3 || t == 5;
                String id = part(start, end, base, footer);
                if (id != null) {
                    b.append("<w:").append(footer ? "footer" : "header").append("Reference w:type=\"")
                            .append(TYPES[t]).append("\" r:id=\"").append(id).append("\"/>");
                }
            }
            refs.add(b.toString());
        }
    }

    private String part(int start, int end, int base, boolean footer) throws IOException {
        long key = (long) start << 32 | end;
        String id = parts.get(key);
        if (id != null) {
            return id;
        }
        Rels rels = new Rels();
        StringBuilder b = new StringBuilder(Xml.HEAD).append(footer ? "<w:ftr" : "<w:hdr").append(Xml.NAMESPACES)
                .append('>');
        int mark = b.length();
        new Story(c, rels, Story.Kind.HEADER, base, null).write(start, Stories.trim(c.src.text, start, end), b);
        if (b.length() == mark) {
            b.append("<w:p/>");
        }
        b.append(footer ? "</w:ftr>" : "</w:hdr>");
        String name = footer ? "footer" + ++footers + ".xml" : "header" + ++headers + ".xml";
        c.zip.put("word/" + name, b);
        if (!rels.isEmpty()) {
            c.zip.put("word/_rels/" + name + ".rels", rels.part());
        }
        overrides.add("<Override PartName=\"/word/" + name + "\" ContentType=\"application/vnd.openxmlformats-"
                + "officedocument.wordprocessingml." + (footer ? "footer" : "header") + "+xml\"/>");
        id = docRels.add(footer ? "footer" : "header", name);
        parts.put(key, id);
        return id;
    }

    private int[][] stories() {
        FileInformationBlock fib = c.src.doc.getFileInformationBlock();
        try {
            int size = fib.getPlcfHddSize();
            if (size <= 0) {
                return null;
            }
            PlexOfCps plex = new PlexOfCps(c.src.doc.getTableStream(), fib.getPlcfHddOffset(), size, 0);
            int n = Math.min(plex.length(), 6 + 6 * MAX_SECTIONS);
            int[][] out = new int[n][];
            for (int i = 0; i < n; i++) {
                GenericPropertyNode node = plex.getProperty(i);
                out[i] = new int[] {node.getStart(), node.getEnd()};
            }
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public String at(int end) {
        if (next >= seps.size() - 1) {
            return null;
        }
        if (end < seps.get(next).getEnd()) {
            return null;
        }
        String s = sectPr(next);
        while (next < seps.size() - 1 && end >= seps.get(next).getEnd()) {
            next++;
        }
        return s;
    }

    String last() {
        return sectPr(Math.max(0, seps.size() - 1));
    }

    private String sectPr(int k) {
        StringBuilder b = new StringBuilder("<w:sectPr>");
        if (k < refs.size()) {
            b.append(refs.get(k));
        }
        if (k >= seps.size()) {
            b.append("<w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1800\" w:bottom=\"1440\"")
                    .append(" w:left=\"1800\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/></w:sectPr>");
            return b.toString();
        }
        SectionProperties s = seps.get(k).getSectionProperties();
        String type = switch (s.getBkc()) {
            case 0 -> "continuous";
            case 1 -> "nextColumn";
            case 3 -> "evenPage";
            case 4 -> "oddPage";
            default -> "nextPage";
        };
        b.append("<w:type w:val=\"").append(type).append("\"/>");
        int w = s.getXaPage() > 0 ? s.getXaPage() : 12240;
        int h = s.getYaPage() > 0 ? s.getYaPage() : 15840;
        b.append("<w:pgSz w:w=\"").append(w).append("\" w:h=\"").append(h).append('"');
        if (w > h) {
            b.append(" w:orient=\"landscape\"");
        }
        b.append("/><w:pgMar w:top=\"").append(s.getDyaTop()).append("\" w:right=\"").append(s.getDxaRight())
                .append("\" w:bottom=\"").append(s.getDyaBottom()).append("\" w:left=\"").append(s.getDxaLeft())
                .append("\" w:header=\"").append(s.getDyaHdrTop()).append("\" w:footer=\"").append(s.getDyaHdrBottom())
                .append("\" w:gutter=\"").append(Math.max(0, s.getDzaGutter())).append("\"/>");
        pageBorders(b, s);
        if (s.getNLnnMod() > 0) {
            b.append("<w:lnNumType w:countBy=\"").append(s.getNLnnMod()).append("\" w:distance=\"")
                    .append(Math.max(0, s.getDxaLnn())).append("\" w:start=\"").append(Math.max(0, s.getLnnMin()))
                    .append("\" w:restart=\"").append(switch (s.getLnc()) {
                        case 1 -> "newSection";
                        case 2 -> "continuous";
                        default -> "newPage";
                    }).append("\"/>");
        }
        int nfc = s.getNfcPgn();
        b.append("<w:pgNumType w:fmt=\"").append(nfc >= 0 && nfc < PAGE_FORMATS.length ? PAGE_FORMATS[nfc] : "decimal")
                .append('"');
        if (s.getFPgnRestart()) {
            b.append(" w:start=\"").append(Math.max(0, s.getPgnStart())).append('"');
        }
        b.append("/>");
        columns(b, s, raw.get(k));
        String vAlign = switch (s.getVjc()) {
            case 1 -> "center";
            case 2 -> "both";
            case 3 -> "bottom";
            default -> null;
        };
        if (vAlign != null) {
            b.append("<w:vAlign w:val=\"").append(vAlign).append("\"/>");
        }
        if (s.getFTitlePage()) {
            b.append("<w:titlePg/>");
        }
        if (s.getClm() != 0 && s.getDyaLinePitch() > 0) {
            b.append("<w:docGrid w:type=\"").append(switch (s.getClm()) {
                case 1 -> "linesAndChars";
                case 3 -> "snapToChars";
                default -> "lines";
            }).append("\" w:linePitch=\"").append(s.getDyaLinePitch()).append("\"/>");
        }
        return b.append("</w:sectPr>").toString();
    }

    private static void columns(StringBuilder b, SectionProperties s, List<Sprm> sprms) {
        int n = s.getCcolM1() + 1;
        if (n <= 1) {
            b.append("<w:cols w:space=\"").append(Math.max(0, s.getDxaColumns())).append("\"/>");
            return;
        }
        n = Math.min(n, 45);
        b.append("<w:cols w:num=\"").append(n).append("\" w:space=\"").append(Math.max(0, s.getDxaColumns()))
                .append('"');
        if (s.getFLBetween()) {
            b.append(" w:sep=\"1\"");
        }
        int[] widths = new int[n];
        int[] spaces = new int[n];
        boolean all = true;
        for (int i = 0; i < n; i++) {
            spaces[i] = Math.max(0, s.getDxaColumns());
        }
        boolean[] seen = new boolean[n];
        boolean even = s.getFEvenlySpaced();
        for (Sprm sp : sprms) {
            if (sp.opcode() == 0x3005) {
                even = sp.u8() != 0;
            }
            if ((sp.opcode() == 0xF203 || sp.opcode() == 0xF204) && sp.length() >= 3) {
                int i = sp.u8();
                int v = Sprm.u16(sp.data(), sp.at() + 1);
                if (i < n) {
                    if (sp.opcode() == 0xF203) {
                        widths[i] = v;
                        seen[i] = true;
                    } else {
                        spaces[i] = v;
                    }
                }
            }
        }
        for (boolean x : seen) {
            all &= x;
        }

        if (!even && all) {
            b.append(" w:equalWidth=\"0\">");
            for (int i = 0; i < n; i++) {
                b.append("<w:col w:w=\"").append(widths[i]).append('"');
                if (i < n - 1) {
                    b.append(" w:space=\"").append(spaces[i]).append('"');
                }
                b.append("/>");
            }
            b.append("</w:cols>");
        } else {
            b.append("/>");
        }
    }

    private static void pageBorders(StringBuilder b, SectionProperties s) {
        BorderXml.Line[] lines = {BorderXml.of(s.getBrcTop()), BorderXml.of(s.getBrcLeft()),
            BorderXml.of(s.getBrcBottom()), BorderXml.of(s.getBrcRight())};
        boolean any = false;
        for (BorderXml.Line l : lines) {
            any |= l != null && !l.none();
        }
        if (!any) {
            return;
        }
        int prop = s.getPgbProp();
        b.append("<w:pgBorders w:offsetFrom=\"").append((prop >> 5 & 7) == 1 ? "page" : "text").append("\">");
        String[] names = {"top", "left", "bottom", "right"};
        for (int i = 0; i < 4; i++) {
            if (lines[i] != null && !lines[i].none()) {
                BorderXml.side(b, names[i], lines[i]);
            }
        }
        b.append("</w:pgBorders>");
    }
}
