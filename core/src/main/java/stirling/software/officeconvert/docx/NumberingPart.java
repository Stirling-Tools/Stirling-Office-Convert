package stirling.software.officeconvert.docx;

import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.StyleSheet;

final class NumberingPart {

    private NumberingPart() {}

    static String xml(Numbering numbering, StyleSheet styles) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
                .append("<w:numbering xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">");
        for (Numbering.Instance inst : numbering.instances) {
            Numbering.AbstractList def = inst.definition;
            sb.append("<w:abstractNum w:abstractNumId=\"").append(def.id).append("\">")
                    .append("<w:multiLevelType w:val=\"hybridMultilevel\"/>");
            for (int lvl = 0; lvl < 9; lvl++) {
                Numbering.Level l = def.levels[lvl];
                String format = l != null ? l.format() : "decimal";
                String text = l != null ? l.text() : "%" + (lvl + 1) + ".";
                float left = l != null ? l.indentLeft() : 36f * (lvl + 1);
                float hanging = l != null ? l.hanging() : 18f;
                sb.append("<w:lvl w:ilvl=\"").append(lvl).append("\">")
                        .append("<w:start w:val=\"").append(Math.max(0, inst.starts[lvl])).append("\"/>")
                        .append("<w:numFmt w:val=\"").append(format).append("\"/>")
                        .append("<w:lvlText w:val=\"").append(Xml.esc(text)).append("\"/>")
                        .append("<w:lvlJc w:val=\"left\"/>")
                        .append("<w:pPr><w:ind w:left=\"").append(Xml.twips(left)).append("\" w:hanging=\"")
                        .append(Xml.twips(hanging)).append("\"/></w:pPr>");
                if (l != null && l.markerStyle() != null) {
                    RunStyle m = l.markerStyle();
                    RunStyle base = styles.normal;
                    sb.append("<w:rPr>");
                    if (m.font() != null && ("bullet".equals(format) || !m.font().equals(base.font()))) {
                        String f = Xml.esc(m.font());
                        sb.append("<w:rFonts w:ascii=\"").append(f).append("\" w:hAnsi=\"").append(f)
                                .append("\" w:eastAsia=\"").append(f).append("\" w:cs=\"").append(f)
                                .append("\" w:hint=\"default\"/>");
                    }
                    sb.append(m.bold() ? "<w:b/>" : "<w:b w:val=\"0\"/>");
                    sb.append(m.italic() ? "<w:i/>" : "<w:i w:val=\"0\"/>");
                    if (m.rgb() != 0) {
                        sb.append("<w:color w:val=\"").append(Xml.hex(m.rgb())).append("\"/>");
                    }
                    int hp = Xml.halfPoints(m.size());
                    sb.append("<w:sz w:val=\"").append(hp).append("\"/><w:szCs w:val=\"").append(hp).append("\"/>");
                    sb.append("</w:rPr>");
                }
                sb.append("</w:lvl>");
            }
            sb.append("</w:abstractNum>");
        }
        for (Numbering.Instance inst : numbering.instances) {
            sb.append("<w:num w:numId=\"").append(inst.numId).append("\"><w:abstractNumId w:val=\"")
                    .append(inst.definition.id).append("\"/></w:num>");
        }
        sb.append("</w:numbering>");
        return sb.toString();
    }
}
