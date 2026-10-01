package stirling.software.officeconvert.topdf.doc;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.poi.hwpf.model.StyleDescription;
import org.apache.poi.hwpf.model.StyleSheet;

final class Styles {

    private final Map<Integer, String> ids = new HashMap<>();

    private final Map<Integer, String> names = new HashMap<>();

    private final RunXml runs;

    private final StyleSheet sheet;

    Styles(Source src) {
        this.runs = src.runs;
        this.sheet = src.styles;
        if (sheet == null) {
            return;
        }
        Set<String> used = new HashSet<>();
        int n = Math.min(sheet.numStyles(), 4096);
        for (int i = 0; i < n; i++) {
            StyleDescription sd;
            try {
                sd = sheet.getStyleDescription(i);
            } catch (RuntimeException e) {
                continue;
            }
            if (sd == null || sd.getPAPX() == null) {
                continue;
            }
            String name = sd.getName() == null || sd.getName().isBlank() ? "Style " + i : sd.getName().strip();
            String id = i == 0 ? "Normal" : sanitize(name);
            if (id.isEmpty() || !used.add(id)) {
                id = "S" + i;
                used.add(id);
            }
            ids.put(i, id);
            names.put(i, i == 0 ? "Normal" : name);
        }
    }

    String id(int istd) {
        return ids.get(istd);
    }

    private static String sanitize(String name) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < name.length() && b.length() < 64; i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) && c < 0x80) {
                b.append(c);
            }
        }
        return b.toString();
    }

    String part() {
        StringBuilder b = new StringBuilder(Xml.HEAD).append("<w:styles").append(Xml.NAMESPACES).append('>');
        b.append("<w:docDefaults><w:rPrDefault><w:rPr>");
        String font = runs.font(0);
        if (font != null) {
            String f = Xml.esc(font);
            b.append("<w:rFonts w:ascii=\"").append(f).append("\" w:hAnsi=\"").append(f).append("\" w:cs=\"")
                    .append(f).append("\"/>");
        }
        b.append("<w:sz w:val=\"20\"/><w:szCs w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr/>")
                .append("</w:pPrDefault></w:docDefaults>");
        if (!ids.containsKey(0)) {
            b.append("<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/>")
                    .append("</w:style>");
        }
        for (Map.Entry<Integer, String> e : ids.entrySet()) {
            b.append("<w:style w:type=\"paragraph\"");
            if (e.getKey() == 0) {
                b.append(" w:default=\"1\"");
            }
            b.append(" w:styleId=\"").append(e.getValue()).append("\"><w:name w:val=\"")
                    .append(Xml.esc(names.get(e.getKey()))).append("\"/></w:style>");
        }
        b.append("<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableNormal\"><w:name w:val=\"Normal Table\"/>")
                .append("<w:tblPr><w:tblInd w:w=\"0\" w:type=\"dxa\"/><w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/>")
                .append("<w:left w:w=\"108\" w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/>")
                .append("<w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>");
        return b.append("</w:styles>").toString();
    }
}
