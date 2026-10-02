package stirling.software.officeconvert.topdf.ooo1;

import java.util.Map;

/** The OpenOffice.org 1.x namespaces and the OpenDocument ones that replaced them. */
final class Names {

    private static final String OASIS = "urn:oasis:names:tc:opendocument:xmlns:";

    static final String OFFICE = OASIS + "office:1.0";

    static final String STYLE = OASIS + "style:1.0";

    static final String TEXT = OASIS + "text:1.0";

    static final String TABLE = OASIS + "table:1.0";

    static final String DRAW = OASIS + "drawing:1.0";

    static final String FO = OASIS + "xsl-fo-compatible:1.0";

    static final String SVG = OASIS + "svg-compatible:1.0";

    static final String PRESENTATION = OASIS + "presentation:1.0";

    static final Map<String, String> MAP = Map.ofEntries(
            Map.entry("http://openoffice.org/2000/office", OFFICE),
            Map.entry("http://openoffice.org/2000/style", STYLE),
            Map.entry("http://openoffice.org/2000/text", TEXT),
            Map.entry("http://openoffice.org/2000/table", TABLE),
            Map.entry("http://openoffice.org/2000/drawing", DRAW),
            Map.entry("http://www.w3.org/1999/XSL/Format", FO),
            Map.entry("http://www.w3.org/2000/svg", SVG),
            Map.entry("http://openoffice.org/2000/datastyle", OASIS + "datastyle:1.0"),
            Map.entry("http://openoffice.org/2000/presentation", PRESENTATION),
            Map.entry("http://openoffice.org/2000/chart", OASIS + "chart:1.0"),
            Map.entry("http://openoffice.org/2000/dr3d", OASIS + "dr3d:1.0"),
            Map.entry("http://openoffice.org/2000/form", OASIS + "form:1.0"),
            Map.entry("http://openoffice.org/2000/script", OASIS + "script:1.0"),
            Map.entry("http://openoffice.org/2000/meta", OASIS + "meta:1.0"));

    static final Map<String, String> PREFIXES = Map.ofEntries(
            Map.entry(OFFICE, "office"), Map.entry(STYLE, "style"), Map.entry(TEXT, "text"),
            Map.entry(TABLE, "table"), Map.entry(DRAW, "draw"), Map.entry(FO, "fo"), Map.entry(SVG, "svg"),
            Map.entry(OASIS + "datastyle:1.0", "number"), Map.entry(PRESENTATION, "presentation"),
            Map.entry(OASIS + "chart:1.0", "chart"), Map.entry(OASIS + "dr3d:1.0", "dr3d"),
            Map.entry(OASIS + "form:1.0", "form"), Map.entry(OASIS + "script:1.0", "script"),
            Map.entry(OASIS + "meta:1.0", "meta"), Map.entry("http://www.w3.org/1999/xlink", "xlink"),
            Map.entry("http://purl.org/dc/elements/1.1/", "dc"));

    private Names() {}

    static String map(String ns) {
        return ns == null ? null : MAP.getOrDefault(ns, ns);
    }
}
