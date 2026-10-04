package stirling.software.officeconvert.topdf.rtf;

final class StylesXml {

    private StylesXml() {}

    static String write(Doc doc, PropsXml props) {
        StringBuilder b = new StringBuilder(8192).append(Xml.HEAD).append("<w:styles ").append(RtfPackage.NS)
                .append("><w:docDefaults><w:rPrDefault><w:rPr>");
        CharProps d = doc.defChp.copy();
        int deff = doc.defaultFont;
        defaultFont(d, CharProps.FONT, doc.loFont, deff);
        defaultFont(d, CharProps.H_FONT, doc.hiFont, d.has(CharProps.FONT) ? d.font : deff);
        defaultFont(d, CharProps.EA_FONT, doc.eaFont, deff);
        defaultFont(d, CharProps.CS_FONT, doc.biFont, deff);
        if (!d.has(CharProps.SIZE)) {
            d.size = 24;
            d.mark(CharProps.SIZE);
        }
        if (!d.has(CharProps.CS_SIZE)) {
            d.csSize = d.size;
            d.mark(CharProps.CS_SIZE);
        }
        b.append(props.rPrInner(d, false)).append("</w:rPr></w:rPrDefault><w:pPrDefault>")
                .append(props.pPr(doc.defPap, null, null, false)).append("</w:pPrDefault></w:docDefaults>");
        boolean normal = false;
        for (StyleSheet.Style s : doc.styles.all()) {
            boolean isDefault = s.type == 'p' && s.id == 0;
            normal |= isDefault;
            style(b, doc, props, s, isDefault);
        }
        if (!normal) {
            b.append("<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"s0\"><w:name w:val=\"Normal\"/>")
                    .append("</w:style>");
        }
        return b.append("</w:styles>").toString();
    }

    private static void defaultFont(CharProps d, int prop, int preferred, int fallback) {
        if (d.has(prop)) {
            return;
        }
        int f = preferred >= 0 ? preferred : fallback;
        if (f < 0) {
            return;
        }
        switch (prop) {
            case CharProps.FONT -> d.font = f;
            case CharProps.H_FONT -> d.hFont = f;
            case CharProps.EA_FONT -> d.eaFont = f;
            default -> d.csFont = f;
        }
        d.mark(prop);
    }

    private static void style(StringBuilder b, Doc doc, PropsXml props, StyleSheet.Style s, boolean isDefault) {
        String id = s.styleId();
        b.append("<w:style w:type=\"").append(s.type == 'c' ? "character" : "paragraph").append('"');
        if (isDefault) {
            b.append(" w:default=\"1\"");
        }
        b.append(" w:styleId=\"").append(id).append("\"><w:name w:val=\"")
                .append(Xml.attr(s.name.isEmpty() ? id : s.name)).append("\"/>");
        StyleSheet.Style base = doc.styles.basedOn(s);
        if (base != null && base.type == s.type && !isDefault && !loops(doc, s)) {
            b.append("<w:basedOn w:val=\"").append(base.styleId()).append("\"/>");
        }
        if (s.type == 'p' && s.next >= 0 && doc.styles.paragraph(s.next) != null) {
            b.append("<w:next w:val=\"s").append(s.next).append("\"/>");
        }
        if (s.type == 'p') {
            b.append(props.pPr(s.pap, null, null, false));
        }
        String r = props.rPrInner(s.chp, false);
        if (!r.isEmpty()) {
            b.append("<w:rPr>").append(r).append("</w:rPr>");
        }
        b.append("</w:style>");
    }

    private static boolean loops(Doc doc, StyleSheet.Style s) {
        StyleSheet.Style at = doc.styles.basedOn(s);
        for (int i = 0; at != null && i < 64; i++) {
            if (at == s) {
                return true;
            }
            at = doc.styles.basedOn(at);
        }
        return at != null;
    }
}
