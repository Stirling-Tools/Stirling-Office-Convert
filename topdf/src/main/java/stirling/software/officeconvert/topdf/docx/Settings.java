package stirling.software.officeconvert.topdf.docx;

final class Settings {

    float defaultTabStop = 36;
    boolean evenAndOddHeaders;
    int compatibilityMode = 12;
    boolean doNotExpandShiftReturn;
    boolean htmlAutoSpacingOff;
    boolean mirrorMargins;
    String footnoteFormat = "decimal";
    int footnoteStart = 1;
    String footnoteRestart = "continuous";
    String endnoteFormat = "lowerRoman";
    int endnoteStart = 1;
    XEl colorMapping;
    String eastAsiaLang;

    String bidiLang;
    boolean autoHyphenation;
    float hyphenationZone = 18;
    int consecutiveHyphenLimit;
    boolean doNotHyphenateCaps;
    boolean overrideTableStyleFontSize;
    boolean displayBackgroundShape;
    boolean adjustLineHeightInTable;

    Settings(XEl settings) {
        if (settings == null) {
            return;
        }
        for (XEl k : settings.kids) {
            switch (k.name) {
                case "w:defaultTabStop" -> {
                    Float v = Ooxml.twips(k.val());
                    if (v != null && v > 0.5f) {
                        defaultTabStop = v;
                    }
                }
                case "w:evenAndOddHeaders" -> evenAndOddHeaders = Ooxml.on(k);
                case "w:mirrorMargins" -> mirrorMargins = Ooxml.on(k);
                case "w:displayBackgroundShape" -> displayBackgroundShape = Ooxml.on(k);
                case "w:autoHyphenation" -> autoHyphenation = Ooxml.on(k);
                case "w:hyphenationZone" -> hyphenationZone = Ooxml.twips(k.val(), hyphenationZone);
                case "w:consecutiveHyphenLimit" -> consecutiveHyphenLimit = Ooxml.integer(k.val(), 0);
                case "w:doNotHyphenateCaps" -> doNotHyphenateCaps = Ooxml.on(k);
                case "w:clrSchemeMapping" -> colorMapping = k;
                case "w:themeFontLang" -> {
                    eastAsiaLang = k.attr("eastAsia");
                    bidiLang = k.attr("bidi");
                }
                case "w:footnotePr" -> {
                    XEl f = k.child("w:numFmt");
                    if (f != null && f.val() != null) {
                        footnoteFormat = f.val();
                    }
                    XEl s = k.child("w:numStart");
                    if (s != null) {
                        footnoteStart = Ooxml.integer(s.val(), 1);
                    }
                    XEl r = k.child("w:numRestart");
                    if (r != null && r.val() != null) {
                        footnoteRestart = r.val();
                    }
                }
                case "w:endnotePr" -> {
                    XEl f = k.child("w:numFmt");
                    if (f != null && f.val() != null) {
                        endnoteFormat = f.val();
                    }
                    XEl s = k.child("w:numStart");
                    if (s != null) {
                        endnoteStart = Ooxml.integer(s.val(), 1);
                    }
                }
                case "w:compat" -> compat(k);
                default -> {
                }
            }
        }
    }

    private void compat(XEl compat) {
        for (XEl c : compat.kids) {
            switch (c.name) {
                case "w:doNotExpandShiftReturn" -> doNotExpandShiftReturn = Ooxml.on(c);
                case "w:doNotUseHTMLParagraphAutoSpacing" -> htmlAutoSpacingOff = Ooxml.on(c);
                case "w:adjustLineHeightInTable" -> adjustLineHeightInTable = Ooxml.on(c);
                case "w:compatSetting" -> {
                    if ("compatibilityMode".equals(c.attr("name"))) {
                        compatibilityMode = Ooxml.integer(c.attr("val"), compatibilityMode);
                    } else if ("overrideTableStyleFontSizeAndJustification".equals(c.attr("name"))) {
                        String v = c.attr("val");
                        overrideTableStyleFontSize = "1".equals(v) || "true".equalsIgnoreCase(v);
                    }
                }
                default -> {
                }
            }
        }
    }
}
