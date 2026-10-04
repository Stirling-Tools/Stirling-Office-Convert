package stirling.software.officeconvert.topdf.rtf;

record Wrap(String open, String close) {

    static Wrap field(String instr) {
        return new Wrap("<w:fldSimple w:instr=\"" + Xml.attr(instr) + "\">", "</w:fldSimple>");
    }

    static Wrap link(String rid) {
        return new Wrap("<w:hyperlink r:id=\"" + rid + "\">", "</w:hyperlink>");
    }

    static Wrap anchor(String name) {
        return new Wrap("<w:hyperlink w:anchor=\"" + Xml.attr(name) + "\">", "</w:hyperlink>");
    }
}
