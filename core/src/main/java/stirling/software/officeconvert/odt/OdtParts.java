package stirling.software.officeconvert.odt;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.StyleSheet;

final class OdtParts {

    static final String MIME = "application/vnd.oasis.opendocument.text";

    private OdtParts() {}

    static void commonStyles(StringBuilder sb, StyleSheet sheet, Set<String> fonts) {
        RunStyle normal = sheet.normal;
        fonts.add(normal.font());
        String font = OdtXml.esc(normal.font());
        String size = OdtXml.pt(normal.size());
        sb.append("<office:styles>")
                .append("<style:default-style style:family=\"paragraph\"><style:paragraph-properties fo:orphans=\"0\"")
                .append(" fo:widows=\"0\" style:tab-stop-distance=\"36pt\" fo:hyphenation-ladder-count=\"no-limit\"")
                .append(" style:writing-mode=\"page\"/><style:text-properties style:font-name=\"").append(font)
                .append("\" style:font-name-asian=\"").append(font).append("\" style:font-name-complex=\"").append(font)
                .append("\" fo:font-size=\"").append(size).append("\" style:font-size-asian=\"").append(size)
                .append("\" style:font-size-complex=\"").append(size).append("\" fo:language=\"en\" fo:country=\"US\"")
                .append(" style:letter-kerning=\"false\" fo:hyphenate=\"false\" fo:hyphenation-remain-char-count=\"2\"")
                .append(" fo:hyphenation-push-char-count=\"2\"/></style:default-style>")
                .append("<style:default-style style:family=\"table\"><style:table-properties table:border-model=\"collapsing\"/>")
                .append("</style:default-style>")
                .append("<style:default-style style:family=\"graphic\"><style:graphic-properties draw:shadow=\"hidden\"")
                .append(" style:flow-with-text=\"false\"/></style:default-style>")
                .append("<style:style style:name=\"Frame\" style:family=\"graphic\"><style:graphic-properties")
                .append(" fo:padding=\"0pt\" fo:border=\"none\" style:shadow=\"none\"/></style:style>");
        RunStyle plain = new RunStyle(normal.font(), normal.size(), false, false, false, false, 0, -1, 0, false);
        String normalText = OdtProps.text(normal, plain, fonts);
        sb.append("<style:style style:name=\"Standard\" style:family=\"paragraph\" style:class=\"text\">")
                .append("<style:paragraph-properties fo:margin-top=\"0pt\" fo:margin-bottom=\"0pt\" fo:line-height=\"100%\"/>");
        if (!normalText.isEmpty()) {
            sb.append("<style:text-properties").append(normalText).append("/>");
        }
        sb.append("</style:style>");
        for (StyleSheet.Style s : sheet.all()) {
            if (s.id().equals("Normal")) {
                continue;
            }
            String name = OdtBody.styleName(s.id());
            sb.append("<style:style style:name=\"").append(name).append('"');
            if (!name.equals(s.name())) {
                sb.append(" style:display-name=\"").append(OdtXml.esc(displayName(s))).append('"');
            }
            sb.append(" style:family=\"paragraph\" style:parent-style-name=\"Standard\"");
            if (s.outlineLevel() >= 0 || s.id().equals("Title")) {
                sb.append(" style:next-style-name=\"Standard\"");
            }
            if (s.outlineLevel() >= 0) {
                sb.append(" style:default-outline-level=\"").append(s.outlineLevel() + 1).append('"');
            }
            sb.append(" style:class=\"").append(s.id().equals("Title") ? "chapter" : s.id().equals("ListParagraph") ? "list" : "text")
                    .append("\"><style:paragraph-properties");
            if (s.keepNext()) {
                sb.append(" fo:keep-with-next=\"always\" fo:keep-together=\"always\"");
            }
            if (s.id().equals("ListParagraph")) {
                sb.append(" fo:margin-left=\"36pt\"");
            }
            sb.append("/>");
            RunStyle r = s.run();
            if (r != null && r != normal) {
                String t = OdtProps.text(r.font() == null ? r.withFont(normal.font()) : r, normal, fonts);
                if (!t.isEmpty()) {
                    sb.append("<style:text-properties").append(t).append("/>");
                }
            }
            sb.append("</style:style>");
        }
        sb.append("<style:style style:name=\"Footnote\" style:family=\"paragraph\" style:parent-style-name=\"Standard\"")
                .append(" style:class=\"extra\"/>")
                .append("<style:style style:name=\"Footnote_20_Symbol\" style:display-name=\"Footnote Symbol\" style:family=\"text\">")
                .append("<style:text-properties style:text-position=\"super 58%\"/></style:style>")
                .append("<style:style style:name=\"Footnote_20_anchor\" style:display-name=\"Footnote anchor\" style:family=\"text\">")
                .append("<style:text-properties style:text-position=\"super 58%\"/></style:style>")
                .append("<text:outline-style style:name=\"Outline\">");
        for (int l = 1; l <= 10; l++) {
            sb.append("<text:outline-level-style text:level=\"").append(l).append("\" style:num-format=\"\">")
                    .append("<style:list-level-properties text:list-level-position-and-space-mode=\"label-alignment\">")
                    .append("<style:list-level-label-alignment text:label-followed-by=\"listtab\"/></style:list-level-properties>")
                    .append("</text:outline-level-style>");
        }
        sb.append("</text:outline-style>")
                .append("<text:notes-configuration text:note-class=\"footnote\" text:citation-style-name=\"Footnote_20_Symbol\"")
                .append(" text:citation-body-style-name=\"Footnote_20_anchor\" style:num-format=\"1\" text:start-value=\"0\"")
                .append(" text:footnotes-position=\"page\" text:start-numbering-at=\"document\"/>")
                .append("</office:styles>");
    }

    private static String displayName(StyleSheet.Style s) {
        if (s.id().startsWith("Heading")) {
            return "Heading " + s.id().substring(7);
        }
        return s.id().equals("ListParagraph") ? "List Paragraph" : s.name();
    }

    static void meta(StringBuilder sb, String title, String author) {
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        now = now.endsWith("Z") ? now.substring(0, now.length() - 1) : now;
        sb.append("<office:meta><meta:generator>Stirling-PDF</meta:generator>");
        if (title != null && !title.isBlank()) {
            sb.append("<dc:title>").append(OdtXml.esc(title)).append("</dc:title>");
        }
        if (author != null && !author.isBlank()) {
            String a = OdtXml.esc(author);
            sb.append("<meta:initial-creator>").append(a).append("</meta:initial-creator><dc:creator>").append(a)
                    .append("</dc:creator>");
        }
        sb.append("<meta:creation-date>").append(now).append("</meta:creation-date><dc:date>").append(now)
                .append("</dc:date></office:meta>");
    }

    static void settings(StringBuilder sb) {
        sb.append("<office:settings><config:config-item-set config:name=\"ooo:configuration-settings\">")
                .append(item("TabsRelativeToIndent", true))
                .append(item("AddParaTableSpacingAtStart", false))
                .append(item("ParaSpaceMaxAtPages", false))
                .append("</config:config-item-set></office:settings>");
    }

    private static String item(String name, boolean value) {
        return "<config:config-item config:name=\"" + name + "\" config:type=\"boolean\">" + value + "</config:config-item>";
    }

    static String manifest(List<Picture.MediaRef> media) {
        StringBuilder sb = new StringBuilder(OdtXml.HEAD)
                .append("<manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\"")
                .append(" manifest:version=\"1.3\"><manifest:file-entry manifest:full-path=\"/\" manifest:version=\"1.3\"")
                .append(" manifest:media-type=\"").append(MIME).append("\"/>");
        for (String part : new String[] {"content.xml", "styles.xml", "meta.xml", "settings.xml"}) {
            sb.append("<manifest:file-entry manifest:full-path=\"").append(part).append("\" manifest:media-type=\"text/xml\"/>");
        }
        for (Picture.MediaRef m : media) {
            sb.append("<manifest:file-entry manifest:full-path=\"Pictures/").append(m.name()).append("\" manifest:media-type=\"")
                    .append(m.contentType()).append("\"/>");
        }
        return sb.append("</manifest:manifest>").toString();
    }
}
