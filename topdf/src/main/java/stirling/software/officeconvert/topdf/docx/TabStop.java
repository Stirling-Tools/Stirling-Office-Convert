package stirling.software.officeconvert.topdf.docx;

record TabStop(float pos, Kind kind, char leader) {

    enum Kind {
        LEFT,
        CENTER,
        RIGHT,
        DECIMAL,
        BAR,
        CLEAR
    }

    static TabStop parse(XEl tab) {
        Float pos = Ooxml.twips(tab.attr("pos"));
        if (pos == null) {
            return null;
        }
        String v = tab.val() == null ? "left" : tab.val();
        Kind kind = switch (v) {
            case "center" -> Kind.CENTER;
            case "right", "end" -> Kind.RIGHT;
            case "decimal" -> Kind.DECIMAL;
            case "bar" -> Kind.BAR;
            case "clear" -> Kind.CLEAR;
            default -> Kind.LEFT;
        };
        return new TabStop(pos, kind, leader(tab.attr("leader")));
    }

    static char leader(String v) {
        if (v == null) {
            return 0;
        }
        return switch (v) {
            case "dot" -> '.';
            case "hyphen" -> '-';
            case "underscore", "heavy" -> '_';
            case "middleDot" -> '\u00B7';
            default -> 0;
        };
    }
}
