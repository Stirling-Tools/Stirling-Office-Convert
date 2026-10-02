package stirling.software.officeconvert.topdf.odf;

import java.util.Map;

final class Ns {

    static final String OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";
    static final String STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0";
    static final String TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";
    static final String TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";
    static final String DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0";
    static final String FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0";
    static final String SVG = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0";
    static final String XLINK = "http://www.w3.org/1999/xlink";
    static final String NUMBER = "urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0";
    static final String PRESENTATION = "urn:oasis:names:tc:opendocument:xmlns:presentation:1.0";
    static final String MANIFEST = "urn:oasis:names:tc:opendocument:xmlns:manifest:1.0";
    static final String CONFIG = "urn:oasis:names:tc:opendocument:xmlns:config:1.0";
    static final String LOEXT = "urn:org:documentfoundation:names:experimental:office:xmlns:loext:1.0";
    static final String CALCEXT = "urn:org:documentfoundation:names:experimental:calc:xmlns:calcext:1.0";
    static final String OFFICEOOO = "http://openoffice.org/2009/office";
    static final String XML = "http://www.w3.org/XML/1998/namespace";

    private static final Map<String, String> PREFIXES = Map.ofEntries(Map.entry(OFFICE, "office"),
            Map.entry(STYLE, "style"), Map.entry(TEXT, "text"), Map.entry(TABLE, "table"), Map.entry(DRAW, "draw"),
            Map.entry(FO, "fo"), Map.entry(SVG, "svg"), Map.entry(XLINK, "xlink"), Map.entry(NUMBER, "number"),
            Map.entry(PRESENTATION, "presentation"), Map.entry(LOEXT, "loext"), Map.entry(CALCEXT, "calcext"),
            Map.entry(OFFICEOOO, "officeooo"), Map.entry(XML, "xml"),
            Map.entry("urn:oasis:names:tc:opendocument:xmlns:chart:1.0", "chart"),
            Map.entry("urn:oasis:names:tc:opendocument:xmlns:dr3d:1.0", "dr3d"));

    private Ns() {}

    static String prefix(String uri) {
        return uri == null ? "" : PREFIXES.getOrDefault(uri, "x");
    }
}
