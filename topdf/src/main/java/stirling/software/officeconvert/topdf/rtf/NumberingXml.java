package stirling.software.officeconvert.topdf.rtf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.topdf.io.NumberFormatCodes;

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
                .append("\"/><w:numFmt w:val=\"").append(NumberFormatCodes.ooxml(l.format)).append("\"/>");
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
        List<String> units = new ArrayList<>();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 1; i < t.size(); i++) {
            int v = t.get(i);
            if (v >= 0x10000) {
                drain(bytes, cs, units);
                units.add(String.valueOf((char) (v & 0xFFFF)));
            } else if (v < 9) {
                drain(bytes, cs, units);
                units.add("%" + (v + 1));
            } else if (symbol) {
                units.add(String.valueOf((char) (v >= 0x20 ? 0xF000 + v : v)));
            } else {
                bytes.write(v);
            }
        }
        drain(bytes, cs, units);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(length, units.size()); i++) {
            out.append(units.get(i));
        }
        return out.toString();
    }

    private static void drain(ByteArrayOutputStream bytes, Charset cs, List<String> units) {
        if (bytes.size() > 0) {
            String s = new String(bytes.toByteArray(), cs);
            for (int i = 0; i < s.length(); i += Character.charCount(s.codePointAt(i))) {
                units.add(new String(Character.toChars(s.codePointAt(i))));
            }
            bytes.reset();
        }
    }
}
