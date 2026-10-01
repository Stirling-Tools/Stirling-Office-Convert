package stirling.software.officeconvert.topdf.rtf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class NumberingXml {

    private NumberingXml() {}

    static String write(Doc doc, PropsXml props) {
        StringBuilder b = new StringBuilder(8192).append(Xml.HEAD).append("<w:numbering ").append(RtfPackage.NS)
                .append('>');
        Map<Integer, Integer> abstractIds = new HashMap<>();
        int n = 0;
        for (ListTable.ListDef d : doc.lists.lists.values()) {
            abstractIds.put(d.id, n);
            b.append("<w:abstractNum w:abstractNumId=\"").append(n).append("\"><w:multiLevelType w:val=\"")
                    .append(d.levels.size() <= 1 ? "singleLevel" : "hybridMultilevel").append("\"/>");
            for (int i = 0; i < d.levels.size(); i++) {
                level(b, doc, props, d.levels.get(i), i);
            }
            b.append("</w:abstractNum>");
            n++;
        }
        for (ListTable.Override o : doc.lists.overrides.values()) {
            Integer a = abstractIds.get(o.listId);
            if (a == null) {
                continue;
            }
            b.append("<w:num w:numId=\"").append(o.ls).append("\"><w:abstractNumId w:val=\"").append(a)
                    .append("\"/>");
            for (Map.Entry<Integer, Integer> e : o.starts.entrySet()) {
                b.append("<w:lvlOverride w:ilvl=\"").append(e.getKey()).append("\"><w:startOverride w:val=\"")
                        .append(e.getValue()).append("\"/></w:lvlOverride>");
            }
            b.append("</w:num>");
        }
        return b.append("</w:numbering>").toString();
    }

    private static void level(StringBuilder b, Doc doc, PropsXml props, ListTable.Level l, int index) {
        b.append("<w:lvl w:ilvl=\"").append(index).append("\"><w:start w:val=\"").append(l.start)
                .append("\"/><w:numFmt w:val=\"").append(format(l.format)).append("\"/>");
        if (l.noRestart) {
            b.append("<w:lvlRestart w:val=\"0\"/>");
        }
        if (l.legal) {
            b.append("<w:isLgl/>");
        }
        b.append("<w:suff w:val=\"").append(l.follow == 1 ? "space" : l.follow == 2 ? "nothing" : "tab")
                .append("\"/><w:lvlText w:val=\"").append(Xml.attr(text(doc, l))).append("\"/><w:lvlJc w:val=\"")
                .append(l.justify == 1 ? "center" : l.justify == 2 ? "right" : "left").append("\"/>");
        ParaProps p = l.pap.copy();
        p.style = -1;
        b.append(props.pPr(p, null, null, false));
        String r = props.rPrInner(l.chp, false);
        if (!r.isEmpty()) {
            b.append("<w:rPr>").append(r).append("</w:rPr>");
        }
        b.append("</w:lvl>");
    }

    static String text(Doc doc, ListTable.Level l) {
        List<Integer> t = l.text;
        if (t.isEmpty()) {
            return "";
        }
        int length = t.get(0) & 0xFFFF;
        int font = l.chp.has(CharProps.FONT) ? l.chp.font : -1;
        boolean symbol = font >= 0 && doc.fonts.symbol(font);
        Charset cs = font >= 0 ? doc.charset(font) : doc.ansi;
        StringBuilder out = new StringBuilder();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int count = 0;
        for (int i = 1; i < t.size() && count < length; i++) {
            int v = t.get(i);
            if (v >= 0x10000) {
                drain(bytes, cs, out);
                out.append((char) (v & 0xFFFF));
            } else if (v < 9) {
                drain(bytes, cs, out);
                out.append('%').append(v + 1);
            } else if (symbol) {
                out.append((char) (v >= 0x20 ? 0xF000 + v : v));
            } else {
                bytes.write(v);
            }
            count++;
        }
        drain(bytes, cs, out);
        return out.toString();
    }

    private static void drain(ByteArrayOutputStream bytes, Charset cs, StringBuilder out) {
        if (bytes.size() > 0) {
            out.append(new String(bytes.toByteArray(), cs));
            bytes.reset();
        }
    }

    static String format(int nfc) {
        return switch (nfc) {
            case 0 -> "decimal";
            case 1 -> "upperRoman";
            case 2 -> "lowerRoman";
            case 3 -> "upperLetter";
            case 4 -> "lowerLetter";
            case 5 -> "ordinal";
            case 6 -> "cardinalText";
            case 7 -> "ordinalText";
            case 10 -> "ideographDigital";
            case 11 -> "japaneseCounting";
            case 12 -> "aiueo";
            case 13 -> "iroha";
            case 14 -> "decimalFullWidth";
            case 15 -> "decimalHalfWidth";
            case 16 -> "japaneseLegal";
            case 17 -> "japaneseDigitalTenThousand";
            case 18 -> "decimalEnclosedCircle";
            case 19 -> "decimalFullWidth2";
            case 20 -> "aiueoFullWidth";
            case 21 -> "irohaFullWidth";
            case 22 -> "decimalZero";
            case 23 -> "bullet";
            case 24 -> "ganada";
            case 25 -> "chosung";
            case 26 -> "decimalEnclosedFullstop";
            case 27 -> "decimalEnclosedParen";
            case 28 -> "decimalEnclosedCircleChinese";
            case 29 -> "ideographEnclosedCircle";
            case 30 -> "ideographTraditional";
            case 31 -> "ideographZodiac";
            case 32 -> "ideographZodiacTraditional";
            case 33 -> "taiwaneseCounting";
            case 34 -> "ideographLegalTraditional";
            case 35 -> "taiwaneseCountingThousand";
            case 36 -> "taiwaneseDigital";
            case 37 -> "chineseCounting";
            case 38 -> "chineseLegalSimplified";
            case 39 -> "chineseCountingThousand";
            case 41 -> "koreanDigital";
            case 42 -> "koreanCounting";
            case 43 -> "koreanLegal";
            case 44 -> "koreanDigital2";
            case 45 -> "hebrew1";
            case 46 -> "arabicAlpha";
            case 47 -> "hebrew2";
            case 48 -> "arabicAbjad";
            case 49 -> "hindiVowels";
            case 50 -> "hindiConsonants";
            case 51 -> "hindiNumbers";
            case 52 -> "hindiCounting";
            case 53 -> "thaiLetters";
            case 54 -> "thaiNumbers";
            case 55 -> "thaiCounting";
            case 56 -> "vietnameseCounting";
            case 57 -> "numberInDash";
            case 58 -> "russianLower";
            case 59 -> "russianUpper";
            case 255 -> "none";
            default -> "decimal";
        };
    }
}
