package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;

final class RunProps {

    static final Color AUTO = new Color(0, 0, 0, 0);

    String ascii;
    String hAnsi;
    String eastAsia;
    String cs;
    String asciiTheme;
    String hAnsiTheme;
    String eastAsiaTheme;
    String csTheme;
    String hint;
    Float size;
    Float sizeCs;
    Boolean bold;
    Boolean boldCs;
    Boolean italic;
    Boolean italicCs;
    Boolean caps;
    Boolean smallCaps;
    Boolean strike;
    Boolean dstrike;
    Boolean vanish;
    Boolean specVanish;
    Boolean rtl;
    Boolean complex;
    Boolean emboss;
    Boolean imprint;
    Boolean outline;
    String underline;
    Color underlineColor;
    Color color;
    Color highlight;
    Color shading;
    Float spacing;
    Integer scale;
    Float position;
    Float kern;
    String vertAlign;
    Border border;
    String styleId;
    String eastAsiaLang;
    String lang;
    Boolean snapToGrid;

    RunProps copy() {
        RunProps r = new RunProps();
        r.mergeFrom(this);
        r.styleId = styleId;
        return r;
    }

    void mergeFrom(RunProps o) {
        if (o == null) {
            return;
        }
        if (o.ascii != null || o.asciiTheme != null) {
            ascii = o.ascii;
            asciiTheme = o.asciiTheme;
        }
        if (o.hAnsi != null || o.hAnsiTheme != null) {
            hAnsi = o.hAnsi;
            hAnsiTheme = o.hAnsiTheme;
        }
        if (o.eastAsia != null || o.eastAsiaTheme != null) {
            eastAsia = o.eastAsia;
            eastAsiaTheme = o.eastAsiaTheme;
        }
        if (o.cs != null || o.csTheme != null) {
            cs = o.cs;
            csTheme = o.csTheme;
        }
        hint = o.hint != null ? o.hint : hint;
        size = o.size != null ? o.size : size;
        sizeCs = o.sizeCs != null ? o.sizeCs : sizeCs;
        bold = o.bold != null ? o.bold : bold;
        boldCs = o.boldCs != null ? o.boldCs : boldCs;
        italic = o.italic != null ? o.italic : italic;
        italicCs = o.italicCs != null ? o.italicCs : italicCs;
        caps = o.caps != null ? o.caps : caps;
        smallCaps = o.smallCaps != null ? o.smallCaps : smallCaps;
        strike = o.strike != null ? o.strike : strike;
        dstrike = o.dstrike != null ? o.dstrike : dstrike;
        vanish = o.vanish != null ? o.vanish : vanish;
        specVanish = o.specVanish != null ? o.specVanish : specVanish;
        rtl = o.rtl != null ? o.rtl : rtl;
        complex = o.complex != null ? o.complex : complex;
        emboss = o.emboss != null ? o.emboss : emboss;
        imprint = o.imprint != null ? o.imprint : imprint;
        outline = o.outline != null ? o.outline : outline;
        if (o.underline != null) {
            underline = o.underline;
            underlineColor = o.underlineColor;
        }
        color = o.color != null ? o.color : color;
        highlight = o.highlight != null ? o.highlight : highlight;
        shading = o.shading != null ? o.shading : shading;
        spacing = o.spacing != null ? o.spacing : spacing;
        scale = o.scale != null ? o.scale : scale;
        position = o.position != null ? o.position : position;
        kern = o.kern != null ? o.kern : kern;
        vertAlign = o.vertAlign != null ? o.vertAlign : vertAlign;
        border = o.border != null ? o.border : border;
        eastAsiaLang = o.eastAsiaLang != null ? o.eastAsiaLang : eastAsiaLang;
        lang = o.lang != null ? o.lang : lang;
        snapToGrid = o.snapToGrid != null ? o.snapToGrid : snapToGrid;
    }

    static RunProps parse(XEl rPr, Theme theme) {
        RunProps r = new RunProps();
        r.apply(rPr, theme);
        return r;
    }

    void apply(XEl rPr, Theme theme) {
        if (rPr == null) {
            return;
        }
        for (XEl k : rPr.kids) {
            switch (k.name) {
                case "w:rStyle" -> styleId = k.val();
                case "w:rFonts" -> fonts(k);
                case "w:b" -> bold = Ooxml.on(k);
                case "w:bCs" -> boldCs = Ooxml.on(k);
                case "w:i" -> italic = Ooxml.on(k);
                case "w:iCs" -> italicCs = Ooxml.on(k);
                case "w:caps" -> caps = Ooxml.on(k);
                case "w:smallCaps" -> smallCaps = Ooxml.on(k);
                case "w:strike" -> strike = Ooxml.on(k);
                case "w:dstrike" -> dstrike = Ooxml.on(k);
                case "w:vanish" -> vanish = Ooxml.on(k);
                case "w:specVanish" -> specVanish = Ooxml.on(k);
                case "w:rtl" -> rtl = Ooxml.on(k);
                case "w:cs" -> complex = Ooxml.on(k);
                case "w:emboss" -> emboss = Ooxml.on(k);
                case "w:imprint" -> imprint = Ooxml.on(k);
                case "w:outline" -> outline = Ooxml.on(k);
                case "w:sz" -> {
                    Float s = Ooxml.halfPoints(k.val());
                    if (s != null && s > 0) {
                        size = Math.min(1638, s);
                    }
                }
                case "w:szCs" -> {
                    Float s = Ooxml.halfPoints(k.val());
                    if (s != null && s > 0) {
                        sizeCs = Math.min(1638, s);
                    }
                }
                case "w:u" -> {
                    String v = k.val();
                    underline = v == null ? "single" : v;
                    Color c = Colors.attribute(k, theme);
                    underlineColor = c;
                }
                case "w:color" -> {
                    Color c = Colors.word(k, theme);
                    color = c == null ? AUTO : c;
                }
                case "w:highlight" -> {
                    Color c = Colors.highlight(k.val());
                    highlight = c == null ? AUTO : c;
                }
                case "w:shd" -> {
                    Color c = Shading.parse(k, theme);
                    shading = c == null ? AUTO : c;
                }
                case "w:spacing" -> spacing = Ooxml.twips(k.val());
                case "w:w" -> {
                    Integer w = Ooxml.integer(k.val() == null ? null : k.val().replace("%", ""));
                    if (w != null && w > 0) {
                        scale = Math.min(600, w);
                    }
                }
                case "w:position" -> position = Ooxml.halfPoints(k.val());
                case "w:kern" -> kern = Ooxml.halfPoints(k.val());
                case "w:vertAlign" -> vertAlign = k.val();
                case "w:bdr" -> border = Border.parse(k, theme);
                case "w:lang" -> {
                    eastAsiaLang = k.attr("eastAsia");
                    lang = k.val();
                }
                case "w:snapToGrid" -> snapToGrid = Ooxml.on(k);
                default -> {
                }
            }
        }
    }

    private void fonts(XEl k) {
        String a = k.attr("ascii");
        String at = k.attr("asciiTheme");
        if (a != null || at != null) {
            ascii = a;
            asciiTheme = at;
        }
        String h = k.attr("hAnsi");
        String ht = k.attr("hAnsiTheme");
        if (h != null || ht != null) {
            hAnsi = h;
            hAnsiTheme = ht;
        }
        String e = k.attr("eastAsia");
        String et = k.attr("eastAsiaTheme");
        if (e != null || et != null) {
            eastAsia = e;
            eastAsiaTheme = et;
        }
        String c = k.attr("cs");
        String ct = k.attr("cstheme");
        if (ct == null) {
            ct = k.attr("csTheme");
        }
        if (c != null || ct != null) {
            cs = c;
            csTheme = ct;
        }
        if (k.attr("hint") != null) {
            hint = k.attr("hint");
        }
    }

    boolean isBold() {
        return Boolean.TRUE.equals(bold);
    }

    boolean isItalic() {
        return Boolean.TRUE.equals(italic);
    }

    boolean hidden() {
        return Boolean.TRUE.equals(vanish);
    }

    float fontSize() {
        return size == null ? 10 : size;
    }

    float fontSizeCs() {
        return sizeCs != null ? sizeCs : fontSize();
    }

    Color textColor() {
        return color == null || color == AUTO ? null : color;
    }

    Color highlightColor() {
        return highlight == null || highlight == AUTO ? null : highlight;
    }

    Color shadingColor() {
        return shading == null || shading == AUTO ? null : shading;
    }

    boolean underlined() {
        return underline != null && !underline.equals("none");
    }
}
