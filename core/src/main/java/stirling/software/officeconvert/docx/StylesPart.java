package stirling.software.officeconvert.docx;

import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.StyleSheet;

final class StylesPart {

    private StylesPart() {}

    static String xml(StyleSheet sheet) {
        RunStyle normal = sheet.normal;
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
                .append("<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">")
                .append("<w:docDefaults><w:rPrDefault><w:rPr>");
        fonts(sb, normal.font());
        int hp = Xml.halfPoints(normal.size());
        sb.append("<w:sz w:val=\"").append(hp).append("\"/><w:szCs w:val=\"").append(hp).append("\"/>")
                .append("<w:lang w:val=\"en-US\" w:eastAsia=\"en-US\" w:bidi=\"ar-SA\"/>")
                .append("</w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:widowControl w:val=\"0\"/>")
                .append("<w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault>")
                .append("</w:docDefaults>");
        sb.append("<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/>");
        if (normal.bold() || normal.italic() || normal.rgb() != 0) {
            sb.append("<w:rPr>");
            if (normal.bold()) {
                sb.append("<w:b/><w:bCs/>");
            }
            if (normal.italic()) {
                sb.append("<w:i/><w:iCs/>");
            }
            if (normal.rgb() != 0) {
                sb.append("<w:color w:val=\"").append(Xml.hex(normal.rgb())).append("\"/>");
            }
            sb.append("</w:rPr>");
        }
        sb.append("</w:style>");
        sb.append("<w:style w:type=\"character\" w:default=\"1\" w:styleId=\"DefaultParagraphFont\">")
                .append("<w:name w:val=\"Default Paragraph Font\"/><w:uiPriority w:val=\"1\"/><w:semiHidden/><w:unhideWhenUsed/></w:style>");
        sb.append("<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableNormal\"><w:name w:val=\"Normal Table\"/>")
                .append("<w:uiPriority w:val=\"99\"/><w:semiHidden/><w:unhideWhenUsed/><w:tblPr><w:tblInd w:w=\"0\" w:type=\"dxa\"/>")
                .append("<w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"108\" w:type=\"dxa\"/>")
                .append("<w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>");
        sb.append("<w:style w:type=\"numbering\" w:default=\"1\" w:styleId=\"NoList\"><w:name w:val=\"No List\"/>")
                .append("<w:uiPriority w:val=\"99\"/><w:semiHidden/><w:unhideWhenUsed/></w:style>");
        for (StyleSheet.Style s : sheet.all()) {
            if (s.id().equals("Normal")) {
                continue;
            }
            sb.append("<w:style w:type=\"paragraph\" w:styleId=\"").append(s.id()).append("\"><w:name w:val=\"")
                    .append(Xml.esc(s.name())).append("\"/><w:basedOn w:val=\"Normal\"/>");
            if (s.outlineLevel() >= 0 || s.id().equals("Title")) {
                sb.append("<w:next w:val=\"Normal\"/>");
            }
            if (s.outlineLevel() >= 0) {
                sb.append("<w:uiPriority w:val=\"9\"/>");
            }
            sb.append("<w:qFormat/><w:pPr>");
            if (s.keepNext()) {
                sb.append("<w:keepNext/><w:keepLines/>");
            }
            if (s.id().equals("ListParagraph")) {
                sb.append("<w:ind w:left=\"720\"/>");
            }
            if (s.outlineLevel() >= 0) {
                sb.append("<w:outlineLvl w:val=\"").append(s.outlineLevel()).append("\"/>");
            }
            sb.append("</w:pPr>");
            RunStyle r = s.run();
            if (r != null && r != normal) {
                sb.append("<w:rPr>");
                if (!r.font().equals(normal.font())) {
                    fonts(sb, r.font());
                }
                if (r.bold()) {
                    sb.append("<w:b/><w:bCs/>");
                }
                if (r.italic()) {
                    sb.append("<w:i/><w:iCs/>");
                }
                if (r.smallCaps()) {
                    sb.append("<w:smallCaps/>");
                }
                if (r.rgb() != 0) {
                    sb.append("<w:color w:val=\"").append(Xml.hex(r.rgb())).append("\"/>");
                }
                int size = Xml.halfPoints(r.size());
                sb.append("<w:sz w:val=\"").append(size).append("\"/><w:szCs w:val=\"").append(size).append("\"/>");
                sb.append("</w:rPr>");
            }
            sb.append("</w:style>");
        }
        sb.append("</w:styles>");
        return sb.toString();
    }

    private static void fonts(StringBuilder sb, String font) {
        String f = Xml.esc(font);
        sb.append("<w:rFonts w:ascii=\"").append(f).append("\" w:hAnsi=\"").append(f).append("\" w:eastAsia=\"")
                .append(f).append("\" w:cs=\"").append(f).append("\"/>");
    }
}
