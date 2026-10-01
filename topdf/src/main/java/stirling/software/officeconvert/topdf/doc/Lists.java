package stirling.software.officeconvert.topdf.doc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.hwpf.model.LFO;
import org.apache.poi.hwpf.model.LFOData;
import org.apache.poi.hwpf.model.ListData;
import org.apache.poi.hwpf.model.ListFormatOverrideLevel;
import org.apache.poi.hwpf.model.ListLevel;
import org.apache.poi.hwpf.model.ListTables;
import org.apache.poi.hwpf.sprm.CharacterSprmUncompressor;
import org.apache.poi.hwpf.sprm.ParagraphSprmUncompressor;
import org.apache.poi.hwpf.usermodel.CharacterProperties;
import org.apache.poi.hwpf.usermodel.ParagraphProperties;

final class Lists {

    static final int MAX_LISTS = 2047;

    private static final String[] FORMATS = {"decimal", "upperRoman", "lowerRoman", "upperLetter", "lowerLetter",
        "ordinal", "cardinalText", "ordinalText", "hex", "chicago", "ideographDigital", "japaneseCounting", "aiueo",
        "iroha", "decimalFullWidth", "decimalHalfWidth", "japaneseLegal", "japaneseDigitalTenThousand",
        "decimalEnclosedCircle", "decimalFullWidth2", "aiueoFullWidth", "irohaFullWidth", "decimalZero", "bullet",
        "ganada", "chosung", "decimalEnclosedFullstop", "decimalEnclosedParen", "decimalEnclosedCircleChinese",
        "ideographEnclosedCircle", "ideographTraditional", "ideographZodiac", "ideographZodiacTraditional",
        "taiwaneseCounting", "ideographLegalTraditional", "taiwaneseCountingThousand", "taiwaneseDigital",
        "chineseCounting", "chineseLegalSimplified", "chineseCountingThousand", "koreanDigital", "koreanCounting",
        "koreanLegal", "koreanDigital2", "vietnameseCounting", "russianLower", "russianUpper", "none",
        "numberInDash", "hebrew1", "hebrew2", "arabicAlpha", "arabicAbjad", "hindiVowels", "hindiConsonants",
        "hindiNumbers", "hindiCounting", "thaiLetters", "thaiNumbers", "thaiCounting"};

    private final Source src;

    private final ListTables tables;

    private final Map<Integer, LFO> lfos = new LinkedHashMap<>();

    private final Map<Integer, Integer> abstracts = new LinkedHashMap<>();

    Lists(Source src) {
        this.src = src;
        ListTables lt;
        try {
            lt = src.doc.getListTables();
        } catch (RuntimeException e) {
            lt = null;
        }
        this.tables = lt;
        if (lt == null) {
            return;
        }
        for (int i = 1; i <= MAX_LISTS; i++) {
            LFO lfo;
            try {
                lfo = lt.getLfo(i);
            } catch (RuntimeException e) {
                break;
            }
            if (lfo == null) {
                break;
            }
            ListData ld;
            try {
                ld = lt.getListData(lfo.getLsid());
            } catch (RuntimeException e) {
                ld = null;
            }
            if (ld == null) {
                continue;
            }
            lfos.put(i, lfo);
            abstracts.putIfAbsent(lfo.getLsid(), abstracts.size() + 1);
        }
    }

    String numPr(int ilfo, int ilvl) {
        if (ilfo <= 0 || !lfos.containsKey(ilfo)) {
            return null;
        }
        return "<w:numPr><w:ilvl w:val=\"" + Math.max(0, Math.min(8, ilvl)) + "\"/><w:numId w:val=\"" + ilfo
                + "\"/></w:numPr>";
    }

    boolean isEmpty() {
        return lfos.isEmpty();
    }

    String part() {
        StringBuilder b = new StringBuilder(Xml.HEAD).append("<w:numbering").append(Xml.NAMESPACES).append('>');
        for (Map.Entry<Integer, Integer> e : abstracts.entrySet()) {
            ListData ld = tables.getListData(e.getKey());
            b.append("<w:abstractNum w:abstractNumId=\"").append(e.getValue()).append("\"><w:multiLevelType w:val=\"")
                    .append(ld.numLevels() == 1 ? "singleLevel" : "multilevel").append("\"/>");
            ListLevel[] levels = ld.getLevels();
            for (int i = 0; i < levels.length && i < 9; i++) {
                level(b, i, levels[i]);
            }
            b.append("</w:abstractNum>");
        }
        for (Map.Entry<Integer, LFO> e : lfos.entrySet()) {
            b.append("<w:num w:numId=\"").append(e.getKey()).append("\"><w:abstractNumId w:val=\"")
                    .append(abstracts.get(e.getValue().getLsid())).append("\"/>");
            overrides(b, e.getKey());
            b.append("</w:num>");
        }
        return b.append("</w:numbering>").toString();
    }

    private void overrides(StringBuilder b, int ilfo) {
        LFOData data;
        try {
            data = tables.getLfoData(ilfo);
        } catch (RuntimeException e) {
            return;
        }
        if (data == null || data.getRgLfoLvl() == null) {
            return;
        }
        for (ListFormatOverrideLevel o : data.getRgLfoLvl()) {
            if (o == null || o.getLevelNum() < 0 || o.getLevelNum() > 8) {
                continue;
            }
            if (o.isFormatting() && o.getLevel() != null) {
                b.append("<w:lvlOverride w:ilvl=\"").append(o.getLevelNum()).append("\">");
                if (o.isStartAt()) {
                    b.append("<w:startOverride w:val=\"").append(o.getIStartAt()).append("\"/>");
                }
                level(b, o.getLevelNum(), o.getLevel());
                b.append("</w:lvlOverride>");
            } else if (o.isStartAt()) {
                b.append("<w:lvlOverride w:ilvl=\"").append(o.getLevelNum()).append("\"><w:startOverride w:val=\"")
                        .append(o.getIStartAt()).append("\"/></w:lvlOverride>");
            }
        }
    }

    private void level(StringBuilder b, int i, ListLevel l) {
        if (l == null) {
            return;
        }
        int nfc = l.getNumberFormat();
        String fmt = nfc == 255 ? "none" : nfc >= 0 && nfc < FORMATS.length ? FORMATS[nfc] : "decimal";
        b.append("<w:lvl w:ilvl=\"").append(i).append("\"><w:start w:val=\"").append(l.getStartAt())
                .append("\"/><w:numFmt w:val=\"").append(fmt).append("\"/>");
        if (l.isLegalNumbering()) {
            b.append("<w:isLgl/>");
        }
        String suff = switch (l.getTypeOfCharFollowingTheNumber()) {
            case 1 -> "space";
            case 2 -> "nothing";
            default -> "tab";
        };
        b.append("<w:suff w:val=\"").append(suff).append("\"/><w:lvlText w:val=\"").append(Xml.esc(text(l)))
                .append("\"/><w:lvlJc w:val=\"").append(switch (l.getAlignment()) {
                    case 1 -> "center";
                    case 2 -> "right";
                    default -> "left";
                }).append("\"/>");
        paragraph(b, l.getGrpprlPapx());
        run(b, l.getGrpprlChpx());
        b.append("</w:lvl>");
    }

    private static String text(ListLevel l) {
        String t = l.getNumberText();
        if (t == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < 9) {
                b.append('%').append((int) c + 1);
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static void paragraph(StringBuilder b, byte[] grpprl) {
        if (grpprl == null || grpprl.length == 0) {
            return;
        }
        ParagraphProperties p;
        try {
            p = ParagraphSprmUncompressor.uncompressPAP(new ParagraphProperties(), grpprl, 0);
        } catch (RuntimeException e) {
            return;
        }
        b.append("<w:pPr>");
        int n = p.getItbdMac();
        int[] tabs = p.getRgdxaTab();
        if (n > 0 && tabs != null) {
            b.append("<w:tabs>");
            for (int k = 0; k < Math.min(n, tabs.length); k++) {
                b.append("<w:tab w:val=\"num\" w:pos=\"").append(tabs[k]).append("\"/>");
            }
            b.append("</w:tabs>");
        }
        b.append("<w:ind w:left=\"").append(p.getDxaLeft()).append('"');
        int f = p.getDxaLeft1();
        if (f < 0) {
            b.append(" w:hanging=\"").append(-f).append('"');
        } else {
            b.append(" w:firstLine=\"").append(f).append('"');
        }
        b.append("/></w:pPr>");
    }

    private void run(StringBuilder b, byte[] grpprl) {
        if (grpprl == null || grpprl.length == 0) {
            return;
        }
        List<Sprm> sprms = Sprm.parse(grpprl, 0);
        CharacterProperties c;
        try {
            c = CharacterSprmUncompressor.uncompressCHP(src.styles, new CharacterProperties(), grpprl, 0);
        } catch (RuntimeException e) {
            return;
        }
        StringBuilder r = new StringBuilder();
        String ascii = has(sprms, 0x4A4F, 0x4A3D) ? src.runs.font(c.getFtcAscii()) : null;
        String other = has(sprms, 0x4A51, 0x4A3D) ? src.runs.font(c.getFtcOther()) : null;
        String fe = has(sprms, 0x4A50) ? src.runs.font(c.getFtcFE()) : null;
        String cs = has(sprms, 0x4A5E) ? src.runs.font(c.getFtcBi()) : null;
        if (ascii != null || other != null || fe != null || cs != null) {
            r.append("<w:rFonts");
            font(r, "ascii", ascii);
            font(r, "hAnsi", other == null ? ascii : other);
            font(r, "eastAsia", fe);
            font(r, "cs", cs);
            r.append("/>");
        }
        toggle(r, sprms, 0x0835, "b", c.isFBold());
        toggle(r, sprms, 0x0836, "i", c.isFItalic());
        toggle(r, sprms, 0x083B, "caps", c.isFCaps());
        toggle(r, sprms, 0x083A, "smallCaps", c.isFSmallCaps());
        toggle(r, sprms, 0x0837, "strike", c.isFStrike());
        toggle(r, sprms, 0x083C, "vanish", c.isFVanish());
        if (has(sprms, 0x2A42, 0x6870)) {
            int rgb = RunXml.color(c);
            if (rgb >= 0) {
                r.append("<w:color w:val=\"").append(Xml.hex(rgb)).append("\"/>");
            }
        }
        if (has(sprms, 0x4A43) && c.getHps() > 0) {
            r.append("<w:sz w:val=\"").append(c.getHps()).append("\"/>");
        }
        if (has(sprms, 0x2A3E)) {
            String u = RunXml.underline(c.getKul());
            r.append("<w:u w:val=\"").append(u == null ? "none" : u).append("\"/>");
        }
        if (!r.isEmpty()) {
            b.append("<w:rPr>").append(r).append("</w:rPr>");
        }
    }

    private static void font(StringBuilder r, String name, String v) {
        if (v != null) {
            r.append(" w:").append(name).append("=\"").append(Xml.esc(v)).append('"');
        }
    }

    private static void toggle(StringBuilder r, List<Sprm> sprms, int op, String name, boolean v) {
        if (has(sprms, op)) {
            r.append("<w:").append(name).append(v ? "/>" : " w:val=\"0\"/>");
        }
    }

    private static boolean has(List<Sprm> sprms, int... ops) {
        for (Sprm s : sprms) {
            for (int op : ops) {
                if (s.opcode() == op) {
                    return true;
                }
            }
        }
        return false;
    }
}
