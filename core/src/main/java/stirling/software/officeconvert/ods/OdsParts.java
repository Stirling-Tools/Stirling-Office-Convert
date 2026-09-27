package stirling.software.officeconvert.ods;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import stirling.software.officeconvert.sheet.SheetXml;

final class OdsParts {

    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";

    private static final String NS = " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
            + " xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\""
            + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\""
            + " xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\""
            + " xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\""
            + " xmlns:svg=\"urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0\""
            + " xmlns:number=\"urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0\""
            + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
            + " xmlns:meta=\"urn:oasis:names:tc:opendocument:xmlns:meta:1.0\""
            + " xmlns:config=\"urn:oasis:names:tc:opendocument:xmlns:config:1.0\"";

    private static final String FONTS = "<office:font-face-decls><style:font-face style:name=\"" + OdsStyles.FONT
            + "\" svg:font-family=\"" + OdsStyles.FONT
            + "\" style:font-family-generic=\"swiss\" style:font-pitch=\"variable\"/></office:font-face-decls>";

    private OdsParts() {}

    static String contentHead(String automaticStyles) {
        return HEAD + "<office:document-content" + NS + " office:version=\"1.3\">" + FONTS
                + "<office:automatic-styles>" + automaticStyles + "</office:automatic-styles>"
                + "<office:body><office:spreadsheet>";
    }

    static String contentTail(List<String> namedRanges) {
        StringBuilder sb = new StringBuilder();
        if (!namedRanges.isEmpty()) {
            sb.append("<table:named-expressions>");
            namedRanges.forEach(sb::append);
            sb.append("</table:named-expressions>");
        }
        return sb.append("</office:spreadsheet></office:body></office:document-content>").toString();
    }

    static String styles(String pageLayouts, String masterPages) {
        return HEAD + "<office:document-styles" + NS + " office:version=\"1.3\">" + FONTS
                + "<office:styles>"
                + "<style:default-style style:family=\"table-cell\"><style:text-properties style:font-name=\""
                + OdsStyles.FONT + "\" fo:font-size=\"11pt\"/></style:default-style>"
                + "<style:style style:name=\"Default\" style:family=\"table-cell\"><style:text-properties style:font-name=\""
                + OdsStyles.FONT + "\" fo:font-size=\"11pt\"/></style:style>"
                + "</office:styles>"
                + "<office:automatic-styles>" + pageLayouts + "</office:automatic-styles>"
                + "<office:master-styles>" + masterPages + "</office:master-styles>"
                + "</office:document-styles>";
    }

    static String meta(String title, String author) {
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        return HEAD + "<office:document-meta" + NS + " office:version=\"1.3\"><office:meta>"
                + "<meta:generator>Stirling-PDF</meta:generator>"
                + (title == null || title.isBlank() ? "" : "<dc:title>" + SheetXml.escape(title) + "</dc:title>")
                + (author == null || author.isBlank() ? ""
                        : "<meta:initial-creator>" + SheetXml.escape(author) + "</meta:initial-creator>")
                + "<meta:creation-date>" + now.substring(0, now.length() - 1) + "</meta:creation-date>"
                + "</office:meta></office:document-meta>";
    }

    static String settings(List<String[]> frozen) {
        StringBuilder sb = new StringBuilder(HEAD).append("<office:document-settings").append(NS)
                .append(" office:version=\"1.3\"><office:settings><config:config-item-set config:name=\"ooo:view-settings\">")
                .append("<config:config-item-map-indexed config:name=\"Views\"><config:config-item-map-entry>")
                .append("<config:config-item config:name=\"ViewId\" config:type=\"string\">view1</config:config-item>")
                .append("<config:config-item-map-named config:name=\"Tables\">");
        for (String[] f : frozen) {
            int rows = Integer.parseInt(f[1]);
            if (rows <= 0) {
                continue;
            }
            sb.append("<config:config-item-map-entry config:name=\"");
            SheetXml.escape(sb, f[0]);
            sb.append("\">")
                    .append(item("HorizontalSplitMode", "short", "0"))
                    .append(item("VerticalSplitMode", "short", "2"))
                    .append(item("HorizontalSplitPosition", "int", "0"))
                    .append(item("VerticalSplitPosition", "int", f[1]))
                    .append(item("ActiveSplitRange", "short", "2"))
                    .append(item("PositionLeft", "int", "0"))
                    .append(item("PositionRight", "int", "0"))
                    .append(item("PositionTop", "int", "0"))
                    .append(item("PositionBottom", "int", f[1]))
                    .append("</config:config-item-map-entry>");
        }
        sb.append("</config:config-item-map-named>");
        if (!frozen.isEmpty()) {
            sb.append("<config:config-item config:name=\"ActiveTable\" config:type=\"string\">");
            SheetXml.escape(sb, frozen.getFirst()[0]);
            sb.append("</config:config-item>");
        }
        return sb.append("</config:config-item-map-entry>")
                .append("</config:config-item-map-indexed></config:config-item-set></office:settings>")
                .append("</office:document-settings>").toString();
    }

    private static String item(String name, String type, String value) {
        return "<config:config-item config:name=\"" + name + "\" config:type=\"" + type + "\">" + value
                + "</config:config-item>";
    }

    static String manifest() {
        return HEAD + "<manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\""
                + " manifest:version=\"1.3\">"
                + "<manifest:file-entry manifest:full-path=\"/\" manifest:version=\"1.3\" manifest:media-type=\""
                + OdsWriter.MIME + "\"/>"
                + "<manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/>"
                + "<manifest:file-entry manifest:full-path=\"styles.xml\" manifest:media-type=\"text/xml\"/>"
                + "<manifest:file-entry manifest:full-path=\"meta.xml\" manifest:media-type=\"text/xml\"/>"
                + "<manifest:file-entry manifest:full-path=\"settings.xml\" manifest:media-type=\"text/xml\"/>"
                + "</manifest:manifest>";
    }
}
