package stirling.software.officeconvert.topdf.wordml;

import java.util.Map;
import java.util.Set;

import stirling.software.officeconvert.topdf.io.NumberFormatCodes;

final class Names {

    static final String W2003 = "http://schemas.microsoft.com/office/word/2003/wordml";

    static final String WX = "http://schemas.microsoft.com/office/word/2003/auxHint";

    static final String AML = "http://schemas.microsoft.com/aml/2001/core";

    static final String V = "urn:schemas-microsoft-com:vml";

    static final String O = "urn:schemas-microsoft-com:office:office";

    static final String W10 = "urn:schemas-microsoft-com:office:word";

    static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    static final String NAMESPACES = " xmlns:w=\"" + W + "\" xmlns:r=\"" + R + "\" xmlns:v=\"" + V + "\" xmlns:o=\""
            + O + "\" xmlns:w10=\"" + W10 + "\"";

    private static final Map<String, String> ELEMENTS = Map.ofEntries(Map.entry("listPr", "numPr"),
            Map.entry("ilfo", "numId"), Map.entry("vmerge", "vMerge"), Map.entry("hmerge", "hMerge"),
            Map.entry("lsid", "nsid"), Map.entry("plt", "multiLevelType"), Map.entry("listStyleLink", "numStyleLink"),
            Map.entry("listDef", "abstractNum"), Map.entry("list", "num"), Map.entry("ilst", "abstractNumId"),
            Map.entry("lists", "numbering"), Map.entry("nfc", "numFmt"), Map.entry("textFlow", "textDirection"),
            Map.entry("docPr", "settings"));

    private static final Map<String, String> ATTRIBUTES = Map.of("fareast", "eastAsia", "listDefId", "abstractNumId",
            "ilfo", "numId", "screenTip", "tooltip");

    private static final Set<String> ENUM_ELEMENTS = Set.of("u", "highlight", "shd", "numFmt", "textDirection",
            "textFlow", "top", "left", "bottom", "right", "insideH", "insideV", "tl2br", "tr2bl", "between", "bar",
            "bdr", "br", "em", "effect", "vAlign", "jc", "textAlignment", "tab", "suff", "type", "vertAlign",
            "dropCap", "fldCharType", "lvlJc", "pgNumType", "lineNumbers", "lnNumType", "pos", "numRestart",
            "footnotePr", "endnotePr", "footnote", "endnote", "hdr", "ftr", "framePr", "tblpPr", "docGrid",
            "tblLayout", "tblOverlap", "sectPr", "lvlRestart", "proofErr", "fitText", "kinsoku", "pgBorders",
            "trHeight", "spacing", "wrap", "tblW", "tcW", "tblInd", "tblCellSpacing", "wBefore", "wAfter",
            "position", "textboxTightWrap", "view", "zoom");

    private static final Set<String> ENUM_ATTRIBUTES = Set.of("val", "type", "fmt", "lineRule", "hRule", "chapSep",
            "wrap", "hAnchor", "vAnchor", "xAlign", "yAlign", "vertAnchor", "horzAnchor", "tblpXSpec", "tblpYSpec",
            "offsetFrom", "display", "zOrder", "leader", "clear", "fldCharType", "restart", "dropCap");

    private Names() {}

    static String element(String local) {
        String mapped = ELEMENTS.get(local);
        return mapped != null ? mapped : camel(local);
    }

    static String attribute(String local) {
        String mapped = ATTRIBUTES.get(local);
        return mapped != null ? mapped : camel(local);
    }

    static String value(String element, String attribute, String original, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (element.equals("numFmt") && original.equals("nfc") && attribute.equals("val")) {
            return numberFormat(value);
        }
        if (element.equals("hdr") || element.equals("ftr") || element.equals("headerReference")
                || element.equals("footerReference")) {
            return value.equals("odd") ? "default" : value;
        }
        if (element.equals("style") && attribute.equals("type") && value.equals("list")) {
            return "numbering";
        }
        if (element.equals("multiLevelType") || element.equals("characterSpacingControl")) {
            String v = value.startsWith("Dont") ? "doNot" + value.substring(4) : value;
            return Character.toLowerCase(v.charAt(0)) + v.substring(1);
        }
        if (value.indexOf('-') > 0 && (ENUM_ATTRIBUTES.contains(attribute) && ENUM_ELEMENTS.contains(element)
                || !attribute.equals("val") && ENUM_ATTRIBUTES.contains(attribute)) && enumLike(value)) {
            return camel(value);
        }
        return value;
    }

    static String numberFormat(String nfc) {
        try {
            return NumberFormatCodes.ooxml(Integer.parseInt(nfc.trim()));
        } catch (NumberFormatException e) {
            return "decimal";
        }
    }

    private static boolean enumLike(String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-')) {
                return false;
            }
        }
        return true;
    }

    static String camel(String s) {
        int dash = s.indexOf('-');
        if (dash < 0) {
            return s;
        }
        StringBuilder b = new StringBuilder(s.length());
        boolean up = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-') {
                up = true;
            } else {
                b.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return b.toString();
    }
}
